# CMake do Claryon Field para o llama.cpp on-device (Etapa B da Fase 4).
#
# Espelha a forma que o `core-voice` ja usa para o whisper.cpp: o upstream e
# vendorizado em `llama/` e entra por `add_subdirectory`. E a mesma forma do
# exemplo oficial `examples/llama.android/lib/src/main/cpp/CMakeLists.txt`, que
# faz `add_subdirectory(${LLAMA_SRC} build-llama)` — conferido no repositorio
# `ggml-org/llama.cpp` em 2026-08-21 (Regra Zero: nao ha artefato Maven a
# confirmar; o caminho Android oficial e build de FONTE).
#
# ── ONDE ESTE ARQUIVO DIVERGE DO EXEMPLO OFICIAL, E POR QUE ───────────────────
#
# O exemplo oficial passa `-DBUILD_SHARED_LIBS=ON` + `-DGGML_BACKEND_DL=ON` +
# `-DGGML_CPU_ALL_VARIANTS=ON`. Aqui e o contrario: TUDO ESTATICO dentro de
# `libclaryonllm.so`. Tres motivos, nesta ordem:
#
#  1. O `core-voice` ja escolheu estatico em 21/08 e o APK ficou com ZERO
#     libggml. Se o llama entrasse compartilhado, ele publicaria `libggml.so`,
#     `libggml-base.so` e `libggml-cpu*.so` num `lib/arm64-v8a/` que e diretorio
#     PLANO — e a colisao voltaria, agora com o whisper de vitima silenciosa.
#  2. `GGML_CPU_ALL_VARIANTS` existe para despachar por nivel de ISA em runtime.
#     Este projeto ja DECLAROU o piso (ARMv8.2 + FP16 + DotProd, ver o rodape de
#     `core-voice/src/main/cpp/CMakeLists.txt`), entao pagar N backends de CPU
#     dentro do APK compraria uma degradacao que nao vamos usar.
#  3. Estatico + `--gc-sections` puxa so o que o llama de fato chama.
#
# ── A ARMADILHA QUE ESTE ARQUIVO NAO PODE REPETIR ────────────────────────────
#
# `CMP0077` (fazer `option()` respeitar variavel normal ja definida) so entrou no
# CMake 3.13. Sob politica OLD, `set(BUILD_SHARED_LIBS OFF)` ANTES do
# `add_subdirectory` e DESCARTADO EM SILENCIO — o build continuaria produzindo
# `.so` de ggml e o relatorio diria "resolvido". Por isso NENHUMA opcao de
# terceiro e ajustada por `set()` aqui: todas entram como `-D` pelo Gradle
# (`core-llm/build.gradle.kts`), que vira entrada de CACHE e e respeitada sob as
# duas politicas. E tambem o que o exemplo oficial faz.
cmake_minimum_required(VERSION 3.22.1)

project("claryon-llm" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama)

if (NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp nao esta em ${LLAMA_SRC}. Rode o setup do README (clone raso "
        "de ggml-org/llama.cpp). Falhar aqui e melhor que compilar pela metade.")
endif ()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(claryonllm SHARED redator_jni.cpp)

target_include_directories(claryonllm PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include)

# ── VISIBILIDADE ESCONDIDA EM TODO BUILD TYPE, INCLUSIVE DEBUG ───────────────
#
# Isto NAO e otimizacao de tamanho; e correcao. `libwhisper.so` e
# `libclaryonllm.so` carregam CADA UM a sua copia estatica do ggml, e as duas
# copias sao de REVISOES DIFERENTES (whisper.cpp e llama.cpp evoluem em cadencias
# proprias). No build debug, o `core-voice` so aplica `-fvisibility=hidden` fora
# do Debug — ou seja, `libwhisper.so` de debug EXPORTA seus ~456 simbolos
# `ggml_*` com visibilidade default.
#
# Se `libclaryonllm.so` tambem exportasse `ggml_*`, as chamadas passariam pela
# PLT e o linker dinamico do Android resolveria pela ordem de carga: uma chamada
# do llama poderia cair na implementacao do whisper. Isso nao quebra o build —
# quebra em runtime, com corrupcao de memoria, e so as vezes.
#
# Escondendo aqui, os simbolos do ggml/llama viram LOCAIS neste `.so` e nao ha
# interposicao possivel em direcao nenhuma. `--exclude-libs,ALL` faz o mesmo pelo
# que veio das bibliotecas estaticas. Conferivel com `llvm-readelf --dyn-symbols`:
# a unica coisa exportada tem de ser `Java_com_claryon_llm_*`.
target_compile_options(claryonllm PRIVATE
    -O3 -DNDEBUG
    -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections)
target_link_options(claryonllm PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL)

find_library(LOG_LIB log)

target_link_libraries(claryonllm ${LOG_LIB} android llama)
