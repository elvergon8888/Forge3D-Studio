plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.forge3d.studio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forge3d.studio.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/forge3d.jks")
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
        jvmTarget = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":render"))
    implementation(project(":mcp-server"))
    implementation(project(":mcp-client"))
    implementation(project(":ui"))
    implementation(libs.kotlinx.coroutines.android)
}
