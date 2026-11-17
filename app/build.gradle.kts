plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.galax71.steamhapticssinger"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.galax71.steamhapticssinger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.4-android-port"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
