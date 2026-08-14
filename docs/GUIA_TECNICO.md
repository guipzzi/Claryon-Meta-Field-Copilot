# Claryon Field — Guia Técnico de Construção

**Complemento técnico do Plano de Negócios — Programa AI Glasses Brasil 2026**
**Alvo:** app companion Android/Kotlin + Meta Wearables Device Access Toolkit (DAT)
**Prazo duro:** Hackathon presencial em 18/09/2026. O código precisa estar pronto **antes**.

---

## Como usar este documento

Este guia define **o que construir, em que ordem e com quais critérios de aceite**
— e, tão importante quanto, o que não deve ser feito sem confirmação. É a
referência de engenharia do projeto; as regras do dia a dia estão condensadas em
[`PADROES_DE_ENGENHARIA.md`](PADROES_DE_ENGENHARIA.md).

Ordem recomendada:

1. Ler [`PADROES_DE_ENGENHARIA.md`](PADROES_DE_ENGENHARIA.md) — as regras duras.
2. Configurar o MCP de documentação viva do DAT (§2) **antes de qualquer linha de código**.
3. Executar os marcos M0 → M8 (§6) em ordem, cada um com critério de aceite verificável.
4. Manter [`DECISIONS.md`](../DECISIONS.md) atualizado (§9).

---

# 1. Contexto do produto

**O produto.** Copiloto de voz para agentes de segurança pública usando Ray-Ban Meta (sem display). O policial fala, o app entende localmente, age (pedir apoio pelo canal tático / gravar evidência / consultar placa) e responde por áudio no ouvido. Mãos livres, olhos no ambiente.

**As cinco restrições que definem toda decisão técnica:**

1. **Sem display.** A única saída rica é áudio. Nada de UI como canal de resposta ao usuário final — a tela do celular existe apenas para configuração, diagnóstico e demonstração à banca.
2. **O código não roda nos óculos.** Óculos = sensores + alto-falantes. Tudo roda no smartphone.
3. **Câmera pelo DAT, áudio pelo Bluetooth.** Não existe `session.audioStream`. Microfone e alto-falantes são acessados por `AudioManager` / `AudioRecord` / `AudioTrack` via perfis HFP/A2DP.
4. **"Hey Meta" não é acessível.** A wake word é nossa, roda no celular sobre o áudio HFP.
5. **Bluetooth Classic é o gargalo.** Vídeo comprimido em resolução modesta; microfone chega mono a 8 kHz via HFP.

**Consequência de produto que é também política de código:** o beamforming do array de 5 microfones isola a voz de quem veste os óculos. Transcrevemos o **agente**, não o abordado. Isso é intencional e não deve ser "corrigido".

---

# 2. Pré-requisito absoluto: contexto vivo do DAT

> ⛔ **REGRA ZERO.** O DAT está em *developer preview*, é versionado e mudou depois do corte de treinamento de qualquer modelo. **Nenhuma assinatura de API do DAT deve ser escrita de memória.** Toda vez que o agente for tocar em `Wearables`, `DeviceSession`, `Stream`, `StreamConfiguration`, `MockDeviceKit` ou qualquer símbolo do SDK, ele consulta a fonte primeiro.

Configure os canais de consulta antes de codar:

| Canal | Endereço | Para quê |
|---|---|---|
| **MCP de docs vivas** | `https://mcp.developer.meta.com/wearables` — ferramenta `search_dat_docs` | Consulta versionada em tempo real. **É o antídoto contra API inventada.** |
| **Referência estática** | `https://wearables.developer.meta.com/llms.txt?full=true` | API reference completa, em texto |
| **Samples oficiais** | `facebook/meta-wearables-dat-android` | Código real vale mais que documentação — clonar num diretório irmão e consultar |

**Teste de fumaça antes de começar:** rodar `search_dat_docs` com a consulta
*"camera streaming setup on Android"* e conferir a versão retornada contra o
`libs.versions.toml`. Se o MCP não responder, **pare e configure antes de
escrever código**.

