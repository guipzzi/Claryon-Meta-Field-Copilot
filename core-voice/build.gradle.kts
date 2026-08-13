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

    defaultConfig {
        minSdk = 31
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

    testImplementation(libs.junit)
}
