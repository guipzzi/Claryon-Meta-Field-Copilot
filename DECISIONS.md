# DECISIONS.md — Claryon Field

Uma linha por decisão não óbvia: data · decisão · alternativa descartada · motivo.
Ordem cronológica inversa (mais recente no topo).

---

## 2026-08-13 — M2 (Mock Device Kit + registro/sessão/câmera)

- **Fonte autoritativa: sample oficial `CameraAccess` (repo `facebook/meta-wearables-dat-android`, tag 0.9.0).** Clonado num diretório irmão e lido para confirmar TODAS as assinaturas 0.9 antes de escrever (Regra Zero, "samples > docs"). Correções vs. material 0.8: `RegistrationState` = {UNAVAILABLE, REGISTERING, REGISTERED, UNREGISTERING} (sem "AVAILABLE"); câmera 0.9 é `session.addCamera(config): DatResult<Camera>` → `Camera.stream` (não `addStream`); `DatResult` usa `.onSuccess/.onFailure { error, _ -> }` (evitar `getOrThrow` em produção); `session.start()` retorna `Unit` (resultado via `session.state`/`session.errors`).

- **`Wearables.initialize` encapsulado em `core-glasses` (`GlassesRuntime.initialize`), chamado no `ClaryonApp: Application`.** O compilador provou a fronteira: o `app` não consegue importar `Wearables` (deps do DAT são `implementation` em core-glasses, não `api`) — o isolamento da fachada é garantido pelo módulo, não só por convenção.

- **Enums do DAT mapeados por NOME** (`enumValueOf<T>(state.name)` com fallback) em vez de referenciar constantes — resiliente a acréscimos no SDK em preview e reduz risco de erro de memória.

- **`DatGlassesFacade`** implementa `GlassesFacade` sobre a 0.9 (registro, sessão, `addCamera`→`Camera`→`stream`, `capturePhoto`) e expõe StateFlows extras (streamState/frameInfo/deviceCount) para o diagnóstico. **`MockDeviceController`** (debug) faz `enable → pairGlasses(RAYBAN_META) → powerOn → don → setCameraFeed(CameraFacing)` (câmera do celular). Painel Compose reflete tudo ao vivo.

- **Aceite de build: `./gradlew :app:assembleDebug` e `:app:assembleDebugAndroidTest` verdes contra o SDK 0.9** (APK ~56 MB, libs nativas do DAT). **Verificação de runtime** (transição de estados + frames) via teste instrumentado `MockDeviceKitStreamTest` + emulador — em andamento (a máquina não tinha emulador; imagem android-35 baixada nesta sessão).

- **Pendência de compliance (registrada):** `mwdat-mockdevice` ainda é `implementation` (não `debugImplementation`) e `MockDeviceController` vive em `src/main` gated por `BuildConfig.DEBUG` no chamador. Mover para `src/debug` no próximo passo.

---

## 2026-08-13 — Revisão de compliance e leitura do material do curso

- **M0/M1 em conformidade com o edital e o material teórico.** Leitura integral de Un12 (Edge-AI/Android, 94 p.) e Un13 (DAT, 64 p.) valida quase todas as escolhas (whisper.cpp, Silero, openWakeWord, Piper/sherpa-onnx, ML Kit, FGS, WorkManager, cascata, roteador determinístico, `getThermalHeadroom`). Un10/Un11 são conceituais (RAG/vetorial/Python) e corretamente fora de escopo. Guidelines e checklist por milestone consolidados em `docs/COMPLIANCE.md`.

- **Revisão de M1: removido bloco `lint {}` redundante do `app`.** O desligamento global de lint na raiz já cobre tudo; o bloco por módulo era inócuo e enganoso.

- **Risco registrado (não é defeito): HFP entrega 8 kHz, Whisper espera 16 kHz.** Exige resample e medição de acurácia com áudio HFP real no M3/M4. Ver `docs/COMPLIANCE.md` §D.

- **`Wearables.initialize` deve ir numa classe `Application` (não Activity)** — criar no M2. Fonte: Un13 p.34 (reconfirmar assinatura 0.9 via `search_dat_docs`).

