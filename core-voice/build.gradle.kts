// core-voice — pipeline de voz 100% on-device: WakeWord, VAD, STT, TTS.
// Toda peça de risco tem interface com dois back-ends (primário + fallback
// nativo do Android), para permitir plano B sem reescrita. Implementado e
// verificado em aparelho: WhisperCppStt (JNI/NDK), PiperTts (sherpa-onnx),
// AndroidTts e AndroidOnDeviceStt (fallbacks), EnergyVoiceActivityDetector.
// A palavra de ativação NÃO mora aqui: `EscutaDeAtivacao` vive no módulo do app,
// onde o microfone e a saída já têm dono único. Este módulo expõe `DetectorDeAtivacao`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.voice"
    compileSdk = 35

    // NDK: compila o whisper.cpp (submódulo) via CMake. As ABIs estão no
    // defaultConfig abaixo e precisam casar com os abiFilters do `app`.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 31
        ndk {
            // arm64-v8a: celulares modernos + emulador Apple Silicon.
            // x86_64: emuladores Intel. (armeabi-v7a — 32 bits, raro — pode ser
            // adicionado no release; dobra o tempo de build nativo do whisper.)
            abiFilters += listOf("arm64-v8a", "x86_64")
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
