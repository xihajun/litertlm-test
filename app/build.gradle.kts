plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gemmalitertlm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gemmalitertlm"
        minSdk = 28          // MediaPipe LLM Inference needs API 28+ (Android 9)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Native libraries are large; allow legacy packaging so .so files aren't compressed.
        ndk {
            // Limit to common 64-bit ABIs to keep APK size manageable.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // Debug signing for CI builds. Release signing would need a real keystore.
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // For now, sign release with debug key so unsigned APK isn't produced by CI.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        // Compose compiler matching Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
        // .task and .litertlm model files might be packaged later; don't compress them.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // --- Compose BOM and core Compose deps -------------------------------------
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // --- AndroidX core ---------------------------------------------------------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // --- Kotlin coroutines -----------------------------------------------------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- MediaPipe GenAI (this is the public Kotlin/Java front-end to LiteRT-LM)
    // It bundles the LiteRT-LM C++ engine via the .aar's native libs.
    implementation("com.google.mediapipe:tasks-genai:0.10.20")

    // --- OkHttp for downloading model files from HF ----------------------------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Tooling ---------------------------------------------------------------
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
