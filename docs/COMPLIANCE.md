# Compliance & Guidelines — Claryon Field

Verificação de conformidade do que foi construído (**M0 a M8**) e **checklist
vivo** de guidelines extraídos do material teórico do curso (CEIA/Meta — Un12
Edge-AI/Android e Un13 DAT) e do edital. Cada item de milestone deve ser
conferido aqui antes de dizer "pronto".

> **Última auditoria: 2026-08-14.** O checklist da seção C foi reconferido item a
> item **contra o código**, não contra a memória de quem escreveu. Onde o estado
> real diverge do planejado, está escrito o estado real.

> Fonte dos guidelines: leitura integral de `Un12_Material_de_apoio_Meta.pdf`
> (94 p.) e `Un13_Material_de_apoio_Meta.pdf` (64 p.). Un10/Un11 são conceituais
> (bancos vetoriais, memória de agente LLM, RAG em Python) e **não se aplicam** ao
> app determinístico on-device. Un14 é encerramento editorial que reendossa a
> Regra Zero e as restrições de hardware.

---

## A. Veredito sobre as escolhas de arquitetura

**Conclusão: em conformidade.** O material valida as decisões estruturais do
projeto; não há contradição. Esta tabela é sobre **escolhas**, não sobre estado
de implementação — para o estado real, ver as seções B e C.

| Decisão nossa | Material | Status |
|---|---|---|
| Fachada `GlassesFacade` (um único ponto toca o DAT) | Un13 p.13,55 — óculos = periférico de I/O; encapsular numa classe | ✅ valida |
| Câmera via DAT, áudio via HFP (não pelo DAT) | Un13 p.6,50 — não existe `session.audioStream` | ✅ valida |
| Boot: HFP **antes** da sessão de streaming | Un13 p.53,55 — inverter = captura intermitente | ✅ valida |
| `mwdat-core/camera/mockdevice`, display omitido | Un13 p.7,23 — 3 artefatos + display fora de escopo | ✅ valida |
| PAT classic `read:packages`, `github_token` em `local.properties` | Un13 p.20,22 — classic obrigatório; nunca versionar | ✅ valida |
| Repo Maven em `settings.gradle.kts` | Un13 Nota 26 p.23 | ✅ valida |
| Manifest: BLUETOOTH/BLUETOOTH_CONNECT/INTERNET/CAMERA + meta-data=0 | Un13 p.19,25 | ✅ valida |
| `claryonfield://` (VIEW+DEFAULT+BROWSABLE), scheme único | Un13 p.21,32 | ✅ valida |
| minSdk 31 | Un13 p.54 — `setCommunicationDevice` exige API 31+ | ✅ valida a escolha |
| whisper.cpp (ggml-tiny quantizado) STT | Un12 p.94,100 — a escolha típica recomendada | ✅ valida |
| Piper/sherpa-onnx para TTS | Un12 p.96,101 — citado nominalmente | ✅ valida — **implementado e verificado** |
| Silero VAD · openWakeWord (planejados) | Un12 p.97,101 — citados nominalmente | ✅ valida a escolha — **ainda não implementados** |
| Roteador determinístico, sem LLM no caminho crítico | Un12 p.98; Un10/11 (RAG/LLM) fora de escopo | ✅ valida |
| Cascata wake→VAD→STT, gatilho-nunca-loop | Un12 p.57,97 — bursty/race-to-sleep | ✅ valida |
| ForegroundService (connectedDevice\|microphone\|camera) | Un12 p.55,59 | ✅ valida |
| WorkManager (charging/UNMETERED/battery) | Un12 p.55,60 | ✅ valida |
| `getThermalHeadroom`, NaN≠0 | Un12 p.58,60 | ✅ valida — **implementado e testado** |
| `sample` como teto de taxa | Un12 p.58,60 | ✅ valida a escolha — **ainda não usado** |

---

## B. Checkpoints obrigatórios do edital (§8.1) — estado real

O edital exige que o componente de IA seja *"funcional e **comprovável durante o
hackathon**"*. Por isso esta tabela separa **o que está construído e verificado**
do **que está montado no app** — a diferença é o trabalho que resta.

