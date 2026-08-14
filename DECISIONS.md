# DECISIONS.md — Claryon Field

Uma linha por decisão não óbvia: data · decisão · alternativa descartada · motivo.
Ordem cronológica inversa (mais recente no topo).

---

## 2026-08-14 — Auditoria final: correções dos achados de código

### Alta severidade

- **Sessão do DAT tinha `start` sem par: `DatGlassesFacade.stopSession()`.**
  Sair da tela cancelava só os coletores; a sessão e o stream seguiam vivos no SDK, os óculos transmitindo por Bluetooth sem indicador, e um `createSession` seguinte encontrava a anterior ativa. Encerramento agora segue o contrato: câmera → sessão → áudio → engines. `DeviceSession.stop()` confirmado por inspeção do artefato `mwdat-core:0.9.0` (`javap`), já que a doc não traz a assinatura — Regra Zero satisfeita pela fonte mais autoritativa possível.

- **Cadeia de custódia: `append()` fazia read-modify-write fora do mutex.**
  Dois `append` concorrentes liam `seq=0`/`prev=null`, gravavam ambos em `seg_00000.enc` (o segundo por cima do primeiro) e a cadeia ficava com duas entradas `sequence=0`. **Perda silenciosa de evidência** — exatamente o que o módulo existe para impedir. Toda a operação passou para dentro do lock. Alternativa descartada: lock só na escrita (é o que havia, e não basta).

- **`AudioRecord.read()` negativo virava laço quente.**
  `ERROR_DEAD_OBJECT` (HFP caiu) fazia o laço girar a 100% de CPU para sempre, sem emitir e sem avisar — "sem fala detectada" para o usuário e a bateria indo embora. Agora encerra o fluxo com `AudioCaptureException` tipada, carregando o código para o mapeamento erro → earcon.

- **Roteamento de áudio virou contagem de referência.**
  `iniciar()`/`liberar()` são chamados por três caminhos (eco, comando, ciclo). Concorrentes, o segundo `iniciar()` memorizava `previousMode = MODE_IN_COMMUNICATION` e ambos os `liberar()` restauravam esse valor: **o celular ficava preso em modo de comunicação** (a armadilha dos 8 kHz), e o `liberar()` do caminho curto derrubava a rota do longo ainda em captura. Só o primeiro roteia, só o último desfaz; `liberarTudo()` para o encerramento.

- **`cicloDeVoz()` colapsava exceção e timeout na mesma mensagem.**
  `runCatching { ... }.getOrNull()` reportava `RECORD_AUDIO` negada como "sem fala detectada (8 s)" — diagnóstico ativamente errado, que manda procurar o problema no lugar errado — e ainda engolia `CancellationException`. Agora são casos distintos, com log da causa.

- **`falarComando()` capturava pelo microfone do celular. (violação de proibição absoluta)**
  Sem rotear o HFP antes, o `SpeechRecognizer` grava pela fonte padrão do sistema — omnidirecional — e **a fala do interlocutor entra na transcrição**. O beamforming dos óculos é o que garante que só o agente é transcrito. A rota passou a ser pré-condição: sem ela, a captura é cancelada.

### Média e baixa

- **`HashChain.verificar()` não via cadeia truncada no fim.** Iterar sobre `segmentos` fazia uma cadeia com os últimos arquivos apagados (manifesto intacto) passar como **íntegra**. Passa a percorrer o maior dos dois tamanhos. Teste novo cobre.
- **VAD sem teto de janela.** Ruído sustentado acima do limiar (sirene, motor) mantinha a janela aberta para sempre: o acumulador crescia sem limite e o copiloto ficava mudo **no ambiente para o qual foi feito**. `maxSpeechMs = 12 s` (casa com o lote do Whisper) + buffer primitivo no lugar de `ArrayList<Short>`, que boxeava 16 000 objetos por segundo.
- **`START_STICKY` recriava o serviço em ATIVO.** Com `intent == null` (recriação pelo sistema) o modo caía no padrão e **o microfone reabria sozinho**, com o app em background. Agora encerra: modo é escolha explícita.
- **Fila de saída.** Um `.item` corrompido subia exceção por `list()` → worker e **travava a fila para sempre**; um item sem `payload` ficava invisível para `list()` mas contado por `size()`, gerando `retry()` eterno. Adicionado `descartarCorrompidos()`. Descarte de item veneno deixou de ser silencioso (`aoDescartar` + log) — é uma mensagem que o agente ouviu "na fila" e que nunca vai chegar.
- **`PrioritySoundQueue` morria com uma exceção**, levando junto os earcons de emergência. Laço isolado por iteração + reprodução em escopo-filho com `SupervisorJob`. Corrigida também a corrida em que a emergência cancelava um job já concluído em vez do que tocava.
- **`AndroidTts`:** listener é por engine, não por utterance — duas sínteses concorrentes penduravam a primeira para sempre. Serializado, id único de verdade (o `identityHashCode` colidia para o mesmo texto), callbacks filtrados por id e **timeout de 5 s** (há motores que não chamam `onDone` nem `onError`; sem limite, falha virava silêncio absoluto).
- **`release()` fora do mutex** em `WhisperCppStt` e `PiperTts` podia anular o contexto no meio da inferência ou criar um segundo contexto de 75 MB órfão. `PiperTts` também descartava a causa da falha de carga — agora registra (`ultimaFalha` + log).
- **`SpeechRecognizer.cancel()`** rodava na thread de quem cancelou; reenviado para a main via `Handler`.
- **STT e ciclo de voz saíram da main thread** (`Dispatchers.Default`): carregar ~75 MB por JNI na Main congelava a UI e arriscava ANR.
- **`withCamera()` sem guarda** sobrescrevia `activeCamera`/`activeStream`, deixando o stream anterior impossível de parar; `capturePhoto()` sem trava de concorrência. Ambos com guarda, e `conflate` no fluxo de frames.
- **Erros de sessão do DAT** deixaram de ser coletados e descartados: expostos em `sessionErrors` para virar earcon.
- **`LaconicityPolicy` reprovava fala legítima:** "por", "tudo" e "bem" estavam na lista de cortesia — "Apoio solicitado **por** rádio." era barrado. Cortesia passou a ser casada por locução ("por favor", "tudo bem"), mantendo isoladas só as inequívocas.
- **`PlacaValidator.extrair()` casava dentro de sequência maior:** "código ABC12345" produzia `ABC1234` e o app consultaria uma placa que ninguém falou. Passou a exigir fronteira de token.
- **Roteador: `"placa"` solto vinha antes de `NARRAR`.** "narrar ocorrência: veículo de placa ABC1234" virava consulta e a narração do agente se perdia. Verbos explícitos primeiro; o termo solto só se nada mais específico casar.
- **`PlacaOcr` virou `Closeable`** (o `TextRecognizer` segura o modelo nativo) e o `getThermalHeadroom` saiu do corpo do composable (chamada de binder a cada recomposição; acima de 1 Hz a API devolve `NaN`, tornando o teto inútil).
---

