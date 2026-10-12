plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.camylla.proof"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.camylla.proof"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("camyllaVersionCode").map(String::toInt).getOrElse(5)
        versionName = providers.gradleProperty("camyllaVersion").getOrElse("0.5-simple-pairing")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val cameraX = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
