# Diário de Bordo — Claryon Field

Registro cronológico, didático e direto do desenvolvimento. Cada entrada explica
**o que** foi feito e, principalmente, **por quê** — para que qualquer pessoa da
equipe (ou uma futura sessão de trabalho) retome o contexto sem arqueologia.

Documentos irmãos:
- [`DECISIONS.md`](../DECISIONS.md) — uma linha por decisão não óbvia (o *registro formal*).
- [`README.md`](../README.md) — setup e visão geral.
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — sequências e contratos.
- [`docs/GUIA_TECNICO.md`](GUIA_TECNICO.md) — guia técnico de engenharia.
- [`docs/COMPLIANCE.md`](COMPLIANCE.md) — conformidade com o edital e o material do curso.
- Este diário — a *narrativa* de como chegamos aqui.

---

## O que é o projeto, em um parágrafo

**Claryon Field** é um app companion Android/Kotlin para os óculos **Ray-Ban Meta
(sem display)**, usando o **Meta Wearables Device Access Toolkit (DAT)**. É um
**copiloto de voz para o agente de segurança pública**: ele fala, o app entende
**localmente** (sem nuvem no caminho crítico), age (pede apoio pelo canal tático,
grava evidência, consulta placa) e responde por **áudio no ouvido**. A ausência de tela
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
   plataforma `android-35`. *(Em 2026-08-14 fomos a AGP 8.9.2 / Gradle 8.11.1
   para reativar o lint — ver entrada do dia.)*

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

## 2026-08-13 — M2: registro, sessão e câmera reais (sem hardware)

**Objetivo:** primeiro marco que exercita o SDK de verdade — `Wearables.initialize`,
fluxo de registro, sessão e stream de câmera — usando o **Mock Device Kit** (câmera
do celular como fonte), sem óculos e sem o app Meta AI.

**Como foi feito com segurança (Regra Zero):** clonei o **sample oficial
`CameraAccess`** (repo `facebook/meta-wearables-dat-android`, 0.9.0) e confirmei
CADA assinatura antes de escrever ("samples > docs"). Isso corrigiu suposições que
eu erraria de memória: no 0.9 não existe `RegistrationState.AVAILABLE`, e a câmera
é `session.addCamera()` → `Camera` → `camera.stream` (não `addStream`).

**O que ficou pronto:**
- `ClaryonApp : Application` inicializa o DAT via `GlassesRuntime` (em
  `core-glasses`). O **compilador provou o isolamento da fachada**: o `app` não
  consegue nem importar `Wearables` — a fronteira de módulo garante que só
  `core-glasses` toca o SDK.
- `DatGlassesFacade` — implementação real sobre a 0.9 (registro, sessão, câmera,
  `capturePhoto`), com os enums do DAT mapeados **por nome** (robusto ao preview).
- `MockDeviceController` (debug): `enable → pairGlasses(RAYBAN_META) → powerOn →
  don → setCameraFeed(câmera do celular)`.
- Painel Compose de diagnóstico refletindo **ao vivo** registro / dispositivos /
  sessão / stream / frames.
- Teste instrumentado `MockDeviceKitStreamTest`.

**Verificação (a máquina não tinha emulador — provisionei um android-35):**
1. Build verde contra o SDK 0.9 (`assembleDebug` + `assembleDebugAndroidTest`).
2. **Teste instrumentado passou** (`tests=1 failures=0`): registro → STARTED →
   STREAMING via MDK.
3. **Painel ao vivo no emulador** confirmou visualmente: Registro **REGISTERED**,
   Sessão **STARTED**, Stream **STREAMING**, **Frames #56 · 480×640** subindo.

**Aprendizado de depuração:** o painel a princípio ficava em "sessão IDLE" ao ser
acionado — não era bug de código, e sim (a) o toque caía fora do botão, porque o
card do mock cresce para duas linhas e empurra o layout, e (b) uma melhoria real:
manter **uma única** instância de `AutoDeviceSelector` (como o sample), em vez de
recriar a cada `createSession`. Com isso, o ciclo completa.

---

## 2026-08-13 — M3: pipeline de áudio HFP

