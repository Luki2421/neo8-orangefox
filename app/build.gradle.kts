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
        versionCode = 48
        versionName = "1.5.2"
    }
    // Staly klucz podpisu - dzieki temu nowe wersje instaluja sie jako aktualizacja
    signingConfigs {
        create("franken") {
            storeFile = file("franken.p12")
            storeType = "pkcs12"
            storePassword = "frankenflasher"
            keyAlias = "franken"
            keyPassword = "frankenflasher"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("franken") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("franken") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true }; resources { excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") } }
}

dependencies {
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.6-3@aar")
}

// Nazwa pliku APK z numerem wersji, np. FrankenFlasher-0.4.5-debug.apk
base { archivesName.set("FrankenFlasher-" + android.defaultConfig.versionName) }
