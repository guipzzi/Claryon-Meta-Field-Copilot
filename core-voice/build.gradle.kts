// core-voice — pipeline de voz 100% on-device: WakeWord, VAD, STT, TTS.
// Toda peça de risco tem interface com dois back-ends (primário + fallback
// nativo do Android), para permitir plano B sem reescrita. No M0, apenas os
// contratos e tipos; implementações nativas (whisper.cpp, sherpa-onnx, Silero,
// openWakeWord) chegam no M4.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.voice"
    compileSdk = 35

    // NDK: compila o whisper.cpp (submódulo) via CMake. arm64-v8a cobre os
    // celulares modernos e o emulador arm64; outras ABIs entram no release.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 31
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-common"))

    // Piper (TTS neural) via sherpa-onnx — API Kotlin `com.k2fsa.sherpa.onnx`.
    // compileOnly aqui (módulo library não repackagea AAR); o `app` empacota as
    // .so + classes via implementation. AAR baixado no setup (ver README).
    compileOnly(":sherpa-onnx-1.13.5@aar")

    testImplementation(libs.junit)
}
