plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "vn.scamshield"
    compileSdk = 36
    defaultConfig {
        applicationId = "vn.scamshield"
        minSdk = 24
        targetSdk = 34
        versionCode = 23
        versionName = "4.18"

        // Android phones/tablets use ARM; do not package emulator-only x86 native libraries.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    buildTypes {
        getByName("debug") {
            // Keep the installable debug APK small by removing unused code/resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

    // TFLite is memory-mapped by the runtime; keep the model asset uncompressed in the APK.
    androidResources {
        noCompress += "tflite"
    }
}

/*
 * The two model files live at the repository root so model training/export does not
 * pollute the app module. Copy only these explicit files into generated Android assets.
 */
val generatedDeepfakeAssets = layout.buildDirectory.dir("generated/deepfakeAssets")
val copyDeepfakeAssets by tasks.registering(Copy::class) {
    from(rootProject.file("deepfake_model.tflite"))
    from(rootProject.file("deepfake_config.json"))
    into(generatedDeepfakeAssets)
}
android.sourceSets.getByName("main").assets.srcDir(generatedDeepfakeAssets)
tasks.named("preBuild").configure {
    dependsOn(copyDeepfakeAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")   // chạy mô hình scam_text.tflite
    implementation("app.rive:rive-android:11.16.0")
}