| Checkpoint | Construído e verificado | Montado no app | Evidência |
|---|---|---|---|
| **Uso de IA** | 3 componentes 100% locais: **whisper.cpp** (STT), **Piper/sherpa-onnx** (TTS neural), **ML Kit Text Recognition** (OCR). VAD por energia (RMS) | ⚠️ **parcial** — os modelos vivem em `app/src/androidTest/assets`; o **APK de produção não os embarca** | `core-voice/…/WhisperCppStt.kt`, `PiperTts.kt`, `app/…/vision/PlacaOcr.kt` |
| **Câmera ou microfone** | Microfone HFP real (canal primário); stream de câmera do DAT sobe e entrega frames | ⚠️ microfone sim; os frames de câmera **não alimentam o OCR** | `core-audio/…/GlassesAudioManagerImpl.kt`, `core-glasses/…/DatGlassesFacade.kt` |
| **Output por áudio** | Reprodução via `AudioTrack` (`USAGE_VOICE_COMMUNICATION`); TTS lacônico (≤7 palavras, com teste); earcons sintetizados; fila de prioridade | ⚠️ a fala sai; **os earcons ainda não tocam** (`core-sound` não é importado por `app/src/main`) | `core-sound/…/EarconSynthesizer.kt`, `PrioritySoundQueue.kt` |
| **Privacidade e dados** | On-device no caminho crítico; **zero** reconhecimento facial; fala de terceiros não transcrita (rota HFP é pré-condição da captura); cofre `EncryptedFile` + Keystore com cadeia de hash — adulterar 1 byte aponta o segmento | ⚠️ o cofre **nunca é instanciado** pelo app | `core-evidence/…/EncryptedEvidenceVault.kt`, `HashChain.kt` |
| **Eficiência de bateria** | Modos Standby/Ativo/Ocorrência como política pura e testada; FGS com tipos derivados do modo; freio térmico com `NaN` tratado; WorkManager em duas faixas | ✅ **montado** — verificado em aparelho | `core-agent/…/PowerPolicy.kt`, `ThermalGovernor.kt`, `app/…/service/CopilotService.kt` |

**Não afirmamos** ter openWakeWord nem Silero VAD: `WakeWordDetector` é interface
sem implementação e o VAD é por energia. O acionamento é **push-to-talk**, que o
Un12 §12.13.3.7.4 endossa como primeiro passo legítimo.

---

## C. Checklist por milestone — reconferido no código (2026-08-14)

Legenda: ✅ feito · ⚠️ parcial · ❌ não feito · ⚪ não se aplica.

### M2 — Mock Device Kit + registro

- ✅ `Wearables.initialize` **uma vez, no `onCreate()` de uma `Application`** — `app/…/ClaryonApp.kt` → `core-glasses/…/GlassesRuntime.kt`. *(Un13 p.34)*
- ✅ Nenhuma chamada ao SDK antes de `initialize` — garantido pelo compilador: as deps do DAT são `implementation` em `core-glasses`, então `app` não consegue importar `Wearables`. *(Un13 p.34)*
- ⚠️ Observar `registrationState` **e** o fluxo separado de erro: o estado é observado; **`registrationErrorStream` ainda não**. *(Un13 p.31)*
- ❌ Detectar "registro perdido" (Dev Mode, 1 app por vez): sem reação a `REGISTERED → UNAVAILABLE`. *(Un13 p.32)*
- ⚠️ Sessão até STOPPED (terminal), não reviver, não reiniciar em PAUSED: `STOPPED → cleanupSession()` ✅; `PAUSED` mapeado mas sem comportamento (ainda não há processamento a segurar). *(Un13 p.35,36)*
- ⚠️ `createSession` trata `NO_ELIGIBLE_DEVICE`/`SESSION_ALREADY_EXISTS`: a falha é logada, mas **sem distinguir os códigos**. `session.errors` **agora é publicado** em `sessionErrors` (era coletado e descartado). *(Un13 p.33,35)*
- ⚠️ MDK atrás de flag DEBUG ✅ (`debugImplementation` + `BuildConfig.DEBUG`); rotação de 90° da foto ❌ — `capturePhoto()` ainda descarta os bytes. *(Un13 p.62,63)*
- ✅ Enums 0.9 reconfirmados via `search_dat_docs`/sample oficial — ver `DECISIONS.md`. *(Regra Zero)*

