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

    // One fixed key for every build, so new APKs install over the old one (new key 23 Nov 2026).
    // The key file is encrypted; its password lives only in the cerniera-studio org secret
    // CERNIERA_SIGNING_PASSWORD, which the build workflow passes in. Without it (a local build)
    // Android's own debug key is used, and that APK will not install over the phone's copy.
    val signingPassword = System.getenv("CERNIERA_SIGNING_PASSWORD")
    signingConfigs {
        if (!signingPassword.isNullOrBlank()) getByName("debug") {
            storeFile = rootProject.file("signing/cielo-2026.p12")
            storeType = "pkcs12"
            storePassword = signingPassword
            keyAlias = "cielo"
            keyPassword = signingPassword
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
    // Today's lead and the shared rain are tested on the JVM: gradle :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}
