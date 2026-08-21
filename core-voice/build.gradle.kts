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

        // ── ggml ESTÁTICO dentro do libwhisper, e por que isso é a Fase 4 ────────
        //
        // Sem esta linha o ggml vira `libggml.so` + `libggml-base.so` +
        // `libggml-cpu.so` soltos no APK. Não por decisão nossa: em
        // `whisper/ggml/CMakeLists.txt:74`, fora de Emscripten e MinGW,
        // `BUILD_SHARED_LIBS_DEFAULT` é ON, e a `option()` da linha 85 apenas herda
        // esse default. Ninguém escolheu — é o padrão do CMake fora do Windows.
        //
        // O llama.cpp da Etapa B vendoriza O MESMO ggml e produz OS MESMOS NOMES:
        // confirmado em `examples/llama.android/lib/build.gradle.kts:27,33` do repo
        // oficial `ggml-org/llama.cpp`, que passa `-DBUILD_SHARED_LIBS=ON` e
        // `-DGGML_BACKEND_DL=ON`. E `lib/arm64-v8a/` dentro do APK é diretório
        // PLANO: dois `libggml-base.so` de revisões diferentes não coexistem. Ou o
        // merge falha, ou um `pickFirst` faz whisper e llama linkarem contra uma
        // única revisão — ABI incompatível, crash só em runtime.
        //
        // Estático corta a colisão na raiz em vez de administrá-la: o whisper não
        // exporta `.so` de ggml nenhum, e o llama.cpp fica livre para usar a
        // configuração oficial dele, que é a suportada.
        //
        // ── POR QUE PELO GRADLE E NÃO POR `set()` NO CMakeLists ──────────────────
        //
        // Isto é armadilha de política, e ela decidiria entre conserto e falso
        // sucesso. `CMP0077` (o que faz `option()` respeitar variável normal já
        // existente) entrou no CMake 3.13. Os escopos aqui declaram:
        //
        //   nosso CMakeLists  cmake_minimum_required(VERSION 3.10)   → CMP0077 OLD
        //   whisper           cmake_minimum_required(VERSION 3.5)    → CMP0077 OLD
        //   whisper/ggml      cmake_minimum_required(3.14...3.28)    → CMP0077 NEW
        //
        // Sob OLD, `option()` APAGA a variável normal e cria a de cache. Um
        // `set(BUILD_SHARED_LIBS OFF)` no nosso CMakeLists seria descartado calado,
        // o build continuaria produzindo os três `.so`, e o relatório diria
        // "resolvido". Um `-D` na linha de comando cria entrada de CACHE, que
        // `option()` respeita sob as DUAS políticas. É também o que o exemplo
        // oficial do llama.cpp faz — ele passa a opção pelo Gradle, não por `set()`.
        //
        // O custo está medido no commit, não estimado: `unzip -l` antes e depois.
        externalNativeBuild {
            cmake {
                arguments += "-DBUILD_SHARED_LIBS=OFF"
            }
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