**Objetivo:** capturar e reproduzir voz pelo canal Bluetooth dos óculos (HFP),
que **não passa pelo DAT** — é `AudioManager`/`AudioRecord`/`AudioTrack`.

**O que ficou pronto (`GlassesAudioManagerImpl` em core-audio):**
- `iniciar()` roteia o áudio para `TYPE_BLUETOOTH_SCO` (`setCommunicationDevice`,
  API 31+) e trata **`false`/lista vazia** com erro claro — nunca falha silenciosa.
- `microfonePcm()` → `AudioRecord(VOICE_COMMUNICATION)` mono 16-bit como
  `Flow<ShortArray>` em `Dispatchers.IO`.
- `reproduzir()` → `AudioTrack(USAGE_VOICE_COMMUNICATION)`.
- `liberar()` chama **`clearCommunicationDevice()`** — sem isso o áudio do sistema
  fica preso em 8 kHz.
- Painel ganhou o botão **"Eco 3 s"** e um card de rota; MainActivity pede
  `RECORD_AUDIO`/`BLUETOOTH_CONNECT` em runtime.

**Verificação:** 3 testes instrumentados passaram no emulador (tratamento
sem-SCO + limpeza + o M2 stream). E o **eco ao vivo funcionou**: `AudioRecord`
capturou **46.720 amostras** (~2,9 s a 16 kHz) e o `AudioTrack` reproduziu — o
pipeline record→playback é real. O **eco HFP roteado pelo SCO** dos óculos/fone
precisa de **fone Bluetooth físico** (o MDK não simula áudio; o emulador não tem
SCO) — validar em dispositivo real.

**Detalhe de flag:** `allowFallbackToDefault` (só em DEBUG) permite exercitar o
pipeline sem Bluetooth, roteando para o dispositivo padrão. Em produto é `false`.

---

## 2026-08-13 — M4: voz on-device (núcleo verificável; nativo diferido)

**Decisão de escopo (o Guia manda isso):** os motores de voz "de verdade"
(whisper.cpp, Piper/sherpa-onnx, openWakeWord, Silero) são **nativos** — exigem
NDK, bibliotecas `.so` por ABI, modelos em assets e um **device físico** para
validar. É o marco de maior risco de ambiente. O Guia é explícito: valide o ciclo
com o fallback primeiro, e trate o nativo como tarefa própria. Foi o que fiz.

**Pronto e verificado nesta sessão:**
- **`DeterministicIntentRouter`** (o "cérebro"): mapeia a frase → intenção por
  padrão/verbos, **sem LLM**. Teste de 20 frases + laconicidade passou; e ao vivo
  no emulador: "pedir apoio suspeito armado" → `PedirApoio` →
  "Apoio solicitado, guarnição avisada."
- **`EnergyVoiceActivityDetector`**: VAD por energia, fecha a janela no silêncio
  (teste JVM). Upgrade: Silero.
- **`AndroidTts`**: fala pelo `TextToSpeech`, entregando PCM ao pipeline HFP do M3.
- Painel ganhou o campo **"Comando de voz (texto)"** → roteador → resposta falada.

**Diferido para o M4-nativo (tarefa própria, em device):** `WhisperCppStt`
(whisper.cpp/JNI), `PiperTts` (sherpa-onnx), `openWakeWord`, Silero — os scaffolds
já existem com `isAvailable()=false` para o pipeline degradar sem inventar
transcrição. O aceite pleno (modo avião, "Claryon, pedir apoio" falado) precisa do
STT real + hardware.

---

## 2026-08-14 — M4-nativo: whisper.cpp on-device, PROVADO

**O marco de maior risco do projeto — e deu certo, verificado em runtime.**

O `whisper.cpp` (STT em C++) foi integrado e **transcreveu de verdade no emulador
arm64**: o teste `WhisperCppSttTest` carregou o modelo `ggml-tiny` e transcreveu o
áudio `jfk.wav` como *"...ask not what your country can do for you..."* — sem
rede, sem hardware de áudio, puro on-device.

**Como (Regra Zero, code real):**
- Adicionei o whisper.cpp como **git submódulo** (`core-voice/src/main/cpp/whisper`).
- Reaproveitei **verbatim** o `jni.c`, `CMakeLists.txt` e o wrapper Kotlin
  (`LibWhisper.kt`) do **exemplo Android oficial** — nada de JNI escrito de memória.
