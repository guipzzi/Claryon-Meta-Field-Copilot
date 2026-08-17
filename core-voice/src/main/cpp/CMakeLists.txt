# CMake do Claryon Field para o whisper.cpp on-device.
# Baseada no exemplo oficial (examples/whisper.android/lib/src/main/jni/whisper),
# com WHISPER_LIB_DIR apontando para o submódulo local (core-voice/src/main/cpp/whisper).
cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

# Raiz do whisper.cpp (submódulo vendorizado).
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/whisper)

# Versão do whisper (para logging).
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1} PARENT_SCOPE)
else()
    set(WHISPER_VERSION "unknown" PARENT_SCOPE)
endif()
message(STATUS " Whisper version: ${WHISPER_VERSION}")

option(GGML_HOME "whisper: Path to external GGML source" OFF)

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va_dotprod")
        # ── +dotprod: os kernels quantizados que estavam sendo compilados fora ──
        #
        # O ggml vendorizado tem **55** usos de `__ARM_FEATURE_DOTPROD` e o
        # `ggml_vdotq_s32` aparece direto nos kernels de
        # ggml/src/ggml-cpu/arch/arm/quants.c. Sem `+dotprod` na linha de comando,
        # esse macro nao e definido e o caminho compilado e o de fallback — e o
        # modelo deste projeto e QUANTIZADO (q5_1), ou seja, e exatamente ali que o
        # tempo e gasto. *Contagem obtida por grep no artefato em 2026-08-17.*
        #
        # `i8mm` ficou de FORA de proposito: o /proc/cpuinfo do aparelho anuncia
        # `asimddp` e `bf16`, mas **nao** `i8mm` (o que aparece e `svei8mm`/
        # `smei8i32`, que sao SVE e SME, features diferentes). Habilitar i8mm aqui
        # geraria instrucao que o aparelho nao executa.
        #
        # Alvo SEPARADO e nao uma flag no existente porque `dotprod` e armv8.2
        # OPCIONAL: um `.so` unico com dotprod trava com SIGILL em aparelho sem a
        # feature. `LibWhisper` escolhe por /proc/cpuinfo, que e o mesmo padrao que
        # o projeto ja usa para `fphp`. Custo: mais um alvo nativo por ABI, e o
        # build nativo do whisper ja e a parte lenta.
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16+dotprod)
        set(GGML_COMPILE_OPTIONS                      -march=armv8.2-a+fp16+dotprod)
    elseif (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS                      -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS                      -mfpu=neon-vfpv4)
    endif ()

    # ── Otimizacao SEMPRE, inclusive no Debug ─────────────────────────────────
    #
    # **Isto era um defeito de medicao, e ele invalidou uma sessao inteira de
    # numeros de latencia.** O bloco original aplicava -O3 apenas quando
    # CMAKE_BUILD_TYPE != Debug. Como os testes instrumentados rodam na variante
    # debug, todo o ggml e o whisper.cpp compilavam SEM flag de otimizacao nenhuma
    # e COM as asercoes ativas.
    #
    # Lido de core-voice/.cxx/Debug/*/arm64-v8a/compile_commands.json em
    # 2026-08-17: '-O' vazio, '-DNDEBUG' ausente. No RelWithDebInfo: '-O3' e
    # '-DNDEBUG' presentes. Foi assim que "o STT leva 14,9 s" entrou no ESTADO.md
    # descrevendo codigo que o produto nunca executa.
    #
    # Por que otimizar o nativo no debug nao custa nada que importe aqui: o que se
    # depura neste projeto e Kotlin, e o Kotlin nao e afetado. Ninguem coloca
    # breakpoint dentro de um kernel de matmul do ggml — e se um dia precisar,
    # comenta esta linha na hora.
    #
    # NDEBUG junto porque as asercoes do ggml rodam DENTRO dos lacos internos.
    target_compile_options(${target_name} PRIVATE -O3 -DNDEBUG)

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)
        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif ()

    if (GGML_HOME)
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
    else()
        FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    endif()
    FetchContent_MakeAvailable(ggml)
    # ── E os alvos do ggml ───────────────────────────────────────────────────
    #
    # O `ggml` do FetchContent nao herda nada do alvo do whisper, e — pior — ele
    # proprio e so a casca: `ggml/src/CMakeLists.txt` cria `ggml-base` (linha 192),
    # `ggml` (242) e um alvo de backend por `ggml_add_backend_library`, que aqui e
    # `ggml-cpu`. **Os kernels de matmul quantizado moram em `ggml-cpu`.**
    #
    # Verificado na marra: aplicar -O3 so em `ggml` deixou `quants.c`, `ggml-cpu.c`
    # e `ggml-quants.c` compilando com '-O' VAZIO — exatamente os arquivos que
    # dominam o tempo de inferencia. O `compile_commands.json` mostrou isso e foi
    # o unico jeito de descobrir; nenhuma leitura do CMakeLists sugeria.
    #
    # `if (TARGET ...)` porque o conjunto de backends depende da configuracao: um
    # alvo ausente derrubaria o configure inteiro em vez de degradar.
    foreach (ggml_alvo ggml ggml-base ggml-cpu)
        if (TARGET ${ggml_alvo})
            target_compile_options(${ggml_alvo} PRIVATE -O3 -DNDEBUG)
        endif ()
    endforeach ()
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper") # alvo padrão