## 2026-08-14 — Auditoria final: isolamento do teste do MockDeviceKit

- **`MockDeviceController` idempotente.**
  `MockDeviceKit.getInstance` é singleton de processo e `enable()` duas vezes sem `disable()` no meio **aborta o processo** nativamente (`MediaCodec CHECK_EQ(mState, UNINITIALIZED)`). O painel de diagnóstico já tinha um guarda, mas ele falhava quando o pareamento retornava `false` (o `enable()` já havia acontecido). O guarda agora vive no controller.

- **`MockDeviceKitStreamTest` fora da suíte padrão (`@Ignore` com motivo), rodável isolado.**
  Passa quando é o único teste da execução; aborta com `MediaCodec CHECK_EQ` sempre que outra classe divide o processo — reproduzido inclusive com `PlacaOcrTest`, que não toca áudio nem vídeo. O `MediaCodec` é do decodificador do **MockDeviceKit** (artefato de debug do SDK em preview), não nosso. Alternativa descartada: deixar a suíte vermelha por defeito de ferramenta. O KDoc traz o comando de execução isolada; reavaliar a cada atualização do `mwdat-mockdevice`.
---

## 2026-08-14 — M8 (energia: modos, FGS por modo, freio térmico)

- **Modos como política pura (`PowerPolicy` em core-agent), não como `if` espalhado no serviço.**
  `perfil(modo)` diz o que fica ligado (HFP, wake word, câmera, teto de FPS, supressão de informativos) e `tiposDeServico(modo)` diz quais `foregroundServiceType` o modo exige. O serviço só obedece. Efeito: a economia de bateria é **testável em JUnit**, sem medir bateria. Standby fecha o HFP porque o SCO é o maior consumidor contínuo; Ativo ouve mas **não filma**; Ocorrência liga tudo em janela curta e é o modo que suprime informativo.

- **`foregroundServiceType` no manifest é a UNIÃO; em `startForeground()` é o subconjunto EXATO do modo.**
  Declarar `connectedDevice|microphone|camera` no `<service>` é obrigatório (Android 14+ recusa o que não foi declarado), mas subir sempre com os três faria o app segurar microfone e câmera em Standby. O runtime deriva os tipos de `PowerPolicy.tiposDeServico`.

- **O serviço DEGRADA quando falta permissão de runtime — nunca crasha.** *(bug real, achado em execução)*
  Subir FGS com tipo `camera`/`microphone` exige a permissão de runtime **concedida**, não só a do manifest — senão é `SecurityException` e o processo morre (aconteceu no emulador). `entrarEmPrimeiroPlano` intersecciona os tipos do modo com as permissões concedidas. Verificado: sem `CAMERA`, Ocorrência sobe com `0x90` (connectedDevice|microphone) e o app segue vivo; com `CAMERA`, sobe com `0xD0`. A falta de sensor vira falha audível na feature, não queda do pipeline.

- **`NaN` do `getThermalHeadroom()` NÃO é 0 (`ThermalGovernor`).**
  `NaN` = sem informação (chamadas próximas demais, aparelho sem sensor). Tratar como 0 faria o app se achar frio e acelerar justamente quando não sabe nada. Regra: sem informação, **mantém o teto vigente** e **não autoriza rajada** (`podeIniciarRajada == false`). Acima de 0,85 cai para 2 fps (taxa mínima válida do DAT); em 1,0 a câmera desce.

