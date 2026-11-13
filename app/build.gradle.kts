plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.luki.frankenflasher"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.luki.frankenflasher"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1-etap2"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
