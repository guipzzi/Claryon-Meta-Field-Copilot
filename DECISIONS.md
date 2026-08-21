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
  o oposto do PTT.

  Assinatura do mock confirmada por **inspeção do artefato** (`javap` sobre
  `mwdat-mockdevice-0.9.0.aar`), já que a doc oficial descreve o comportamento mas não
  publica a API Android: `MockGlassesServices.getCaptouch(): MockCaptouchKit`, com
  `tap()` e `tapAndHold()`.

- **2026-08-15 — O toque na haste sai por completo: do produto, da instrumentação e do plano.**
  A medição de V1 já tinha derrubado a haste como gatilho de PTT, mas o registro deixava a
  porta encostada: falava em "gatilho primário passa a ser o long-press do botão de volume",
  atrás de um `PttTrigger` configurável. Nada disso existia em código — nenhum
  `dispatchKeyEvent`, nenhuma interface `PttTrigger` — e um plano que sobrevive à sua própria
  refutação vira dívida silenciosa: quem lê o documento acredita que há dois gatilhos.

  Decisão: **acionamento é por voz (comandos) e pelo botão do app (transmissão), e nada mais.**
  Removidos `MockDeviceController.tap()`/`tapAndHold()` e o `PttTriggerTest`. O teste de caos
  do *cascading stop* passou a derrubar a sessão por **desligamento** em vez de gesto — um
  teste é o último lugar onde uma capacidade removida deveria continuar viva.

  Alternativa descartada: botão de volume como PTT. Ganharia uso com luva e com o aparelho no
  coldre, mas só funciona com o app em primeiro plano ou com um serviço interceptando a tecla,
  e sequestrar o volume de um aparelho institucional é um efeito colateral que ninguém pediu.
  A medição de V1 fica registrada acima como evidência de por que a haste caiu; o que sai é o
  código, não a razão.

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

- **✅ Transporte verificado contra o Supabase real (2026-08-14).**
  `TransporteRealtimeIntegracaoTest`, três testes executados (não pulados): socket abre e
  entra no canal; **um quadro de 90 bytes atravessa entre dois clientes byte a byte**;
  anúncio de fala atravessa com a prioridade preservada. O envelope de canais do Phoenix e
  os nomes de evento em `ProtocoloRealtime` funcionam como escritos — nenhuma correção de
  formato de fio foi necessária.
  Topologia de dois clientes, e não auto-eco, de propósito: em produção o emissor não deve
  receber a própria voz, então validar por eco provaria uma configuração indesejada. É
  também a topologia da demonstração.

- **`INTERNET` declarada no manifest do `core-net`, não só no `app`.**
  Sem isso o teste instrumentado do módulo — que roda num APK próprio, isolado do `app` —
  falhava ao abrir o socket sem dizer por quê. Declarar a permissão no módulo que de fato a
  usa faz o consumidor herdá-la por merge, em vez de precisar saber disso.

- **Credenciais do Supabase por `local.properties` → `BuildConfig`, nunca versionadas.**
  Chave `anon` (respeita RLS), não `service_role` — esta fica só nas variáveis de ambiente
  das Edge Functions. Ausentes, o teste de integração se declara pulado: ninguém deve
  precisar de credencial para rodar a suíte.

## Conflitos do PTT — o alto-falante open-ear (2026-08-14)

- **⚠️ Defeito no próprio SQL, pego antes de aplicar: `current_agent_id()` era referenciada
  6 vezes nas políticas de linha e nunca foi definida.** `CREATE POLICY` teria falhado. As
  duas RPCs chamadas pelas Edge Functions (`agentes_no_raio`, `posicao_relativa`) também não
  existiam. Corrigido: a função entra no topo do `0002`, antes das políticas que a usam, e as
  RPCs viram `0003`. `agents` ganha `auth_user_id` — é o que liga o agente ao usuário
  autenticado e, portanto, a base de toda política.
  `current_agent_id()` é `SECURITY DEFINER` por necessidade, não conveniência: ela lê
  `agents`, e com os privilégios do chamador uma política sobre `agents` que a invocasse
  entraria em recursão. `search_path` travado porque função DEFINER com caminho aberto é
  escalada de privilégio.

- **Um mecanismo para quatro conflitos: `SupressorDeSaidaPropria`.**
  Os alto-falantes são *open-ear*, a centímetros do array de microfones: **todo som que
  produzimos é um som que vamos capturar**. Isso gera (1) o tom de início entrando na própria
  transmissão, (2) a cauda de uma transmissão recebida voltando ao grupo, (3) o TTS do
  copiloto entrando na fala, (4) o detector de ativação acordando com a própria saída.
  Registrar as janelas de reprodução e descartar da captura o que cai nelas resolve os
  quatro. **Descartar é o certo, não um mal menor:** enquanto o alto-falante toca, o
  microfone capta a mistura e não há como separá-la; perder 200 ms de sobreposição é melhor
  que difundir a própria saída. No caso da cauda de recepção, descartar **é** a disciplina
  de meio-duplex.
  Detalhe que só apareceu ao desenhar: o tom de início é alta energia, então o VAD retroativo
  do pré-roll o marcaria como "início da fala" — o recurso que existe para não cortar a
  primeira sílaba passaria a cortá-la.

- **Recomendação de feedback: háptico no aperto, sonoro no resto.**
  O aperto é o único instante em que realimentação e captura coincidem; tudo o mais acontece
  com a captura desligada ou com a transmissão descartada. Vibração não vaza para o
  microfone por construção (celular no bolso, microfone nos óculos). O supressor existe de
  qualquer forma — os conflitos 2 e 3 exigem — então um tom de início continua possível, e a
  escolha entre os dois fica para medição com fone físico (colete tático pode abafar a
  vibração).

- **`GatilhoPtt`: debounce de 250 ms e duração mínima de 150 ms.**
  Botão mecânico gera múltiplos eventos por acionamento, e com luva gera mais — sem debounce
  um aperto vira duas transmissões, e a segunda corta a primeira no controle de piso. Toque
  abaixo de 150 ms é encosto acidental: difundir 80 ms de ruído para a guarnição é pior que
  não transmitir. `cancelar()` arma o debounce para o agente não reabrir por reflexo em cima
  do próprio cancelamento.

## Esquema aplicado e políticas verificadas (2026-08-14)

- **✅ Migrações 0001–0004 aplicadas no projeto real; 17 de 17 verificações passam.**
  `servidor/verificacoes/0001_reciprocidade.sql` roda em transação com `ROLLBACK` — não
  deixa rastro e pode ser reexecutada em produção. Prova, com dados reais e trocando de
  papel: reciprocidade simétrica dentro do talk group, isolamento entre talk groups,
  `transmissions` recusando `UPDATE`/`DELETE`, `posicao_relativa` devolvendo 1195 m e rumo 0°
  sem expor coordenada, e as funções de `private` inalcançáveis por `anon`/`authenticated`.

- **⚠️ Achado da verificação: o `0002` protegeu o tráfego e esqueceu o cadastro.**
  RLS ficou habilitado em `transmissions`, `deliveries` e `agent_positions`, mas `units`,
  `agents`, `talk_groups` e `memberships` ficaram sem política. Dois efeitos opostos e ambos
  ruins: a política de posições, que fazia *join* em `memberships`, passou a negar tudo —
  inclusive o agente ver a si próprio; e, no sentido inverso, qualquer autenticado leria o
  **cadastro inteiro** (matrícula e indicativo de toda a corporação). Proteger a porta e
  esquecer a janela. Corrigido no `0004`.

- **Recursão de RLS resolvida por funções `SECURITY DEFINER` em `private`.**
  Uma política sobre `memberships` que consulte `memberships` recursa infinitamente.
  `meus_talk_groups()` e `pares_do_talk_group()` resolvem a associação ignorando RLS (o que
  DEFINER faz por definição) e cortam o laço. `pares_do_talk_group()` faz `union` com o
  próprio id: sem isso um agente recém-cadastrado, ainda sem guarnição, não enxergaria a
  própria posição — o app quebraria no primeiro uso.

- **`positions_read` reescrita como pertinência a conjunto, não *join*.**
  Com RLS agora ativo em `memberships`, o *join* original acrescentaria avaliação de política
  aninhada por linha. **A reciprocidade continua garantida por construção**: o conjunto
  `pares_do_talk_group` é simétrico — se A está no de B, B está no de A.

- **Erros que só apareceram contra um Postgres real, e o que ensinam.**
  (a) `search_path = ''` é a recomendação certa de segurança, mas obriga a qualificar
  **tudo** — inclusive o tipo `geography`, não só as funções `ST_*`. (b) Para desambiguar um
  parâmetro de uma coluna homônima usa-se `funcao.parametro`, **sem** o schema; com
  `private.` na frente vira erro de sintaxe. Pior: sem prefixo nenhum, a condição viraria
  tautologia e "onde está Alfa Dois?" devolveria um agente qualquer. (c) PostGIS ficou em
  `public` neste projeto; se migrar para `extensions`, os prefixos do `0003` mudam junto.

- **SQL no Supabase pela Management API, não por `psql`.**
  A senha do banco nunca autenticou (três tentativas, pooler e conexão direta, mesmo erro),
  e insistir nela travava o trabalho. `servidor/executar_sql.py` usa
  `POST /v1/projects/{ref}/database/query` com um personal access token — caminho que
  independe da senha do Postgres. Só biblioteca padrão: o build precisa funcionar offline, e
  ~120 linhas de HTTP não justificam uma dependência nova.
  `--somente-leitura` é imposto pelo **servidor** (a sessão roda como
  `supabase_read_only_user` e o Postgres recusa DDL), não é convenção do cliente.
  Armadilha registrada: o Cloudflare à frente da API recusa o User-Agent padrão do `urllib`
  com `error code: 1010` — que parece erro de credencial e não é. Identificar-se resolve.

## Fase 2 fechada — codec, receptor e a ligação com o produto (2026-08-14)

- **⚠️ MEDIDO: o decodificador Opus devolve a 24 kHz, não a 8 kHz.** Entram 160 amostras
  por quadro de 20 ms e saem **480**. O Opus trabalha internamente a 48 kHz e o decodificador
  do Android reamostra na saída. Se o receptor tivesse assumido a taxa de entrada, a voz
  sairia **três vezes mais grave e três vezes mais lenta** — defeito que soa como problema de
  microfone e manda procurar no lugar errado. `CodecDeVoz.taxaDeSaidaHz` passa a ser contrato,
  descoberto pela contagem de amostras do primeiro quadro, com teste de regressão.

- **`codificar` devolve zero ou mais pacotes, não um.**
  O `MediaCodec` é um **pipeline**, não uma função: consome quadros antes de emitir o
  primeiro pacote (medido: o 1º sai no quadro 1) e pode emitir mais de um por chamada. Um
  contrato de 1-entra-1-sai obrigaria a inventar um pacote vazio no aquecimento — que a
  camada de perda interpretaria como quadro perdido, disparando PLC sobre áudio inexistente.
  Efeito colateral útil: acomoda o agrupamento de 3 quadros sem mudar a assinatura.
  A sequência avança por **pacote enviado**, não por quadro capturado, senão a numeração
  ficaria com buracos que o receptor leria como perda.

- **PLC caseiro, com limitação declarada.** `MediaCodec` não expõe o PLC do Opus. Repetimos o
  último quadro com atenuação de 40%, que desvanece em perdas seguidas (medido: rms 8002 →
  79 na décima). Pior que o PLC real, muito melhor que silêncio — que soa como corte. Se
  incomodar em campo, o caminho é libopus via NDK, onde `opus_decode(dec, NULL, ...)` faz o
  PLC de verdade; a toolchain já existe.