- **`FPS_PADRAO = 7`.**
  O gargalo é o Bluetooth Classic: `LOW`/`MEDIUM` com FPS baixo dá qualidade **por frame** melhor que `HIGH`/30 fps. 7 é um valor válido do DAT (`frameRate ∈ {2,7,15,24,30}`).

- **`START_STICKY` + serviço iniciado sempre de tela visível.**
  Iniciar FGS em background é `ForegroundServiceStartNotAllowedException` — por isso os botões de modo vivem no painel e `CopilotService.iniciar` é chamado da UI. `START_STICKY` faz o pipeline voltar se o sistema matar por memória.
---

## 2026-08-14 — M7 (rede: Supabase + fila offline)

- **WhatsApp fora do escopo por ora (decisão do produto).**
  `MessagingGateway` e `TacticalMessage` permanecem como contrato (M0); nenhuma implementação de WhatsApp foi escrita. A saída de rede do M7 é o **Supabase**. Reavaliar quando o escopo do canal de mensageria for definido.

- **Fila offline durável em disco (`FileOutbox`): um arquivo por item, nome = sequência com zero-pad.**
  Alternativa: Room. Descartada por peso (compilador de anotação, esquema, migrações) para uma fila de chave-valor FIFO. Um arquivo por item dá ordem FIFO pelo nome, escrita **atômica** (`.tmp` + rename — não deixa item meio-escrito se o processo morrer) e remoção sem reescrever o resto. `payload` em Base64 porque pode conter quebras de linha. Só `java.io` ⇒ testável em JVM pura.

- **A fila é agnóstica ao conteúdo (`payload: String` opaco).**
  Motivo: não acoplar a fila a `TacticalMessage`/Supabase. `TacticalMessageCodec` traduz; quem drena interpreta. Evidência, telemetria e mensagem tática usam a mesma fila com `type` diferente.

- **`OutboxDrainer` para na primeira falha do lote; item veneno é descartado após 5 tentativas.**
  Parar cedo: se a rede caiu, insistir nos próximos do lote só gasta bateria. Descarte após N: senão um item malformado trava a fila para sempre — o descarte é contado no `DrainReport`.

- **Honestidade no despacho (`TacticalDispatcher` → `Despacho.Enviada` | `Despacho.Enfileirada`).**
  Materializa a regra do M0 em **tipo**, não em comentário: o chamador é obrigado a distinguir os dois casos, então o TTS não tem como dizer "enviado" quando só enfileirou. Testado nos dois caminhos.

- **Supabase por PostgREST com OkHttp, sem SDK.**
  Alternativa: `supabase-kt`. Descartada por arrastar Ktor + serialização para um único `POST`. `Prefer: resolution=merge-duplicates` torna o reenvio **idempotente** (retry após queda de rede não duplica ocorrência). Credenciais em runtime, nunca versionadas.

- **Duas faixas de WorkManager: tática (`CONNECTED`) e pesada (`UNMETERED` + `requiresCharging` + `requiresBatteryNotLow`).**
  Mensagem tática não pode esperar o celular ir para o carregador; evidência e modelos podem. Ambas com backoff exponencial e `enqueueUniqueWork(KEEP)` para não empilhar drenagens concorrentes. O gateway (com a chave) vive num holder de processo — não vai para a base do WorkManager.
---

## 2026-08-14 — M6 (visão: OCR de placa) + evidência cifrada

- **Placa: um único validador (`PlacaValidator` em core-agent), Mercosul estrito `[A-Z]{3}[0-9][A-Z][0-9]{2}` + antigo `[A-Z]{3}[0-9]{4}`.**
  Antes o roteador tinha regex própria com 5º caractere permissivo (`[A-Z0-9]`). Unificado num validador puro reusado por voz **e** OCR — uma fonte de verdade, testável em JVM. O 5º dígito Mercosul é sempre letra, então o estrito é o correto.

- **OCR on-device: ML Kit Text Recognition (Latin embarcado, `com.google.mlkit:text-recognition:16.0.1`).**
  Alternativa: Tesseract (via JNI) — descartada por peso de integração e por o modelo Latin do ML Kit já rodar **offline** (sem rede no caminho crítico, exigência do edital). O frame é insumo efêmero: só o texto validado sobrevive à inferência (minimização). `PlacaOcr` vive em `app` (orquestra câmera + ML Kit); o validador puro fica em core-agent.

- **Cofre: um `EncryptedFile` (AES-256 GCM/Tink) por segmento + chave-mestra no Android Keystore.**
  Alternativa: um único arquivo com todos os segmentos concatenados. Descartada porque **um arquivo por segmento** permite apontar exatamente qual segmento foi adulterado (o GCM autenticado já faz a descriptografia falhar naquele arquivo). Manifesto (`manifest.txt`) fica em claro — hashes não são segredo e permitem verificação por terceiros sem a chave.

- **Cadeia de custódia: `HashChain` puro (SHA-256 encadeado, `sha256(hash_anterior + bytes)`).**
  `verificar()` devolve o índice do 1º segmento adulterado/faltante, ou `-1` se íntegro. Camada dupla: (1) GCM apanha adulteração de byte no ciphertext; (2) hash encadeado apanha troca/remoção/reordenação de segmentos. Verificado em teste instrumentado: 30 segmentos → cadeia íntegra; virar 1 byte do segmento 2 → `verificar()` retorna 2.

