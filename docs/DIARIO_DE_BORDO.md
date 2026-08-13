# Diário de Bordo — Claryon Field

Registro cronológico, didático e direto do desenvolvimento. Cada entrada explica
**o que** foi feito e, principalmente, **por quê** — para que qualquer pessoa da
equipe (ou uma futura sessão de trabalho) retome o contexto sem arqueologia.

Documentos irmãos:
- [`DECISIONS.md`](../DECISIONS.md) — uma linha por decisão não óbvia (o *registro formal*).
- [`README.md`](../README.md) — setup e visão geral.
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — sequências e contratos.
- [`docs/GUIA_TECNICO.md`](GUIA_TECNICO.md) — briefing de engenharia original.
- Este diário — a *narrativa* de como chegamos aqui.

---

## O que é o projeto, em um parágrafo

**Claryon Field** é um app companion Android/Kotlin para os óculos **Ray-Ban Meta
(sem display)**, usando o **Meta Wearables Device Access Toolkit (DAT)**. É um
**copiloto de voz para o agente de segurança pública**: ele fala, o app entende
**localmente** (sem nuvem no caminho crítico), age (pede apoio no WhatsApp, grava
evidência, consulta placa) e responde por **áudio no ouvido**. A ausência de tela
é a especificação, não uma limitação — o canal visual do agente fica livre para o
ambiente. Entrega no hackathon presencial em **18/09/2026**.

---

## 2026-08-13 — M0: esqueleto e contratos

**Objetivo do marco:** ter um repositório multi-módulo que **compila** (`./gradlew
build` verde), com todas as interfaces de risco fixadas, mas **sem implementação**
e **sem tocar o SDK do DAT**.

**O que foi feito:**
- Provisionei o toolchain do zero (a máquina estava sem nada): **JDK 17**, **Android
  SDK 34/Build-Tools 34**, tudo via Homebrew, sem sudo. Gerei o **Gradle Wrapper
  8.9** para o build ser reproduzível (`./gradlew`).
- Criei **9 módulos** seguindo a arquitetura do guia: `app` + `core-common`,
  `core-agent`, `core-glasses`, `core-audio`, `core-voice`, `core-sound`,
  `core-evidence`, `core-sync`.
- **Regra de dependência:** os `core-*` só dependem de `core-common`; `app`
  orquestra todos. Isso permite trabalho paralelo e troca de implementação sem
  refatoração em cascata.
- Fixei as **interfaces do §3.1/§3.2** do guia: `SttEngine`, `TtsEngine`,
  `WakeWordDetector`, `VoiceActivityDetector`, `GlassesFacade`,
  `GlassesAudioManager`, `MessagingGateway`, `EvidenceVault`, `SoundQueue`,
  `IntentRouter` e o modelo `Intent`.
- Escrevi peças puras já testáveis: tipo `Result` tipado, `ClaryonError`,
  `LaconicityPolicy` (regra das ≤7 palavras) — com testes unitários.

**Decisões didáticas:**
- **Por que `core-common` e `core-agent` são Kotlin/JVM puro (e não Android)?**
  Porque a fundação (Result/telemetria) e o roteador determinístico não têm nada
  de Android. Sendo JVM puro, rodam em JUnit local, sem emulador — teste rápido e
  barato, essencial para o roteador que precisa de 92% de acurácia.
- **Por que uma fachada `GlassesFacade`?** Para isolar o ÚNICO ponto do código que
  depende de uma API em *developer preview*. Quando o SDK quebrar assinaturas,
  conserta-se um arquivo, não a base inteira.

**Resultado:** `./gradlew build` verde nos 9 módulos. Marco cumprido.

---

## 2026-08-13 — Configuração do MCP de docs vivas do DAT

**O problema:** a **Regra Zero** do projeto proíbe escrever qualquer assinatura do
DAT de memória (o SDK mudou depois do corte de treinamento de qualquer modelo). O
antídoto é o MCP `search_dat_docs`, que consulta a documentação oficial em tempo
real. Ele não estava configurado.