- **Controle de piso como função do Postgres, não Edge Function.**
  A concessão precisa ser **atômica**: dois agentes que apertam no mesmo instante não podem
  ambos receber o canal, e "ler, decidir, escrever" numa função serverless abre exatamente
  essa janela. `for update` + `on conflict` fecham no banco. A identidade **não é parâmetro**
  — sai de `current_agent_id()`; se viesse por argumento, qualquer cliente pediria o canal em
  nome de outro e o piso viraria negação de serviço contra a guarnição.
  Verificado contra o banco real: 13 de 13.

- **O laço de reprodução do receptor encerra sozinho depois de 2 s sem nada a tocar.**
  Mudança motivada por teste: o laço infinito era impossível de verificar em tempo virtual.
  Mas o desenho novo é melhor **no produto**, não só no teste — girar 50 acordadas por
  segundo enquanto ninguém fala é desperdício de bateria num aparelho que precisa durar o
  turno, e silêncio é o estado normal de um rádio. Também resolve o emissor que some no meio
  sem enviar o quadro final.

- **`core-net` ligado ao produto por `RadioTatico`.**
  Até aqui o rádio existia testado e desligado — o mesmo padrão que a Fase 1 corrigiu no
  executor, uma camada acima. O orquestrador amarra pré-roll, supressor, gatilho, sessão,
  codec, transporte e receptor, e o que ele de fato resolve é a **coordenação entre entrada e
  saída de áudio no mesmo aparelho**: toda reprodução abre janela no supressor, o pré-roll só
  é alimentado fora dessas janelas, e a captura descarta o que cair dentro delas.

- **Armadilhas de harness registradas** (custaram tempo e voltariam a custar):
  corpo de teste que termina em `Log.i` devolve `Int` e o JUnit recusa com
  `initializationError`, sem dizer qual método; e corrotinas do `backgroundScope` **não** são
  acordadas por `advanceUntilIdle()` nesta versão — medido, `subscriptionCount` ficava em
  zero. O escopo do receptor nos testes é filho do agendador, não do teste.


## Fase 3 — modo diferido, telemetria e caos de aparelho (2026-08-14)

- **⚠️ MEDIDO com MockDeviceKit: tirar os óculos do rosto é invisível para o app.**
  Dobrar as hastes e desligar o aparelho levam o stream de STREAMING a STOPPED; `doff` não
  muda nada. A doc esclarece que a sessão reage ao *doff* "when wear detection is enabled",
  e **não há API para habilitar** — é ajuste do aparelho, no app Meta AI.
  Consequência: com a detecção desligada, óculos fora do rosto seguem com sessão e rota
  ativas, e o beamforming que isola quem os veste deixa de valer — um PTT apertado nessa
  condição difunde a conversa ao redor. Mitigações existentes (PTT explícito, teto de 30 s,
  pré-roll nunca persistido) limitam a exposição, mas não substituem a notificação.
  A asserção fixa o comportamento medido: se um SDK futuro notificar, o teste falha e a
  limitação sai da lista. Verificação em aparelho real registrada no doc de hardware.

- **Modo diferido guarda Opus codificado, não PCM.** Um minuto de fala ocupa ~90 KB em vez
  de ~1 MB, e é o mesmo formato que vai para a rede — sem recodificar, sem perder qualidade
  de novo. Escrita atômica (`.tmp` + rename): processo morto no meio deixaria um arquivo que
  o drenador tentaria enviar para sempre.
  **A distinção que não pode se perder:** isto grava fala que o agente *decidiu* transmitir
  apertando o botão; o pré-roll vive em RAM e some se o botão não for apertado. A diferença
  entre as duas é a diferença entre rádio e escuta ambiente.
  Poda por validade de 6 h: fala de ontem chegando hoje não é informação operacional, é
  ruído — e guardar voz indefinidamente contraria a política de retenção.

- **Telemetria declara o que NÃO mede.** As medições são internas: captura→envio e
  recepção→reprodução. A saída Bluetooth (40–150 ms) e a rede ficam fora do que qualquer
  código dentro do app enxerga. O relatório diz isso em texto, e há teste que falha se a
  ressalva sumir — publicar medição interna como se fosse boca a ouvido seria um número
  otimista num documento técnico. Janela deslizante de 200 amostras: percentil recente é
  mais útil que média de tudo, porque a rede de agora importa mais que a de três horas atrás.
  Sem amostras devolve `null`, nunca zero: zero pareceria uma medição excelente.

## Fase 4 — consulta de posição, léxico de ocorrências e permissões (2026-08-15)

- **⚠️ ACHADO: a consulta de posição por voz não pode sair do espelho local.** A primeira
  versão de C2 lia `CanalDePosicoes`, o espelho que alimenta o mapa. Escrevendo o teste
  ficou claro que isso não fecha: o espelho **só existe enquanto o mapa está visível** — regra
  de bateria que vale — e a consulta por voz é justamente para usar com a tela apagada.
  Um copiloto de mãos livres que exige a tela ligada contradiz a própria premissa.
  A correção foi levar C2 para o servidor, e ela ficou melhor em três eixos: funciona com a
  tela apagada; custa uma requisição por pergunta em vez de um canal aberto o turno inteiro;
  e **a coordenada do par nunca chega ao aparelho**, porque `ST_Distance` e `ST_Azimuth`
  rodam dentro do Postgres. Filtrar no cliente exigiria entregar a coordenada primeiro — e
  "o aparelho de um agente jamais recebe a posição de outro" deixaria de ser garantia para
  virar promessa. O espelho ficou sendo o que sempre deveria ter sido: fonte do mapa, e só.

- **`public.consultar_posicao(indicativo)` não aceita o solicitante como parâmetro.**
  `private.posicao_relativa` aceita — e é por isso que ela continua em `private`. Se o
  solicitante fosse argumento da função exposta, qualquer agente autenticado perguntaria
  "onde está Alfa Dois em relação a Bravo Um" e, variando o segundo argumento entre os pares
  do talk group, **trilateraria a posição absoluta de qualquer um usando só distâncias** —
  que é exatamente o dado que a API foi desenhada para poder devolver. A checagem de talk
  group continuaria passando: o solicitante forjado é membro legítimo. O solicitante vem do
  JWT. Mesma classe de defeito que tirou `agentes_no_raio` do schema público na Fase 2.
  Verificado por `servidor/verificacoes/0003_consulta_de_posicao.sql`, 15 checagens.

- **Vocabulário sobreposto entre dois classificadores é defeito de desenho.** "Apoio" estava
  no léxico de ocorrências *e* era `Intent.PedirApoio`. Resultado: "pedir apoio" virava
  alerta de ocorrência, porque o ramo do léxico vinha antes no `when`. O comportamento
  passou a depender da ordem das regras, que é a pior forma de decidir o que o produto faz.
  "Apoio" saiu do léxico. Na mesma linha, "modo abordagem" virava alerta porque "abordagem" é
  tipo de ocorrência: comandos explícitos passaram a vir antes da classificação automática —
  o verbo do agente vence o palpite do sistema, porque ele disse o que queria.

- **Uma régua de escalada, dois consumidores.** Ao tirar "apoio" do léxico, apareceu que
  `Intent.PedirApoio` tinha escala própria e mais fraca: "policial baleado" era emergência
  pelo léxico e prioridade normal pelo pedido de apoio. A mesma frase, dois despachos
  diferentes, conforme o caminho que o roteador tomasse — inconsistência invisível até
  acontecer em campo. `LexicoDeOcorrencias.escalarPrioridade` virou pública e é a única régua.

- **"tiros" e "homem caído" saíram do gatilho genérico de emergência.** O léxico os classifica
  com tipo, prioridade **e** logradouro. Mesma urgência, mais contexto para quem recebe.

- **⚠️ MEDIDO: o runner de instrumentação não concede permissões automaticamente.**
  A expectativa era que concedesse o que está no manifest; o emulador estava com tudo negado,
  e o teste reprovou. É o estado real de primeira instalação, e virou o cenário do teste.
  `pm revoke` no pacote sob teste **mata o processo** — por isso as combinações de negativa
  ficam na JVM, onde varrer todas custa milissegundos, e o aparelho só verifica conceder.