- **`security-crypto = 1.1.0-alpha06`.**
  A 1.0.0 estável arrasta uma versão antiga do Tink com problemas em APIs novas; a alpha06 é a linha usada de fato em produção para `EncryptedFile`/`MasterKey`. Reavaliar quando a 1.1.0 estabilizar.
---

## 2026-08-14 — Ajuste dos achados em aberto (lint, mock, ABIs)

- **Lint REABILITADO** (AGP 8.7.2 → **8.9.2**, Gradle 8.9 → **8.11.1**). O bug `IncompatibleClassChangeError` do lint com Kotlin 2.2 estava no AGP 8.7.2; o 8.9.2 corrige. Removido o `subprojects { lint disabled }`. Build completo verde COM lint. O lint pegou 1 achado real (`AudioRecord` sem `@RequiresPermission`) → suprimido com justificativa (`RECORD_AUDIO` é garantido pelo onboarding do app).

- **`mwdat-mockdevice` gateado para fora do release:** `compileOnly` em core-glasses + `debugImplementation` no app. O `MockDeviceController` compila mas não é empacotado no release e nunca é carregado (gate `BuildConfig.DEBUG`). Verificado: `assembleRelease` verde.

- **ABIs consistentes:** `abiFilters = arm64-v8a + x86_64` (app + core-voice). O AAR do sherpa trazia 4 ABIs mas o whisper só compila as filtradas — agora batem (celulares + emuladores Intel).

- **Correção de flakiness:** `MockDeviceKitStreamTest` agora para o stream e espera assentar antes de `mock.disable()` — evitava um SIGSEGV na thread nativa `AsyncVideoFrame` (frames em voo). Suíte instrumentada: 5/5 verde.
---

## 2026-08-14 — Resample 8→16 kHz + Piper (sherpa-onnx) VERIFICADO

- **`PcmResampler` (core-common):** interpolação linear, upsample 8→16 kHz (HFP→whisper). Em core-common para respeitar a regra de módulos (core-audio e core-voice usam). `WhisperCppStt` reamostra internamente. Teste JVM.

- **`PiperTts` (core-voice):** TTS neural pt-BR via **sherpa-onnx** (onnxruntime). Usa o **AAR pré-compilado** (v1.13.5, 49 MB) — evita compilar o onnxruntime do zero. Integração: **flatDir repo** (`core-voice/libs`) + `compileOnly` no core-voice (library não repackagea AAR) + `implementation` no app (empacota `.so` + classes). AAR e modelo **não versionados** (baixados no setup).

- **Verificado em runtime:** `PiperTtsTest` sintetizou "Apoio solicitado, guarnição avisada." → **52.736 amostras @ 22 kHz** no emulador arm64, pt-BR neural, 100% local. Suíte instrumentada completa: 5/5 verdes (MDK, áudio, whisper, Piper juntos).

- **Requisito do espeak:** o `espeak-ng-data` (fonemização) usa `fopen` → precisa de **diretório de filesystem**, não pode vir de assets. Copiado para `filesDir` (padrão `copyDataDir()` do exemplo oficial). O `.onnx`/`tokens` vêm dos assets.

- **Modelo Piper:** `vits-piper-pt_BR-faber-medium` **int8** (21 MB vs 67 MB do fp32) — alinhado à orientação "quantizado" do Un12.

- **Revisão (correção):** `AndroidTts.readWavAsPcm` agora localiza o chunk `data` (não assume offset 44) — robusto a WAVs com chunks LIST/fact.
---

## 2026-08-14 — M4-nativo: whisper.cpp on-device VERIFICADO

- **whisper.cpp compilado e transcrevendo no emulador arm64.** Teste instrumentado `WhisperCppSttTest` carregou o `ggml-tiny` e transcreveu o `jfk.wav` corretamente ("...ask not what your country can do for you...") — 100% local, sem rede. O maior risco de ambiente do projeto está superado e provado em runtime.

- **Integração via submódulo + código verbatim do exemplo oficial (Regra Zero).** whisper.cpp adicionado como **git submódulo** em `core-voice/src/main/cpp/whisper`. `jni.c`, `CMakeLists.txt`, `LibWhisper.kt`, `WhisperCpuConfig.kt` reaproveitados **verbatim** de `examples/whisper.android`. `WhisperCppStt` (nosso) implementa `SttEngine` sobre o `WhisperContext`.

- **Toolchain nativo:** NDK **27.0.12077973** + CMake **3.22.1** (instalados via sdkmanager). `externalNativeBuild` em `core-voice`, `abiFilters = arm64-v8a` (cobre celulares modernos + emulador; outras ABIs no release). No emulador arm64 (com fp16) carrega `libwhisper_v8fp16_va.so`.

- **Quirk registrado:** `initContextFromInputStream` é declarado no `LibWhisper.kt` mas **não** implementado no `jni.c` (UnsatisfiedLinkError). Usar `createContextFromFile` (produção, modelo baixado) ou `createContextFromAsset` (teste, lê do APK sem copiar 77 MB para o disco do emulador).

