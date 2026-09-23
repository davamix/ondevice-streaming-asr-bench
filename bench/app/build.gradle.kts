plugins {
    // AGP 9 provides Kotlin support itself; the standalone
    // org.jetbrains.kotlin.android plugin is rejected as of AGP 9.0.
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.davamix.asrbench"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.davamix.asrbench"
        // API 33 is the floor: createOnDeviceSpeechRecognizer() (Arm A) and
        // EXTRA_AUDIO_SOURCE -- feeding a file descriptor instead of the
        // microphone -- both arrived in 33, and the whole method depends on
        // the second one.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a is the device under test. x86_64 is here only so the
            // emulator can run the native arms (Moonshine ships ONNX Runtime
            // for both), which keeps the emulator-first rule in §11.5 usable
            // for Arm B -- otherwise the only place to debug native model
            // loading would be the phone, which is exactly what that rule
            // exists to prevent. No number from x86_64 is ever published (D1).
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // Measurements run against the debug build. Kept explicit so it is
            // obvious in the results write-up which build produced them.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Arm B. Pulls appcompat, material, okhttp and WorkManager transitively --
    // the last two are the SDK's own model downloader, which we bypass by
    // loading pinned .ort files from disk with Transcriber.loadFromFiles().
    implementation(libs.moonshine.voice)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.junit)
}