- **A tela de permissões não pede nada no `onCreate`.** A versão anterior disparava o diálogo
  no instante em que a Activity nascia, com lista incompleta, e **ignorava o resultado** —
  negar não mudava nada, e o app seguia para o painel simplesmente sem funcionar. Diálogo sem
  contexto é diálogo negado, e negado duas vezes é negado para sempre. Motivo primeiro,
  diálogo depois do toque. E o texto fala de capacidade perdida ("Sem câmera, não leio
  placas"), nunca do identificador da plataforma — há teste que reprova MAIÚSCULA_COM_SUBLINHADO.

- **`shouldShowRequestPermissionRationale` devolve `false` em dois casos opostos** — nunca
  pedimos, ou negaram em definitivo — e o Android não distingue. Sem registro local do que já
  foi pedido, o botão "Permitir" apareceria para sempre abrindo um diálogo que o sistema não
  mostra mais; o sintoma, para o agente, é "apertei e não aconteceu nada".

- **`ACCESS_BACKGROUND_LOCATION` não é pedida.** O serviço em primeiro plano com tipo
  `location` cobre o turno inteiro. A de segundo plano só acrescentaria o diálogo "o tempo
  todo" — assustador, e sem capacidade nova. Há teste no aparelho que reprova se ela entrar
  no manifest final por merge de biblioteca.

- **Só o microfone bloqueia a entrada no app.** Barrar por câmera negada seria o produto se
  recusando a fazer o que ainda sabe fazer, com o agente já no pátio. Sem Bluetooth o ciclo
  de voz roda inteiro com fone comum — perde-se o beamforming, o que muda quem é gravado, e
  isso é dito em voz alta.

## Achados da revisão adversarial e C5 (2026-08-15)

- **⚠️ O léxico casava gatilho por substring.** `contains` cru fazia qualquer palavra que
  *contivesse* um gatilho disparar alerta para a guarnição inteira, com prioridade P1/P2, sem
  confirmação e sem desfazer. Verificado executando: "obrigado" e "chama a brigada militar"
  → BRIGA/ALTA; "vou pegar o suspeito" → RACHA; "soltaram fogos" → INCÊNDIO/EMERGÊNCIA;
  "cobrança de pensão" → ANIMAL PERIGOSO. Em RS/SC a corporação **se chama** Brigada Militar:
  o falso positivo seria diário. A função que conserta — `palavraInteira` — já existia no
  arquivo e estava morta. Lição de processo: código morto ao lado de código errado é sinal de
  que a versão certa foi escrita e não ligada.

- **"de novo" é locução de rádio, não comando.** Estava em `DETALHAR`, avaliado antes do
  léxico: "tiroteio de novo na Rui Barbosa" virava `Detalhar` e o app repetia a última
  resposta — ou dizia "Nada a repetir." — enquanto nenhum alerta saía.

- **Índice de texto normalizado não vale no texto original.** `normalizarTexto` remove
  pontuação e colapsa espaços, então `original.substring(normalizado.indexOf(x))` diverge:
  "Tiroteio, na Rui Barbosa" produzia o logradouro **"Rui Barbos"**. O gazetteer passou a ser
  `normalizado → grafia canônica`, e devolve a canônica.

- **`Math.round(NaN)` é 0**, então `Rumo.deGraus(NaN)` devolvia **norte** — rumo afirmado com
  confiança total a partir de um número inválido. E `NaN` é o que o PostGIS produz em
  `ST_Azimuth` para pontos coincidentes: dupla na mesma viatura, que não é borda. `Rumo?`
  agora, e a fala vira "com você".

- **Laconicidade testada só com o caso fácil.** A frase de posição cabia em 7 palavras com
  "Alfa Dois" e estourava com "Alfa Dois Zero" — indicativo de três palavras que existe e que
  o roteador produz. A regra dura passava verde e quebraria em campo. A fala agora degrada por
  corte, do detalhe menos essencial para o mais.

- **`shouldShowRequestPermissionRationale` e recomposição.** Negar produzia um
  `EstadoDePermissoes` estruturalmente **igual** ao anterior, então o Compose não recompunha e
  o ramo "Abrir ajustes" era inalcançável: o botão continuava "Permitir", abrindo um diálogo
  que o sistema não mostra mais. `neverEqualPolicy` + observador de `ON_RESUME` (conceder
  pelos ajustes não reinicia o processo; só revogar reinicia).

- **`podeOperar` como portão de entrada criava beco sem saída.** Quem concedesse o microfone e
  negasse a localização ia direto ao painel da segunda abertura em diante, e C2, C3 e o mapa
  ficavam mortos em silêncio para sempre. O portão passou a ser `tudoConcedido`, e "seguir
  assim mesmo" vale só para a sessão.

- **O `append` de evidência rodava fora do mutex.** Só a leitura do handle estava protegida, e
  o pipeline de áudio anexa continuamente enquanto o agente pode dizer "encerrar gravação":
  `finalize` e `append` corriam em paralelo sobre o mesmo handle, e um bloco podia entrar
  depois do manifesto. Cadeia de custódia com bloco fora da cadeia é o que o módulo existe
  para impedir.

- **Preservar o handle do cofre para sempre era beco sem saída.** Numa falha persistente,
  `iniciar` respondia "Já gravando." e `encerrar` respondia "Cofre falhou." pelo resto do
  turno. Agora o handle é liberado após 3 tentativas: os segmentos continuam no disco e
  cifrados, perde-se o manifesto — e evidência sem manifesto ainda se pericia, enquanto app
  que não grava não produz evidência nenhuma.

- **`CanalDePosicoes` não tinha exclusão mútua.** `assinar` e `desassinar` suspendem no meio
  (rede). Fechar o mapa enquanto `assinar` estava suspenso fazia `desassinar` rodar inteiro e,
  **depois**, `assinar` gravar `assinado = true`: assinatura viva com o mapa fechado — a
  difusão de todos para todos o turno inteiro, que é a regra que a classe existe para impor.
  Mutex + `@Volatile` + `ConcurrentHashMap`. **O teste de regressão foi validado por mutação**:
  com um `Mutex()` novo por chamada ele falha; com o mutex compartilhado, passa.

- **Prefixo ambíguo devolvia par arbitrário.** Com Alfa-01 e Alfa-02 na guarnição, "alfa"
  devolvia o primeiro por ordem de inserção — o agente ouvia a posição do par errado. E
  indicativo que normaliza para vazio casava com tudo, porque `startsWith("")` é sempre
  verdadeiro. Ambíguo passou a ser o mesmo que não encontrado.

- **C5: o mapa para de afirmar o que não sabe mais.** Três estados, não dois — atual, esmaecido
  (2 min) e antigo (10 min, e aí o marcador troca posição por idade). "Deslocando" só é
  afirmado sobre posição atual: dizer isso a partir de um dado de dez minutos é uma afirmação
  sobre o presente feita com informação do passado. E o redesenho é por tempo, não por pacote
  recebido — um par que **parou** de publicar precisa esmaecer sozinho, que é exatamente o
  caso que a regra cobre.

- **Mapa vazio e mapa indisponível são estados diferentes.** São indistinguíveis para quem
  olha, e a leitura errada é a perigosa: "ninguém por perto" quando a verdade é "não estou
  recebendo". A causa aparece escrita na tela.

## Campanha de caos com o MockDeviceKit (2026-08-15)

Doze cenários novos, escritos depois de confirmar por inspeção do artefato
`mwdat-mockdevice-0.9.0` três capacidades que a doc descreve mas não publica em
assinatura Android: `MockDeviceKitConfig(initiallyRegistered, initialPermissionsGranted)`,
`MockPermissions.set/setRequestResult`, e `pairGlasses` até três vezes + `unpairDevice`.

- **⚠️ `startSession` devolvia `Result.Success` sem a sessão estar utilizável.** Completava
  logo depois de `created.start()`, deixando o estado real para quem observasse
  `session.state`. Medido: `startSession()` → `Success`, `withCamera()` → `Success`, **zero
  frames**. Nenhum caminho de erro, nenhum earcon. É a violação da regra central do projeto —
  sucesso que não significa sucesso — dentro da própria fachada que deveria protegê-la.
  Agora espera `STARTED` com teto de 12 s; `STOPPED` e estouro viram falha tipada, e a
  referência é limpa (mantida, a chamada seguinte devolveria sucesso imediato apontando para
  uma sessão que nunca funcionou).

- **⚠️ `withCamera` também devolvia sucesso mudo.** Com a permissão de câmera do DAT negada,
  `addCamera` devolve sucesso e `stream.start()` não reclama — simplesmente nenhum frame
  chega, para sempre. O consumidor ficava suspenso esperando uma imagem que não vem.
  Vigia de primeiro frame com teto de 6 s → `glasses.no_frames`, que vira earcon.

- **Duas capturas concorrentes: `[Success, Failure]`.** A guarda de uma-por-vez faz a segunda
  falhar limpo e a primeira concluir — que é o comportamento certo. O agente que diz
  "consultar placa" duas vezes sob estresse recebe uma resposta, não nenhuma.

- **Desemparelhar durante a operação → sessão `STOPPED`.** Sem sessão fantasma. `tapAndHold`
  → `STOPPED`, e criar sessão nova funciona (*cascading stop* confirmado). Oscilação rápida
  de don/doff/fold/unfold 12 vezes não deixa estado inconsistente.

- **Três aparelhos pareados → uma sessão, `STARTED`.** O `AutoDeviceSelector` escolhe um.

- **Sem registro → `RegistrationStatus.UNAVAILABLE`,** e parear depois **não** leva a
  `REGISTERED` (medido: fica `null` após 10 s). O registro exige o fluxo do Meta AI; o
  pareamento sozinho não basta.

### Erros meus, registrados porque custaram tempo

- **`{ frames++ }` não conta frames.** O bloco de `withCamera` recebe o *Flow* e é invocado
  uma vez; incrementar ali mede invocações. Duas medições foram publicadas erradas antes de
  eu perceber — a correção é consumir o fluxo (`fluxo.first()`).
- **O runner não concede permissão de runtime.** Sem `CAMERA` concedida por
  `uiAutomation.grantRuntimePermission`, o feed do MDK não entrega frame nenhum, e o sintoma
  imita perfeitamente "a permissão do DAT foi respeitada". A própria doc do MDK faz isso no
  `@Before`; eu não seguia.
- **`capturePhoto` exige stream ativo.** A primeira versão do teste de concorrência não abria
  stream, as duas capturas falhavam com `no_stream`, e aquilo parecia defeito grave do produto.

### Limitações do simulador, mapeadas

- **Áudio não existe.** `MockGlassesServices` expõe `camera` e `captouch`, e nada mais.
  HFP, beamforming, wake word e latência fala→resposta só em hardware.
- **Conceder permissão depois de negada não restaura o vídeo**, nem com sessão nova. O estado
  parece fixado no pareamento, e reparear exige `disable()/enable()` — que aborta o processo.
- **Um método por processo.** Testes que abrem stream passam isolados e devolvem zero frames
  em lote: o singleton do MDK não volta ao estado limpo. `scripts/caos_mdk.sh` roda assim.

### Revisão da própria correção (2026-08-15)

Reler a correção do MDK depois de escrita rendeu quatro defeitos, e o pior nasceu
**dentro dela**:

- **⚠️ Corrida de ordem introduzida pela correção.** `startSession` fazia
  `observeSession(created)` e só então `_session.value = STARTING`. Era inofensivo enquanto a
  função devolvia na hora; virou corrida no instante em que passou a esperar `STARTED`: se o
  coletor emitisse `STARTED` antes da atribuição, o bom estado era sobrescrito por `STARTING`,
  o `first { }` esperava os 12 s inteiros, e uma sessão **funcionando** era derrubada por
  `cleanupSession()`. Uma correção de honestidade que criava uma falha inventada.

- **`cleanupSession` não parava a sessão no SDK.** Só limpava referências. Enquanto era
  chamado apenas na transição para `STOPPED`, tudo bem — a sessão já tinha caído. Ao passar a
  ser chamado no estouro de prazo, virou vazamento: sessão viva dentro do SDK, óculos
  transmitindo por Bluetooth sem indicador, e o `createSession` seguinte encontrando a
  anterior ativa. Agora chama `stop()` antes de soltar.

- **O vigia de primeiro frame vazava.** `comVigia.cancel()` estava só no caminho feliz; com
  `block` lançando, a corrotina sobrevivia até o prazo e chamava `camera.stop()` sobre uma
  câmera já parada. Foi para o `finally`.

- **⚠️ `startRegistration` nunca era chamado por ninguém.** O método existia em
  `DatGlassesFacade`, documentado, junto de `ensureRegistered` — e nenhum dos dois tinha
  chamador. O app media `RegistrationStatus.UNAVAILABLE` e não fazia nada. Como foi medido que
  **parear um aparelho não restaura o registro**, o agente ficaria com um app que não conecta
  e nenhuma pista do porquê. O painel ganhou "Conectar aos óculos" quando o estado não é
  `REGISTERED`, e a perda passou a ser anunciada em voz alta.

- **Asserção fraca em `tresAparelhosPareados`.** Verificava só que a sessão saía do limbo — o
  que passaria igual se o app tivesse aberto três. Agora afirma `Result.Success`, estado
  `STARTED`, e que a segunda chamada reaproveita em vez de empilhar.

### Varredura de código sem chamador (2026-08-15)

Depois de encontrar `startRegistration` órfão, varri o projeto atrás da mesma classe de
defeito. O resultado justificou a varredura — e incluiu reincidência minha.

- **⚠️ `RadioTatico` nunca é construído.** O orquestrador do rádio tático — PTT, pré-roll,
  supressor de eco, gatilho, controle de piso, Opus, transporte — existe como classe, tem
  testes, e **nenhum código do app o instancia**. O supressor e o gatilho *são* usados, mas só
  dentro dele. Na prática, C1 não é alcançável por quem abre o aplicativo: a capacidade está
  construída e desligada da tomada. É o mesmo defeito de `startRegistration`, uma ordem de
  grandeza acima.

- **Reincidência, na mesma sessão.** Criei `anunciarCapacidadesPerdidas` e
  `anunciarRegistroPerdido` e não chamei nenhum dos dois — logo depois de escrever, em
  `DECISIONS.md`, que método de aviso sem chamador é pior que aviso nenhum. Agora ambos saem
  de `anunciarEstadoDegradado`, chamado por `LaunchedEffect` na abertura do painel.

- **⚠️ `videoStream` era coletado duas vezes.** `Stream.videoStream` é `Flow`, não
  `SharedFlow` (confirmado em `mwdat-camera-0.9.0`), então `observeStream` e `withCamera`
  abriam **duas assinaturas independentes** do mesmo vídeo: cada quadro entregue e convertido
  em dobro, no caminho crítico, e a segunda coleta existindo só para alimentar um contador que
  ninguém lê fora do painel. Num produto com meta de 12%/h em modo Ativo, decodificar vídeo em
  dobro para atualizar um rótulo é caro do jeito errado. `FrameInfo` passou a sair da coleta
  única de `withCamera`.

- **A mensagem do mapa culpava o agente.** Quando a assinatura falhava, dizia sempre "Sem
  publicar sua posição, não é possível ver a dos outros" — mas a causa real, em 100% dos
  casos, era outra: `PublicadorDePosicaoSupabase.assinarPares` devolve `false` por construção,
  porque **a recepção de posições dos pares não existe no transporte**. Culpar o usuário por
  uma capacidade ausente é a mesma desonestidade de "Alfa Dois não localizado" quando falta
  login. As duas causas agora têm mensagens distintas.

- **`emitindoAgora` removido.** Alias puro de `suprimido`, sem chamador. Duas portas para a
  mesma sala fazem quem lê procurar a diferença que não existe.

## App principal e o rádio ligado na tomada (2026-08-15)

### O rádio deixou de ser código morto

`RadioTatico` passou a ser instanciado por `RadioViewModel`, e a captura foi verificada no
emulador: o indicador de microfone do próprio Android acende ao abrir a guarnição — o
pré-roll está rodando. Três invariantes sustentadas na ligação:

- **A rota de áudio é pré-condição de tipo.** `entrarEmModoAtivo(rota)` só aceita um
  `GlassesAudioRoute`, e a única forma de obter um é rotear de fato. Não existe caminho que
  suba o rádio capturando pelo microfone do celular.
- **O botão não espera a rede.** `aoPressionar` chama e volta; concessão de canal e socket
  correm em paralelo.
- **A amplitude da forma de onda é medida do PCM real**, com pico (não RMS, que achata o
  ataque da sílaba) e suavização assimétrica. Uma senoide decorativa mentiria exatamente
  quando a verdade importa: microfone mudo, rota caída, mão sobre o aparelho.

### Design: painel de instrumento, não aplicativo escuro

- **Âmbar `#FF6B35` tem um significado só: no ar.** Não marca botão primário, não pinta
  ícone selecionado, não destaca nada. No instante em que âmbar significa duas coisas, deixa
  de significar qualquer uma — e a coisa que ele significa é "sua voz está indo para a
  guarnição inteira".
- **A moldura de 2 px na tela toda enquanto transmite**, por cima de qualquer aba. Uma
  transmissão acidental difunde a fala do agente *e de quem está ao lado*; um ícone de 24 dp
  não é aviso proporcional a isso.
- **Monoespaçada para indicativo, horário, canal, distância e rumo.** Não é estilo: é o
  vernáculo do rádio, e é a mesma razão pela qual o produto fala dígito a dígito.
- **Fundo `#0A0C0F`, com viés azul-ardósia em vez de preto puro.** O agente olha esta tela no
  escuro da viatura, com a pupila dilatada; preto absoluto contra texto branco é contraste
  agressivo. E tema claro não existe — visão noturna queimada custa minutos.
- **Nenhum ripple.** `clickable` padrão lê `LocalIndication`, e o Material 2 entra por
  dependência transitiva fornecendo um `PlatformRipple` que o Foundation atual **recusa** — o
  app morria na primeira composição com botão. `indication = null` conserta o crash e o
  desenho de uma vez: onda circular é gramática do Material, e este painel é feito de fios.
- **Navegação por rótulo, sem ícone.** Ícone de "guarnição" é metáfora a aprender; a palavra
  já está no vocabulário do agente.

### A tela de perfil é relatório de prontidão, não configurações

Quase não há o que configurar, de propósito: cada interruptor é uma forma de o produto se
comportar de um jeito que o agente não previu. A tela responde uma pergunta — *dá para
confiar nisto hoje?* — e cada capacidade morta traz a causa junto, no vocabulário dele.
Verificado na tela: "Registro em UNAVAILABLE" vazava identificador de plataforma e foi
trocado por "Os óculos não estão pareados. Conecte pelo app Meta AI."

### Transcrever a guarnição não é transcrever terceiros

A proibição do projeto vale para a fala de **terceiros** — o abordado, o transeunte. O
histórico do grupo é tráfego de rádio entre agentes que apertaram um botão para transmitir a
colegas, no exercício da função. A distinção é de consentimento e de papel, e está escrita
onde o dado é definido (`FalaNoGrupo`).

## Seed do piloto e a primeira vez que as telas tiveram conteúdo (2026-08-15)

Rodar o app com dados reais rendeu quatro achados que nenhuma suíte pegaria.

- **⚠️ "Rádio" é o modelo de interação, não o transporte — e a barra mentia sobre isso.**
  `_estado` gravava `Pronto` uma vez, ao abrir, e nunca mais olhava: dizia "segure para
  falar" com o WebSocket caído. Isto não é detalhe de interface. O produto é **PTT sobre IP**;
  o rádio analógico da corporação funciona em túnel, em subsolo e com a torre caída, e este
  não. Se a tela sugerir a mesma independência, mente sobre a única coisa que separa os dois —
  e a mentira só é descoberta na hora em que a diferença importa. Vigia de 2 s, e a falha diz
  a causa: *"Sem dados. O canal depende da rede."*

- **⚠️ O modelo de leitura exige entrega explícita, e o seed não criou nenhuma.**
  `transmissions_read` libera o que o agente transmitiu **ou** o que foi entregue a ele, via
  `deliveries` — não tudo que passou pelo talk group. É mais forte que "membro vê tudo":
  produz trilha auditável de quem foi de fato informado, que num contexto de segurança pública
  é a diferença entre "a guarnição foi avisada" e "alguém falou no rádio". O app mostrava
  exatamente uma fala — a própria — e parecia defeito de consulta. Era o modelo funcionando
  como projetado, com dado pela metade.

- **PostgREST recusa embed ambíguo (PGRST201).** Há **dois** caminhos de `transmissions` para
  `agents`: a autoria e a tabela de entregas. `agents!inner` não basta; é preciso nomear a
  chave estrangeira — `agents!transmissions_author_agent_id_fkey`.

- **Embed um-para-um devolve objeto, não array.** `agent_positions` tem `agent_id` como chave
  primária, então vem objeto. Ler só o array fazia a idade virar `null`, o par virar offline e
  a contagem mostrar 0/2 com todo mundo publicando. O leitor aceita as duas formas.

- **Seed direto em `auth.users` exige as colunas de token vazias, não nulas.** O GoTrue as lê
  em `string` do Go; `NULL` produz "converting NULL to string is unsupported" e a resposta vira
  HTTP 500 — que na tela aparece como "servidor indisponível", indistinguível de rede caída.

### Identificador e nome do canal são coisas separadas

A consulta usa o UUID do talk group; a tela mostra "GTA-3 Alfa". Mostrar o UUID seria vazar
chave primária para o agente, e usar o nome na consulta quebraria no dia em que dois grupos se
chamassem igual.

### Sobre o aparelho de teste

O **Galaxy A30 não roda este app**: topa em Android 11 (API 30) e o `minSdk` é 31, por causa de
`setCommunicationDevice` — a API que roteia o HFP. Verificado no **Moto G15** (720×1600, 280
dpi, Android 15), que é o perfil realista de aparelho de corporação e roda. Se o piloto
precisar alcançar a faixa do A30, é preciso um caminho de compatibilidade com
`startBluetoothSco()` — decisão de produto, não de código.

## Posição em segundo plano ligada, e o botão em gramática de HUD (2026-08-15)

- **⚠️ `ColetorDePosicao` estava sem chamador — o quarto caso na mesma sessão.**
  Construído, comentado e nunca instanciado. Agora vive no `CopilotService`, com escopo do
  **serviço** e não do ViewModel: a coleta tem de sobreviver à tela, porque o agente fecha o
  app e guarda o celular no bolso. Verificado no emulador — `coleta em ATIVO por gps:
  60000ms / 50.0m` no log, e o serviço segue vivo depois do HOME.

- **O publicador é injetado antes de o serviço subir.** Ao contrário, o serviço nasce
  coletando e descartando: o GPS acorda e o dado morre no caminho, que é o pior desperdício
  possível porque não aparece em lugar nenhum da interface.

- **O serviço não para ao sair da tela.** Só "Encerrar turno" o derruba — é a única ação em
  que o agente declara que parou de trabalhar. Fechar o app é guardar o celular, não sair de
  serviço.

- **Postgres Changes descartado por privacidade.** Publicar `agent_positions` no Realtime
  entregaria a linha inteira, `geom` incluso, a cada aparelho da guarnição. O mapa pergunta e
  o servidor responde em grandezas (`public.posicoes_do_grupo`). Com a tela aberta 5% do
  turno, sondar custa menos que assinatura viva — e a garantia permanece verificável.

- **Origem velha invalida a tela inteira, não uma linha.** Se a posição própria está
  desatualizada, todas as distâncias foram medidas do lugar errado. O mapa recusa mostrar
  qualquer uma, em vez de exibir seis números plausíveis.

- **Botão: a terceira versão, e a lição das duas descartadas.** Retângulo com texto centrado
  dizia "toque". Alvo circular dizia o gesto certo e destoava de um painel de fios retos.
  Quatro colchetes simétricos e finos liam como **enfeite**. A referência do Gotham é HUD, e
  marca de registro nessa linguagem é **assimétrica e usinada**: duas âncoras em cantos
  opostos, curtas e grossas. O controle virou interruptor de painel — trilho de estado à
  esquerda para a visão periférica, rótulo alinhado à esquerda, leitura em monoespaçada à
  direita, barra de pressão na base.

### Caos verificado no emulador

| Cenário | Resultado |
|---|---|
| App fechado (HOME) | Serviço segue vivo, coleta continua |
| Permissão de local revogada durante a coleta | Sem crash |
| GPS desligado no meio | Degrada para provedor de rede, sem crash |
| Processo morto, recriado pelo sistema | Encerra sem reabrir o microfone, como projetado |

## A garantia de posição era falsa, e a verificação certificava o buraco (2026-08-15)

Uma pesquisa adversarial em quatro frentes derrubou a afirmação central de privacidade do
projeto — e derrubou junto o teste que a sustentava.

### ⚠️ `positions_read` entregava a coordenada de toda a guarnição

A política era `agent_id IN (private.pares_do_talk_group())`. Qualquer agente autenticado
podia, pelo PostgREST:

    GET /rest/v1/agent_positions?select=agent_id,geom

e receber `geom` bruto de todos os pares. Não por derivação trigonométrica — por **consulta
direta**. Toda a arquitetura de `consultar_posicao` e `posicoes_do_grupo` — devolver
grandezas, calcular no servidor, esconder `private.posicao_relativa` — protegia a porta da
frente com a lateral aberta.

Corrigido na 0010: leitura direta só da própria linha. Posição de par sai exclusivamente
pelas funções `SECURITY DEFINER`, que continuam funcionando porque são definer.

### ⚠️ E a verificação de reciprocidade **afirmava o buraco**

`0001_reciprocidade.sql` tinha "Alfa enxerga Bravo (mesmo talk group) → true", testando
justamente a leitura direta de `agent_positions`. Ela passava verde há semanas, certificando
como correto o comportamento que o produto declarava impossível.

É o pior tipo de teste: um que documenta o defeito como requisito. Reescrito para verificar
reciprocidade **sem exigir coordenada** — quem vê é visto, e ambos veem a mesma classe de
dado, que é distância e rumo.

### A derivação continua possível, e isso é honesto declarar

Com a própria coordenada + distância + rumo, a posição do par sai por trigonometria (fórmula
de destino). Medido: 30 m do valor real, e o erro é de arredondamento do teste.

O que a arquitetura **de fato** garante, agora verificado em `0005_posicao_inacessivel.sql`:

- a coordenada de par **nunca trafega e nunca repousa** no aparelho — não está em socket, em
  heap, em *heap dump*, em relatório de erro, nem numa apreensão do aparelho;
- a trilateração por origem forjada está bloqueada, porque o solicitante vem do JWT;
- a reciprocidade é estrutural: sem publicar, não se recebe.

O que ela **não** garante: que um cliente legítimo não possa calcular. É defesa em
profundidade, não impossibilidade — e a diferença entre "o aparelho poderia calcular" e "o
aparelho tem" é exatamente o que se defende numa auditoria.

### Dois defeitos no Whisper, achados na mesma varredura

- **`params.language = "en"`** sobre modelo multilíngue recebendo áudio em português. O motor
  tentava casar fonemas de pt-BR com vocabulário do inglês: salada com aparência de
  transcrição, que num registro operacional é pior que não transcrever.
- **`Result.success(Transcript(""))`** — texto vazio devolvido como sucesso, indistinguível
  de transcrição legítima que resultou em nada. Num produto cuja regra é que falha nunca é
  silêncio, sucesso vazio é a definição de silêncio. Virou falha tipada.

- **2026-08-15 — Auditoria de conformidade com as três premissas do material CEIA/Meta: um
  achado domina todos os outros.** Seis agentes independentes varreram o repositório contra
  (1) operação sem toque, (2) envelope do hardware, (3) orçamento de bateria e IA local.
  Veredictos: premissa 1 **VIOLA**, premissas 2 e 3 **PARCIAL**. 27 achados.

  **O achado que domina: o ciclo de voz não tem porta de entrada no app entregue.**
  `DiagnosticsScreen` é a única tela que chama `runCommand`/`falarComando`/`cicloDeVoz`, e ela
  saiu do `MainActivity` no commit `d888970` e nunca voltou — `grep -rn DiagnosticsScreen`
  devolve uma linha, a própria definição. Como `IntentExecutor.execute` só é alcançado por
  ela e por `VoiceCycle`, **C2, C3 e C4 estão mortos por voz**. O app que o agente abre é
  100% toque, que é exatamente o que a premissa 1 proíbe. Nenhuma suíte apontou: elas cobrem
  o comportamento das peças, não a existência do caminho até elas.

  É a quinta vez que este projeto encontra o mesmo defeito — capacidade construída, testada e
  nunca ligada (`RadioTatico`, `startRegistration`, `anunciarCapacidadesPerdidas`,
  `ColetorDePosicao`, agora o ciclo inteiro). A lição já registrada em §"Fluxo de trabalho"
  não bastou porque ela pede leitura de código; o que falta é uma **verificação de
  alcançabilidade** — para cada capacidade do edital, o caminho do gesto do agente até a
  execução, ou a admissão de que não existe.

  Corolários do mesmo defeito, já confirmados: `WakeWordDetector` é interface sem
  implementação enquanto `PowerPolicy` declara `wakeWordAtiva = true`; o modo **Standby**
  (a maior economia de bateria do desenho, porque fecha o SCO) é inalcançável, o serviço sobe
  em ATIVO e nada o tira de lá; `TelemetriaDoRadio` e todos os `Telemetry.mark()` não têm
  chamador, então **nenhuma das seis metas está instrumentada** — apesar de a regra do projeto
  ser "métrica adicionada no fim nunca é adicionada"; `SyncManager.instalar()` e as duas
  faixas de `WorkManager` não têm chamador; `aoDetectarRedeDisponivel` não tem chamador, então
  o teto de 5 min do backoff é só custo.

  Dois defeitos de áudio que quebram o rádio na prática, e que nenhum teste pegou porque o
  MDK não simula áudio: o microfone entrega **16 kHz** e o `RadioTatico`/Opus assumem
  **8 kHz** — a fala transmitida sai uma oitava grave e na metade da velocidade, e a taxa de
  pacotes dobra; e a recepção cria, escreve e libera um `AudioTrack` **por quadro de 20 ms**,
  em corrotinas concorrentes, o que reproduz a fala fora de ordem.

  Também confirmado, do lado bom: **não há IA na nuvem em caminho nenhum.** Whisper e Piper
  rodam locais, os modelos estão em `app/src/main/assets/`, e nenhum áudio, transcrição ou
  frame sai do aparelho no caminho crítico.

- **2026-08-15 — VERIFICADO NO ARTEFATO: o DAT 0.9.0 não expõe microfone, e o MockDeviceKit
  não simula áudio. As duas regras duras estão certas.**
  A dúvida veio de material de aula sugerindo captura de microfone pelo DAT. Conferido por
  `javap` sobre os três AAR do cache do Gradle, e confirmado pela doc oficial via MCP:

  ```
  Stream            → stop, state, videoStream, errorStream, start, capturePhoto
  StreamConfiguration → (VideoQuality, frameRate: Int, compressVideo: Boolean)
  MockGlassesServices → getCamera(), getCaptouch()
  ```

  Nenhum tipo de áudio na superfície pública, nenhum campo de áudio na configuração de stream,
  e o mock expõe exatamente dois serviços. Existem classes de áudio no artefato
  (`camera.internal.codec.AudioDecoder`, `camera.internal.events.AudioEvent`,
  `MetaWearablesDATAudioEventListener`), **todas em pacotes `internal`** — não são API pública,
  quebram a cada versão, e a Regra Zero manda parar diante de assinatura não confirmável.

  A doc oficial diz o mesmo por outro caminho: *"HFP (bidirecional, 8 kHz mono) — captura de
  voz do microfone dos óculos"* e *"sessões do DAT **compartilham** acesso a microfone e
  alto-falante com a pilha Bluetooth do sistema"*. O microfone dos óculos é alcançável, e é
  isso que `GlassesAudioManagerImpl` faz — só que por HFP/SCO, não pelo DAT.

  Provável origem da confusão: o sample oficial `CameraAccess` ganhou gravação com som na
  0.9.0, e o arquivo que faz isso é `AudioInputHandler.kt` — cujo cabeçalho diz **"Phone
  Microphone Audio Input"**. É o microfone do celular, para trilha de vídeo.

  Consequência prática: o MDK continua sem servir para testar o ciclo de voz. Fone Bluetooth
  com HFP permanece a única bancada honesta.

- **2026-08-16 — `CLAUDE.md` vira fonte única da verdade; `ARCHITECTURE.md`, `GUIA_TECNICO.md`
  e `INDICE.md` saem.** Medida a sobreposição antes de decidir: "Sequências que não podem ser
  invertidas" existia em **três** arquivos (ARCHITECTURE, GUIA_TECNICO §4, PADROES);
  "arquitetura de módulos" em **quatro**; "Regra Zero" em **três**. Eram ~565 linhas
  duplicadas, e duplicação foi exatamente o mecanismo pelo qual 96 linhas de regra dura
  divergiram em silêncio em 15/08 — o mesmo texto em dois lugares diverge, e nada denuncia.

  O `CLAUDE.md` novo tem 144 linhas e carrega o que é **regra**: os três pilares, as
  proibições, os invariantes sustentados por compilador, o gatilho de leitura por área, e o
  índice. O que é **referência** (`PADROES_DE_ENGENHARIA.md`) e **história** (`DIARIO`,
  `DECISIONS`) continua linkado e não é colado — colar o documento inteiro é o antipadrão que
  o material do programa descreve. `AGENTS.md` fica como ponteiro de dez linhas para
  ferramentas que procuram esse nome, com a regra explícita de não guardar conteúdo próprio.
  Os arquivos apagados continuam no histórico do git; nada se perdeu.

- **2026-08-16 — Três defeitos que reordenam o plano, achados na auditoria dos três pilares.**

  **(a) O PTT não é demonstrável hoje.** `RadioTatico.kt:88` declara `sampleRateHz = 8_000`
  como padrão e `RadioViewModel` não sobrescreve, enquanto `GlassesAudioManagerImpl` captura
  a 16 kHz. A voz transmitida sai uma oitava abaixo e com o dobro da duração. Não é detalhe
  de qualidade: é a ausência de qualquer vídeo, checkpoint ou demonstração possível. Meia
  sessão de conserto, e é a maior alavanca do projeto.

  Corolário que a auditoria anterior não tinha visto: o mesmo 8 kHz faz
  `PreRollBuffer(sampleRateHz = 8_000)` guardar **300 ms reais** em vez dos 600 ms que
  anuncia — o recurso que existe para não cortar a primeira sílaba corta a primeira sílaba —
  e faz `amostrasPorQuadro = 160` partir cada bloco de 20 ms em dois "quadros", dobrando a
  taxa de pacotes.

  **(b) As três Edge Functions não têm chamador.** `grep "functions/v1" --include=*.kt`
  devolve **zero**. `transmissions` nunca recebe INSERT, e `HistoricoDoCanal.falas()` devolve
  lista vazia sempre. O fio do canal construído em 16/08 exibe apenas as inserções otimistas
  locais, que somem em 10 s na recarga. A superfície visível do Pilar 1 está vazia em
  produção — e isso é maior que "falta escrever o campo `transcricao`".

  **(c) `AgrupadorDeQuadros` não existe**, apesar de `Transmissao.kt:28` afirmar que existe.
  São 50 mensagens por segundo de ~300 B para 30 B de voz.

  É a **sexta** vez que este projeto encontra capacidade construída, testada e nunca ligada.
  Por isso "construído" passa a ter definição escrita no `CLAUDE.md`: tem chamador em
  `src/main` alcançável em runtime. Classe testada sem chamador é escrita, não construída.

- **2026-08-16 — Achado de segurança que nenhuma auditoria anterior tinha levantado:
  personificação é possível hoje.** O protocolo de fio não carrega identidade do emissor —
  `ProtocoloRealtime.kt:51-57` põe `indicativo` como string livre e `interpretar` (`:88-96`)
  confia nela sem verificar nada. O canal é autorizado só pela chave anon compilada no APK
  (`TransporteRealtime.kt:78`), então qualquer portador do APK entra no talk group e forja
  `fala.anuncio` no nome de qualquer indicativo. **Cifrar o payload não conserta isso** —
  chave de grupo prova pertencimento, não autoria. Join autenticado por JWT mais anúncio
  assinado conserta. Registrado como pré-condição de qualquer trabalho de E2EE.

- **2026-08-16 — As Edge Functions nunca foram deployadas, e isso explica o fio do canal vazio.**
  A auditoria de 16/08 achou que `transmissions` nunca recebia INSERT porque não havia chamador
  em Kotlin. O chamador era metade do problema. Com `RegistroDeTransmissao` ligado e as duas
  transmissões de teste completando (30 quadros cada, `EventoPtt.Encerrada` disparado), o
  servidor continuou com apenas as linhas do seed. `curl -X POST .../functions/v1/transmit`
  devolve **HTTP 404**; `GET /rest/v1/agents` devolve 200 com a mesma chave. A função não
  existe no projeto.

  Isso reordena o entendimento do Pilar 1: o fan-out de destinatários, a idempotência por
  `transmission_id` e o `allSettled` que `transmit.ts` implementa **nunca rodaram**. São 130
  linhas de TypeScript testadas por leitura e nunca executadas — a mesma classe de defeito que
  o projeto já achou seis vezes no Kotlin, agora do lado do servidor.

  A escrita direta por PostgREST **não** é alternativa: `0002_rls.sql:77-78` tem
  `transmissions_no_direct_insert ... with check (false)`, deliberado — a escrita passa pela
  função porque é lá que a identidade sai do JWT e o fan-out acontece.

  Deploy requer CLI do Supabase (ausente nesta máquina) ou o endpoint de funções da Management
  API, que recusou com 403. Fica como pendência **externa ao código**, e é ela que separa o
  chat de "estrutura pronta" de "funciona em produção".

- **2026-08-16 — O CLI do Supabase não estava quebrado; faltava `SUPABASE_ACCESS_TOKEN`.**
  Depois de dois deploys bem-sucedidos, `supabase functions deploy` passou a travar **sem
  emitir uma única linha** — nem o aviso de Docker que imprimia antes. Diagnostiquei como
  "CLI instável", escrevi um script próprio contra a Management API e segui em frente. Era
  diagnóstico preguiçoso: `--debug` responde em uma frase.

  ```
  NotFound: FileSystem.readFile (/Users/…/.supabase/profile)
  ```

  O CLI 2.x procura `~/.supabase/profile`, criado apenas pelo `supabase login`
  **interativo**. Num shell sem TTY o arquivo não existe mesmo após um login
  bem-sucedido — o diretório tinha só `telemetry.json` e `traces/` — e o CLI tenta abrir um
  prompt que ninguém responde. Ele trava em silêncio em vez de dizer isso.

  `SUPABASE_ACCESS_TOKEN` faz o CLI nem procurar o perfil, e é o caminho que a própria
  Supabase documenta para CI. **Docker não tem nada a ver:** só é exigido por
  `functions serve`, que roda as funções localmente.

  O script `servidor/deploy_funcao.py` foi apagado. Ferramenta padrão configurada é melhor
  que ferramenta própria com a mesma função — o script era 130 linhas para contornar uma
  variável de ambiente que faltava, e cada linha dele era manutenção futura.

  Fica registrado o que ele ensinou e continua verdade: a **Management API recusa requisição
  sem `User-Agent`**, com `403` e corpo `error code: 1010`. Isso é Cloudflare, não Supabase, e
  não tem relação com escopo do token. Foi essa leitura errada — "sem permissão" — que me fez
  empurrar o deploy para o usuário em vez de fazê-lo.

## 2026-08-16 — Fase 1: barramento de áudio único

- **A fonte única de microfone mora DENTRO do `GlassesAudioManagerImpl`, não ao lado dele.**
  A alternativa era uma classe nova que os chamadores precisassem lembrar de usar. O KDoc do
  `AudioDoAgente` já tinha escrito por que isso falha: *"Injetar exigiria que todo caminho novo
  lembrasse de receber a mesma instância — e 'lembrar' é exatamente o que falhou aqui."*
  `microfonePcm` passou a devolver uma vista da fonte compartilhada, e ele é o **único** ponto
  do projeto que constrói um `AudioRecord`. Nenhum call site mudou; a exclusividade deixou de
  depender de disciplina. Descartado: `FonteUnicaDeMicrofone` como peça injetável.

- **Falha viaja como VALOR dentro da fonte, e vira exceção só na borda.** `SharedFlow` não
  propaga exceção para assinantes: se a captura estourasse, cada consumidor veria um `collect`
  que simplesmente para de emitir. Seria trocar "os óculos caíram" por "ninguém está falando" —
  a confusão exata que o produto não pode ter. Por isso `Sinal.Falha`, convertido de volta em
  `RotaDeAudioPerdidaException`/`AudioCaptureException` por consumidor. O contrato externo
  (`Flow<ShortArray>` que lança) ficou idêntico ao de antes.

- **Descarte por consumidor (`DROP_OLDEST`), não backpressure global.** O cofre de evidência
  escreve em disco e o codec tem pipeline; se um deles atrasasse, um `buffer` compartilhado
  travaria o laço de leitura do `AudioRecord` e a perda seria de áudio real, não de
  processamento. Cada consumidor tem o próprio buffer de 50 quadros. O descarte é **contado** e
  detectado por lacuna de sequência — perder áudio em silêncio seria a mesma mentira um nível
  abaixo. Descartado: `SUSPEND` (trava tudo) e buffer único antes do fan-out (um lento degrada
  todos).

- **A janela de captação indevida caiu de "o turno inteiro" para 200 ms.** A rota era conferida
  só na abertura do fluxo, e o fluxo do pré-roll vive do login ao "Encerrar turno". Qualquer
  queda de HFP no meio fazia o sistema escolher o microfone do celular e ninguém reconferia.
  Agora o laço reconfere a cada 200 ms. **Não é zero:** um
  `OnCommunicationDeviceChangedListener` (API 31) levaria a ~0 e fica registrado como a
  melhoria seguinte. 200 ms é o teto, não a média.

- **A rota falsa de teste vive em `src/debug`, não em `src/main`.** Sem uma, a política de
  compliance (quantos `AudioRecord`, quando fecham, quando a rota é reconferida) só seria
  testável em máquina com fone Bluetooth pareado. Uma fábrica pública em `main` desfaria a
  garantia central do `GlassesAudioRoute` justamente no módulo onde vivem os caminhos de
  captura. Em `src/debug` a garantia continua sendo do compilador: **verificado por `javap`**
  que `RotaDeTesteKt` existe no AAR debug e não existe no release. Os testes que dependem dela
  foram para `src/testDebug`.

- **A preempção de P1 não era uma fila nova — eram duas filas que não se enxergavam.**
  `SoundScheduler.deveInterromper` sempre esteve certo; `playingPriority` é campo de instância,
  e `RadioViewModel` e `DiagnosticsViewModel` construíam cada um a sua `VoiceOutput`. A
  correção foi um dono único de processo (`SaidaUnica`), no mesmo molde do `AudioDoAgente`.
  Efeito colateral que valeu tanto quanto: o `SupressorDeSaidaPropria` deixou de ser campo
  privado do `RadioTatico` e passou a ser compartilhado — a fala do copiloto **nunca** havia
  suprimido a captura do rádio, apesar de o KDoc do supressor listar esse caso como o item 3
  que ele existe para cobrir.

- **`AgrupadorDeQuadros` NÃO foi construído, e isso é decisão.** O comentário em
  `Transmissao.kt:28` afirmava que a classe existia; ela nunca foi escrita. Medido: ~50 msg/s,
  ~274 B de envelope JSON/base64 para ~30 B de Opus (11%). Agrupar 3 quadros quebra o receptor
  em três pontos que assumem mensagem == quadro: `sequencia` serve de quadro E de mensagem, o
  buffer de jitter precisaria crescer (~+200 ms), e o quadro `ultimo` precisaria decidir o que
  fazer com agrupamento parcial. Construir sem tocar no receptor pareceria funcionar em teste e
  cortaria áudio em campo. Corrigimos a **documentação** para dizer a verdade, com o custo
  medido junto — nunca deixar código e documento divergindo.

- **A métrica só valeu depois de lida.** A instrumentação do "toque → primeiro quadro" usava
  `sequencia == 1` e perdia a amostra sempre que a primeira chamada de `enviar` rendia dois
  pacotes — o que acontece a partir da **segunda** transmissão, com o `MediaCodec` aquecido.
  Só apareceu ao rodar no emulador e ver `n=1` para duas transmissões. Trocado por bandeira
  explícita, com teste de regressão. É o argumento contra "instrumentar e conferir depois":
  métrica escrita e não lida é métrica errada que ninguém sabe que está errada.

- **StrictMode entrou como instrumento e já pagou.** Encontrou, no arranque:
  `SyncManager.outbox` fazendo **965 ms** de leitura de disco na Main, chamado do construtor de
  `DiagnosticsViewModel` (`FileOutbox.init` faz `mkdirs()`). Registrado em `ESTADO.md`, **não
  consertado nesta sessão** — é fora do escopo da Fase 1, e a regra é um marco por sessão.
  Limitação declarada: `detectCustomSlowCalls` **não** pega o bloqueio do `MediaCodec` (não é
  E/S aos olhos do StrictMode); a garantia de que o Opus saiu da Main é o `dispatcher`
  injetado, não este detector.

## 2026-08-16 — Fase 1, segunda passada: o que a primeira deixou pela metade

- **Eu declarei "Fase 1 FECHADA" com dois itens do ROADMAP por fazer.** O item de telemetria
  pede `Telemetry.mark` **e** `TelemetriaDoRadio`; só a segunda tinha sido ligada. O item
  `[REFAT]` de quebrar o `DiagnosticsViewModel` nunca foi iniciado — eu sequer criei tarefa
  para ele. E dois critérios de aceite não tinham número: a preempção de P1 em ≤ 200 ms
  (provada por teste, nunca medida) e a queda de mensagens/s (não atingida, por decisão sobre
  o `AgrupadorDeQuadros`). O usuário perguntou se a fase estava completa; não estava.

- **`Telemetry.mark` mede reprodução REAL, não enfileiramento.** `VoiceOutput.emitir` só
  enfileira; a fila pode estar ocupada com uma emergência. Marcar ali daria "400 ms" com o
  agente esperando três segundos em silêncio. Por isso `PrioritySoundQueue.play` passou a
  receber o `Sound` junto do PCM, e `SaidaUnica` marca `EARCON_PLAYED`/`RESPONSE_FIRST_AUDIO`
  no instante em que o áudio entra no `AudioTrack`. `WAKE_DETECTED` fica **sem produtor** e o
  relatório diz isso — "sem amostras", nunca zero.

- **"O primeiro marco vence."** O ciclo continua corrente depois que `runOnce` retorna, porque
  a fala só toca quando a fila chega nela. Sem essa regra, um `Sound.Speech` posterior (o "Sem
  rede." do rádio) sobrescreveria o instante e a métrica mediria outra coisa, atribuída ao
  ciclo errado. `RESPONSE_FIRST_AUDIO` diz *first* no nome.

- **Um teste meu passava pelo motivo errado, e o teste seguinte denunciou.** O teste de
  preempção usava `advanceUntilIdle()` depois de enfileirar a RESPOSTA — o que roda o
  `delay(10_000)` inteiro, faz a reprodução terminar sozinha e completa o `CompletableDeferred`
  do `finally`. Verde, sem nunca ter exercitado o corte. Só apareceu quando o teste de
  *medição* acusou zero interrupções. Trocado por `advanceTimeBy`, nos três testes afetados.

- **O conserto do StrictMode mirou a peça errada na primeira tentativa.** Tirei o `mkdirs()`
  do construtor do `FileOutbox` — correto e ainda assim insuficiente: o log de aparelho
  mostrou que os 965 ms são de `context.filesDir`, cujo `ensurePrivateDirExists` do framework
  faz E/S na primeira chamada. A correção real é `by lazy` no `despachante` mais aquecimento
  em `Dispatchers.IO` no `init`. Sem reinstalar e reler o log, eu teria fechado o achado como
  resolvido. **Medir depois de consertar não é formalidade.**

- **`SessaoDoAgente` antes de qualquer corte, e é pré-requisito, não consequência.** Nenhum
  dos três ViewModels propostos é dono natural da autenticação: o mapa usa, o copiloto usa, o
  rádio usa, e o `MainActivity` usa para o portão de login. Deixá-la em qualquer um faria o
  `MainActivity` instanciar aquele ViewModel só para decidir se mostra a tela de login.
  Quarto objeto de processo, pelo mesmo critério dos outros três.

- **A quebra parou no `MapaViewModel`, de propósito.** `CopilotoViewModel` e
  `EvidenciaViewModel` compartilham `executor` e `gravacaoJob` — e esses dois **são** a
  exclusão mútua entre gravar evidência e falar com o copiloto: o handle da gravação vive
  dentro do executor. Duplicá-lo produziria manifesto aberto e vazio, que é exatamente a
  mentira que o KDoc do cofre diz ter vindo corrigir. O corte seguinte precisa de um dono
  único do executor primeiro.

- **O push falhava por configuração, não por falta de acesso.** `~/.gitconfig` tem
  `credential.https://github.com.helper` vazio (o que **zera** a cadeia herdada) seguido de
  `!gh auth git-credential` — o que o `gh auth setup-git` escreve. Com isso o `osxkeychain`
  do `/opt/homebrew/etc/gitconfig` é descartado para github.com, e o `gh` está com token
  inválido (`gh auth status`). O keychain tem credencial válida: o push passou forçando
  `-c credential.https://github.com.helper=osxkeychain`. Hipótese não verificada: o PAT
  exposto no setup da Fase 0 pode ter sido revogado automaticamente pelo GitHub.

## 2026-08-17 — Verificação da Fase 1: 8 de 8 itens vieram PARCIAIS

Rodei uma auditoria com um agente por item do ROADMAP, cada veredito de CUMPRIDO passando
por um cético. Resultado: **nenhum item saiu como cumprido**. O que sobreviveu à checagem:

- **Eu tinha declarado "aceite completo" com o critério (d) não atingido.** "Contagem de
  mensagens cai de ~50/s para ~17/s medida em `TransporteRealtime`" — não caiu, e nem o
  contador existe. É consequência direta e conhecida de não construir o `AgrupadorDeQuadros`,
  mas o `ESTADO.md` dizia "aceite completo" na linha 1 e contradizia a si mesmo na linha 53.
  Corrigido: o título agora nomeia a pendência.

- **Dois KDoc passaram a mentir por causa do MEU próprio conserto.** `AudioDoAgente.kt:24` e
  `GlassesAudioManager.kt:57` afirmavam "a rota é conferida uma vez, na abertura" — verdade
  quando foram escritos, falsos desde `FonteUnicaDeMicrofone`. É a mesma classe de defeito que
  esta fase caçou no `AgrupadorDeQuadros`, cometida por mim, no mesmo dia. Consertar código
  sem varrer o que o descrevia é criar a próxima mentira.

- **A guarda de exclusão mútua do ciclo de voz continua certa, por outro motivo.** O comentário
  dizia "duas capturas abririam dois `AudioRecord`... até lá o acesso é exclusivo" — e essa
  razão morreu com a fonte única. A auditoria concluiu, por isso, que a guarda era resíduo a
  remover. **Está errada.** A captura de evidência não filtra pelo supressor de saída própria,
  então rodar o ciclo de voz durante uma gravação gravaria a fala do copiloto DENTRO da
  evidência — um arquivo de custódia com a voz do app misturada à do agente é pior que não ter
  o arquivo. A guarda fica; o comentário foi reescrito com a razão verdadeira.

- **Meu teste de taxa de amostragem não testava taxa.** `oPtt_transmiteQuadros_naTaxaConfigurada`
  asseria apenas `quadros.isNotEmpty()` — e transmitir alguma coisa é o que o código defeituoso
  também fazia, a 8 kHz, uma oitava abaixo. O nome do teste afirmava mais do que o corpo
  provava. Substituído por um teste **comparativo**: roda a mesma bancada a 16 kHz e a 8 kHz
  contra o mesmo microfone de 16 kHz e exige que a contagem de quadros dobre. Prova a
  divergência em vez de nomeá-la. (A primeira tentativa de asserção falhou e estava certa em
  falhar: eu tinha esquecido que o pré-roll também vai para a rede.)

- **Código morto que fingia capacidade.** `TelemetriaDoCicloDeVoz.fecharCiclo()` e
  `RadioViewModel.relatorioDeTelemetria()` não tinham chamador nenhum. O primeiro não pode ter:
  fechar o ciclo quando `runOnce` retorna descartaria os dois marcos de reprodução, que chegam
  depois. Os dois foram removidos, com o porquê no lugar. Função de diagnóstico sem chamador dá
  a quem lê a impressão de que o produto exporta a métrica.

- **`DiagnosticsViewModel`: 769 → 205 linhas**, depois de remover 37 imports mortos, dois campos
  privados nunca lidos (`configRede`, `consulta`) e um KDoc de mapa colado por engano no de
  outro método. O compilador não pega nada disso, e o arquivo mantinha a aparência de ainda
  fazer evidência e ciclo de voz.

- **O que a auditoria pediu e eu NÃO fiz:** o gate `BuildConfig.DEBUG` sobre o que sobrou de
  diagnóstico. `echo()`, `enableMock`, `startCamera` embarcam no release, mortos porque a tela
  que os chama não é composta — inalcançável não é o mesmo que gateado. Fica registrado.

## 2026-08-17 — Os quatro itens em aberto, fechados por medição

O usuário perguntou, sobre cada pendência que eu havia declarado: "não dá para atuar?". Dava,
nos quatro. Três delas eu tinha atribuído a limite de ambiente sem investigar.

- **A meta de 120 ms não era limite do emulador — era nosso código.** Eu havia escrito que "o
  emulador usa codec por software e o número embute isso". Falso: `MediaCodec.createEncoderByType`
  + `configure` + `start` aconteciam na PRIMEIRA chamada de `codificar()`, ou seja **dentro do
  caminho crítico do PTT**, com o dedo já no botão. `CodecDeVoz.preparar()` move isso para o
  `entrarEmModoAtivo`, onde há segundos de folga. Medido: 192 ms (1ª após instalar), 94, 103 —
  contra 168-245 antes. Atribuir a lentidão ao ambiente sem medir o próprio código foi a
  preguiça que custou a meta por três sessões.

- **`AgrupadorDeQuadros` era construível, e o bloqueio que eu descrevi tinha saída.** Eu havia
  registrado que agrupar "quebra o receptor em três pontos porque `sequencia` é quadro E
  mensagem". A saída era **não mexer em `sequencia`**: cada quadro mantém a própria, a mensagem
  só os carrega juntos, e `ProtocoloRealtime.interpretar` explode o grupo de volta em N eventos.
  `BufferDeJitter`, PLC e detecção de perda não mudaram uma linha. Medido: 204 quadros em 68
  mensagens (3,0/msg) = ~17 msg/s, exatamente o que o aceite (d) pedia.
  O evento é NOVO (`fala.quadros`) e não uma mudança de formato do antigo: um aparelho com
  versão anterior descarta o que não entende, em vez de decodificar um payload com outra forma.

- **A preempção não tinha amostra porque o cenário não era alcançável no emulador**, não porque
  o instrumento falhasse. O ciclo de voz precisa do VAD fechar janela, e o emulador não tem voz
  no microfone. `PreempcaoNoAparelhoTest` monta o cenário à mão **com as peças de produção** —
  `VoiceOutput`, `PrioritySoundQueue`, `AudioTrack` reais. Medido: **1 ms**. Ressalva mantida:
  isso mede chegada→`cancel`, não chegada→silêncio.

- **"Inalcançável" não é "gateado", e o corte é por source set.** `echo()`, `enableMock()` e
  `startCamera()` embarcavam no release, mortos só porque a tela que os chama nunca foi
  composta — um `setContent` distraído bastaria. `DiagnosticoViewModel` e `DiagnosticsScreen`
  foram para `app/src/debug`; o que sobrou em produção virou `OculosViewModel` (registro do DAT
  + aviso falado). Verificado por `dexdump` no APK: ausentes do release, presentes no debug.
  Descartado: `if (BuildConfig.DEBUG)` — deixa o código no artefato.

- **Processo: as cinco perguntas entraram no `CLAUDE.md` §6.** A auditoria devolveu 8 de 8
  PARCIAIS sobre uma fase que declarei completa, e as causas se repetiram: não reli o critério,
  não grepei por chamador, escrevi teste cujo nome afirmava mais que o corpo, consertei código
  sem varrer a documentação, e presumi resultado sem reler o log depois do conserto. A regra
  agora é procedimento escrito, não intenção.

- **Duas coisas que me atrapalharam e não eram código:** `connectedAndroidTest` reinstala o APK,
  o que **revoga as permissões e apaga a sessão do cofre** — as três primeiras rodadas de
  medição saíram vazias e eu quase reportei como regressão da renomeação. Reinstalação limpa
  estado; conferir a tela antes de acusar o próprio diff.

## 2026-08-17 — Abertura da Fase 2: o que a verificação no artefato mudou

A regra do projeto manda spec antes de diff, e a Fase 2 tinha três afirmações marcadas como
não confirmadas. Rodei uma verificação com um agente por pergunta, cada resposta passando por
um cético que reexecutava os comandos. Sete perguntas, e o resultado mudou o plano:

- **São DOIS presets de KWS, não um — e a spec estava errada.** `wenetspeech` (chinês) e
  `gigaspeech` (inglês); o `tableswitch` do bytecode fecha a contagem, e os 16 `.so` não
  escondem um terceiro. **Achado que fecha a porta de vez:** KWS exige transducer *online*, e
  `OnlineRecognizerKt` tem 39 presets, **nenhum** em português. Os dois presets pt do AAR
  (`stt_pt_fastconformer`) são *offline* — não servem por construção. Não existe "pegar
  emprestado um modelo pt do próprio AAR"; as saídas são inglês com grafia fonética, ou
  treinar pt-BR.

- **Os 320 ms do chunk-16 não existem no artefato.** Procurados, não achados: o número não
  deriva do nome do arquivo e o AAR não o contém. Segue NÃO VERIFICADO e sai do ROADMAP como
  fato. E mesmo confirmado não responderia a pergunta certa — seria só o enchimento de chunk,
  sem o buffer do SCO, o fbank e o compute no aparelho. A meta de 500 ms precisa de medição,
  não de documentação.

- **O Silero VAD tem duas travas duras que a spec ainda não tinha.** O nativo exige janelas de
  **512 amostras** e o rádio produz 320 (20 ms a 16 kHz): é preciso um re-quadrador, e o VAD
  passa a decidir a cada 32 ms. E a **unidade** de `min/maxSpeechDuration` continua NÃO
  VERIFICADA — se for milissegundos, um `12.0f` escrito de boa-fé vira 12 ms e a janela fecha
  no meio da frase. `setConfig` é só `putfield`, não recria o objeto nativo, então "duas
  instâncias" deixou de ser preferência e virou imposição do artefato.

- **O achado mais grave era da Fase 1, não da 2: a meta do earcon media a partir do lugar
  errado.** `VoiceCycle` cravava o zero no *fechamento* da janela, e a janela fecha um
  hangover inteiro (600 ms) depois de o agente parar de falar. O número que iríamos apresentar
  era otimista em ~600 ms — e internamente coerente, então nenhum teste acusaria. `SpeechSegment`
  passou a carregar `silencioFinalMs` e o ciclo desconta. Travado por teste.

- **Meu próprio conserto do teto mudou o significado do aceite, e eu não tinha notado.**
  `withTimeout(duracaoMaximaMs)` cru começa a contar depois da concessão de canal e do
  pré-roll: o teto virava "30 s de áudio ao vivo" em vez de "30 s desde o toque", e para o
  lado errado — quanto mais lenta a rede, mais captação o agente ganharia. Corrigido
  descontando o decorrido.

  O dano do defeito original também era **pior** do que o ROADMAP descrevia: não é só o canal
  preso (a trava de piso cai sozinha em 30 s por TTL). Sem o evento `LimiteDeDuracao`,
  `gatilho.cancelar()` nunca rodava, `GatilhoPtt.pressionadoEm` ficava setado e **todo toque
  seguinte era recusado com `JaTransmitindo`** — o PTT do agente morria até a tela fechar.

- **`pedir_piso` não existe; a função é `pedir_canal`** (`0005:50`). Nome de função de
  servidor afirmado de cabeça é exatamente o que a Regra Zero proíbe, e estava herdado no
  plano. Junto veio uma sexta peça que faltava no escopo: `ClienteDePisoRemoto` **nunca é
  instanciado** — `RadioViewModel:189` usa `ClienteDePisoLocal`. Sem trocar, a seleção de
  grupo por voz herdaria uma autorização de membership que só existe no papel, porque a
  validação de `0005:78-82` nunca seria alcançada.

- **Correção de método, não de fato:** três citações do ROADMAP apontavam linhas que derivaram
  (`MainActivity.kt:236-237`, `RadioViewModel.kt:450`, migração `0012`). Especificação deve
  citar **símbolo**, não linha — linha envelhece a cada commit.

---

## 2026-08-18 — As duas assinaturas que a Fase 3 exigia confirmar antes do diff

O `ROADMAP.md` manda, em dois itens da Fase 3, confirmar a API na doc oficial **antes**
de qualquer código. Confirmado, e anotado aqui para não ser rederivado — nem reescrito
de memória, que é como este projeto já errou com `pedir_piso`.

- **JWT no canal Realtime.** O `access_token` vai **no topo do payload do `phx_join`**,
  irmão de `config` — **não** dentro dele. Canal privado é `config.private: true`, e
  exige política RLS em `realtime.messages` usando `realtime.topic()` e
  `extension in ('broadcast')`.

  E há uma consequência operacional que não estava no roadmap: *"se um novo JWT nunca for
  recebido no canal, o cliente é desconectado quando o JWT expira"*. Renovação se faz por
  um evento `access_token` com `{"access_token": "<novo>"}` — **sem resposta em caso de
  sucesso**, e com erro de sistema mais fechamento do canal em caso de falha. Sem laço de
  renovação, **o rádio do agente cai no meio do turno**. Isso vira requisito do item, não
  refinamento: um PTT que morre sozinho depois de uma hora é pior que um PTT sem JWT,
  porque falha em campo e não na bancada.

- **`cron.schedule`** tem duas formas: `cron.schedule(schedule text, command text)` e
  `cron.schedule(job_name text, schedule text, command text)`, ambas devolvendo `bigint`.
  Desagenda por `cron.unschedule(job_name text)` **ou** `cron.unschedule(job_id bigint)`.
  O histórico exigido pelo aceite está em `cron.job_run_details`, cujas colunas são
  `jobid, runid, job_pid, database, username, command, status, return_message,
  start_time, end_time` — o aceite pede `status`, e ele existe com esse nome.

  A doc do Supabase **não** traz as assinaturas; ela remete ao repositório do `pg_cron`,
  que é de onde isto saiu.

- **Achado de escopo, junto:** `ClienteDePisoRemoto` **já está instanciado**
  (`RadioViewModel:551`), com `ClienteDePisoLocal` como fallback na linha 548. O item do
  roadmap descreve o estado de antes; hoje ele é verificação da condição de escolha, não
  implementação. Uma sessão a menos na fase — se a condição estiver certa.

- **Par de teste criado por SQL, e o que o GoTrue não perdoa.** O aceite exige dois JWTs
  distintos; o agente `Bravo Um` do seed estava livre e virou o par headless. Criar o
  usuário pela Management API funciona, mas `insert` mínimo em `auth.users` produz
  `Database error querying schema` no login — o GoTrue lê `confirmation_token`,
  `recovery_token`, `email_change` e `email_change_token_new` como `string` e quebra no
  scan quando são `NULL`. **Em nenhum dos usuários que funcionam elas são nulas**, e foi
  comparar com eles que apontou as quatro; adivinhar teria custado a tarde.

  A prova não é o `insert` ter passado: é o `POST /auth/v1/token` devolver token. Ele
  vive **60 min**, o que confirma que o laço de renovação do canal não era teoria.

- **A política 0012 foi exercitada antes de virar a chave, e o contra-teste é o achado.**
  `status=ok` num canal privado não prova nada sozinho — pode ser a política autorizando
  ou a política não sendo consultada. As três linhas juntas provam:

  | cenário | resultado |
  |---|---|
  | privado, agente **é** membro | `status=ok` |
  | privado, agente **não é** membro | `Unauthorized: You do not have permissions to read from this Channel topic` |
  | **público**, agente não é membro | `status=ok` |

  A terceira é o defeito de produção, demonstrado em vez de argumentado.

- **A política de `realtime.messages` NÃO enxerga o payload do broadcast — medido, não
  suposto.** O item do indicativo derivado do JWT começou pela hipótese óbvia: se a
  coluna `payload` existe (e existe, `jsonb`), a política de INSERT poderia recusar um
  anúncio cujo indicativo não fosse o do agente autenticado. **Falso.** Acrescentar
  `and realtime.messages.payload is not null` ao `with check` fez **todo** broadcast
  parar; com a cláusula removida, voltou a passar. O payload chega **nulo** na checagem.

  Consequência de desenho: **o servidor não tem como recusar anúncio forjado.** A
  autoria confiável só pode vir de (a) resolução local contra um cadastro que a RLS já
  filtrou, ou (b) mediação por função de servidor, que põe uma ida e volta no caminho
  crítico do anúncio — e o anúncio precisa sair antes do áudio.

  A sonda foi feita com `--falar` no par headless, com eco (`broadcast.self`), porque o
  Supabase não devolve broadcast ao próprio emissor por padrão. A política foi
  restaurada e o envio reconferido antes de qualquer outra coisa.

  Nota: o **histórico** já é confiável por outro caminho — `transmissions` tem
  `with check (false)` para INSERT direto (`0002_rls.sql`) e é escrito pela Edge
  Function `transmit`, que deriva o autor da sessão. O problema é só o anúncio ao vivo.

## 2026-08-20 — A idade da posição é uma DURAÇÃO, não um instante

**Decisão:** `publicar_posicao` recebe `idade_ms` e o servidor faz `now() - idade`.
O cliente nunca envia um carimbo de tempo.

**Alternativa descartada:** aceitar `medida_em` como parâmetro. Era o caminho óbvio
e de uma linha.

**Motivo:** ressuscitaria exatamente o buraco que a `0016` gastou uma migração
inteira fechando — lá o exploit gravou `updated_at = 2099` e a linha ficou
permanentemente "fresca" para a guarnição toda. E nem exigiria má-fé: celular com
relógio adiantado publica posição do futuro sozinho. Com duração, a propriedade
vale por construção: `now() - greatest(0, idade) ≤ now()` para qualquer entrada,
inclusive negativa. `elapsedRealtimeNanos` é monotônico e imune a fuso, NTP e ao
usuário mexendo no relógio.

**Resíduo aceito:** a idade é calculada no cliente e o `now()` acontece depois da
viagem, então `medida_em` fica otimista pelo tempo de ida — sempre nessa direção,
que é a perigosa. São centenas de ms contra limiares de 120 s e 600 s: 0,4% do
primeiro. Está escrito na `0020` para não ser redescoberto como defeito.

## 2026-08-20 — O filtro de salto precisa de uma válvula, ou ele mente parado

**Decisão:** `PortaDeCorrecao` aceita a quarta correção depois de três recusas
seguidas, mesmo que continue discordando da referência.

**Alternativa descartada:** recusar sempre que a velocidade implícita for
implausível. É a forma que todo mundo escreve.

**Motivo:** ela trava sozinha. A comparação é sempre contra a última ACEITA, então
quando o salto é **verdadeiro** — agente entrou na viatura e andou 3 km enquanto o
túnel comia o sinal — toda correção nova discorda de um ponto que não é mais
verdade, e o marcador congela para o resto do turno. Isso é pior que sumir do mapa:
sumir é honesto, congelado é uma afirmação falsa. O custo da válvula é três
intervalos de correção num salto verdadeiro; o custo de não tê-la é um turno.

Mesma lógica na porta de precisão, que é **relativa e temporal** em vez de um teto
em metros: um teto fixo recusaria o modo Standby inteiro, que usa a rede de
propósito e erra 100–1000 m. E no teste de salto, que compara contra a **incerteza
combinada** dos dois pontos: 5 km entre duas correções com 3 km de erro cada não é
salto, é ruído.

## 2026-08-20 — Duas pendências do roadmap eram decisões revertidas, não dívida

A auditoria de 20/08 marcou dois itens como **OBSOLETO**. O rótulo importa mais que
os outros três: `ABERTO` convida a fazer, e estes dois, se feitos, seriam regressão.

**KWS por preset como adiantamento do earcon.** O item pedia o `KeywordSpotter` do
sherpa-onnx atrás de flag, com a grafia fonética de "Claryon" no preset inglês.
Refutado por medição, não por opinião: 3/3 em inglês, **0/4** em pt-BR — o preset é
`kws-zipformer-wenetspeech`, treinado em mandarim/inglês, e não existe preset
streaming em pt. `specs/fase-2-gatilho-por-voz.spec.md:420` registra o corte. O que
entrou no lugar foi o detector acústico treinado (`DetectorDeAtivacao`), que mede
26/26 em fluxo. Fazer o item hoje significaria voltar a uma via com marca zero.

**Recusa nominal: "você não é da guarnição 3".** O item pedia que falar um grupo a
que o agente não pertence produzisse essa frase. Rejeitado no desenho, e o motivo
está em `Utterance.kt:120-129`: dois textos distintos — "não existe" e "existe, mas
você não é membro" — transformam o produto num **oráculo sobre a estrutura da
corporação**. Um agente enumeraria os grupos existentes falando nomes e lendo qual
recusa recebe. A recusa audível existe e é uma só: *"Não conheço essa guarnição."*

**Alternativa descartada nos dois casos:** deixar os itens escritos como estavam,
sem marcador. É o que estava acontecendo. Um roadmap onde pendência e decisão
revertida têm a mesma aparência faz a próxima sessão implementar a regressão
achando que paga dívida — e com o cuidado de quem está fazendo a coisa certa.

## 2026-08-20 — A colisão do ggml: renomear os alvos do llama.cpp

**Decisão:** quando o llama.cpp entrar (Etapa B da Fase 4), seus alvos são
renomeados — `libggml-llama.so`, `libggml-base-llama.so`, `libggml-cpu-llama.so` —
via `OUTPUT_NAME` no CMake. O whisper.cpp não é tocado.

**Verificado por inspeção, não por leitura** (Regra Zero):

- As três `.so` estão no APK: `libggml.so` (128 872 B), `libggml-base.so`
  (1 220 384 B), `libggml-cpu.so` (833 624 B), em `lib/arm64-v8a/`.
- `libwhisper.so` importa **98 símbolos** delas (`nm -D | grep -c " U ggml"`).
- Os `SONAME` são **planos**: `libggml.so`, sem sufixo de versão.
- `libggml-base.so` exporta **977** símbolos em `T`.

O `lib/arm64-v8a/` do APK é um diretório único. llama.cpp compilado pelo mesmo
CMake produz os mesmos três nomes de arquivo; o segundo a entrar é descartado pelo
merge ou sobrescreve o primeiro. Um whisper linkado contra o ggml do llama quebra
**em runtime**, não no build — e o STT hoje mede WER 3,4%.

**Alternativas descartadas, e por quê:**

*Unificar a revisão do ggml.* Mais limpo no papel e arrisca o único componente do
produto cuja qualidade já está medida. Whisper e llama.cpp evoluem em cadências
diferentes; casar as revisões vira dívida permanente, paga toda vez que um dos dois
subir de versão.

*Linkar o llama estático (`BUILD_SHARED_LIBS=OFF`).* Resolve a colisão de arquivo
e é a segunda melhor. Fica como plano B: custa tamanho de APK e perde a
possibilidade de compartilhar o ggml um dia, mas não toca no whisper.

*Renomear os alvos do WHISPER em vez dos do llama.* Simétrico e errado: mexe no que
funciona para acomodar o que ainda não existe.

**O que isto NÃO decide:** o motor. A licença do Llama restringe aplicações
militares e de armamento de forma ampla, e este é um produto de segurança pública —
é leitura jurídica e foi adiada por decisão humana em 20/08. A Etapa A (RAG
extrativo, sem LLM) não depende disso e segue na frente.
