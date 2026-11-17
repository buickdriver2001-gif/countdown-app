plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamehub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamehub.app"
        minSdk = 26
        targetSdk = 35
        // Each GitHub build gets a higher number, so updates install cleanly and show their version
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    signingConfigs {
        create("shared") {
            storeFile = file("../../signing.keystore")
            storePassword = "synthnotes2026"
            keyAlias = "apps"
            keyPassword = "synthnotes2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