- **`mwdat-mockdevice` a ser gated por `DEBUG` no M2** (hoje é `implementation` em core-glasses). Produção não deve linkar mock ativo. Fonte: Un13 p.63.

---

## 2026-08-13 — M1 (setup do DAT)

- **DAT 0.9.0 integrado e resolvendo** (`com.meta.wearable:mwdat-core/camera/mockdevice`), via GitHub Packages com PAT `read:packages` em `local.properties` (`github_token`). `mwdat-display` omitido de propósito (óculos sem display). Deps só em `core-glasses`.

- **Kotlin 1.9.24 → 2.2.0 (forçado pelo SDK).** O `mwdat-core` foi compilado com metadata Kotlin 2.2.0; Kotlin 1.9 não consegue lê-lo (`incompatible version ... metadata 2.2.0`). Consequência: Compose passou a usar o plugin `org.jetbrains.kotlin.plugin.compose` (o `kotlinCompilerExtensionVersion` deixou de existir no Kotlin 2.x).

- **compileSdk/targetSdk 34 → 35 e AGP 8.5.2 → 8.7.2.** O DAT puxa AndroidX novo (`activity 1.10.1`) que exige compileSdk 35; o AGP 8.5.2 tem teto no 34. AGP 8.7.2 suporta 35 e é compatível com o Gradle 8.9 do wrapper. Instalada a plataforma `android-35` + `build-tools 35.0.0`.

- **Android Lint temporariamente DESLIGADO (workaround).** O lint do AGP 8.7.2 quebra com `IncompatibleClassChangeError` em `NonNullableMutableLiveDataDetector` ao analisar UAST de código Kotlin 2.2 — bug do ferramental, não do nosso código (nem usamos LiveData). `lintOptions.disable` não impede o detector de executar. Desligadas as tasks `lint*` em `subprojects` (raiz `build.gradle.kts`). Compilação e testes unitários seguem ativos. **TODO: reativar quando houver AGP/lint compatível com Kotlin 2.2 (revisitar no M8).**

- **Manifest do DAT:** permissões `BLUETOOTH`, `BLUETOOTH_CONNECT`, `INTERNET`, `RECORD_AUDIO`, `CAMERA` + `uses-feature camera required=false`; meta-data `com.meta.wearable.mwdat.APPLICATION_ID`/`CLIENT_TOKEN` = `0` (Developer Mode dispensa attestation); intent-filter `claryonfield://` já presente. Fonte: `search_dat_docs`, 2026-08-13.

- **API de registro (Kotlin, para o M2, não escrita de memória):** `Wearables.startRegistration(activity)`, `Wearables.startUnregistration(activity)`, `Wearables.registrationState.collect { }`, `Wearables.devices.collect { }`. Câmera: `session.addCamera(StreamConfiguration(videoQuality=…, frameRate=…))`. Fonte: `search_dat_docs`.

- **Aceite do M1:** `./gradlew clean build` verde com os artefatos `mwdat-*` resolvidos; APK sobe de ~22 MB para ~52 MB (libs nativas do DAT embutidas). A execução de `Wearables.initialize()`/registro sem hardware fica para o M2 (MockDeviceKit).

---

## 2026-08-13 — M0 (contexto e esqueleto)

- **Toolchain fixado: AGP 8.5.2 · Gradle 8.9 · Kotlin 1.9.24 · JDK 17 · compileSdk 34 · minSdk 31.**
  Alternativa: Kotlin 2.0 + plugin `compose`. Descartada por ora — o par Kotlin 1.9.24 ↔ Compose Compiler 1.5.14 é o caminho mais estável e sem surpresas para o primeiro build verde. `minSdk 31` porque as APIs centrais do projeto (`setCommunicationDevice`, `createOnDeviceSpeechRecognizer`, thermal headroom) são API 31+.