- Instalei **NDK 27 + CMake 3.22.1**; liguei o `externalNativeBuild` em
  `core-voice` (arm64-v8a). O `.so` compilou (whisper + ggml) e carregou no device.
- `WhisperCppStt` implementa nosso `SttEngine` sobre o `WhisperContext`.

**Percalços honestos (e as correções):**
- `initContextFromInputStream` está declarado no Kotlin mas **não** implementado
  no `jni.c` → usei `createContextFromAsset` (lê do APK sem copiar 77 MB para o
  disco cheio do emulador).
- Assets de `androidTest` vivem no **APK de teste** (contexto da instrumentação),
  não no `targetContext`.

**Pendências reais:** o antigo alerta **8 kHz→16 kHz** agora é concreto — o
`AudioRecord`/`jfk.wav` são 16 kHz, mas o HFP dos óculos entrega 8 kHz; falta o
**resample** no caminho HFP→whisper. E ainda: **Piper (sherpa-onnx)** para TTS
neural, **openWakeWord** e **Silero VAD**.

---

## 2026-08-14 — Ciclo de voz completo, M5 (som) e M6 (visão + evidência)

**Ciclo de voz (`VoiceCycle`).** Amarra a cascata sobre abstrações: PCM (HFP) →
VAD fecha a janela → **earcon "ouvi você" imediato** → STT (lote) → roteador →
TTS → playback. O earcon dispara no **fechamento do VAD**, não no fim do STT
(teste JVM com STT falso prova a ordem e o resultado do roteador).

**M5 — fila de som.** Separei política de mecanismo: `SoundScheduler` (puro —
prioridade por ordinal, suprime informativo em Modo Tático, emergência
interrompe) com testes JVM; `PrioritySoundQueue` (coroutine, Channel + Mutex, job
de emergência cancela o que toca); `EarconSynthesizer` (sintetiza os earcons por
seno — inclui o tom de 2 s de "gravando"). Resposta de TTS ≤ 7 palavras e sem
cortesia já tinham teste desde o M4.

**M6 — visão + evidência.** Três peças:
- `PlacaValidator` (puro, core-agent): Mercosul + antigo, reusado por voz e OCR.
- `PlacaOcr` (app): ML Kit Text Recognition **on-device** (modelo Latin
  embarcado, roda offline). Verificado: renderei uma placa impressa sintética
  ("ABC1D23") e o OCR leu e validou. O frame é efêmero — só o texto sobrevive.
- `EncryptedEvidenceVault` (core-evidence): um `EncryptedFile` (AES-256 GCM,
  chave no Keystore) por segmento + `HashChain` (SHA-256 encadeado). Verificado
  em device: 30 segmentos → cadeia íntegra; virar **1 byte** do segmento 2 →
  `verificar()` aponta o segmento 2. Dupla camada: o GCM apanha byte adulterado,
  a cadeia apanha troca/remoção/reordenação.

---

## 2026-08-14 — M7: rede (Supabase + fila offline)

WhatsApp saiu do escopo por ora (decisão de produto); o contrato
`MessagingGateway` fica de pé, sem implementação. A saída de rede é o Supabase.

O que importa aqui não é o `POST` — é o **comportamento sem rede**. Três peças:

- `FileOutbox`: fila durável em disco, um arquivo por item, escrita atômica
  (`.tmp` + rename). Sobrevive à morte do processo, que é o ponto inteiro.
  Testado: enfileira em uma instância, lê em outra, FIFO e payload íntegros.
- `OutboxDrainer`: drena FIFO, para na primeira falha (se a rede caiu, insistir
  só gasta bateria) e descarta item veneno após 5 tentativas — senão um item
  malformado tranca a fila para sempre.
- `TacticalDispatcher`: devolve `Enviada` **ou** `Enfileirada`. A regra "não
  mente dizendo que enviou" virou **tipo**, não comentário — quem chama é
  obrigado a distinguir, então o TTS não tem como escorregar.

