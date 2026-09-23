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
            // The device under test only. Shipping other ABIs would just make
            // the APK bigger for no benefit.
            abiFilters += "arm64-v8a"
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

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.junit)
}