- **Modelo não versionado:** `ggml-tiny.bin` (~77 MB) baixado no setup (`.gitignore`). O teste usa `Assume` — se o modelo faltar, é ignorado, não quebra o build. jfk.wav (344 KB) versionado como fixture.

- **Pendência real (não bloqueia):** o HFP entrega **8 kHz**; o whisper espera **16 kHz** → falta o **resample 8→16 kHz** no caminho HFP→whisper (o `AudioRecord` a 16 kHz e o `jfk.wav` já são 16 kHz). Ver `docs/COMPLIANCE.md` §D.
---

## 2026-08-13 — M4 (voz on-device: núcleo verificável; nativo diferido)

- **Escopo Guia-alinhado:** "AndroidTts/fallback primeiro valida o ciclo; whisper/piper/openWakeWord/Silero depois". O build **nativo** (NDK, `.so` por ABI, modelos em assets) é o maior risco de ambiente e **não** foi feito nesta sessão — os scaffolds (`WhisperCppStt`, `AndroidOnDeviceStt`) declaram `isAvailable()=false` e o pipeline degrada graciosamente (comando → `NaoReconhecida` → earcon), sem inventar transcrição.

- **`DeterministicIntentRouter` (core-agent):** correspondência por padrão + verbos-chave sobre a transcrição normalizada (minúsculas, sem acento), **sem LLM**. Extrai placa (Mercosul/antiga) e prioridade (armado→EMERGENCIA, urgente→ALTA). Teste de **20 frases operacionais + laconicidade ≤7 palavras** passou. `OperationalResponses` mapeia intenção → frase curta (resultado sensível não é falado — vira earcon).

- **`EnergyVoiceActivityDetector` (core-voice):** VAD por energia (RMS), fecha a janela após hangover de silêncio. Teste JVM. Upgrade planejado: **Silero VAD** (neural).

- **`AndroidTts` (TtsEngine):** `TextToSpeech.synthesizeToFile` → WAV → `PcmAudio`, reproduzido pelo pipeline HFP do M3. Nunca `speak()` antes do `onInit`. Primário será **Piper (sherpa-onnx)** pt-BR.

- **Wake word:** push-to-talk como 1ª versão (Un12 endossa "zero custo ocioso"); **openWakeWord** depois.

- **Impedância registrada:** o `SpeechRecognizer` do Android **auto-captura** o áudio e não encaixa em `transcribe(pcm: ShortArray)` — o fallback Android exige um caminho auto-capturador separado (M4-nativo), sempre com `createOnDeviceSpeechRecognizer`/`EXTRA_PREFER_OFFLINE` (o reconhecedor padrão vaza áudio).

- **Verificação:** unit tests verdes; ciclo **comando(texto) → roteador → TTS** demonstrado ao vivo no emulador (`PedirApoio` → "Apoio solicitado, guarnição avisada."). O aceite pleno do M4 (modo avião: "Claryon, pedir apoio" falado → resposta) precisa do **STT real (whisper.cpp)** e de **device físico** — M4-nativo.
---

## 2026-08-13 — Verificação: áudio NÃO passa pelo DAT (3 fontes)

- **Confirmado na doc viva 0.9, no sample oficial e no Un13:** o áudio dos óculos usa os **perfis Bluetooth do sistema** (A2DP saída / HFP bidirecional), **não** uma API de áudio do DAT. Doc textual: "DAT sessions share microphone and speaker access with the system Bluetooth stack". O sample `AudioInputHandler.kt` captura via `AudioRecord(AudioSource.MIC)` puro — nenhum símbolo `mwdat`. O "sound-in-video" (CHANGELOG 0.9) é áudio do `AudioRecord` **muxado** no vídeo (vídeo = DAT; áudio = Bluetooth). ⇒ **M3 (AudioManager/AudioRecord/AudioTrack) está correto.**

- **REFINAMENTO de ordering (0.9 vence a redação 0.8 dos padrões de engenharia):** com HFP + câmera DAT, a ordem oficial é **(1) `addCamera` → (2) configurar/iniciar HFP, esperar a rota assentar → (3) `stream.start()`**. Iniciar o stream antes do HFP faz a rota de áudio falhar silenciosamente. A orquestração (M8) deve garantir que o **stream** da câmera só inicie após a rota HFP confirmada. (Para o pipeline de voz sem câmera, o HFP sobe independentemente.)

- **NUANCE A2DP×HFP:** mutuamente exclusivos — ativar HFP (mic) derruba o A2DP e a **saída cai para 8 kHz mono** durante a sessão. TTS/earcons durante a escuta (HFP) saem em 8 kHz; saída de alta qualidade exigiria A2DP (sem mic). Tensão de design a tratar em M4/M5.

- **Nota:** o sample usa `AudioSource.MIC`; usamos `VOICE_COMMUNICATION` (AEC/NS, melhor para comando de voz) — escolha deliberada.
---

## 2026-08-13 — M3 (pipeline de áudio HFP)