**Fontes secundárias** (contexto de projeto, não de API): material de apoio Un12 (Kotlin/Android, Edge AI, visão, STT/TTS, background e energia) e Un13 (DAT: arquitetura, setup, sessão, câmera, áudio, Mock Device Kit). Onde eles divergirem da doc oficial, **a doc oficial vence** — o material foi escrito contra a 0.8.0.

---

# 3. Arquitetura de módulos

```
claryon-field/
├── app/                    UI Compose: onboarding, diagnóstico, painel de demo
├── core-glasses/           DAT: registro, sessão, stream de câmera, MDK
├── core-audio/             Roteamento HFP, AudioRecord, AudioTrack
├── core-voice/             WakeWord, VAD, STT, TTS — interfaces + implementações
├── core-agent/             IntentRouter, modelo de intenções, políticas de ação
├── core-sound/             Earcons, fila de prioridade, protocolo de laconicidade
├── core-evidence/          Cofre cifrado, hash chain, cadeia de custódia
├── core-sync/              Supabase, MessagingGateway, WorkManager
└── core-common/            Result types, logging, feature flags, telemetria local
```

**Regra de dependência:** `app` depende de todos; os `core-*` **não dependem uns dos outros**, exceto `core-common`. A orquestração acontece em `app` (ou num `core-orchestrator` se crescer). Isso permite trabalho paralelo entre os três integrantes e substituição de implementações sem refatoração em cascata.

## 3.1 Interfaces a fixar no dia 1

Estas assinaturas existem para permitir **plano B sem reescrita**. Fixe-as antes de qualquer implementação.

```kotlin
// core-voice
interface SttEngine {
    val id: String
    suspend fun isAvailable(): Boolean
    suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): Result<Transcript>
}
// Implementações: WhisperCppStt (primária), AndroidOnDeviceStt (fallback)

interface TtsEngine {
    suspend fun isAvailable(): Boolean
    suspend fun synthesize(text: String): Result<PcmAudio>
}
// Implementações: PiperTts (primária), AndroidTts (fallback)

interface WakeWordDetector {
    fun detect(pcm: Flow<ShortArray>): Flow<WakeEvent>
}

interface VoiceActivityDetector {
    fun segment(pcm: Flow<ShortArray>): Flow<SpeechSegment>
}

// core-sync
interface MessagingGateway {
    suspend fun send(msg: TacticalMessage): Result<MessageId>
    fun incoming(): Flow<InboundMessage>
}
// Implementações: WhatsAppDirectGateway (1:1 — demo oficial),
//                 WhatsAppGroupGateway (Groups API — atrás de feature flag),
//                 FakeGateway (desenvolvimento e testes)

// core-glasses
interface GlassesFacade {
    val registration: StateFlow<RegistrationStatus>
    val session: StateFlow<SessionStatus>
    suspend fun ensureRegistered(): Result<Unit>
    suspend fun startSession(): Result<Unit>
    suspend fun withCamera(config: CameraProfile, block: suspend (Flow<Frame>) -> Unit): Result<Unit>
    suspend fun capturePhoto(): Result<PhotoData>
}

// core-evidence
interface EvidenceVault {
    suspend fun beginRecording(context: OccurrenceContext): Result<RecordingHandle>
    suspend fun append(handle: RecordingHandle, chunk: ByteArray): Result<ChunkHash>
    suspend fun finalize(handle: RecordingHandle): Result<CustodyManifest>
}
```

**Por que `GlassesFacade` e não usar o DAT direto:** isola o único ponto do código que depende de uma API em preview. Quando a 0.9 quebrar assinaturas, você conserta um arquivo.

## 3.2 Modelo de intenções

