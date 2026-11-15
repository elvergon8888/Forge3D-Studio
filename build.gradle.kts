buildscript {
    ext {
        compileSdk = 35
        minSdk = 29 // Android 10+
        targetSdk = 35
        kotlinVersion = "1.9.23"
        agpVersion = "8.11.1"
        ktorVersion = "2.3.12"
        mcpSdkVersion = "0.8.0"
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

allprojects {
    configurations {
        implementation {
            exclude group: "androidx.lifecycle", module: "lifecycle-livedata"
        }
    }
}
