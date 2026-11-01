plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nick.stackcam"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nick.stackcam"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        versionName = "2.3"
    }
    // Always sign with the same debug keystore so new APKs install over old ones (a backup lives in
    // /workspace/keystore-backup/debug.keystore; restore it to ~/.android/ if this machine is rebuilt).
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val camerax = "1.3.4"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM (Android's built-in copy is only a stub in unit tests).
    testImplementation("org.json:json:20240303")
}