```kotlin
sealed interface Intent {
    data class PedirApoio(val prioridade: Prioridade, val resumo: String?) : Intent
    data class IniciarGravacao(val motivo: String?) : Intent
    data object EncerrarGravacao : Intent
    data class ConsultarPlaca(val placa: String?) : Intent   // null = ler pela câmera
    data class NarrarOcorrencia(val texto: String) : Intent
    data object Emergencia : Intent
    data object Detalhar : Intent                             // repete último resultado por voz
    data class TrocarModo(val modo: ModoOperacao) : Intent    // Standby | Ativo | Ocorrencia
    data class NaoReconhecida(val transcricao: String) : Intent
}
```

**Roteamento determinístico primeiro.** O `IntentRouter` usa correspondência por padrão + verbos-chave sobre a transcrição, com validação de esquema. Não use LLM no caminho crítico: latência imprevisível, dependência de rede, e comportamento não auditável em contexto de segurança pública. `NaoReconhecida` responde com earcon de falha e pedido curto de repetição — **nunca** age por adivinhação.

---

# 4. Sequências que não podem ser invertidas

## 4.1 Boot do app

```
1. Wearables.initialize(context)              // uma vez por processo
2. Observar registrationState + registrationErrorStream
3. Se != REGISTERED → deeplink para app Meta AI → retorno via claryonfield://
4. GlassesAudioManager.iniciar()              // ⚠️ ANTES do passo 5
   ├─ availableCommunicationDevices → localizar TYPE_BLUETOOTH_SCO
   ├─ setCommunicationDevice(óculos)  [API 31+; tratar retorno false]
   └─ confirmar dispositivo de comunicação ativo
5. Wearables.createSession(AutoDeviceSelector) → session.start()
6. [somente sob intenção] session.addStream(config) → stream.start()
```

> ⚠️ **Inverter 4 e 5 produz o bug mais caro do projeto:** captura de voz que "às vezes funciona". O canal SCO não sobe de forma confiável com o streaming já ativo. A documentação oficial é explícita: HFP totalmente configurado **antes** da sessão de streaming que depende de áudio.

## 4.2 Ciclo de voz

```
PCM (HFP, mono 16-bit) → WakeWordDetector
  → [detectou] earcon "ouvi você" IMEDIATO
  → VoiceActivityDetector → fecha janela ao detectar silêncio
  → SttEngine.transcribe()
  → IntentRouter.route()
  → executor da intenção (local ou rede, assíncrono)
  → TtsEngine.synthesize() → SoundQueue → AudioTrack
```

**O earcon dispara quando o VAD fecha a janela, não quando o STT termina.** O agente sabe que foi ouvido em ~400 ms, mesmo que a ação leve 2 s. É a otimização de UX de maior retorno do projeto e custa três linhas.

## 4.3 Encerramento

```
clearCommunicationDevice()   // senão TODO áudio do telefone fica preso em 8 kHz
stream.stop() → session.stop()
liberar interpretadores, AudioRecord, AudioTrack
```

---

# 5. Armadilhas conhecidas — checklist de revisão

O agente deve verificar esta lista antes de declarar qualquer marco concluído.

