plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.pressreadmeplease"
    compileSdk = 34

    // Chiave fissa: ogni build (anche quelle di GitHub Actions) ha la stessa firma,
    // così l'APK si installa sopra la versione precedente senza disinstallare.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/pressreadmeplease.jks")
            storePassword = "pressreadmeplease"
            keyAlias = "pressreadmeplease"
            keyPassword = "pressreadmeplease"
        }
    }

    defaultConfig {
        applicationId = "io.github.pressreadmeplease"
        minSdk = 24
        targetSdk = 34
        // Cresce a ogni run di CI, così Android accetta sempre l'aggiornamento.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