### M3 — Áudio HFP

- ✅ Roteamento SCO (`TYPE_BLUETOOTH_SCO`) via `setCommunicationDevice`. *(Un13 p.53)*
- ✅ Trata `setCommunicationDevice() == false` e lista vazia, com erro tipado. **Testado.** *(Un13 p.54)*
- ✅ `AudioRecord` `VOICE_COMMUNICATION`, mono, PCM 16-bit, `Dispatchers.IO`. *(Un13 p.54; Un12 p.28)*
- ✅ `clearCommunicationDevice()` sempre no encerramento — agora com contagem de referência, para um caminho não derrubar a rota de outro. *(Un13 p.56)*
- ⚠️ Resample 8→16 kHz: **existe** (`PcmResampler`), mas o `AudioRecord` já abre a 16 kHz, então na prática **não dispara** na rota HFP. Acurácia com áudio HFP real **não medida**.
- ❌ Ordem "HFP antes do stream" **não é imposta pela orquestração** — a UI libera a câmera sem exigir rota de áudio. *(Un13 p.53; §D)*
- ❌ Eco HFP validado em **fone Bluetooth físico** (o emulador não tem SCO). *(Un13 p.61)*

### M4 — Voz on-device

- ✅ `WhisperCppStt` em **lote**: `VoiceCycle` só transcreve após o VAD fechar a janela. *(Un12 Nota 20 p.101)*
- ✅ Fallback usa `createOnDeviceSpeechRecognizer` + `isOnDeviceRecognitionAvailable` + `EXTRA_PREFER_OFFLINE` — nunca o `SpeechRecognizer` padrão, que vaza áudio. *(Un12 p.99,101)*
- ✅ `SpeechRecognizer` na main thread, `destroy()` no `finally`, `cancel()` reenviado à main; `ERROR_NO_MATCH` tratado como "não entendi". ⚠️ ainda vira texto na tela, **não earcon**. *(Un12 p.100)*
- ✅ `AndroidTts.speak()` só após `onInit` (enfileira em `ready`); agora com timeout e id por utterance. *(Un12 p.100)*
- ✅ Assinaturas de whisper.cpp e sherpa-onnx confirmadas nos repos oficiais. *(Un12 p.101)*
- ❌ Validar tudo em **modo avião** (nenhum byte sai) — não registrado. *(Un12 p.98)*
- ❌ Wake word: `WakeWordDetector` é interface **sem implementação**. Acionamento é push-to-talk.

### M6 — Visão e evidência

- ⚠️ Pipeline de frames: `conflate` **adicionado** em `withCamera`; mas **nenhum frame alimenta o OCR** — `PlacaOcr` recebe um `Bitmap` avulso e não é chamado por código de produção. *(Un12 p.86,87)*
- ⚪ `dataType()` do tensor — não há LiteRT/`.tflite` no projeto; o ML Kit não expõe tensores. *(item não se aplica)*
- ❌ OCR de placa **a distância** não medido: o teste usa bitmap sintético 900×320 com texto de 180 px — o oposto do cenário difícil. *(Un12 p.80)*
- ⚠️ `capturePhoto()`: guarda de "sem stream" ✅ e **trava de concorrência adicionada** ✅; foto ainda descartada ❌. *(Un13 p.43)*
- ✅ Cofre cifrado + cadeia de custódia: `EncryptedFile` (AES-256-GCM) + Keystore, hash encadeado, manifesto parcial a cada segmento. **Adulterar 1 byte → aponta o segmento** (teste instrumentado). ⚠️ nunca instanciado pelo app.

### M8 — Energia

- ✅ `foregroundServiceType` no manifest **e** em `startForeground()`, com o tipo **derivado do modo**; FGS só de tela visível. Verificado em aparelho (`0x90`/`0xD0`). Bônus: intersecção com as permissões concedidas, após `SecurityException` real. *(Un12 p.55,59)*
- ⚠️ `getThermalHeadroom` com `NaN` tratado ✅ (3 testes); mas o teto de FPS é apenas **exibido** — não é aplicado ao stream, e `podeIniciarRajada` nunca é chamado. `sample` não é usado. *(Un12 p.58,60)*
- ❌ Medir com Power Profiler + `dumpsys batterystats` — nenhum número medido. *(Un12 p.61)*
- ✅ Android Lint **reativado** (AGP 8.9.2 + Gradle 8.11.1); pegou um achado real de `MissingPermission`. Ver `DECISIONS.md`.
- ✅ WorkManager em duas faixas: tática (`CONNECTED`) e pesada (`UNMETERED` + carregando + bateria ok).