| # | Armadilha | Sintoma | Correção |
|---|---|---|---|
| 1 | HFP configurado depois do stream | Captura de voz intermitente | Sequenciar conforme §4.1 |
| 2 | `foregroundServiceType` ausente no manifest **ou** em `startForeground()` | Exceção no Android 14+ | Declarar nos **dois** lugares |
| 3 | Iniciar FGS com app em background | `ForegroundServiceStartNotAllowedException` (Android 12+) | Iniciar sempre de tela visível |
| 4 | `setCommunicationDevice()` retorna `false` ou óculos não listados | Falha silenciosa | Tratar ambos com mensagem clara; testar `MODE_IN_COMMUNICATION` se `MODE_NORMAL` não subir |
| 5 | Developer Mode: só 1 app de terceiros registrado por vez | `registrationState` volta a `AVAILABLE` sozinho | Detectar e oferecer re-registro em 1 toque; aparelho dedicado no hackathon |
| 6 | Intent filter com URI scheme ausente | Usuário fica preso no app Meta AI | `claryonfield://` com action VIEW + DEFAULT + BROWSABLE. Scheme exclusivo, nunca genérico |
| 7 | `speak()` antes de `onInit` do `TextToSpeech` | Falha silenciosa | Enfileirar até estado `ready` |
| 8 | `SpeechRecognizer` fora da main thread | Crash | API exige main thread; sempre `destroy()` |
| 9 | `ERROR_NO_MATCH` tratado como erro grave | UX quebrada | Significa "não entendi" — responder com earcon e seguir |
| 10 | Processar todos os frames em fila | Latência acumulativa | `conflate` no Flow: um frame por vez, descarta os do meio |
| 11 | Inferência em `while(true)` | Bateria e throttling térmico | Sempre por gatilho de evento |
| 12 | Token do GitHub commitado | Vazamento de credencial | `local.properties` (já no `.gitignore`) ou `GITHUB_TOKEN` |
| 13 | Whisper tratado como streaming | Resultado ruim | Whisper processa em **lote**: feche a janela (VAD ou push-to-talk) e só então transcreva |
| 14 | `capturePhoto()` sem stream ativo, ou duas simultâneas | Erro / `CaptureError.CaptureInProgress` | Exige streaming ativo; uma por vez |
| 15 | Pedir `HIGH`/30 fps | *Ladder* automático degrada, qualidade pior | Pedir `LOW`/`MEDIUM` com FPS baixo **melhora** a qualidade por frame |
| 16 | Sessão encerrada e o app tenta "revivê-la" | Estado inconsistente | *Cascading stop*: sessão parou → streams pararam. Criar nova sessão |
| 17 | `getThermalHeadroom()` retornando `NaN` tratado como 0 | Recuo indevido | API 30+; `NaN` = "sem informação", manter só o teto de taxa |
| 18 | MDK usado para testar áudio | Não funciona | **O Mock Device Kit não simula áudio.** Use fone Bluetooth comum com HFP |

---

# 6. Marcos de construção

Cada marco tem **critério de aceite verificável sem hardware dos óculos**. Não avance com marco anterior incompleto.

### M0 — Contexto e esqueleto
- Plugin do DAT e MCP `search_dat_docs` instalados e testados
- Repositório com módulos de §3, `PADROES_DE_ENGENHARIA.md`, `DECISIONS.md`, `.gitignore` com `local.properties`
- Interfaces de §3.1 escritas (sem implementação), compilando

**Aceite:** `./gradlew build` verde. `search_dat_docs` retorna resultado e a versão do SDK está registrada em `DECISIONS.md`.

### M1 — Setup do DAT
- PAT do GitHub com escopo `read:packages` em `local.properties`
- Repositório Maven em `settings.gradle.kts` (`dependencyResolutionManagement`)
- `mwdat-core`, `mwdat-camera`, `mwdat-mockdevice` em `libs.versions.toml`
- Manifest: `BLUETOOTH`, `BLUETOOTH_CONNECT`, `INTERNET`, `RECORD_AUDIO`, `CAMERA`; meta-data `APPLICATION_ID` e `CLIENT_TOKEN` (podem ser `0` em Developer Mode); intent filter `claryonfield://`

**Aceite:** Gradle sync verde com os artefatos resolvidos. `Wearables.initialize()` executa sem exceção.

### M2 — Mock Device Kit
- `MockDeviceKit.getInstance(context).enable()` → `pairGlasses(RAYBAN_META)` → `powerOn()` + `don()`
- Feed de câmera do celular como fonte simulada
- UI de diagnóstico expondo estado do dispositivo, registro e sessão

**Aceite:** stream chegando ao app, estado transitando `STARTING → STARTED → STREAMING`, frames renderizando. Sem óculos, sem app Meta AI.

### M3 — Pipeline de áudio (contra fone HFP comum)
- `GlassesAudioManager` com `iniciar()` / `liberar()`
- `AudioRecord(VOICE_COMMUNICATION)` → `Flow<ShortArray>` em `Dispatchers.IO`
- `AudioTrack(USAGE_VOICE_COMMUNICATION)` para reprodução