`SupabaseSyncGateway` fala PostgREST com OkHttp (sem SDK) e usa
`Prefer: resolution=merge-duplicates` para o reenvio ser idempotente. Duas
faixas de WorkManager: tática (só `CONNECTED`) e pesada (`UNMETERED` +
carregando + bateria ok) — mensagem tática não espera o carregador; evidência
espera.

---

## 2026-08-14 — M8: energia (serviço de primeiro plano, modos, térmico)

Os modos viraram **política pura** (`PowerPolicy`, em core-agent): `perfil(modo)`
diz o que fica ligado e `tiposDeServico(modo)` diz quais `foregroundServiceType`
o modo exige. O serviço só obedece — então a economia de bateria é **testável em
JUnit**, sem medir bateria. Standby fecha o HFP (o SCO é o maior consumidor
contínuo); Ativo ouve mas **não filma**; Ocorrência liga tudo em janela curta.

`ThermalGovernor` existe para uma armadilha só: **`NaN` não é 0**.
`getThermalHeadroom()` devolve `NaN` quando o sistema não tem informação; tratar
como 0 faria o app se achar frio e acelerar justamente quando não sabe nada. Sem
informação: mantém o teto e não autoriza rajada.

**Um bug real, achado em execução — e é o motivo de verificar em aparelho.**
Subir o FGS com tipo `camera` exige a permissão de runtime **concedida**, não só
a declaração no manifest. Sem ela: `SecurityException` e o processo morre — foi
exatamente o que aconteceu no primeiro teste. Corrigido: o serviço
intersecciona os tipos do modo com as permissões concedidas e **degrada** em vez
de cair. Falta de sensor vira falha audível na feature, nunca queda do pipeline.

Verificação no emulador (`dumpsys activity services`):

| Modo | tipos observados | leitura |
|---|---|---|
| Standby | serviço parado | nada segurado |
| Ativo | `0x90` | connectedDevice \| microphone |
| Ocorrência | `0xD0` | connectedDevice \| microphone \| camera |
| Ocorrência **sem** permissão de câmera | `0x90`, sem crash | degradou |

---

## 2026-08-14 — Auditoria final: o que os testes verdes não mostravam

Com M0–M8 concluídos, fizemos a auditoria completa: código, compliance contra o
edital e o material do curso, e documentação. O resultado mudou a leitura do
projeto.

**O achado central não é um bug — é uma ausência.** Todos os componentes existem
e passam nos testes, mas **o produto não está montado**:

```
imports de core-* em app/src/main:  agent, voice, glasses, common, audio
                          ausentes:  sound, evidence, sync
```

Não há executor de intenções: o roteador devolve `Intent`, `OperationalResponses`
devolve a frase, o TTS fala — e acabou. O app diz *"Apoio solicitado, guarnição
avisada."* **sem tentar enviar**. É a violação mais dura possível da regra de
honestidade que o próprio `TacticalDispatcher` codificou em tipo. Nenhum earcon
toca. Os modelos de IA vivem em `androidTest` e **não entram no APK de produção**.

**Vinte e um defeitos de código corrigidos** (6 altos). Os que mais ensinam:

- **A cadeia de custódia tinha uma corrida.** `append()` lia `seq`/`prev` fora do
  mutex: dois appends concorrentes gravavam ambos em `seg_00000.enc`, o segundo
  por cima do primeiro. Perda silenciosa de evidência — no módulo que existe
  justamente para impedir isso. Testes verdes não pegam corrida.
- **`falarComando()` transcrevia terceiros.** Sem rotear o HFP antes, o
  `SpeechRecognizer` grava pelo microfone do celular, que é omnidirecional. Sem o
  beamforming dos óculos, a fala do abordado entra na transcrição — proibição
  absoluta do projeto, atravessada por uma função de diagnóstico.
- **O VAD nunca fechava a janela sob ruído sustentado.** Sirene ou motor mantêm a
  energia acima do limiar; sem teto de duração, o acumulador cresce até estourar
  e o copiloto fica mudo **exatamente no ambiente para o qual foi feito**.
- **`HashChain` dava "íntegra" para cadeia truncada.** Apagar os últimos
  segmentos deixando o manifesto intacto passava na verificação: o laço iterava
  sobre os segmentos presentes, e ninguém reparava nos que sumiram.
