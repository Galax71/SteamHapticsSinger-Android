# PowerShell 5.1+ dependency bootstrap for SteamHapticsSinger Android.
# Installs missing tools into user-owned locations where possible.
# Downloads official Android command-line tools and Gradle distributions over HTTPS.
[CmdletBinding()]
param(
    [string]$ProjectDirectory,
    [switch]$SkipSdkLicenses
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$toolsHome = Join-Path $env:LOCALAPPDATA 'Galax71\AndroidBuildTools'
New-Item -ItemType Directory -Force -Path $toolsHome | Out-Null

function Has-Command([string]$name) { return [bool](Get-Command $name -ErrorAction SilentlyContinue) }
function Check([string]$task) { if ($LASTEXITCODE -ne 0) { throw "$task failed (exit $LASTEXITCODE)" } }
function Install-Winget([string]$id) {
    if (-not (Has-Command 'winget')) {
        throw "Windows Package Manager (winget) is required to install $id. Install App Installer from Microsoft, then retry."
    }
    Write-Host "Installing missing dependency: $id" -ForegroundColor Cyan
    & winget install --id $id --exact --source winget --accept-source-agreements --accept-package-agreements --silent --disable-interactivity
    Check "winget install $id"
}
function Download-Archive([string]$url, [string]$destination) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Write-Host "Downloading $url" -ForegroundColor Cyan
    Invoke-WebRequest -Uri $url -OutFile $destination -UseBasicParsing
}
function Find-Jdk17 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Where-Object Name -Match '^jdk-17' | ForEach-Object FullName)
    $candidates += @(Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Where-Object Name -Match '^jdk-17' | ForEach-Object FullName)
    $candidates += @(Get-ChildItem 'C:\Program Files\Microsoft' -Directory -ErrorAction SilentlyContinue | Where-Object Name -Match '^jdk-17' | ForEach-Object FullName)
    foreach ($candidate in $candidates) {
        $exe = Join-Path $candidate 'bin\java.exe'
        if (Test-Path $exe) {
            # Java prints its version to stderr; PowerShell 5.1 may turn it into
            # a terminating NativeCommandError when ErrorActionPreference=Stop.
            $versionFile = [System.IO.Path]::GetTempFileName()
            try {
                $process = Start-Process -FilePath $exe -ArgumentList '-version' -NoNewWindow -Wait -PassThru -RedirectStandardError $versionFile
                $version = [System.IO.File]::ReadAllText($versionFile)
                if ($process.ExitCode -eq 0 -and $version -match '(?:openjdk|java) version "17\.|openjdk 17\.') {
                    return $candidate
                }
            } finally {
                Remove-Item -LiteralPath $versionFile -Force -ErrorAction SilentlyContinue
            }
        }
    }
    return $null
}
if (-not (Has-Command 'git')) {
    Install-Winget 'Git.Git'
    $env:PATH = "C:\Program Files\Git\cmd;$env:PATH"
}
if (-not (Has-Command 'gh')) {
    Install-Winget 'GitHub.cli'
    $env:PATH = "C:\Program Files\GitHub CLI;$env:PATH"
}
foreach ($name in @('git','gh')) {
    if (-not (Has-Command $name)) { throw "$name still missing after installation. Restart PowerShell and rerun." }
}
$jdk = Find-Jdk17
if (-not $jdk) {
    Install-Winget 'EclipseAdoptium.Temurin.17.JDK'
    $jdk = Find-Jdk17
}
if (-not $jdk) { throw 'JDK 17 not found after installation. Restart PowerShell and rerun.' }
$env:JAVA_HOME = $jdk
$env:PATH = "$(Join-Path $jdk 'bin');$env:PATH"
Write-Host "JDK 17: $jdk" -ForegroundColor Green

# Use a local Gradle version known to work with Android Gradle Plugin 8.7.3.
$gradleHome = Join-Path $toolsHome 'gradle-8.12'
$gradleExe = Join-Path $gradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleExe)) {
    $zip = Join-Path $toolsHome 'gradle-8.12-bin.zip'
    Download-Archive 'https://services.gradle.org/distributions/gradle-8.12-bin.zip' $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $toolsHome -Force
    Remove-Item $zip -Force
}
if (-not (Test-Path $gradleExe)) { throw 'Gradle 8.12 installation failed.' }
$env:PATH = "$(Join-Path $gradleHome 'bin');$env:PATH"
Write-Host "Gradle: $gradleExe" -ForegroundColor Green

$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), (Join-Path $toolsHome 'sdk'))
$sdk = $null
foreach ($candidate in $sdkCandidates) {
    if ($candidate -and (Test-Path $candidate -PathType Container)) {
        if ((Test-Path (Join-Path $candidate 'platforms')) -or (Test-Path (Join-Path $candidate 'cmdline-tools'))) {
            $sdk = (Resolve-Path $candidate).Path
            break
        }
    }
}
if (-not $sdk) { $sdk = Join-Path $toolsHome 'sdk' }
New-Item -ItemType Directory -Force -Path $sdk | Out-Null
$cmdline = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $cmdline)) {
    $zip = Join-Path $toolsHome 'android-commandline-tools.zip'
    $extract = Join-Path $toolsHome 'android-cmdline-extract'
    # Official Google Android SDK command-line tools archive for Windows.
    Download-Archive 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force
    $source = Join-Path $extract 'cmdline-tools'
    if (-not (Test-Path (Join-Path $source 'bin\sdkmanager.bat'))) { throw 'Android SDK tools archive structure is unexpected.' }
    $latest = Join-Path $sdk 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $latest) | Out-Null
    if (Test-Path $latest) { Remove-Item -LiteralPath $latest -Recurse -Force }
    Move-Item -LiteralPath $source -Destination $latest
    Remove-Item $extract -Recurse -Force
    Remove-Item $zip -Force
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = "$(Join-Path $sdk 'platform-tools');$env:PATH"
Write-Host "Android SDK: $sdk" -ForegroundColor Green
if (-not (Test-Path (Join-Path $sdk 'platforms\android-35')) -or
    -not (Test-Path (Join-Path $sdk 'build-tools\35.0.0')) -or
    -not (Test-Path (Join-Path $sdk 'platform-tools\adb.exe'))) {
    Write-Host 'Installing Android SDK Platform 35, Build Tools 35.0.0 and Platform Tools...' -ForegroundColor Cyan
    & $cmdline "--sdk_root=$sdk" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
    Check 'Installing Android SDK components'
}
if (-not $SkipSdkLicenses) {
    Write-Host 'Checking Android SDK licenses. Read and accept any license prompts if you agree.' -ForegroundColor Yellow
    & $cmdline "--sdk_root=$sdk" --licenses
    Check 'Android SDK license review'
}
if ($ProjectDirectory) {
    if (-not (Test-Path $ProjectDirectory -PathType Container)) { throw "Project directory does not exist: $ProjectDirectory" }
    $normalized = $sdk.Replace('\', '/')
    [System.IO.File]::WriteAllText((Join-Path $ProjectDirectory 'local.properties'), "sdk.dir=$normalized`n", (New-Object System.Text.UTF8Encoding($false)))
}
# Export for parent script, which executes in the same PowerShell process.
Write-Host 'Android build dependencies are ready.' -ForegroundColor Green