**O que foi feito:**
- Registrei o servidor MCP `meta-wearables` →
  `https://mcp.developer.meta.com/wearables` em **escopo de projeto** (arquivo
  `.mcp.json`, versionado, para toda a equipe herdar).
- Verifiquei que o endpoint responde **sem autenticação** e expõe as ferramentas
  `search_dat_docs` e `search_webapps_docs`.
- Rodei o **smoke test** do guia ("camera streaming setup on Android") e a doc
  viva retornou a API real — confirmando a versão vigente do SDK.

**Aprendizado:** o MCP carregou na própria sessão (não exigiu reiniciar). A partir
daqui, toda API do DAT é confirmada na fonte antes de virar código.

---

## 2026-08-13 — M1: integração do DAT 0.9.0

**Objetivo do marco:** o SDK do DAT resolvendo e compilando no projeto, com o
manifest correto — sem ainda escrever chamadas de API (isso é o M2).

**O que foi feito:**
- **Repositório GitHub Packages** em `settings.gradle.kts`, lendo o PAT
  `read:packages` de `local.properties` (chave `github_token`) ou da env
  `GITHUB_TOKEN`. O token **nunca** é versionado.
- Declarei `mwdat 0.9.0` (`com.meta.wearable:mwdat-core/camera/mockdevice`) no
  catálogo de versões e adicionei as deps **só em `core-glasses`**. `mwdat-display`
  foi omitido de propósito: nossos óculos não têm display.
- Manifest com as permissões e meta-data confirmados na doc (ver abaixo).

**A saga do toolchain (três obstáculos reais, todos resolvidos):**
1. **`401 Unauthorized`** — o PAT tinha sido colado sem o último caractere.
   Corrigido; a partir daí os artefatos baixaram.
2. **`Kotlin metadata 2.2.0, expected 1.9.0`** — o `mwdat-core` foi compilado com
   **Kotlin 2.2**, e o Kotlin 1.9.24 (que escolhemos no M0 pela estabilidade com
   Compose) não consegue lê-lo. **Subimos o projeto para Kotlin 2.2.0.** Efeito
   colateral: no Kotlin 2.x o Compose passa a usar o plugin
   `org.jetbrains.kotlin.plugin.compose` (o `kotlinCompilerExtensionVersion`
   deixou de existir).
3. **`requires compileSdk 35`** — o DAT puxa AndroidX novo (`activity 1.10.1`) que
   exige **compileSdk 35**; o AGP 8.5.2 tem teto no 34. Subimos para **AGP 8.7.2**
   (compatível com o Gradle 8.9) e **compileSdk/targetSdk 35**; instalamos a
   plataforma `android-35`.

**Uma decisão que precisa ficar clara:** o **Android Lint ficou desligado**
temporariamente. O lint embarcado no AGP 8.7.2 quebra com
`IncompatibleClassChangeError` ao analisar código Kotlin 2.2 — é um **bug do
ferramental**, não do nosso código (nem usamos LiveData, que é o detector que
estoura). Desabilitar o detector específico não impede o crash, então desligamos
as tasks `lint*` na raiz. Compilação e testes unitários seguem ativos. **TODO:
reativar o lint quando houver combinação AGP/lint compatível com Kotlin 2.2
(revisitar no M8).**

**Manifest (confirmado via `search_dat_docs`):**
- Permissões: `BLUETOOTH`, `BLUETOOTH_CONNECT`, `INTERNET`, `RECORD_AUDIO`,
  `CAMERA` + `uses-feature camera required=false`. (`RECORD_AUDIO` é nosso, para a
  captura HFP — não passa pelo DAT.)
- Meta-data `com.meta.wearable.mwdat.APPLICATION_ID`/`CLIENT_TOKEN` = `0` — em
  Developer Mode a attestation não é usada.
- Intent-filter `claryonfield://` (VIEW + DEFAULT + BROWSABLE) para o retorno do
  app Meta AI.

**API do DAT confirmada para o M2 (não escrita de memória):**
- Registro: `Wearables.startRegistration(activity)`,
  `Wearables.registrationState.collect { }`, `Wearables.devices.collect { }`.