# ── A arquitetura do ggml, UMA vez e no fim ──────────────────────────────────
#
# **O esquema de multiplas variantes de `libwhisper` e ficcao, e a medicao
# provou.** `FetchContent_MakeAvailable(ggml)` e idempotente: as tres chamadas a
# `build_library` compartilham UM alvo `ggml-cpu`, e os kernels de matmul moram
# nele. `target_compile_options` ACUMULA, entao a linha de comando de
# `arch/arm/quants.c` saia com dois `-march` e o ULTIMO vence.
#
# Verificado em 2026-08-17, tres camadas:
#   - `compile_commands.json`: dois `-march` em `quants.c`, o ultimo sem dotprod
#   - `llvm-objdump -d libggml-cpu.so`: **sdot = 0, smmla = 0**
#   - o APK traz **um** `libggml-cpu.so`, e os dois `libwhisper*` variam 16 bytes
#
# Ou seja: a variante "_dotprod" existia, era escolhida em runtime, e nao mudava
# uma instrucao. Ela foi removida.
#
# **E o esquema ja era inseguro antes disto.** O mesmo `libggml-cpu.so` tem 1765
# instrucoes FP16 vetoriais, e `libwhisper.so` — que `LibWhisper` escolhe
# justamente quando `/proc/cpuinfo` NAO tem `fphp` — depende dele. O "fallback"
# para aparelho sem FP16 executaria FP16: SIGILL. Nao aparece no emulador Apple
# Silicon nem em celular moderno; apareceria no aparelho de campo barato.
#
# Portanto a escolha honesta e **declarar o piso** em vez de fingir degradacao:
# ARMv8.2 + FEAT_FP16 + FEAT_DotProd. Todo arm64 Android de 2018 em diante
# atende, e o `minSdk 31` (Android 12, 2021) ja e mais restritivo que isso.
#
# O caminho para degradacao de verdade existe e nao foi tomado agora:
# `GGML_CPU_ALL_VARIANTS` (`ggml/CMakeLists.txt:183`) constroi um backend por
# nivel de ISA e despacha em runtime — mas exige `GGML_BACKEND_DL` e
# `BUILD_SHARED_LIBS`, que mudam como o `.so` e carregado. Fica registrado.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    foreach (ggml_alvo ggml ggml-base ggml-cpu)
        if (TARGET ${ggml_alvo})
            target_compile_options(${ggml_alvo} PRIVATE -march=armv8.2-a+fp16+dotprod)
        endif ()
    endforeach ()
endif ()

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
