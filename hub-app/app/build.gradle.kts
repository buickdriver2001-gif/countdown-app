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
        // Each GitHub build gets a higher number, so updates install cleanly. NEXUS 1.0 is build 161; each build
        // after it is 1.0.1, 1.0.2, ... (the builds before it were the 1.N previews)
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        val first = 161
        // (Builds that failed and were never released don't take an update number)
        val notReleased = setOf(162)
        versionCode = build
        versionName = when {
            build == first -> "1.0"
            build > first -> "1.0.${build - first - notReleased.count { it < build }}"
            else -> "1.$build"
        }
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
            // The build that's installed: Android runs it at full speed (a debug build runs in a slower,
            // debuggable mode) and R8 optimises the code. Same signing key, so it installs over earlier builds.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
