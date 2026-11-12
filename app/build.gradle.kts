plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.framecritic.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.framecritic.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 24
        versionName = "2.2.0"
        // GitHub Actions run number is the same number the release tag uses (offline-v2-N),
        // so the Build label on screen identifies the installed APK exactly.
        buildConfigField("int", "CI_RUN_NUMBER", System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // 빌드 58부터 앱은 네이티브 추론 프로세스를 쓰지 않는다. 검증용으로만 만드는
    // libframecritic-server.so(~90MB)는 APK에 넣지 않는다.
    packaging { jniLibs { useLegacyPackaging = true; excludes += "**/libframecritic-server.so" } }
    // The critic engine is measurement-only: the GGUF language/vision weights are no longer loaded,
    // so they are kept out of the APK to keep install size and download time small.
    androidResources {
        noCompress += listOf("gguf", "tflite")
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!*.gguf"
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
