plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.galax71.steamhapticssinger"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.galax71.steamhapticssinger"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.6-device-compat"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
