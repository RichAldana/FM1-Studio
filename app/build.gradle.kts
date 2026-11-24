plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.fm1mix"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.fm1mix"
        minSdk = 31
        targetSdk = 35
        versionCode = 30
        versionName = "0.25"
        ndk { abiFilters += "arm64-v8a" }
    }
    // a fixed debug key, so that a new build installs over the previous one
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // libfm1mix.so is built by ../build-native.sh into src/main/jniLibs/arm64-v8a
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