- **`GlassesAudioManagerImpl` (core-audio) — áudio NÃO passa pelo DAT.** `iniciar()` roteia `TYPE_BLUETOOTH_SCO` via `availableCommunicationDevices`+`setCommunicationDevice` (API 31+); trata **`false` e lista vazia** com erro tipado claro (`audio.no_sco`/`audio.set_comm_device_false`), nunca falha silenciosa. `microfonePcm()` = `AudioRecord(VOICE_COMMUNICATION)` mono PCM16 → `Flow<ShortArray>` em `Dispatchers.IO` (release no finally). `reproduzir()` = `AudioTrack(USAGE_VOICE_COMMUNICATION)` com drain antes do stop. `liberar()` = **`clearCommunicationDevice()`** (obrigatório; senão o áudio do sistema fica preso em 8 kHz) + restaura o modo.

- **`allowFallbackToDefault` (flag de construtor).** Quando `true` e não há SCO, roteia para o dispositivo de comunicação padrão — permite exercitar record→playback **sem** fone Bluetooth (o MDK não simula áudio; o emulador não tem SCO). Em produto: `false` (só HFP dos óculos). No app, ligado apenas em `BuildConfig.DEBUG`.

- **Aceite de RUNTIME.** 3 testes instrumentados passaram no emulador (`AudioRoutingTest`: sem-SCO → falha clara + limpeza segura; + M2 stream). O **eco ao vivo no painel funcionou**: `AudioRecord` capturou **46.720 amostras** (~2,9 s a 16 kHz) e o `AudioTrack` reproduziu — pipeline record→playback real, mesmo com o emulador sem áudio. O **eco HFP específico** (roteado pelo SCO dos óculos/fone) exige **fone Bluetooth físico** — a ser validado em dispositivo real (o emulador não tem SCO).

- **Permissões em runtime:** MainActivity pede `RECORD_AUDIO` + `BLUETOOTH_CONNECT` no launch.
---

## 2026-08-13 — M2 (Mock Device Kit + registro/sessão/câmera)

- **Fonte autoritativa: sample oficial `CameraAccess` (repo `facebook/meta-wearables-dat-android`, tag 0.9.0).** Clonado num diretório irmão e lido para confirmar TODAS as assinaturas 0.9 antes de escrever (Regra Zero, "samples > docs"). Correções vs. material 0.8: `RegistrationState` = {UNAVAILABLE, REGISTERING, REGISTERED, UNREGISTERING} (sem "AVAILABLE"); câmera 0.9 é `session.addCamera(config): DatResult<Camera>` → `Camera.stream` (não `addStream`); `DatResult` usa `.onSuccess/.onFailure { error, _ -> }` (evitar `getOrThrow` em produção); `session.start()` retorna `Unit` (resultado via `session.state`/`session.errors`).

- **`Wearables.initialize` encapsulado em `core-glasses` (`GlassesRuntime.initialize`), chamado no `ClaryonApp: Application`.** O compilador provou a fronteira: o `app` não consegue importar `Wearables` (deps do DAT são `implementation` em core-glasses, não `api`) — o isolamento da fachada é garantido pelo módulo, não só por convenção.

- **Enums do DAT mapeados por NOME** (`enumValueOf<T>(state.name)` com fallback) em vez de referenciar constantes — resiliente a acréscimos no SDK em preview e reduz risco de erro de memória.

- **`DatGlassesFacade`** implementa `GlassesFacade` sobre a 0.9 (registro, sessão, `addCamera`→`Camera`→`stream`, `capturePhoto`) e expõe StateFlows extras (streamState/frameInfo/deviceCount) para o diagnóstico. **`MockDeviceController`** (debug) faz `enable → pairGlasses(RAYBAN_META) → powerOn → don → setCameraFeed(CameraFacing)` (câmera do celular). Painel Compose reflete tudo ao vivo.

- **Aceite de build: `./gradlew :app:assembleDebug` e `:app:assembleDebugAndroidTest` verdes contra o SDK 0.9** (APK ~56 MB, libs nativas do DAT).