**Aceite:** ciclo eco — falar no fone, gravar 3 s, reproduzir de volta. Roteamento liberado corretamente ao final (áudio do sistema volta ao normal).

### M4 — Voz on-device
- `AndroidOnDeviceStt` e `AndroidTts` primeiro (rápido de montar, valida o ciclo)
- Depois `WhisperCppStt` (whisper.cpp, JNI, modelo `ggml-tiny` quantizado) e `PiperTts` (sherpa-onnx, voz pt-BR)
- `WakeWordDetector` (openWakeWord) + `VoiceActivityDetector` (Silero VAD)

**Aceite:** **com o celular em modo avião**, dizer "Claryon, pedir apoio" e ouvir a confirmação sintetizada. Latência do fim da fala à primeira nota de áudio medida e registrada.

> ⚠️ M4 é o marco de maior risco de ambiente: whisper.cpp e sherpa-onnx são nativos (NDK, `.so` por ABI, modelos em assets). **Resolva e commite semanas antes do hackathon.** Se travar mais de um dia, siga com o fallback nativo do Android e volte depois — o ciclo completo funcionando vale mais que o modelo ideal.

### M5 — Agente e som
- `IntentRouter` determinístico cobrindo todas as intenções de §3.2
- `SoundQueue` com três níveis de prioridade e Modo Tático
- Biblioteca de earcons; protocolo de laconicidade com teste automatizado de ≤7 palavras

**Aceite:** teste unitário cobrindo 20 frases operacionais reais → intenção correta ou `NaoReconhecida`. Nenhuma resposta de TTS excede 7 palavras.

### M6 — Visão e evidência
- Stream sob demanda (`LOW` @ 7 fps para OCR; `MEDIUM` @ 15 fps para evidência), com `conflate`
- ML Kit Text Recognition + validador de placa (Mercosul e padrão antigo)
- `EvidenceVault`: `EncryptedFile` + chave no Android Keystore, SHA-256 encadeado por segmento, manifesto de custódia
- Tom contínuo de 2 s ao iniciar gravação

**Aceite:** apontar a câmera (mock) para uma placa impressa → texto validado. Gravação de 30 s → arquivo cifrado + manifesto com cadeia íntegra. Adulterar 1 byte → verificação falha e aponta o segmento.

### M7 — Rede
- Supabase: schema com RLS por unidade/agente, PostGIS, Storage cifrado, log append-only com hash encadeado
- Edge Functions: envio ao WhatsApp Cloud API, webhook de entrada
- `WhatsAppDirectGateway` (demo oficial) + `WhatsAppGroupGateway` atrás de feature flag
- Fila offline: mensagem sem rede fica pendente e envia ao reconectar

**Aceite:** comando de voz → mensagem estruturada chega ao número de teste. Resposta no WhatsApp → webhook → FCM → TTS no ouvido. Em modo avião, a mensagem entra na fila e o TTS confirma corretamente ("apoio na fila") — **não** mente dizendo que foi enviada.

### M8 — Energia e resiliência
- `ForegroundService` com `foregroundServiceType="connectedDevice|microphone|camera"`
- `WorkManager` com `requiresCharging` + `UNMETERED` + `requiresBatteryNotLow`
- Modos Standby / Ativo / Ocorrência
- `getThermalHeadroom()` com recuo progressivo; `sample` no Flow como teto de taxa

**Aceite:** sessão de 10 minutos medida com `adb shell dumpsys batterystats` e Battery Historian; comparação contínuo vs. rajada documentada em `DECISIONS.md`. App sobrevive à tela apagada por 10 minutos com o pipeline vivo.

---

# 7. Metas mensuráveis

O agente deve instrumentar o código para produzir estes números, não estimá-los.