- **`core-common` e `core-agent` são módulos Kotlin/JVM puros; os demais `core-*` são `com.android.library`.**
  Alternativa: tudo `com.android.library`. Descartada porque a fundação (Result/telemetria) e o roteador determinístico não têm dependência de Android e ganham em testabilidade rodando em JUnit local, sem emulador nem Android SDK — coerente com "roteamento determinístico, testável, sem LLM".

- **Tipo `Result` próprio (sealed Success/Failure + `ClaryonError` tipado) em vez de `kotlin.Result`.**
  Motivo: erro auditável com `code` estável (telemetria e mapeamento erro→earcon) e caminho de falha visível na assinatura de toda operação de risco. "Falha nunca é silêncio."

- **`GlassesFacade` é o único ponto que tocará o DAT; nenhum outro módulo importa símbolos do SDK.**
  Motivo: isola a dependência de uma API em *developer preview*. Quando a 0.9 quebrar assinaturas, conserta-se um arquivo.

- **Sem `jvmToolchain(...)`; compila com o JDK 17 em execução (source/target 17, jvmTarget 17).**
  Alternativa: `kotlin { jvmToolchain(17) }`. Descartada para evitar provisionamento/resolução de toolchain (download) — a build roda sob `JAVA_HOME` = openjdk@17 e compila direto.

- **Dependências do DAT (`mwdat-core/camera/mockdevice`) NÃO adicionadas no M0.**
  Motivo (Regra Zero): dependem de credencial (PAT `read:packages`) e da versão vigente confirmada via `search_dat_docs`. O repositório Maven do GitHub Packages está como placeholder comentado em `settings.gradle.kts`. **Pendência para o M1.**

- **MCP de docs vivas do DAT configurado (escopo de projeto, `.mcp.json`).**
  Servidor `meta-wearables` → `https://mcp.developer.meta.com/wearables`, transporte HTTP, **sem autenticação** (verificado: `initialize` e `tools/list` respondem 200; ferramentas `search_dat_docs` e `search_webapps_docs` disponíveis). Escopo de projeto para toda a equipe herdar via `.mcp.json`. Só carrega em **nova sessão** do Claude Code (servidores MCP sobem no startup) e exige aprovação de confiança na primeira vez.

- **✅ Versão do SDK do DAT registrada: `mwdat = "0.9.0"`.**
  Fonte: `search_dat_docs` (MCP oficial `meta-wearables`), consulta "Android Gradle dependency setup", 2026-08-13. Grupo `com.meta.wearable`; artefatos `mwdat-core`, `mwdat-camera`, `mwdat-display`, `mwdat-mockdevice`. Repositório Maven: `https://maven.pkg.github.com/facebook/meta-wearables-dat-android` (GitHub Packages, exige PAT `read:packages`). A versão exata mais recente deve ser reconferida em GitHub Packages no início do M1. Fecha o último item de aceite do M0.

- **Credencial do GitHub Packages: `username = ""` + chave `github_token` em `local.properties` (ou env `GITHUB_TOKEN`).**
  Correção sobre o placeholder inicial, que supunha `gpr.user`/`gpr.token`. Forma alinhada à doc oficial (`search_dat_docs`, 2026-08-13). Placeholders em `settings.gradle.kts`, `README.md` e `local.properties` ajustados.

- **API real de câmera confirmada (não escrever de memória): `session.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))`.**
  Fonte: `search_dat_docs` "camera streaming setup on Android", 2026-08-13. `frameRate` válido ∈ {2,7,15,24,30}; `VideoQuality` ∈ {LOW 360×640, MEDIUM 504×896, HIGH 720×1280}; `StreamState`: STARTING→STARTED→STREAMING→PAUSED→STOPPING→STOPPED→CLOSED. Divergências vs. minhas suposições do M0 (`addStream`/`quality`) ficam ABSORVIDAS por `GlassesFacade` — nenhum outro módulo muda. A tradução concreta é escrita no M2.

- **`coroutines-core` exposto como `api` em `core-common`.**
  Motivo: os contratos usam `Flow`/`StateFlow`; expor uma vez evita repetir a dependência em cada módulo consumidor.
