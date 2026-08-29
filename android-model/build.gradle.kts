plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sih26168.idr.androidmodel"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-types"))
    api(project(":core-model"))
    api(project(":core-assets"))
    implementation(project(":engine"))

    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // Parity gates G3 / G4 — run on a device: ./gradlew :android-model:connectedAndroidTest
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
