plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

// Manifest-only, opt-in artifact: it declares Eora's screen-recording service and the foreground-service
// permissions it needs. Apps that skip it keep a permission-free Eora; the recording code itself lives in :eora.
android {
    namespace = "com.easyhooon.eora.recording"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":eora"))
}