- Câmera: `session.addCamera(StreamConfiguration(videoQuality=…, frameRate=…))`;
  `frameRate ∈ {2,7,15,24,30}`; `VideoQuality ∈ {LOW,MEDIUM,HIGH}`;
  `StreamState: STARTING→STARTED→STREAMING→PAUSED→STOPPING→STOPPED→CLOSED`.

**Resultado:** `./gradlew clean build` verde com os artefatos `mwdat-*`
resolvidos; o APK sobe de ~22 MB para ~52 MB (libs nativas do DAT embutidas).
Marco cumprido.

---

## 2026-08-13 — Leitura do material do curso e revisão de compliance

**O que foi feito:** leitura integral dos materiais de apoio técnicos do curso
CEIA/Meta (Un12 — Edge-AI/Android, 94 páginas; Un13 — DAT, 64 páginas) e
identificação de Un10/Un11/Un14, para extrair guidelines e conferir se o que
construímos faz sentido.

**Resultado — validação forte:** o material **confirma quase todas as nossas
escolhas**. whisper.cpp (ggml-tiny quantizado), Silero VAD, openWakeWord,
Piper/sherpa-onnx, ML Kit OCR, ForegroundService com os três tipos, WorkManager
com constraints, cascata gatilho-nunca-loop, roteador determinístico,
`getThermalHeadroom`, `conflate`, "HFP antes da sessão", isolamento via fachada —
tudo aparece nominalmente no material como o caminho recomendado. Un10/Un11 são
conceituais (bancos vetoriais, memória de agente LLM, RAG em Python) e **não se
aplicam** ao nosso app determinístico — confirmando a orientação do guia de não
arrastar frameworks Python para o Android. Un14 (encerramento) reendossa a Regra
Zero e as restrições de hardware.

**Riscos concretos que a leitura trouxe à tona** (registrados em
[`docs/COMPLIANCE.md`](COMPLIANCE.md) §D, para não se perderem):
1. **8 kHz (HFP) → 16 kHz (Whisper):** o microfone chega a 8 kHz e o Whisper
   espera 16 kHz; exige resample e pode pressionar a meta de 92% de acurácia.
   Validar com áudio HFP real (não com fone melhor). *[M3/M4]*
2. **`SpeechRecognizer` padrão vaza áudio para servidor** — o fallback nativo só
   vale na variante on-device explícita. *[M4]*
3. **`Wearables.initialize` deve ir numa classe `Application`** (ainda não existe;
   criar no M2).
4. **OCR de placa a distância** pode não bastar com ML Kit puro — medir. *[M6]*

**Revisão de M1:** removi um bloco `lint {}` redundante no módulo `app` (o
desligamento global de lint na raiz já cobre tudo). Consolidei o checklist de
compliance por milestone em [`docs/COMPLIANCE.md`](COMPLIANCE.md) — é o documento a
consultar antes de declarar qualquer marco "pronto".

**Acesso à documentação:** confirmado — MCP DAT ativo, `llms.txt` (200), repo
oficial público, GitHub Packages resolve, 15 PDFs do curso lidos/mapeados.

---

## Estado atual (fim de 2026-08-13)

- **Marcos concluídos:** M0, MCP, M1.
- **Build:** verde (`./gradlew clean build`), lint desligado (workaround documentado).
- **Toolchain:** JDK 17 · Gradle 8.9 · AGP 8.7.2 · Kotlin 2.2.0 · compileSdk 35 · minSdk 31.
- **DAT:** `mwdat 0.9.0` integrado em `core-glasses`.
- **Próximo marco (M2):** MockDeviceKit — `Wearables.initialize()`, fluxo de
  registro e a câmera do celular como fonte simulada, com o estado transitando no
  painel de diagnóstico. Ainda **sem óculos**.

**Pendências conhecidas (registradas para não se perderem):**
- Reativar o Android Lint quando o ferramental acompanhar o Kotlin 2.2 (M8).
- Revogar e rotacionar o PAT do GitHub (foi exposto em conversa durante o setup).
- Confirmar a versão mais recente do `mwdat` em GitHub Packages no início de cada marco que toque o SDK.
