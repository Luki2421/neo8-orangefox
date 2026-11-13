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
        versionCode = 2
        versionName = "0.1.1-etap1"
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
