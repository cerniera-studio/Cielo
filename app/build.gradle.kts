plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fergusonconsultancy.cielo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fergusonconsultancy.cielo"
        // Android 11: the hinge angle sensor and LocationManager.getCurrentLocation
        minSdk = 31
        targetSdk = 35
        // Each GitHub build gets a higher number so it installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build, so new APKs install over the old one.
    // Debug-only key for personal sideloading. Never use it for a store release.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/cielo-debug.jks")
            storePassword = "cielodebug"
            keyAlias = "cielo"
            keyPassword = "cielodebug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
    implementation(project(":kit"))
}