- **✅ Aceite de RUNTIME atingido (emulador android-35 provisionado nesta sessão).** O teste instrumentado `MockDeviceKitStreamTest` passou (`tests=1 failures=0`, ~0,97 s), confirmando registro → sessão STARTED → stream STREAMING via MockDeviceKit, sem hardware. O painel ao vivo confirmou o mesmo visualmente: Registro REGISTERED, Sessão STARTED, Stream STREAMING, Frames subindo (#56 · 480×640, câmera emulada como fonte).

- **`AutoDeviceSelector` mantido como instância única** (como o sample oficial, `by lazy`), não recriado a cada `createSession`. E `createSession` on-failure agora é **logado** (falha nunca é silêncio).

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
  Servidor `meta-wearables` → `https://mcp.developer.meta.com/wearables`, transporte HTTP, **sem autenticação** (verificado: `initialize` e `tools/list` respondem 200; ferramentas `search_dat_docs` e `search_webapps_docs` disponíveis). Escopo de projeto para toda a equipe herdar via `.mcp.json`. Servidores MCP sobem no startup da ferramenta, então só carrega numa sessão nova, e exige aprovação de confiança na primeira vez.

- **✅ Versão do SDK do DAT registrada: `mwdat = "0.9.0"`.**
  Fonte: `search_dat_docs` (MCP oficial `meta-wearables`), consulta "Android Gradle dependency setup", 2026-08-13. Grupo `com.meta.wearable`; artefatos `mwdat-core`, `mwdat-camera`, `mwdat-display`, `mwdat-mockdevice`. Repositório Maven: `https://maven.pkg.github.com/facebook/meta-wearables-dat-android` (GitHub Packages, exige PAT `read:packages`). A versão exata mais recente deve ser reconferida em GitHub Packages no início do M1. Fecha o último item de aceite do M0.

- **Credencial do GitHub Packages: `username = ""` + chave `github_token` em `local.properties` (ou env `GITHUB_TOKEN`).**
  Correção sobre o placeholder inicial, que supunha `gpr.user`/`gpr.token`. Forma alinhada à doc oficial (`search_dat_docs`, 2026-08-13). Placeholders em `settings.gradle.kts`, `README.md` e `local.properties` ajustados.

- **API real de câmera confirmada (não escrever de memória): `session.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))`.**
  Fonte: `search_dat_docs` "camera streaming setup on Android", 2026-08-13. `frameRate` válido ∈ {2,7,15,24,30}; `VideoQuality` ∈ {LOW 360×640, MEDIUM 504×896, HIGH 720×1280}; `StreamState`: STARTING→STARTED→STREAMING→PAUSED→STOPPING→STOPPED→CLOSED. Divergências vs. minhas suposições do M0 (`addStream`/`quality`) ficam ABSORVIDAS por `GlassesFacade` — nenhum outro módulo muda. A tradução concreta é escrita no M2.

- **`coroutines-core` exposto como `api` em `core-common`.**
  Motivo: os contratos usam `Flow`/`StateFlow`; expor uma vez evita repetir a dependência em cada módulo consumidor.

## Aditivo 02 — escopo congelado (2026-08-14)

- **WhatsApp fora do escopo, em definitivo.** Era ideia de plano, não consta do formulário
  de inscrição submetido — verificado antes de decidir, portanto não há alteração de escopo
  submetido a formalizar (item 14.1 do edital). O `MessagingGateway` permanece como contrato;
  o canal concreto passa a ser a rede tática própria (`core-net`), não plataforma de terceiro.

- **Palavra de ativação: "Claryon". *Slash* segue como nome do copiloto.**
  Critério acústico: o canal HFP em banda estreita corta em 4 kHz, então a palavra precisa
  carregar identidade abaixo disso. "Claryon" (plosiva + líquida + duas vogais abertas +
  nasal final) sobrevive ao corte; "Slash" (/s/ + /ʃ/) tem quase toda a energia acima dele.
  Alternativa descartada: **"Câmbio"** — acusticamente ótima, mas é vocabulário de protocolo
  de rádio, o que cria laço acústico (alto-falante *open-ear* a centímetros do microfone
  reproduz "câmbio" de uma transmissão recebida e acorda o detector) e inverte o significado
  consolidado (marca *fim* de fala, não início). Nome do produto e gatilho falado são coisas
  separadas — precedente: Siri / "Hey Siri".
  A decisão final deve ser reconfirmada pelo protocolo de medição (50 pronúncias × 3 pessoas,
  filtro passa-baixa de 4 kHz, 30 min de fala natural como material de falso positivo).

- **⚠️ Regra Zero — o toque capacitivo na haste NÃO é gatilho livre para o app.**
  Fonte: `search_dat_docs` (MCP oficial `meta-wearables`), consultas "touch gesture on glasses
  temple" e "listen to input events from glasses", 2026-08-14. A doc oficial afirma, em três
  lugares independentes, que o toque na haste é **gesto de sistema ligado ao ciclo de vida da
  sessão**: *tap* alterna pausa/retomada de um stream ativo e *tap-and-hold* encerra a sessão.
  Entrega de eventos de toque ao app por callback existe **apenas no contexto da capacidade de
  display** ("users can interact with display content through captouch") — que os Ray-Ban Meta
  não têm e que o projeto omitiu do Gradle de propósito.
  Consequência: usar a haste como PTT tende a **pausar ou encerrar a sessão de streaming**.

- **✅ V1 — MEDIDO: o toque na haste pausa o stream E a sessão. Descartado como gatilho de PTT.**
  Teste `PttTriggerTest` (emulador Android 15, mwdat 0.9.0, 2026-08-14), com sessão STARTED e
  stream STREAMING, disparando `services.captouch.tap()`:

  ```
  stream STREAMING → PAUSED · sessão STARTED → PAUSED
  ```

  Um único toque derrubou os dois. Apertar para falar interromperia a própria transmissão —
  o oposto do PTT. **Gatilho primário passa a ser o long-press do botão de volume**, que
  ainda ganha em latência: o evento de haste viaja por Bluetooth (100–200 ms estimados),
  o botão do celular é local. O gatilho fica atrás de `PttTrigger` para que a escolha seja
  configuração, e o teste virou asserção de regressão — se o SDK mudar, ele falha e a
  decisão é revisitada.

  Assinatura do mock confirmada por **inspeção do artefato** (`javap` sobre
  `mwdat-mockdevice-0.9.0.aar`), já que a doc oficial descreve o comportamento mas não
  publica a API Android: `MockGlassesServices.getCaptouch(): MockCaptouchKit`, com
  `tap()` e `tapAndHold()`.

- **`espeak-ng-data` podado para `pt_dict` + `en_dict` (113 dicionários → 2).**
  O modelo Piper declara `espeak: {voice: pt-br}`; os outros 111 idiomas eram peso morto.
  Mantidos íntegros o núcleo fonético (`phondata`, `phonindex`, `phontab`, `intonations`),
  `lang/` e `voices/` — baratos (≈950 KB somados) e arriscados de podar. Inglês fica para
  termos estrangeiros; o produto é focado no Brasil.
  Ganho: **cópia no primeiro boot de 18 MB → 1,8 MB** (o que o usuário sente) e APK de
  release de 235 → 227 MB (os dicionários comprimem bem, então o ganho no pacote é menor
  que os 16 MB brutos). Verificado por `ModelosProducaoTest`, que apaga a cópia anterior
  antes de sintetizar — senão o teste passaria lendo dados que o APK não empacota mais.

- **Verificações dependentes de hardware isoladas em `docs/VERIFICACOES_COM_HARDWARE.md`.**
  O emulador não tem rádio: a latência de saída Bluetooth (40–150 ms) e todo o
  comportamento do HFP/SCO são propriedades de hardware. O MockDeviceKit também não simula
  áudio. Em vez de testes que passam sem exercitar nada, os cenários ficam com `Assume` e
  a lista de execução mora num documento próprio. Aguarda celular Android (15/08) e fone
  Bluetooth com HFP.

## Fase 2 — rádio tático: fatia vertical (2026-08-14)

- **`core-net` criado; depende apenas de `core-common`.** Mantém a regra de que os
  `core-*` não dependem uns dos outros. `PrioridadeTransmissao` é definida no próprio
  módulo em vez de importada de `core-agent` — e a distinção é real: prioridade *de
  transmissão* (quem recebe, em que raio) não é prioridade *de reprodução* (o que
  interrompe o quê).

- **✅ V2 — MEDIDO: `c2.android.opus.encoder` existe.** Sonda no emulador (Android 15):
  encoder e decoder Opus presentes, decoder aceitando 8 kHz nativamente (nossa taxa HFP)
  com bitrate a partir de 6 kbps. **Caminho: MediaCodec**, sem dependência nativa nova.
  Ressalva registrada: a lista de codecs de um emulador é a do sistema convidado e difere
  de aparelho real — a confirmação que decide fica em `docs/VERIFICACOES_COM_HARDWARE.md`.
  Se o encoder faltar no aparelho, o plano B é libopus via NDK, cuja toolchain já existe.

- **Pré-roll de 600 ms com VAD retroativo, e não recuo fixo de 300 ms.**
  O aditivo divergia de si mesmo (600 numa seção, 300 em duas outras); fica 600. O ponto
  não é "guardar mais": o VAD retroativo transmite **a partir do início detectado da fala**,
  então a janela maior aumenta a margem de busca, não o que é transmitido. Uma fala iniciada
  450 ms antes do toque — comum sob estresse — seria cortada pelo recuo fixo.
  Limite explícito, testado: buffer circular, só em RAM, nunca persistido, zerado ao fim de
  cada transmissão e no descarte por canal ocupado.

- **Buffer de jitter começa em 100 ms e cresce só sob perda medida** (piso 60, teto 300).
  Buffer fixo grande é a forma mais comum de jogar fora 200 ms sem necessidade. Quadro
  perdido vira PLC, nunca silêncio — silêncio soa como corte, interpolação soa como voz.
  Quadro que chega tarde demais é **descartado**: tocá-lo fora de ordem inverteria a fala.

- **Emergência toma o canal de prioridade menor, mas não de outra emergência.**
  Cortar quem já está numa ocorrência em curso é pior que esperar, e duas P1 se revezando
  indefinidamente calariam as duas. TTL de 30 s na concessão: se o cliente morre no meio,
  a trava expira e o canal volta ao grupo — sem isso, um crash calaria a guarnição.

- **Soltar o PTT é cancelamento, e o encerramento roda em `NonCancellable` com timeout.**
  Defeito encontrado relendo o próprio código: chamada suspensa em `finally` sob
  cancelamento falha na hora, então o último quadro nunca sairia e **o receptor esperaria
  indefinidamente por uma fala que já acabou** — o modo de falha mais confuso possível num
  rádio. O timeout de 2 s impede que um socket morto trave o botão.

- **`java.util.Base64` no protocolo, não `android.util.Base64`.**
  O do JDK existe desde a API 26 (mínimo do projeto é 31) e funciona fora do Android,
  mantendo a camada verificável sem emulador quando o JSON não estiver no caminho.

- **Formato de fio isolado em `ProtocoloRealtime`; transporte é encanamento fino.**
  O que muda num serviço em evolução é o envelope, e concentrá-lo faz a correção ser de um
  arquivo. Round-trip de quadro (inclusive bytes altos e a marca de último) é testado; o
  `TransporteRealtime` em si **não foi verificado contra projeto real** — depende de
  credencial, e está declarado como tal no KDoc.
