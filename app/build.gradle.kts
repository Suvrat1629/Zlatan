plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.sih26168.idr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sih26168.idr"

        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(project(":core-types"))
    implementation(project(":core-replay"))
    implementation(project(":engine"))
    implementation(project(":android-sensors"))
    implementation(project(":android-assets"))
    implementation(project(":android-model"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
