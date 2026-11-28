plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cleantv.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cleantv.tv"
        minSdk = 22
        targetSdk = 34
        versionCode = 6
        versionName = "2.0"
        // Fire TV devices are ARM; leaving out x86 keeps the built-in VLC engine small.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("app") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("app") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("app")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // VLC's playback engine: plays every common video/audio format (MKV, AC3/E-AC3, DTS, TrueHD, HEVC…).
    implementation("org.videolan.android:libvlc-all:3.7.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
}