---

## D. Riscos e lacunas em aberto

1. **8 kHz (HFP) → 16 kHz (Whisper).** O microfone dos óculos chega mono a 8 kHz; o Whisper espera 16 kHz. Exige resample e pode pressionar a meta de 92% de acurácia do STT. **Validar com áudio HFP real, não com fone de melhor qualidade.** *(Un12 p.93; Un13 p.52)*
2. **`SpeechRecognizer` padrão vaza áudio para servidor.** O fallback nativo só é aceitável na variante on-device explícita; se o pacote pt-BR não estiver no aparelho, não há STT local nativo → plano B é depender só do whisper.cpp. *(Un12 p.99,101)*
3. **`initialize` na `Application`, não na Activity.** Ainda não temos classe `Application`; criar no M2 (importante porque FGS/WorkManager recriam processo). *(Un13 p.34)*
4. **OCR de placa a distância** pode não atingir acurácia útil com ML Kit puro; orçar medição e possível recorte prévio. *(Un12 p.80)*
5. **Dois runtimes nativos de inferência** (sherpa-onnx/ONNX para TTS; LiteRT/.tflite para visão) + whisper.cpp — peso de APK e NDK a dimensionar no M4/M6. *(Un12 p.101)*
6. **Divergência de versão do material (0.8/0.6) vs. SDK 0.9.** Todo enum/assinatura do DAT citado no material é **mapa conceitual**, não fonte — reconfirmar no `search_dat_docs` e registrar em `DECISIONS.md`. *(Un13, Regra Zero)*

---

## E. Lacunas de montagem (a maior dívida do projeto)

Descobertas na auditoria de 2026-08-14 e detalhadas no `README.md`:

1. **Não existe executor de intenções.** O roteador devolve `Intent`, a resposta é falada, e **nada é executado** — o app diz "Apoio solicitado, guarnição avisada." sem tentar enviar. Colide com o checkpoint 1 do edital ("funcional e comprovável") e com o critério de considerações éticas (§11.2).
2. **`core-sound`, `core-evidence` e `core-sync` não são importados por `app/src/main`.** São dependências declaradas e código testado, mas morto no produto.
3. **Modelos fora do APK de produção.** `ggml-tiny` e a voz Piper vivem em `app/src/androidTest/assets` — no aparelho fornecido pela organização (§9), whisper e Piper não existem.
4. **Fluxo de permissões incompleto.** `MainActivity` pede só 2 das 4 permissões (faltam `CAMERA` e `POST_NOTIFICATIONS`), sem checagem prévia, rationale ou recuperação de negação permanente — o Un12 §12.9.4.6 avisa que negação permanente é o que mais derruba demo de hackathon.
5. **Nenhuma métrica é medida.** `Telemetry` não tem uma única chamada. As seis metas do projeto são alvos, não medições — contra a própria regra "métrica adicionada no fim nunca é adicionada".
6. **Entregáveis da Etapa 5 (§5.5)** — documento final revisado e apresentação no template da organização, prazo **22/08** — ainda não existem no repositório.
7. **§14.1 veda alteração de escopo.** O WhatsApp saiu do escopo por decisão de produto; se o documento submetido o menciona, a mudança precisa ser formalizada com a organização, não apenas removida da documentação.

---

## F. Confirmação de acesso à documentação (2026-08-13)

| Fonte | Estado |
|---|---|
| MCP `search_dat_docs` (docs vivas 0.9) | ✅ ativo (`.mcp.json`), usado nesta sessão |
| `llms.txt?full=true` | ✅ HTTP 200 |
| Repo `facebook/meta-wearables-dat-android` | ✅ público, atualizado 2026-08-09 |
| GitHub Packages (artefatos `mwdat-*`) | ✅ token resolve |
| Material do curso (Un1–Un14 + edital) | ✅ 15 PDFs lidos/mapeados |