- **O celular ficava preso em `MODE_IN_COMMUNICATION`.** Três caminhos chamavam
  `iniciar()`/`liberar()`; concorrentes, cada um restaurava o modo do outro. É a
  armadilha dos 8 kHz da tabela do projeto, chegando por um caminho que a tabela
  não previa. Virou contagem de referência.

**Lição que vale registrar:** todos esses passaram por revisão e por suíte verde.
O que os expôs foi ler o código procurando **cenários de falha concretos**, não
procurando erros. Teste verde prova que o caminho feliz funciona; não prova que
os outros existem.

---

## Estado atual (fim de 2026-08-14)

- **Marcos concluídos:** M0, MCP, M1, M2, M3, **M4** (whisper.cpp nativo +
  Piper/sherpa-onnx verificados), ciclo de voz, **M5** (som), **M6** (visão +
  evidência), **M7** (rede), **M8** (energia). **Todos os marcos do plano.**
- **Build:** verde, **lint reabilitado** (AGP 8.9.2 corrige o bug com Kotlin 2.2).
- **Toolchain:** JDK 17 · Gradle 8.11.1 · AGP 8.9.2 · Kotlin 2.2.0 · compileSdk 35 · minSdk 31 · **NDK 27 · CMake 3.22.1**.
- **Voz:** roteador determinístico + VAD + TTS (Piper/Android) + **STT whisper.cpp
  on-device (provado)** + fallback (SpeechRecognizer) + resample 8→16 kHz.
- **Rede:** fila offline durável + drenagem por WorkManager (duas faixas) +
  Supabase por PostgREST. WhatsApp fora do escopo por ora.
- **Energia:** `CopilotService` (FGS com tipos derivados do modo, degrada sem
  permissão), `PowerPolicy`, `ThermalGovernor`, WorkManager em duas faixas.
- **Auditoria final:** concluída (código, compliance e documentação). 21 defeitos
  corrigidos; documentação reconciliada com o código.

### O que falta — em ordem de importância

1. **Montar o produto.** Um `IntentExecutor` ligando `Intent` → `EvidenceVault` /
   `TacticalDispatcher` / `PlacaOcr` / `CopilotService`, com a resposta derivada
   do **resultado** da ação (`Despacho.Enviada` vs `Enfileirada` já modela isso).
   Fecha de uma vez o "fala sem agir" e os três módulos mortos.
2. **Ligar os earcons** (`PrioritySoundQueue` + `EarconSynthesizer`) ao
   `onOuviVoce` e a todo caminho de falha. É o item mais barato do relatório e é
   literalmente *"o único canal de saída"* do checkpoint 3 do edital.
3. **Embarcar os modelos** em `app/src/main/assets` (ou um worker de download com
   as constraints que `SyncManager` já sabe agendar) e instanciar o `PiperTts`.
4. **Completar o fluxo de permissões**: 4 permissões, rationale e recuperação de
   negação permanente (`openAppSettings`).
5. **Instrumentar o `Telemetry`** — sem isso, quatro das seis metas do projeto
   são afirmações sem prova diante da banca.
6. **Entregáveis da Etapa 5** (§5.5 do edital): documento final e apresentação no
   template da organização — prazo **22/08**.
7. Ensaio da demo com hardware real, eco HFP em fone físico e medição de
   bateria/latência em campo.

**Pendências conhecidas (registradas para não se perderem):**
- Revogar e rotacionar o PAT do GitHub (foi exposto em conversa durante o setup).
- Confirmar a versão mais recente do `mwdat` em GitHub Packages no início de cada marco que toque o SDK.
- `security-crypto` está em `1.1.0-alpha06`; migrar para a 1.1.0 estável quando sair.
- **§14.1 do edital veda alteração de escopo:** conferir se o documento submetido
  menciona WhatsApp. Se menciona, a saída do escopo precisa ser formalizada com a
  organização — não apenas removida da documentação.
- `MockDeviceKitStreamTest` roda **isolado** (o decodificador do mock aborta ao
  dividir processo com outra classe de teste). Reavaliar a cada atualização do
  `mwdat-mockdevice`.
