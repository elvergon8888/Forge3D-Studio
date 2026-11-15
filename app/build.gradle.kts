plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.forge3d.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forge3d.studio"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/forge3d.jks")
            storePassword = "forge3d2026"
            keyAlias = "forge3d"
            keyPassword = "forge3d2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    // Cero dependencias externas: solo kotlin-stdlib y APIs del sistema Android.
    // Sin Firebase, sin Ads, sin Amplitude, sin AndroidX.
}