| Métrica | Alvo | Como medir |
|---|---|---|
| Fim da fala → earcon de reconhecimento | ≤ 500 ms | Timestamp no VAD e no `AudioTrack.write` |
| Fim da fala → primeira nota da resposta (local) | ≤ 2,0 s | Log estruturado por estágio |
| Fim da fala → resposta (com rede) | ≤ 3,0 s | Idem, com marcação do RTT |
| Acurácia do STT em 20 comandos operacionais | ≥ 92% | Suíte de áudios gravados com ruído de rua |
| Falsos positivos da wake word | ≤ 1 por hora | Sessão de 1 h com conversa ambiente |
| Bateria do celular, modo Ativo | ≤ 12%/h | Battery Historian |
| Tamanho do APK com modelos | Registrar | `./gradlew :app:analyzeReleaseBundle` |

**Instrumente desde M3.** Métrica adicionada no fim nunca é adicionada.

---

# 8. Estratégia de teste

**Sem hardware (fase online — é onde tudo é construído):**
- Testes instrumentados com MDK: `permissions.set(...)`, `setRequestResult(...)`, `setCameraFeed(...)`, `setCapturedImage(...)`; transições `powerOn`/`don`/`doff`/`fold`
- Áudio: fone Bluetooth comum com HFP. Para o Android, fone e óculos são o mesmo `TYPE_BLUETOOTH_SCO`
- STT/TTS: suíte de arquivos PCM 16 kHz mono gravados em ambiente ruidoso, rodando em teste instrumentado
- `FakeGateway` para todo o fluxo de mensageria em CI

**Testes de caos obrigatórios** (a banca vai criar algum deles sem querer):
- Rede cai no meio do envio
- Sessão do DAT é encerrada pelo sistema durante gravação (usuário tira os óculos)
- `setCommunicationDevice()` retorna `false`
- Registro perdido para outro app
- Armazenamento cheio durante gravação de evidência
- Bluetooth desligado no meio da sessão

Cada um deve produzir **feedback sonoro específico**, não silêncio. Silêncio num sistema sem display é indistinguível de falha total.

---

# 9. Regras de trabalho

## 9.1 Anti-alucinação
- Nunca escrever API do DAT de memória — consultar `search_dat_docs` ou o repo oficial primeiro
- Ao usar uma assinatura, registrar em `DECISIONS.md` a versão do SDK e a fonte consultada
- Se a doc divergir do material de apoio (Un12/Un13), **a doc oficial vence** e a divergência vai para `DECISIONS.md`
- Se uma API necessária não existir na versão vigente: **parar e perguntar.** Não inventar workaround silencioso

## 9.2 Quando parar e perguntar
- Necessidade de credencial (PAT do GitHub Packages, chave do Supabase)
- Necessidade de hardware físico para validar
- Qualquer nova dependência de terceiros (justificar tamanho, licença e alternativa nativa)
- Qualquer decisão que afete latência, bateria ou privacidade
- Descoberta de que um marco anterior está quebrado

## 9.3 Proibições absolutas
- ❌ **Reconhecimento facial, extração de embeddings faciais ou integração com base biométrica.** Em nenhuma versão, atrás de nenhuma flag
- ❌ Transcrição, classificação ou indexação da fala de terceiros. Áudio bruto do abordado é evidência, não dado analisável
- ❌ Envio de áudio, transcrição ou frame para serviço externo no caminho crítico
- ❌ Credencial em código-fonte ou em qualquer arquivo versionado
- ❌ `localStorage`-equivalentes inseguros para evidência: sempre `EncryptedFile` + Keystore
- ❌ LLM no caminho crítico de decisão operacional

## 9.4 Higiene
- `DECISIONS.md`: uma linha por decisão não óbvia, com data, alternativa descartada e motivo
- Commits pequenos, mensagem descrevendo o *porquê*
- `README.md` com setup reproduzível do zero em outra máquina — inclusive NDK e download de modelos
- **Build precisa funcionar offline** após a primeira sincronização. Wi-Fi de evento é ruim, e isso se descobre às 15h de 18/09

---
