plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.easyhooon.eora.api"
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
    testImplementation(libs.junit)
}
