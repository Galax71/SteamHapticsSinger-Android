# Build locally; publish a GitHub prerelease only if explicitly requested.
[CmdletBinding()]
param(
  [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
  [string]$Version = '1.6',
  [switch]$SkipPublish
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = $root
function Assert-Ok([string]$task) {
  if ($LASTEXITCODE -ne 0) { throw "$task failed (exit $LASTEXITCODE)." }
}
Push-Location $root
try {
  . (Join-Path $PSScriptRoot 'ensure-android-build-dependencies.ps1') -ProjectDirectory $project
  if (Test-Path '.\gradlew.bat') {
    & '.\gradlew.bat' --no-daemon assembleDebug
  } else {
    & gradle --no-daemon assembleDebug
  }
  Assert-Ok 'Build Android APK'
  $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
  if (-not (Test-Path $apk)) { throw "Build did not produce APK: $apk" }
  $destination = Join-Path $root 'dist'
  New-Item -ItemType Directory -Path $destination -Force | Out-Null
  $filename = "SteamHapticsSinger-Android-Version-$Version.apk"
  $output = Join-Path $destination $filename
  Copy-Item $apk $output -Force
  $sha = (Get-FileHash $output -Algorithm SHA256).Hash
  Write-Host "APK built: $output" -ForegroundColor Green
  Write-Host "SHA256: $sha"
  if (-not $SkipPublish) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI gh is required for publishing.' }
    & gh auth status
    Assert-Ok 'GitHub authentication'
    $repo = 'Galax71/SteamHapticsSinger-Android'
    $tag = "android-v$Version"
    # Avoid using a failing 'gh release view' as an existence check:
    # PowerShell can promote its stderr ('release not found') to a terminating error.
    $existingTags = @(& gh release list --repo $repo --limit 100 --json tagName --jq '.[].tagName')
    Assert-Ok 'List GitHub releases'
    if ($existingTags -contains $tag) {
      & gh release upload $tag $output --repo $repo --clobber
    } else {
      & gh release create $tag $output --repo $repo --target main --title "SteamHapticsSinger Android $Version (experimental)" --notes "Experimental Android port. Haptic playback is unverified; USB note output is experimental and Bluetooth musical note output is not yet implemented." --prerelease --latest=false
    }
    Assert-Ok 'Publish GitHub prerelease'
    Write-Host "Release: https://github.com/$repo/releases/tag/$tag"
  }
} finally { Pop-Location }
