// core-llm — Etapa B da Fase 4: o LLM como camada de REDAÇÃO, nunca de decisão.
//
// **A fronteira deste módulo é normativa, igual à do `core-knowledge`.** A lista
// de dependências abaixo não tem `core-agent` e não pode ganhar: sem `Intent`,
// `IntentExecutor` e `ActionOutcome` no classpath, nenhuma linha daqui consegue
// nomear um efeito no mundo, nem por engano. O LLM redige texto; ação continua
// vindo do `DeterministicIntentRouter`, que é regex e não modelo.
//
// Também não depende de `core-knowledge`: o redator recebe **strings** e devolve
// **string**. Assim ele não sabe o que é norma, o que é citação nem o que é
// limiar — e a decisão de recusar (que é regra de produto) fica onde já está.
//
// O motor é o llama.cpp, decisão humana de 20/08. Não há artefato Maven a
// consumir: o caminho Android oficial é build de FONTE, confirmado no repo
// `ggml-org/llama.cpp` em 2026-08-21 (Regra Zero). Ver
// `src/main/cpp/CMakeLists.txt` para onde e por que divergimos do exemplo
// oficial.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.llm"
    compileSdk = 35

    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 31
        ndk {
            // As MESMAS do `core-voice` e do `app`. ABI compilada aqui e ausente
            // lá (ou o contrário) produz APK que instala e falha só no aparelho
            // daquela arquitetura.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // ── POR QUE TUDO ENTRA COMO `-D` E NADA COMO `set()` ──────────
                //
                // `CMP0077` — o que faz `option()` respeitar variável normal já
                // definida — entrou no CMake 3.13. O escopo do llama.cpp declara
                // `cmake_minimum_required(VERSION 3.14...3.28)`, ou seja NEW; o
                // nosso declara 3.22.1, também NEW. Ainda assim NADA é ajustado
                // por `set()`: em 21/08 essa exata armadilha quase produziu falso
                // sucesso do lado do whisper, e a regra "opção de terceiro entra
                // por argumento de CACHE" é barata demais para ser abandonada
                // quando a política *hoje* seria favorável. Basta um upstream
                // baixar o `cmake_minimum_required` para o defeito voltar mudo.

                // ── A LINHA QUE JÁ INVALIDOU UMA MEDIÇÃO DESTA SESSÃO ────────
                //
                // Sem ela, o AGP passa `CMAKE_BUILD_TYPE=Debug` na variante
                // debug e o llama.cpp inteiro compila **sem flag de otimização
                // nenhuma e com as asserções ligadas**. Não é hipótese: lido em
                // `core-llm/.cxx/tools/debug/arm64-v8a/compile_commands.json`
                // em 21/08 — `ggml-cpu.c`, `quants.c` e `llama-model.cpp` saíam
                // com `-g -fno-limit-debug-info` e **nenhum `-O`**, nenhum
                // `-DNDEBUG`. O `-march` aplicava; a otimização, não.
                //
                // O efeito medido no emulador: prefill de **5 a 6 tokens/s**,
                // 249 tokens de prompt em 37–54 s. Foi o primeiro número que
                // este módulo produziu, e ele descrevia código que o produto
                // nunca vai executar — a mesma família de erro que fez "o STT
                // leva 14,9 s" entrar no ESTADO.md em 17/08 pelo mesmo motivo,
                // no `core-voice`.
                //
                // O exemplo oficial do llama.cpp passa exatamente esta opção, e
                // é a PRIMEIRA da lista dele (`lib/build.gradle.kts`). Ler e não
                // aplicar foi o defeito.
                //
                // Otimizar o nativo no debug não custa nada que importe aqui: o
                // que se depura neste projeto é Kotlin.
                arguments += "-DCMAKE_BUILD_TYPE=Release"

                // Estático: o llama não publica `.so` de ggml no APK, e a
                // colisão com o whisper não tem como voltar. Ver o CMakeLists.
                arguments += "-DBUILD_SHARED_LIBS=OFF"

                // `common` traz parser de JSON, minja e afins que só servem aos
                // exemplos. O JNI usa apenas `llama.h`, que já tem tokenizador,
                // template de chat e cadeia de amostragem.
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_UI=OFF"
                arguments += "-DLLAMA_USE_PREBUILT_UI=OFF"

                // Rede, explicitamente desligada, mesmo sem chamador: o pilar P3
                // é 100% local e a proibição não depende de ninguém lembrar.
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DLLAMA_CURL=OFF"

                // `GGML_NATIVE` mediria a CPU do MAC que compila. É cross-compile.
                arguments += "-DGGML_NATIVE=OFF"

                // `GGML_BACKEND_DL`/`CPU_ALL_VARIANTS` exigem `BUILD_SHARED_LIBS`
                // e existem para despachar por nível de ISA em runtime. Este
                // projeto já DECLAROU o piso (ARMv8.2 + FP16 + DotProd) em
                // `core-voice/src/main/cpp/CMakeLists.txt`; pagar N backends de
                // CPU no APK compraria degradação que não vamos usar.
                arguments += "-DGGML_BACKEND_DL=OFF"
                arguments += "-DGGML_CPU_ALL_VARIANTS=OFF"

                // KleidiAI é buscado por `FetchContent` do repositório da ARM —
                // rede em tempo de build, e build que depende de rede quebra
                // sozinho na véspera. Fica registrado como desempenho na mesa.
                arguments += "-DGGML_CPU_KLEIDIAI=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_CCACHE=OFF"

                // O MESMO piso de ISA declarado para o whisper, agora explícito
                // (`ggml/src/ggml-cpu/CMakeLists.txt:169` lê esta variável).
                // Ignorada em x86_64, e o CMake avisa que não a usou — é ruído
                // esperado, não defeito.
                arguments += "-DGGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // A versão instalada no SDK desta máquina. O exemplo oficial pede
            // 3.31.6, que aqui não existe — por isso o nosso CMakeLists declara
            // 3.22.1 e não copia o `cmake_minimum_required` de lá.
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

    testImplementation(libs.junit)
}
