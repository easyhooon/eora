plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.easyhooon.eora.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.easyhooon.eora.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // stg wires the real Eora; prod gets the no-op so QA code, UI and uploader never reach a shipped build.
    flavorDimensions += "env"
    productFlavors {
        create("stg") {
            dimension = "env"
            applicationIdSuffix = ".stg"
        }
        create("prod") {
            dimension = "env"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    "stgImplementation"(project(":eora"))
    // Opt-in screen recording; adds two install-time foreground-service permissions to the stg build only.
    "stgImplementation"(project(":eora-recording"))
    "prodImplementation"(project(":eora-noop"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
