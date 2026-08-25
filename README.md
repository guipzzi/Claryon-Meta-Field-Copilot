# Claryon Field

Copiloto de voz **hands-free** para agentes de segurança pública, sobre óculos
**Ray-Ban Meta (sem display)** e o **Meta Wearables Device Access Toolkit (DAT) 0.9.0**.
O agente fala, o app entende **localmente**, age (abre o canal da guarnição, lê a placa
pela câmera, consulta a norma, grava evidência) e responde por **áudio no ouvido**.

App companion **Android/Kotlin**: todo o processamento roda no smartphone pareado.
Os óculos são **sensores e alto-falantes** — nenhuma linha de código nossa roda neles.

> Programa **AI Glasses Brasil 2026** (CEIA/Meta) · Trilha **Produtividade** ·
> Hackathon presencial em **18/09/2026**.

**Equipe Claryon** — coautores [@guipzzi](https://github.com/guipzzi) e
[@Lemos021](https://github.com/Lemos021), ambos em `Co-authored-by:` de todos os
commits. Ver [`AUTHORS.md`](AUTHORS.md).

---

## Índice

- [1. O produto em uma página](#1-o-produto-em-uma-página)
- [2. As restrições que definem todo o resto](#2-as-restrições-que-definem-todo-o-resto)
- [3. A régua deste repositório: o que significa "construído"](#3-a-régua-deste-repositório-o-que-significa-construído)
- [4. Setup reproduzível do zero](#4-setup-reproduzível-do-zero)
- [5. Arquitetura](#5-arquitetura)
- [6. As capacidades, uma a uma](#6-as-capacidades-uma-a-uma)
  - [6.1 Rota de áudio HFP/SCO](#61-rota-de-áudio-hfpsco)
  - [6.2 Palavra de ativação](#62-palavra-de-ativação-claryon)
  - [6.3 Transcrição on-device (STT)](#63-transcrição-on-device-stt)
  - [6.4 Roteamento de intenção determinístico](#64-roteamento-de-intenção-determinístico)
  - [6.5 TTS e design de áudio](#65-tts-e-design-de-áudio)
  - [6.6 Leitura de placa pela câmera](#66-leitura-de-placa-pela-câmera-dat--ml-kit)
  - [6.7 Rádio tático PTT com transcrição na origem](#67-rádio-tático-ptt-com-transcrição-na-origem)
  - [6.8 Geolocalização e mapa](#68-geolocalização-e-mapa)
  - [6.9 Cofre de evidência e cadeia de custódia](#69-cofre-de-evidência-e-cadeia-de-custódia)
  - [6.10 Consulta externa (Overpass/OSM)](#610-consulta-externa-overpassosm)
  - [6.11 Etapa A — norma on-device](#611-etapa-a--norma-on-device-rag-extrativo)
  - [6.12 Etapa B — o LLM local, embarcado e desligado](#612-etapa-b--o-llm-local-embarcado-e-desligado)
- [7. O que existe no código e não roda](#7-o-que-existe-no-código-e-não-roda)
- [8. O que não foi medido](#8-o-que-não-foi-medido)
- [9. Licenças e procedência dos modelos](#9-licenças-e-procedência-dos-modelos)
- [10. Documentos do projeto](#10-documentos-do-projeto)

---

## 1. O produto em uma página

Três pilares, e cada um tem uma regra de negócio que o define:

| Pilar | O que é | A regra que o define |
|---|---|---|
| **P1 · Rede de comunicação** | PTT/walkie-talkie entre operadores, com transcrição | **A transcrição ocorre na ORIGEM**, antes de o áudio trafegar. Todos os receptores exibem exatamente o mesmo texto, e o servidor nunca precisa transcrever |
| **P2 · Geolocalização** | Posição da guarnição, atualizada e persistida | O servidor devolve **grandezas** (distância, rumo), **nunca a coordenada** de terceiro |
| **P3 · IA on-device** | Copiloto especialista em segurança pública | **Local.** Palavra de ativação **"Claryon"**. STT, TTS, OCR, ativação e busca na norma são do aparelho. Existe **um** degrau de rede, e ele não é IA: consulta geoespacial estruturada, depois que o local não respondeu (§6.10) |

**Usuário-alvo.** Policial militar em blitz de 4 a 6 horas, abordando de luva, com as
mãos ocupadas, em rua barulhenta. O levantamento de campo (entrevista autorizada com
PM da PMERJ) está em [`docs/IMPACTO_E_VALIDACAO_DE_CAMPO.md`](docs/IMPACTO_E_VALIDACAO_DE_CAMPO.md).

---

## 2. As restrições que definem todo o resto

Cinco fatos do hardware. Nenhuma decisão deste projeto faz sentido sem eles.

1. **Sem display.** A única saída rica é áudio. A tela do celular serve para
   configuração, diagnóstico e demonstração.
2. **O código não roda nos óculos.** Óculos = sensores + alto-falantes; a lógica vive
   no celular.
3. **Câmera pelo DAT, áudio pelo Bluetooth.** **Não existe `session.audioStream`** —
   verificado por `javap` no AAR `mwdat-camera-0.9.0`: `Stream` expõe `videoStream`,
   `errorStream`, `state`, `start`, `stop`, `capturePhoto`, e nada de áudio. Microfone e
   alto-falante são **HFP/SCO**, via `AudioManager`/`AudioRecord`/`AudioTrack`.
4. **"Hey Meta" não é acessível.** A palavra de ativação é nossa e roda no celular sobre
   o áudio HFP.
5. **Bluetooth Classic é o gargalo.** Vídeo modesto; o elo de voz é mono, banda estreita
   (CVSD, ~8 kHz).

Consequência de produto, intencional: o beamforming dos óculos isola a voz de quem os
veste. **Transcrevemos o agente, não o interlocutor.**

### Proibições absolutas

- ❌ **Reconhecimento facial, embeddings faciais ou base biométrica** — em versão
  nenhuma, sob flag nenhuma.
- ❌ **Transcrever, classificar ou indexar a fala de terceiros.** O pré-roll do PTT vive
  em RAM e nunca é persistido.
- ❌ **Enviar áudio, frame ou transcrição LITERAL para serviço externo.** Continua
  absoluto para os três. **Revogado em parte em 22/08, por decisão humana registrada:**
  consulta **textual derivada** — reconstruída a partir da intenção, nunca da fala — é
  permitida sob as condições de
  [`specs/consulta-externa.spec.md`](specs/consulta-externa.spec.md), e só sob elas.
  Nada de dado de terceiro, posição de par ou identificador de agente sai daqui.
- ❌ **Função de servidor que receba a identidade de quem pergunta como parâmetro.** Com
  ela, distâncias trilateram a posição absoluta de qualquer par. O solicitante vem do
  JWT; o parâmetro só existe dentro do schema `private`.
- ❌ **LLM escolhendo ação.** Ver §6.4 e §6.12.
- ❌ **Credencial versionada.** Evidência fora de `EncryptedFile` + Android Keystore.
- ❌ **Assinatura de API escrita de memória** (DAT, MapLibre, sherpa-onnx, Android).
  Confirmar por `javap` no artefato ou na doc oficial. Não confirmou? Para e pergunta.

A análise de risco voluntária sob o art. 38 da LGPD — risco, medida adotada, risco
residual assumido — está em
[`docs/RELATORIO_DE_IMPACTO_LGPD.md`](docs/RELATORIO_DE_IMPACTO_LGPD.md).

---

## 3. A régua deste repositório: o que significa "construído"

Este projeto usa uma definição estrita, e ela vale para cada afirmação deste documento:

> **Construído = tem chamador em `src/main` alcançável em runtime, sem passar por tela
> de diagnóstico. Classe testada sem chamador é *escrita*, não construída.**

O motivo é histórico e caro: **nove vezes** algo foi escrito, testado, coberto por suíte
verde — e nunca chamado. O caso que custou mais: as Edge Functions do Supabase existiam
e não tinham um único chamador em Kotlin, então a tabela `transmissions` nunca recebia
`INSERT` e o fio do canal era permanentemente vazio em produção, com todos os testes
passando.

Duas consequências práticas para quem avalia:

- Onde uma capacidade existe e **não** está ligada, este README diz isso, com o nome do
  símbolo. A lista completa é
  [`docs/CAPACIDADES_DESLIGADAS.md`](docs/CAPACIDADES_DESLIGADAS.md).
- Onde há número, ele é **medido**, e o ambiente da medição vem junto. **Tudo foi medido
  em emulador arm64 API 35 — nada foi medido em óculos reais** (§8).

Estado corrente, reescrito a cada sessão, teto de 60 linhas: [`ESTADO.md`](ESTADO.md).

---

## 4. Setup reproduzível do zero

### 4.1 Pré-requisitos

| Ferramenta | Versão | Observação |
|---|---|---|
| JDK | 17 | `brew install openjdk@17` |
| Android SDK | Platform 35, Build-Tools 35.0.0, Platform-Tools | Android Studio ou `android-commandlinetools` |
| NDK | 27.0.12077973 | whisper.cpp e llama.cpp (JNI, `.so` por ABI) — `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"` |
| Gradle | 8.11.1 | vem pelo wrapper |

Versões fixadas em [`gradle/libs.versions.toml`](gradle/libs.versions.toml): AGP `8.9.2`,
Kotlin `2.2.0`, `mwdat 0.9.0`, MapLibre `11.11.0`, ML Kit text-recognition `16.0.1`,
OkHttp `4.12.0`. `minSdk 31` (é o piso de `setCommunicationDevice`), `compileSdk 35`,
`targetSdk 35`, ABIs `arm64-v8a` e `x86_64` (`app/build.gradle.kts:16-29`).

### 4.2 `local.properties` (não versionado)

```properties
sdk.dir=/caminho/para/o/android/sdk
github_token=ghp_...          # PAT clássico com read:packages — artefatos do DAT
supabase_access_token=sbp_... # opcional, só para rodar SQL pela Management API
```

Alternativa ao PAT no arquivo: variável de ambiente `GITHUB_TOKEN`. **Nunca versione
credenciais** — a resolução está em [`settings.gradle.kts`](settings.gradle.kts).

### 4.3 Submódulo, AAR e modelos

```bash
# whisper.cpp é submódulo (core-voice/src/main/cpp/whisper)
git submodule update --init --recursive

# AAR pré-compilado do sherpa-onnx (Piper TTS + onnxruntime). OBRIGATÓRIO para compilar.
mkdir -p core-voice/libs
curl -L -o core-voice/libs/sherpa-onnx-1.13.5.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar
```

Os modelos de **produção** já estão versionados em `app/src/main/assets/models/`
(204 MB) e vão dentro do APK — esse é o ponto: no aparelho da corporação a IA local
precisa existir sem download.

| Arquivo | Bytes | Papel |
|---|---|---|
| `models/ggml-small-q5_1.bin` | 190 085 487 | STT (whisper.cpp) |
| `models/vits-piper-pt_BR-faber-medium-int8/pt_BR-faber-medium.onnx` | 18 681 781 | TTS (Piper VITS) |
| `models/silero_vad.onnx` | 643 854 | VAD |
| `models/ativacao/melspectrogram.onnx` | 1 087 958 | ativação — mel |
| `models/ativacao/embedding_model.onnx` | 1 326 578 | ativação — embedding |
| `models/ativacao/cabeca.f32` | 1 156 | ativação — cabeça logística v5 |

Sem o submódulo, a build nativa de `core-voice` falha. Sem o AAR do sherpa-onnx,
`core-voice` e `app` não compilam.

### 4.4 llama.cpp e o GGUF (Etapa B — opcional, e desligada)

`core-llm` não compila sem o llama.cpp vendorizado. Ele **não é submódulo** (custo medido:
clone raso de 203 MB contra 43 MB do whisper; decisão em aberto), está no `.gitignore`
como trava, e é clonado à mão:

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp core-llm/src/main/cpp/llama
```

Ausente, o CMake de `core-llm` falha com essa instrução em vez de compilar pela metade.

O modelo é **`Qwen2.5-1.5B-Instruct-Q4_K_M`** (Apache-2.0, **986 048 768 B**) e **não vai
no APK**: `llama_model_load_from_file` faz `fopen`, e asset não tem caminho no sistema de
arquivos.

```bash
curl -L -o /tmp/redator.gguf \
  https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf

# (a) RECOMENDADO — sem cópia, funciona também em release
adb push /tmp/redator.gguf /sdcard/Android/data/com.claryon.field/files/redator.gguf

# (b) diretório privado — só em debug, e duplica o arquivo em disco
adb push /tmp/redator.gguf /data/local/tmp/redator.gguf
adb shell run-as com.claryon.field cp /data/local/tmp/redator.gguf files/redator.gguf
```

O nome no aparelho é `redator.gguf`, **não** o nome do modelo — foi essa escolha que fez
a troca de família custar zero linha de Kotlin e zero linha de C++ (§6.12). **(b) vence
quando os dois existem**, de propósito: armazenamento compartilhado é gravável por quem
tem acesso ao aparelho, e um GGUF trocado é um copiloto trocado.

Sem o arquivo, o app decide `LerVerbatim(SEM_MODELO)` no boot e os testes de bancada da
Etapa B são **pulados** por `Assume`, não verdes.

### 4.5 Build e execução

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)"

./gradlew build                                    # compila tudo + testes unitários (JVM)
./gradlew build :app:compileDebugAndroidTestKotlin  # ⚠️ use ESTE: `build` sozinho NÃO compila androidTest
./gradlew :app:installDebug                        # instala no dispositivo/emulador
./gradlew connectedAndroidTest                     # testes instrumentados — exige device
adb logcat -s ClaryonField                         # logs do app

# O teste do MockDeviceKit roda ISOLADO (o decodificador do mock aborta quando
# divide processo com outra classe de teste — ver o KDoc da classe).
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.claryon.field.MockDeviceKitStreamTest
```

> O emulador precisa ser **arm64-v8a** ou **x86_64** (são as ABIs compiladas). Numa
> máquina virgem, aceite as licenças antes: `sdkmanager --licenses`. Depois da primeira
> sincronização, **a build precisa funcionar offline** — Wi-Fi de evento é ruim.

Estado atual da suíte: **1004 testes únicos em 125 classes, 0 falhas, 0 pulados**
(contagem por classe; `debug+release` inflava o número em 63%).

### 4.6 Servidor (Supabase)

Migrações e verificações rodam pela Management API, que **não depende da senha do
banco** — só de um personal access token com permissão `database_write`:

```bash
python3 servidor/executar_sql.py servidor/migracoes/0001_esquema_inicial.sql
python3 servidor/executar_sql.py --somente-leitura -c "select count(*) from agents"
```

`--somente-leitura` é imposto pelo servidor, não pelo cliente: a sessão roda como
`supabase_read_only_user` e o Postgres recusa qualquer escrita. São **24 migrações**
(`servidor/migracoes/0001`…`0024`), e o esquema de segurança vive nelas, não no cliente.

### 4.7 Regra Zero — antes de qualquer código do DAT

O DAT está em *developer preview* e mudou depois do corte de treinamento de qualquer
modelo. **Nenhuma assinatura do DAT deve ser escrita de memória.** Antes de tocar em
`Wearables`, `DeviceSession`, `Stream`, `StreamConfiguration` ou `MockDeviceKit`,
confirme na fonte viva: MCP `search_dat_docs`, `llms.txt?full=true` da Meta, ou o repo
oficial `facebook/meta-wearables-dat-android` — e registre versão e fonte em
[`DECISIONS.md`](DECISIONS.md). Foi essa regra que produziu o achado do §2.3.

---

## 5. Arquitetura

12 módulos Gradle ([`settings.gradle.kts`](settings.gradle.kts)):

```
claryon-field/
├── app/             UI Compose + orquestração + ViewModels + serviço de primeiro plano
├── core-common/     Result, Logger, FeatureFlags, Telemetry, Priority/Earcon,
│                    LaconicityPolicy, PcmResampler                        [Kotlin/JVM]
├── core-agent/      Intent · DeterministicIntentRouter · ActionOutcome ·
│                    utteranceFor · PlacaValidator · PlacaDitada · PowerPolicy   [JVM]
├── core-knowledge/  Corpus de norma · índice BM25 · limiar de recusa            [JVM]
├── core-glasses/    Fachada única sobre o DAT (registro, sessão, câmera)    [Android]
├── core-audio/      Rota HFP/SCO, FonteUnicaDeMicrofone, AudioRecord/Track  [Android]
├── core-voice/      Wake word · VAD · STT (whisper.cpp) · TTS (Piper)       [Android]
├── core-sound/      Earcons, fila de prioridade, laconicidade               [Android]
├── core-evidence/   Cofre cifrado + cadeia de custódia                      [Android]
├── core-net/        PTT ao vivo, protocolo Realtime, base veicular, Overpass [Android]
├── core-sync/       Fila offline durável, WorkManager                       [Android]
└── core-llm/        llama.cpp + política de degradação (Etapa B)            [Android]
```

**Regra de dependência.** `app` depende de todos; os `core-*` **não dependem uns dos
outros**, exceto de `core-common`. A orquestração fica em `app`. **Todo acesso ao DAT
passa por `GlassesFacade`** (`core-glasses`) — quando a preview quebrar assinaturas,
conserta-se um arquivo.

Duas fronteiras não são convenção: são teste.

- `core-llm` sem `core-agent` no classpath (`FronteiraDoRedatorTest`), e nenhum arquivo
  de produção de `app` juntando `com.claryon.llm` ao caminho de ação
  (`FronteiraDoRedatorEmAppTest`). Motivo medido: **2 de 3** respostas geradas por LLM,
  entregues ao roteador, viravam ação executável.
- `core-knowledge` idem (`FronteiraDoConhecimentoEmAppTest`): **252 dos 1817 trechos de
  lei**, entregues ao roteador, viravam ação. A garantia é não existir arquivo onde o
  texto recuperado e o construtor de intenção se encontrem
  (`app/src/main/kotlin/com/claryon/field/norma/ConsultaDeNorma.kt:10-36`).

Diagrama completo, com os cinco checkpoints do edital marcados:
[`docs/ARQUITETURA.mmd`](docs/ARQUITETURA.mmd) (Mermaid).

### Duas sequências que não podem ser invertidas

```
BOOT
1. Wearables.initialize(context)              // uma vez por processo
2. observar registrationState + errorStream
3. se != REGISTERED → deeplink Meta AI → retorno via claryonfield://
4. GlassesAudioManager.iniciar()              // ⚠️ ANTES do passo 5
5. Wearables.createSession(...) → session.start()
6. [sob demanda] withCamera(CameraProfile.OCR)
```
Inverter 4 e 5 dá captura de voz intermitente: o HFP precisa estar configurado **antes**
da sessão de streaming.

```
CICLO DE VOZ
"Claryon" → [DESPERTAR] → microfone abre → [CANAL_ABERTO] → VAD fecha a janela
  → [CANAL_FECHADO, imediato] → STT → IntentRouter → IntentExecutor.execute()
  → utteranceFor(ActionOutcome) → fila de prioridade (earcon e/ou TTS)
```
O earcon de fechamento dispara quando o **VAD fecha a janela**, não quando o STT termina.
E **a ação acontece antes de existir qualquer frase** — ver §6.5.

O detalhamento longo (armadilhas, energia, sequências) está em
[`docs/PADROES_DE_ENGENHARIA.md`](docs/PADROES_DE_ENGENHARIA.md).

---

## 6. As capacidades, uma a uma

Cada seção responde às mesmas cinco perguntas: **o problema**, **os caminhos
estudados**, **a decisão e o porquê**, **o que custou** e **como está construído**, com
`arquivo:linha`.

---

### 6.1 Rota de áudio HFP/SCO

**O problema.** O produto inteiro depende de capturar a voz do agente e devolver som ao
ouvido dele. A primeira suposição — a natural — era que o SDK dos óculos entregaria
áudio, como entrega vídeo.

**Os caminhos estudados.**

1. **`session.audioStream` do DAT.** Seria o caminho limpo: uma API, um ciclo de vida.
2. **A classe de áudio no pacote `internal` do AAR.** Existe. Não é API pública.
3. **HFP/SCO pelo Android**, tratando os óculos como um headset Bluetooth qualquer.
4. **A2DP** para a reprodução, que tem banda melhor que o SCO.

**A decisão e o porquê.** `javap` no artefato `mwdat-*-0.9.0` mostrou que `Stream` expõe
`videoStream`, `errorStream`, `state`, `start`, `stop` e `capturePhoto` — **e nada de
áudio**. O caminho 1 não existe; o 2 é pacote interno e quebraria na próxima preview.
Sobrou **HFP/SCO**, e ele é o mesmo `TYPE_BLUETOOTH_SCO` de qualquer fone com microfone,
o que tem um efeito colateral valioso: **dá para desenvolver e medir sem os óculos**.
O caminho 4 foi descartado para o PTT: A2DP acrescenta 100–200 ms de buffer, e num rádio
tático isso é fala pisada.

Esse achado é o argumento central para a Regra Zero (§4.7): a descoberta veio de `javap`,
não de uma falha em produção.

**O que custou.** O elo de voz é mono e de banda estreita (CVSD, ~8 kHz). Isso não é
detalhe: é a causa direta do teto da palavra de ativação por transcrição (§6.2), porque
a oclusiva velar /kl/ de "Claryon" morre acima de ~3,4 kHz. Custa também perder o A2DP
enquanto o SCO está ativo, e obriga `clearCommunicationDevice()` no encerramento — sem
ele, **todo o áudio do sistema fica preso em modo de comunicação**.

**Como está construído.**

- `core-audio/src/main/kotlin/com/claryon/audio/GlassesAudioManager.kt:31` — a interface.
- `core-audio/src/main/kotlin/com/claryon/audio/GlassesAudioManagerImpl.kt:42` — a
  implementação. `iniciar()` (`:93`) faz, nesta ordem:
  `mode = MODE_IN_COMMUNICATION` (`:106`) → `availableCommunicationDevices` (`:108`) →
  filtra `TYPE_BLUETOOTH_SCO` (`:109`) → `setCommunicationDevice(target)` **com
  tratamento do `false`** (`:123`) → prova a rota (`:83`). `startBluetoothSco`, obsoleto,
  não é usado em lugar nenhum do código.
- **A pré-condição é de tipo, não de disciplina.**
  `core-audio/src/main/kotlin/com/claryon/audio/GlassesAudioRoute.kt:28` é um
  `@JvmInline value class` de **construtor privado**; a única porta de produção é
  `acquire(am)` (`:54`), que só devolve sucesso se a rota ativa for `TYPE_BLUETOOTH_SCO`
  (`:62`). E `microfonePcm(route: GlassesAudioRoute)` exige esse tipo
  (`GlassesAudioManager.kt:64`). **Gravar pelo microfone do celular não compila.** O
  motivo é de privacidade: o microfone do celular é omnidirecional e capta terceiros; num
  PTT, gravar pela fonte errada **difunde** a fala do abordado para a guarnição inteira.
- **Uma captura, N consumidores.**
  `core-audio/src/main/kotlin/com/claryon/audio/FonteUnicaDeMicrofone.kt:72` —
  um `AudioRecord` por rota, `SharedFlow` com fan-out, contagem de referência. Dois
  `AudioRecord` na mesma fonte fazem a segunda falhar ou roubar o fluxo da primeira.
- **A rota é reconferida DURANTE o stream**, a cada 200 ms
  (`FonteUnicaDeMicrofone.kt:163-166`, `MS_ENTRE_CONFERENCIAS_DE_ROTA = 200L` em `:215`),
  e a perda vira `RotaDeAudioPerdidaException`
  (`GlassesAudioManagerImpl.kt:487`). Conferir só na abertura não bastava: HFP cai em
  campo — óculos dobrados, fone desligado, ligação entrando — e o Android escolhe um
  substituto **em silêncio**.
- **Barramento interno a 16 kHz**, não a 8: `DEFAULT_SAMPLE_RATE_HZ = 16_000`
  (`GlassesAudioManagerImpl.kt:472`), `TAXA_SAIDA_HZ = 16_000`
  (`app/.../voice/VoiceOutput.kt:144`). A reamostragem com filtro anti-alias vive em
  `core-common/src/main/kotlin/com/claryon/common/PcmResampler.kt:32`.
- Dono único de processo: `object AudioDoAgente`
  (`app/src/main/kotlin/com/claryon/field/audio/AudioDoAgente.kt:48`).

---

### 6.2 Palavra de ativação ("Claryon")

**O problema.** *"A Alexa é chamada e responde. Por que Claryon não?"* A implementação
inicial detectava a ativação **através do transcritor de propósito geral** — pedia ao
whisper a frase inteira e procurava a palavra no texto.

**Os caminhos estudados**, e cada um foi medido antes de ser descartado
([`docs/PALAVRA_DE_ATIVACAO.md`](docs/PALAVRA_DE_ATIVACAO.md) tem as oito hipóteses):

1. **Detectar por transcrição.** Teto medido: **33,3% de recall** com a grafia exata da
   marca (6/18), contra meta de 90%. Afrouxar para a rima `-on`/`-om` dobra o recall para
   **66,7%** e abre **3 falsos positivos em 30** — todos em *"elétron e próton"*.
2. **KWS pronto do sherpa-onnx.** Controle canônico 3/3; a palavra "Claryon" dá **0/8** em
   todas as grafias e nas duas bandas. Está **fora de domínio**: os dois presets do AAR
   são mandarim e inglês, e não há preset streaming em português.
3. **Corrigir a grafia da chave** (`CLARYON` → `CLARION` e outras 4). 0/8. Refutada.
4. **Culpar a banda estreita.** Banda cheia dá 0 igual. Refutada.
5. **Banco de formas** — colecionar os erros do decodificador. 18 rendições produziram
   **18 formas distintas, nenhuma repetida**. Refutada.
6. **Treinar um detector acústico próprio** sobre o *embedding* congelado do
   **openWakeWord**.

**A decisão e o porquê.** O caminho 6. A justificativa é estrutural, não de ajuste: o
whisper escolhe entre 51.865 tokens com um modelo de linguagem puxando para palavras
frequentes do português, e **acerta a cauda inteira do comando** (*"mudar para a guarnição
4"*) errando só o nome — porque o nome é a única coisa fora do vocabulário dele. Nenhum
parâmetro conserta isso.

Antes de escrever qualquer linha no Android, foi medida a pergunta que decide: **o
embedding em inglês do openWakeWord separa "Claryon" das vizinhas em português?** Treino
com **um** locutor (27 elocuções), teste em três locutores inéditos: **0,963 · 0,996 ·
0,999**, contra ≤ 0,216 nos controles. O embedding transfere.

Uma medição intermediária inverteu uma recomendação anterior e vale registrar: **treinar
com voz sintética destrói o que funcionava.** Margem com só voz humana: **+0,109**
(separa). Humana + Piper: **−0,020** (não separa). A voz do TTS ocupa uma região do
espaço de embedding distante da voz real.

**O que custou.**

- **Falso positivo de 2,08/h contra meta de 0,5/h** — medido pela cabeça `v5` que está de
  fato embarcada, sobre fala espontânea retida
  (`app/src/androidTest/.../bench/FalsoPositivoEmFalaEspontaneaTest.kt:106`). É o defeito
  aberto nº 4 do [`ESTADO.md`](ESTADO.md). Mitigação de produto, não de modelo: **toda
  ativação toca o earcon `DESPERTAR`**, então acordar sozinho nunca é silencioso.
- **Recall nunca medido por HFP.** O lado positivo da curva ainda são 9 clipes limpos de
  **um** locutor, gravados pelo microfone do celular a 48 kHz. A banda estreita simulada
  **não** reproduz o codec CVSD. Por isso subir o limiar acima de 0,5 continua sem
  justificativa medida.
- Um treino que vive **fora** do repositório (`ferramentas/ativacao/`), com dados de voz
  que não são versionados.

**Como está construído.**

- `core-voice/src/main/kotlin/com/claryon/voice/DetectorDeAtivacao.kt:93` —
  `class DetectorDeAtivacao(pesos, vies, limiar = 0.5f, refratarioMs = 1_000)`.
  Anel de **1,0 s** (`AMOSTRAS = 16_000`) deslizando **80 ms** (`PASSO_AMOSTRAS = 1_280`)
  → **12,5 decisões por segundo**. Extrator ONNX (`melspectrogram` + `embedding`) em
  `core-voice/src/main/cpp/ativacao_jni.c`; a **cabeça** é um produto escalar de 288
  dimensões mais viés, 289 floats em `cabeca.f32`.
- **Custo zero de dependência:** `libonnxruntime.so` já entra no APK pelo AAR do
  sherpa-onnx e já exporta `OrtGetApiBase` — faltava chamador, não motor.
- **O chamador em produção:**
  `app/src/main/kotlin/com/claryon/field/voice/EscutaDeAtivacao.kt:91`, construída em
  `app/src/main/kotlin/com/claryon/field/service/CopilotService.kt:136-161`. Ela roda
  **só onde `PowerPolicy` já declara `hfpAberto`** — a mesma regra que decide se o serviço
  de primeiro plano pede o tipo `MICROPHONE`. Escrever `if (modo != STANDBY)` daria o
  mesmo resultado hoje e seria uma segunda cópia da regra, livre para divergir.
- **As três bocas que a escuta fecha**, e a razão de cada uma: a **própria saída** (o
  earcon volta pelo microfone — alto-falante *open-ear* a centímetros), o **rádio** (um
  "Claryon" dito no ar é conversa, não comando) e **o ciclo que ela mesma abriu**. Nas três
  ela chama `reiniciar()` ao voltar: sem isso o anel emendaria os dois lados da mudez e
  avaliaria uma janela **que nunca existiu no mundo** — falso positivo por construção.
- **A palavra de ativação NUNCA abre canal sozinha** (§6.7). Ela antecipa o earcon; quem
  abre é a transcrição íntegra contra léxico fechado.

> **Achado de método que este bloco produziu.** Os pesos do modelo viviam em
> `androidTest/assets`. O teste instrumentado lia os assets do **próprio APK de teste** e
> passava; em produção, `preparar()` devolveria `false` e o detector ficaria desligado em
> silêncio. Teste verde sobre um caminho que o produto não percorre.

---

### 6.3 Transcrição on-device (STT)

**O problema.** Transcrever comando de policial, em português, em rua barulhenta, sobre
um elo de 8 kHz, **sem rede** — porque offline é o caso normal em campo, não a exceção.

**Os caminhos estudados.**

1. **`SpeechRecognizer` do Android.** Depende de serviço do Google, que o aparelho da
   corporação pode não ter, e o modo offline em pt-BR é irregular.
2. **STT em nuvem.** Proibido pelo §2 e, pior, inútil onde o produto mais precisa
   funcionar.
3. **whisper.cpp**, com escolha de tamanho de modelo: `tiny` (~75 MB), `base` (~142 MB),
   `small-q5_1` (**181 MiB**).

**A decisão e o porquê.** whisper.cpp com **`ggml-small-q5_1`**, e a escolha do tamanho é
o ponto. A regra que decidiu está escrita: **transcrição errada é pior que transcrição
ausente, porque a ausente o agente percebe.** Um erro aqui não é um *typo* — é uma placa
errada consultada ou um endereço errado despachado. `small-q5_1` mede **WER 3,4% em
pt-BR**; `tiny` e `base` foram medidos e reprovados no domínio (alfabeto militar,
indicativos, ordinais de placa). O `AndroidOnDeviceStt` continua no repositório como
*fallback* atrás da interface `SttEngine`, e o Android permanece o plano B, não o plano A.

**O que custou.** **106 MB a mais** de modelo, aceitos com o número na mão. E o custo é
pior do que o disco sugere: o modelo é lido por *streaming* para o heap nativo
(`core-voice/src/main/cpp/jni.c:98-147`, `AASSET_MODE_STREAMING`) e **não** por `mmap` —
são 181 MiB de memória suja e não-descartável, que o sistema não recupera sob pressão.
Projeção para o aparelho de referência (Samsung Galaxy M34) com a Etapa A ativa:
**≈420–560 MB de PSS**.

**Como está construído.**

- `core-voice/src/main/kotlin/com/claryon/voice/WhisperCppStt.kt:32`, sobre
  `com/whispercpp/whisper/LibWhisper.kt:47` e `core-voice/src/main/cpp/jni.c`.
- Asset: `models/ggml-small-q5_1.bin` (`app/.../voice/Modelos.kt:45`), **190 085 487 B**.
- **Contexto quente entre invocações:** `object EscutaDoAgente`
  (`app/src/main/kotlin/com/claryon/field/voice/EscutaDoAgente.kt:47`). Antes, cada ciclo
  de voz fazia carregar e `release()` — recarregando o modelo inteiro por comando. Hoje é
  objeto de processo com liberação por pressão de memória, ligada ao
  `Application.onTrimMemory` (`ClaryonApp.kt:152`).
- **Whisper é lote, não streaming.** Quem decide a janela é o VAD: **Silero** via
  sherpa-onnx (`app/.../voice/SileroVoiceActivityDetector.kt:54`,
  `models/silero_vad.onnx`, 643 854 B), com janela de 512 amostras (32 ms). São **duas
  instâncias, não uma**: a do gatilho quer segmentos curtos; a da transmissão precisa
  tolerar 30 s via `maxSpeechDuration` (`RadioViewModel.kt:386`). O VAD por energia RMS
  que existia antes continua no repositório, desligado, porque é o único VAD em Kotlin
  puro e sustenta testes de JVM que rodam sem `.so`.
- O `initial_prompt` de domínio **não** é usado em produção: medido, o prior custa **1,8
  ponto de WER** (12,5% sem, 14,3% com) e não ajudou nem nas frases cujo vocabulário está
  dentro dele.

---

### 6.4 Roteamento de intenção determinístico

**O problema.** Transformar a transcrição em ação. Este é o ponto do sistema onde um
erro vira consequência operacional — despachar apoio, abrir canal, iniciar gravação.

**Os caminhos estudados.**

1. **LLM classificando a intenção** (*function calling* local).
2. **Classificador estatístico treinado** sobre comandos rotulados.
3. **Casamento por padrão sobre léxico fechado**, determinístico.

**A decisão e o porquê.** Caminho 3, e a regra é dura: **o LLM nunca escolhe ação.** Ele
pode, no máximo, propor o preenchimento de campos de uma intenção **já definida**, com a
saída validada contra esquema estrito. O que decide é latência previsível, ausência de
rede e **auditabilidade**: um roteador determinístico é lido, e a razão de uma ação é
apontável numa linha.

E a decisão tem prova experimental, não só argumento. Duas medições:

- **2 de 3** respostas geradas pelo LLM, entregues ao roteador, viraram ação executável
  (*"preciso chamar apoio para essa ocorrência de trânsito"* → `PedirApoio`). Modelo
  pequeno repete a pergunta ao responder, e o roteador é padrão sobre português. **Não
  tem conserto por prompt.**
- **252 dos 1817 trechos de lei** do corpus, entregues ao roteador, também viravam ação.

Por isso a garantia deixou de ser cuidado e virou **estrutura**: não existe, em lugar
nenhum de `src/main`, um arquivo onde o texto recuperado (ou gerado) e o construtor de
intenção se encontrem. Isso é sustentado por `FronteiraDoRedatorEmAppTest` e
`FronteiraDoConhecimentoEmAppTest`, sem lista de exceção escrita à mão — porque lista de
exceção é onde a próxima entra sem ninguém notar.

**O que custou.** Vocabulário fechado: o que não casa vira `Intent.NaoReconhecida`, e o
agente ouve um pedido de repetição. Não há generalização para frases que ninguém previu.
E a **ordem do `when` vira regra de negócio**, com armadilhas reais já pagas: *"modo
abordagem"* contém "abordagem", que é tipo de ocorrência — sem a ordem certa, trocar de
modo dispararia alerta para a guarnição.

**O que custa em tempo: 93 µs.** Medido em
`app/src/test/kotlin/com/claryon/field/agent/PlacaDitadaPeloRoteadorTest.kt:351-372`
(200 iterações de aquecimento, 50 rodadas, p50/p95/max em µs). **O teste imprime, não
asserta** — o número é observação de bancada, não invariante.

**Como está construído.**

- `core-agent/src/main/kotlin/com/claryon/agent/DeterministicIntentRouter.kt:16`.
  Pipeline: remove o gatilho da fala (`:26`) → normaliza (minúsculas, NFD, remove
  diacríticos, colapsa espaços — `:485`) → cascata `when` do mais específico ao mais
  genérico (`:37`), sobre listas literais no `companion object` (`:297-479`).
- **Distância de edição sobre chave fonética**, não sobre a grafia:
  `core-agent/src/main/kotlin/com/claryon/agent/ChaveFonetica.kt:34`, com
  `TOLERANCIA_GUARNICAO = 2`. É o que faz *"Guarney são 1 na escuta"* ainda resolver.
- Modelo de domínio: `sealed interface Intent`
  (`core-agent/src/main/kotlin/com/claryon/agent/Intent.kt:17`, 15 variantes) e
  `sealed interface ActionOutcome`
  (`core-agent/src/main/kotlin/com/claryon/agent/ActionOutcome.kt:16`, 19 variantes).
- Executor: `interface IntentExecutor`
  (`core-agent/src/main/kotlin/com/claryon/agent/IntentExecutor.kt:19`), implementado por
  `app/src/main/kotlin/com/claryon/field/agent/ClaryonIntentExecutor.kt:101`.
  **`IntentExecutor` nunca lança**: toda falha vira `ActionOutcome.Falhou` com causa
  tipada.
- Chamadores reais em `src/main`: `app/.../voice/CopilotoDoAgente.kt:708` e
  `app/.../voice/VoiceCycle.kt:133`.

---

### 6.5 TTS e design de áudio

**O problema.** Num sistema sem display, **o áudio é a UX inteira**. E o alto-falante é
*open-ear*: o que sai vaza para quem está ao lado — inclusive para o abordado.

**Os caminhos estudados.**

1. **`android.speech.tts.TextToSpeech`.** Disponível, grátis, e depende do motor
   instalado no aparelho.
2. **Piper VITS via sherpa-onnx**, pt-BR, quantizado int8.
3. Para o vocabulário não-verbal: **fala para tudo** × **earcons + fala curta**.

**A decisão e o porquê.** Piper como primário (qualidade e independência do aparelho), com
`AndroidTts` mantido como *fallback* atrás da interface `TtsEngine`. E, para o vocabulário,
o caminho 3, com quatro regras que são código, não estilo:

- **Máximo 7 palavras** por resposta operacional (`LaconicityPolicy.MAX_WORDS = 7`,
  `core-common/.../AudioSignals.kt:137`), com teste que varre **todos** os ramos de
  `utteranceFor` (`core-agent/src/test/.../UtteranceTest.kt:231`).
- **Sem cortesia** — e a lista é fina de propósito: `favor`, `desculpe`, `obrigado`
  entram; `por`, `tudo` e `bem` **não**, porque são palavras de fala operacional legítima
  (*"Apoio solicitado por rádio."*).
- **Resultado sensível sai como earcon codificado + fala curta.** Até 21/08 a regra era
  "nunca falado"; mudou porque o vazamento *open-ear* é o risco real.
- **Falha nunca é silêncio.** Todo caminho de erro tem earcon próprio: sem display,
  silêncio é indistinguível de aplicativo morto.

**A honestidade é garantida por assinatura, não por disciplina.**

```
roteador → Intent → IntentExecutor.execute() → ActionOutcome → utteranceFor(outcome)
                                               ↑ a ação acontece AQUI
```

`fun utteranceFor(outcome: ActionOutcome)`
(`core-agent/src/main/kotlin/com/claryon/agent/Utterance.kt:52`) aceita **apenas**
`ActionOutcome`. Não existe sobrecarga que aceite `Intent`, e há teste que falha se
alguém acrescentar. Corolários que isso força:

- *Entregue* ≠ *enfileirado*: `Despacho.Enviada | Enfileirada` é escolha do compilador. Sem
  rede, o agente ouve *"Sem rede. Na fila."*, jamais *"apoio solicitado"*.
- Contagem desconhecida não vira zero: `ApoioTransmitido(null)` fala *"Apoio enviado."*, e
  só com contagem real fala *"Quatro unidades receberam."*

**O vocabulário sonoro tem gramática, e ela foi decidida, não escolhida por gosto.**
São **10 earcons** (`enum class Earcon`, `core-common/.../AudioSignals.kt:62`):

```
"Claryon"                → DESPERTAR      BOMMM      IDENTIDADE
"guarnição N na escuta"  → CANAL_ABERTO   bipbip     CONVENÇÃO (chirp ascendente)
parou de falar, ou 30 s  → CANAL_FECHADO  trimtrim   CONVENÇÃO (chirp descendente)
```

**Despertar é identidade** — golpe de sino inarmônico (parciais 1 · 2,76 · 5,40 sobre
466 Hz, ataque de 2 ms, decaimento de 520 ms), o único `GOLPE` do vocabulário, e é o que a
marca registra. **Abrir e fechar canal é convenção**, copiada do chirp do Nextel/iDEN
porque o policial já sabe o que significa. Inverter os papéis custaria as duas coisas:
uma marca que ninguém reconhece e um par de sons a aprender.

Duas restrições físicas viraram regra:

- **Todo earcon vive entre 400 e 3400 Hz.** Acima disso o HFP não entrega; abaixo mora o
  ruído de viatura. O earcon `FALHA` varria até 300 Hz e tinha **54% da energia debaixo do
  motor** — justamente o sinal que avisa que algo deu errado.
- **Dois earcons se separam por MORFOLOGIA, não por frequência.** Sob banda estreita e
  ruído grave, o que sobrevive é quantos elementos há, separados por quanto silêncio, e se
  cada um sobe, desce ou fica plano. Há teste de distinguibilidade **par a par**, calculado
  do PCM, com régua própria para os primeiros ~120 ms — é neles que o agente decide se o
  som é para ele. Sem esse teste, `GRAVANDO` e `CONSULTA_FURTO_ROUBO` eram **idênticos bit
  a bit por 115 ms**.

**O que custou.** Preempção. `Priority` tem três níveis
(`EMERGENCIA, RESPOSTA, INFORMATIVO`, `AudioSignals.kt:18`): nível 1 interrompe tudo,
nível 3 é suprimido em Modo Tático. E "interrompe tudo" precisou incluir **a síntese**,
não só o que já está soando — uma fala do copiloto passa ~1 s dentro do Piper antes de
virar som, e um P1 que chegasse nessa janela esperava a fala inteira tocar (**~10,9 s**
contra os ≤ 200 ms do aceite). Pior: **`cancel()` de corrotina não interrompe JNI.** O
Piper é nativo; quem corta não *para* a síntese, **desiste de esperar por ela**. Qualquer
conserto de preempção que dependa de o `render` obedecer ao `cancel` funciona no teste e
mente em campo.

**Como está construído.**

- `core-voice/src/main/kotlin/com/claryon/voice/PiperTts.kt:44`, sobre
  `com.k2fsa.sherpa.onnx.OfflineTts`. Modelo
  `models/vits-piper-pt_BR-faber-medium-int8/pt_BR-faber-medium.onnx` (18 681 781 B,
  22 050 Hz), velocidade de campo 0.9 (`Modelos.kt:103`).
- `core-sound/src/main/kotlin/com/claryon/sound/PrioritySoundQueue.kt:84` — síntese e
  reprodução num job só; `SoundScheduler.kt:14` é a **política pura** (testável sem
  Android), com `deveInterromper(novo, atual)` em `:52`.
- `core-sound/src/main/kotlin/com/claryon/sound/EarconSynthesizer.kt:62` — síntese em
  Kotlin, sem arquivo de áudio, com cache por processo.
- **Todo earcon precisa de chamador em `src/main`** (`ChamadorDosEarconsTest`). Earcon
  sintetizado e nunca tocado é a mesma família de defeito do §3, e é a mais fácil de
  cometer: nasce numa entrada de `enum`, ganha um ramo no `when`, passa nos testes de
  síntese, e nunca sai por um alto-falante.

---

### 6.6 Leitura de placa pela câmera (DAT + ML Kit)

**O problema.** Numa blitz da PMERJ, o agente de checagem faz de **60 a 150 consultas de
placa por turno**, cada uma custando no mínimo 2,5 minutos: tirar o celular, abrir o app,
esperar, entrar no Sinesp e digitar. São ~2h30 de uma operação de 4 a 6 horas — e o custo
maior não é o tempo, é **olhar para a tela justamente na abordagem**.

**Os caminhos estudados.**

1. **Reconhecimento em nuvem.** Proibido pelo §2 (frame não sai daqui) e dependente de
   sinal.
2. **`capturePhoto()` do DAT** — devolve `PhotoData` já decodificado.
3. **Stream de vídeo do DAT + OCR local**, com o problema de que `VideoFrame` **não tem
   campo de formato**.
4. Para o reconhecimento: **ML Kit Text Recognition** (modelo Latin embarcado) × Tesseract
   × modelo próprio.

**A decisão e o porquê.**

**(a) Stream, não foto.** `capturePhoto()` exige stream ativo de qualquer jeito (não
elimina o `withCamera`), é uma foto por vez, e a nossa implementação **devolve
`ByteArray(0)`** — o payload é descartado desde o M2. Continua com zero chamadores.

**(b) Ler apenas o plano Y.** `javap` em `mwdat-camera-0.9.0` mostrou
`VideoFormat.Companion.getDefaultFormat() → H265, 504x896, 30 fps, colorFormat 19`. O
`19` é `COLOR_FormatYUV420Planar` — mas isso é o que o SDK **pede** ao `MediaCodec`, não
o que cada aparelho **entrega**, e `VideoFrame` não expõe o formato. As alternativas eram
adivinhar o layout de croma (e errar **em silêncio** no aparelho que devolvesse NV12) ou
desistir do stream. **O plano Y é o primeiro e ocupa exatamente `largura × altura` bytes
em I420, YV12, NV12 e NV21** — os quatro layouts possíveis. O que muda entre eles é croma,
e croma é a única coisa que reconhecimento de texto não usa. A conversão fica
**independente de uma informação que o SDK não expõe**, e há teste alimentando o mesmo Y
com cromas diferentes exigindo a mesma placa.

**(c) 7 fps, e é ESCOLHA, não teto do SDK.** `CameraProfile.OCR = CameraProfile(Quality.LOW,
frameRate = 7)` (`core-glasses/src/main/kotlin/com/claryon/glasses/Models.kt:108`). A
bateria crítica é a **dos óculos**; 30 fps só aqueceriam a armação. E a medição mostrou
que o gargalo não é o reconhecedor: o pior frame custa **113 ms** contra os ~143 ms de
intervalo entre frames, então a inferência acompanha praticamente todo frame entregue.
Aumentar o FPS renderia mais tentativas; baixar o custo do OCR, não.

**(d) Validador estrito, com o portão repetido de propósito.** `PlacaValidator`
(`core-agent/src/main/kotlin/com/claryon/agent/PlacaValidator.kt:8`) só aceita
`[A-Z]{3}[0-9][A-Z][0-9]{2}` (Mercosul) ou `[A-Z]{3}[0-9]{4}` (antiga), **sete caracteres
exatos, sem fatiar**. A conferência se repete dentro de `PlacaOcr`, dentro do roteador e
em `ClaryonIntentExecutor.consultarPlacaDe`, porque este último é o **único ponto por onde
uma placa entra na consulta** — venha do reconhecedor, do roteador, ou de uma ditada.

**Os números.** Emulador arm64, ML Kit Latin embarcado, 504×896:

```
leitura completa ......  2 frames, 67 / 85 / 93 / 180 ms
custo COM placa .......  medianas 31 / 56 / 63 / 83 ms   (extremos 7–113)
custo SEM placa .......  medianas 8 / 9 / 9 / 9 ms       (extremos 8–35)
```

O **conteúdo distinto por repetição** não é capricho: medindo o *mesmo* frame cinco vezes
a mediana caía para 6–8 ms, número que não sobrevive a leitura honesta, porque em campo
dois frames nunca são idênticos. O frame "sem placa" também tem texto de verdade
("RUA DAS FLORES / 1234 CENTRO"), com asserção exigindo isso — com um retângulo cinza liso
o detector desiste em milissegundos e o custo medido seria o de um caso que nunca acontece.

**Sobre as "31 imagens de campo": elas são sintéticas, e isso precisa estar dito.** São
**26 cenas com placa + 5 negativos**, geradas em runtime por `Canvas` — ângulo, chuva,
contraluz, reflexo, oclusão, noite, barro — em
`app/src/androidTest/kotlin/com/claryon/field/vision/CorpusDePlacasSinteticas.kt:48`
(`corpus()` em `:419`). **Não há fotografia de placa neste repositório**, e o KDoc do
arquivo declara isso. Sobre elas,
`OcrDePlacaEmCondicoesDeCampoTest.oOcrDeCampo_naoAceitaNenhumaPlacaErrada` (`:144`) mede
**zero placas erradas aceitas**, e o `p50 = 8 ms` sai de `:178-181`. Para placa **ditada**
por rádio, o banco de elocuções dá **40/40** com **zero** extração errada e **zero** falso
positivo em 44 negativos.

**O que custou.**

- **Barro é 0/3.** E é exatamente onde afrouxar o validador mata: com 6 caracteres,
  `DEF4567` viraria `DEF456` — outro carro. Há um teste que demonstra isso
  (`oValidatorAfrouxado_fabricaPlacaQueAEstritaRecusou`, `:241`). O sistema **recusa e
  fala** (*"Placa ilegível. Aproxime."*) em vez de arriscar.
- **Farol piora a leitura noturna** (reflexo na placa retrorrefletiva).
- Perde placa de veículo em movimento rápido — resolve com segunda tentativa, e a recusa
  falada imediata existe para o agente reposicionar.
- **O teto do ciclo de voz subiu para 14 s** por causa disto: `withTimeoutOrNull(8_000)`
  cobria tudo (janela do VAD + whisper + ação), e frase de 2,5 s + ~1 s de whisper + 5 s
  de câmera passa de 8 — o ciclo seria cancelado **exatamente quando a captura estava
  funcionando**. A alternativa (encurtar a janela do OCR) é aceite escrito em spec, e
  portanto decisão humana.
- **Dois aceites seguem inconsistentes, e isso é achado, não conserto:** no caminho em que
  a placa **não** aparece, gasta-se a janela inteira — 945 ms + 5 000 ms ≈ **5,9 s** contra
  os ≤ 4 s da Fase 4.

**Como está construído** — cadeia alcançável, ponta a ponta:

```
CopilotoDoAgente.kt:322     lerPlacaPelaCamera = { PlacaPelaCamera.ler { … } }
PlacaPelaCamera.kt:121      ler(avisar)                       → fala "Aponte para a placa."
CapturaDePlaca.kt:109       facade().withCamera(CameraProfile.OCR) { fluxo -> … }
                            JANELA_MS = 5_000  (CapturaDePlaca.kt:172)
FrameParaBitmap.kt:60       luminancia(frame)                 ← só o plano Y
PlacaOcr.kt:45              lerPlaca(bitmap)                  ← ML Kit 16.0.1
PlacaValidator.kt:28        extrair(texto)
```

Ponto de entrada em produção: `CopilotoDoAgente.de(app)`, chamado por
`service/CopilotService.kt:120` e `ui/CopilotoViewModel.kt:41`.

**Placa ditada.** *"tango bravo unido três delta sete zero"* é como se dita placa por
rádio. O extrator `PlacaDitada` (`core-agent/.../PlacaDitada.kt:63`) nasceu medido e
passou uma sessão inteira **sem chamador** — o agente que ditasse caía em
`ConsultarPlaca(placa = null)` e o app **abria a câmera** para ler a placa que ele acabara
de falar. Hoje está ligado em `DeterministicIntentRouter.extrairPlaca` (`:214`), na ordem
`literal ?: ditada`. Detalhe que não é detalhe: a ditada recebe a transcrição **crua**, não
a normalizada, porque **a caixa alta é evidência** — é a única coisa que separa "ABC" de
"do" em *"a placa do carro"*.

**Falha de câmera chega ao ouvido, e chega DIFERENTE.** As oito causas de `ErroDeStream`
do SDK são agrupadas **por recuperação**, não por causa (`app/.../oculos/FalhaDaCamera.kt`,
um `when` **sem `else`** — valor novo no SDK quebra a compilação em vez de cair num balde
genérico): `HINGE_CLOSED` → abra as hastes · `PERMISSIONS_DENIED` → mexa no app da Meta ·
`THERMAL_HOT` → espere esfriar (insistir piora) · `BATTERY_LOW`/`PEAK_POWER_LIMIT` → ponha
no estojo · resto → tente de novo.

---

### 6.7 Rádio tático PTT com transcrição na origem

**O problema.** Substituir/complementar o rádio VHF com um canal que a guarnição inteira
ouve **e lê**, sem que a fala do abordado seja transcrita e sem que o servidor precise
processar áudio.

**Os caminhos estudados.**

1. **Gravar o arquivo e enviar ao terminar.** Simples, e a fala chega segundos depois —
   inútil para coordenação.
2. **Áudio ao vivo em quadros curtos**, com o Storage como arquivamento assíncrono.
3. Para a transcrição: **no servidor** (uma implementação, uma conta a pagar) × **em cada
   receptor** (N transcrições, N textos possivelmente diferentes) × **na origem**.
4. Para o codec: **AMR-NB** × **Opus**.

**A decisão e o porquê.**

**(a) Ao vivo, em quadros Opus de 20 ms**, enquanto o agente fala. A captura **não bloqueia
esperando a rede**: o `AudioRecord` começa no instante do toque e a concessão de canal corre
em paralelo — rede lenta atrasa a entrega, **nunca perde fala**.

**(b) Opus e não AMR-NB, por causa do FEC.** `ConfigOpus`
(`core-net/src/main/kotlin/com/claryon/net/CodecDeVoz.kt:21`): quadro de 20 ms, `fecAtivado
= true`, `perdaEsperadaPct = 10`. Implementado com o **`MediaCodec` do Android**
(`MediaCodecOpus.kt:62`, MIME `audio/opus`) — zero dependência nativa nova.

**(c) Transcrição na ORIGEM.** É a decisão que define o Pilar 1. Transcrever no servidor
faria áudio de policial trafegar para um processador; transcrever em cada receptor
produziria **textos diferentes para a mesma fala**, e num registro operacional isso é
inaceitável. Na origem, todos leem exatamente a mesma string, com o mesmo `transmissaoId`.
A invariante fina: **transcrever os bytes que foram ao ar**, não os que foram capturados —
por isso existe um acumulador alimentado **depois** de a codificação ter sucesso
(`SessaoPtt.kt:467`).

**(d) Pré-roll de 600 ms, só em RAM, nunca persistido.** Ao pressionar, um VAD retroativo
localiza o início real da fala e transmite a partir dali — nunca de um recuo fixo. Se o
PTT não for pressionado, o conteúdo **se perde por definição**. É privacidade por
construção, não por política.

**O bloqueador que a bateria de caos achou, e como ele foi resolvido.** Um agente
interrompido por uma transmissão P1 continuava no fio por **232 quadros — 4 640 ms** de
vozes sobrepostas, porque só descobria na renovação seguinte (`RENOVAR_MS = 5_000L`). Duas
saídas foram descartadas com motivo:

- **Encurtar a renovação** para 200 ms fecha a janela e cobra **5 RPCs por segundo por
  locutor**, o turno inteiro, num aparelho que precisa durar 12 h.
- **Cortar no próprio anúncio** é instantâneo e gratuito — e dá a qualquer cliente forjado
  o poder de **calar quem quiser** anunciando P1 sem ter piso. É a mesma classe de negação
  de serviço que a proibição de identidade por parâmetro existe para fechar.

O que ficou: o **anúncio de fala, que já era difundido para o grupo inteiro e ninguém
lia**, dispara uma confirmação imediata com o árbitro, e **é a resposta do árbitro que
corta**. Resultado: **4 640 ms → 60 ms** (232 quadros → 3). Custo: um RPC, só quando uma
P1 aparece. Degradação declarada: se o anúncio se perder, a janela volta a ser o intervalo
de renovação — e há teste medindo as duas corridas.

**O que custou.**

- **As três recusas de canal têm falas diferentes porque pedem gestos opostos:** canal
  ocupado se resolve **esperando**; pedido sem resposta se resolve **andando** até pegar
  sinal; recusa de autorização se resolve conferindo credencial. O earcon é o mesmo — a
  categoria é a mesma — e o que separa é a causa curta. Antes, rede caída se disfarçava de
  `Ocupado`, e um 4xx do PostgREST mandava o agente procurar torre por um problema de token.
- **Piso local se declara em voz na abertura** (*"Sem servidor. Piso local."*): sem sessão
  não há arbitragem, e dois aparelhos podem se achar donos do mesmo canal. A degradação
  fica — o rádio precisa funcionar em túnel —, o silêncio sobre ela não. Custo declarado:
  essa fala abre janela de supressão de ~2 s do detector de ativação.
- **A marca de "fala cortada" vive em disco LOCAL e não vai ao servidor.** É de propósito:
  o corte é **conclusão do receptor**, e dois receptores da mesma transmissão discordam com
  razão — quem está no túnel conclui `CORTADA_NO_MEIO`, quem está no descampado recebeu
  tudo. Escrever no servidor faria a condição de rede de um aparelho virar fato global.
- **A emergência (`P1_EMERGENCIA`) não é transmissível pela origem.** O transporte a
  respeita, o piso preempta por ela e o receptor toca o earcon certo, mas nenhum caminho em
  `src/main` transmite com ela — `RadioViewModel.aoPressionar` cai no default `P2_APOIO`. É
  capacidade construída e **inalcançável**, listada em
  [`docs/CAPACIDADES_DESLIGADAS.md`](docs/CAPACIDADES_DESLIGADAS.md).

**Como está construído.**

- `app/src/main/kotlin/com/claryon/field/radio/RadioTatico.kt:83` — orquestra.
- `core-net/src/main/kotlin/com/claryon/net/SessaoPtt.kt:129` — a sessão de transmissão.
  `DURACAO_MAXIMA_MS = 30_000L` (`:587`), `RENOVAR_MS = 5_000L` (`:588`),
  `ENCERRAMENTO_MS = 2_000L` (`:591`). O teto de 30 s é contado **desde o toque**, com
  `withTimeout` descontando o já decorrido — não "30 s de áudio ao vivo".
- **A transcrição é disparada no `finally`** (`SessaoPtt.kt:386`), **fora** do
  `withTimeoutOrNull` e dentro de `NonCancellable`, porque soltar o PTT **é** cancelamento
  e uma transcrição disparada ali morreria antes de começar. Cadeia:
  `SessaoPtt` → `RadioTatico.transcreverEDifundir` (`:685`) →
  `transporte.transcrever(transmissaoId, texto)` (`:694`) →
  `core-net/.../Transmissao.kt:192`. O STT é ligado em `RadioViewModel.kt:473`/`:710`.
- Transporte: `core-net/.../TransporteRealtime.kt:54` (WebSocket Phoenix), com **5 eventos
  de aplicação** em `ProtocoloRealtime.kt`: `fala.anuncio`, `fala.quadro`, `fala.quadros`,
  `fala.fim`, `fala.transcricao`.
- Agrupamento: `AgrupadorDeQuadros.kt:42` — 3 quadros de 20 ms por mensagem, porque o
  envelope JSON/base64 do Realtime custa ~300 B para ~30 B de Opus útil.
- Arbitragem de piso: `ClientesDePiso.kt:21` (local) e `:68` (remoto, RPCs `pedir_canal` /
  `renovar_canal` / `liberar_canal`). A escolha é em runtime — sem sessão, cai no local
  (`RadioViewModel.kt:1226-1229`).
- **`liberar` devolve resultado tipado**, não `Unit` nem `Boolean`: `false` do
  `liberar_canal` significa "nada foi apagado", que é bom; o que é ruim é **ausência de
  resposta**. Daí `ResultadoDaLiberacao` com dois casos — um `liberar_canal` perdido deixa
  a guarnição muda até o TTL de 30 s.
- Registro durável: `RegistroDeTransmissao.kt:86` chama a Edge Function
  `functions/v1/transmit`. **A Edge Function `ack` não tem chamador Kotlin**, então a
  tabela `deliveries` nunca recebe `INSERT` — declarado em
  [`docs/CAPACIDADES_DESLIGADAS.md`](docs/CAPACIDADES_DESLIGADAS.md).

**Ordem que não pode ser invertida** (custou um defeito real): em `RadioViewModel.abrir`, o
registro do fio de voz vem **antes** de `audio.iniciar()`. Estava depois do `return` da
falha de rota, e com óculos não pareados, fone ausente ou emulador ele nunca rodava —
*"Claryon, guarnição 3 na escuta"* era recusado sem motivo, com detector, whisper e
roteador funcionando. Travado por `FioDeVozSemRotaDeAudioTest`.

---

### 6.8 Geolocalização e mapa

**O problema.** A guarnição precisa saber onde os pares estão. E a forma óbvia de resolver
isso — enviar a coordenada de cada um para o aparelho de todos — cria um sistema de
vigilância de policiais.

**Os caminhos estudados.**

1. **Servidor devolve coordenadas; o cliente calcula distância e desenha.**
2. **Servidor devolve grandezas** — distância e rumo — e nunca a coordenada de terceiro.
3. Para a consulta por voz: **filtrar no cliente** × **calcular no servidor**.

**A decisão e o porquê.** Caminho 2, e a razão é que o caminho 1 não tem como ser
verificado: **filtrar no cliente exigiria entregar a coordenada primeiro**, e a garantia
viraria promessa. `public.consultar_posicao` calcula no banco e devolve distância, rumo e
estado.

Uma segunda decisão fecha o buraco que sobra: **nenhuma função exposta aceita a identidade
de quem pergunta como parâmetro.** Com `solicitante_id` na assinatura, um agente legítimo
varre os pares do talk group e **trilatera a posição absoluta de qualquer um usando só
distâncias** — o dado que a API pode devolver. O solicitante vem do JWT.
`private.posicao_relativa` aceita o parâmetro, e é exatamente por isso que ela fica em
`private` e **não tem chamador Kotlin** (verificado).

Isso já foi violado uma vez, na borda: uma Edge Function `locate.ts` aceitava
`solicitante_id` do corpo e chamava a função privada com `service_role`, reabrindo na borda
a trilateração que a migração `0006` tinha fechado no banco. A função foi **apagada**, e os
comentários que explicam por quê são o que impede alguém de reintroduzi-la.

**Três regras de produto que são requisito de segurança, não polimento.**

- **Reciprocidade:** dentro do talk group, quem vê é visto. Não existe modo de observar sem
  ser observado. Assimetria de visibilidade entre pares é vigilância; simetria é
  coordenação.
- **Marcador esmaece após 2 min sem atualização.** Mostrar posição velha como atual é pior
  que não mostrar.
- **Assinatura do canal de posições só enquanto a tela do mapa está visível.** Difundir
  posição de todos para todos o turno inteiro drena bateria para uma tela fechada 95% do
  tempo.

**O que a medição encontrou, e é o achado deste bloco.** Eram **0 linhas persistidas em
20 minutos**, com o Android confirmando entrega. Seis defeitos, e o principal era
inalcançabilidade: o `minDistance` de `requestLocationUpdates` **suprime a entrega** —
*"the potential location update will not occur"*, AOSP —, então agente **parado** não
recebia callback e a linha do batimento nunca rodava. Medido no emulador, parado, 3,5 min:
**5 publicações com o conserto, 1 sem**.

Outros três, todos com contra-teste:

- **Idade de posição é uma DURAÇÃO, não um instante.** O cliente manda a duração derivada
  de `elapsedRealtimeNanos`; o servidor carimba com `now() - greatest(0, idade)`, que **não
  produz futuro por construção**. Relógio de par adiantado geraria idade negativa, lida como
  "recentíssima" pela política de obsolescência — o pior erro possível.
- **Porta de correção com válvula.** `PortaDeCorrecao`
  (`core-agent/.../PortaDeCorrecao.kt:64`) recusa salto implausível — mas com **válvula de
  3 recusas**, sem a qual um salto verdadeiro congela o marcador para sempre.
- **`EscolhaDeCorrecao` (`:216`) escolhe a MELHOR correção, não a mais nova** — idade antes
  de precisão.

**O que custou.** Não dá para desenhar rota até o par, e é preciso assumir que duas medidas
trilateram — mitigado no banco (arredondamento de distância na `0021`, sem dump em massa,
sem consulta a par arbitrário). E o mapa **em branco com a posição própria** virou o estado
correto quando ninguém publicou: antes ele escrevia *"ninguém publicando"* quando a culpa
era do portador. Hoje distingue *não publiquei* de *ninguém publicou* de *não estou
recebendo*.

**Retenção em duas camadas, e a regra de sequenciamento que a acompanha.** A trilha
contínua (`private.trilha_de_posicao`, particionada por dia, sem `GRANT` para
`authenticated`, sem índice geográfico) só entra **na mesma sessão** em que entram a porta
de turno, o job de retenção e o log de acesso. Criar a trilha sem os três controles deixaria
o sistema estritamente pior. Fecha o conjunto: `publicar_posicao` **recusa escrita fora de
turno aberto**, e há encerramento automático por inatividade — sem ele, "esqueci de
encerrar" vira 24 h de rastreamento e a defesa jurídica inteira cai.

**Como está construído.**

- Dono único da escrita: `app/src/main/kotlin/com/claryon/field/local/ColetorDePosicao.kt:97`.
  O mapa **não** publica posição.
- Mapa: `app/.../ui/componentes/MapaDeRuas.kt:67` sobre **MapLibre 11.11.0**
  (BSD-2-Clause, sem chave de API, sem telemetria, **sem depender do Google Play
  Services** — que o aparelho da corporação pode não ter), com tiles do **OpenFreeMap**
  (`MapaDeRuas.kt:518`), livres e sem cota. MapTiler e Mapbox foram descartados por
  exigirem chave e terem cota: dependência operacional que um piloto de segurança pública
  não deveria ter. A versão `11.x` e não a `13.x` porque a 13 embarca um `prefab/` com
  `.so` não-strippada de 162 MB.
- RPCs com chamador Kotlin verificado: `publicar_posicao`
  (`core-net/.../PosicaoSupabase.kt:119`), `consultar_posicao`
  (`core-net/.../ConsultaDePosicao.kt:91`), `posicoes_do_grupo`
  (`core-net/.../HistoricoDoCanal.kt:205`), `rastro_do_par` (`:289`), `quem_me_consultou`
  (`:265`), `abrir_mapa`/`fechar_mapa` (`:175`/`:179`), `iniciar_turno`/`encerrar_turno`
  (`PosicaoSupabase.kt:45`/`:48`).
- **`public.quem_me_consultou()` converte conformidade em característica de produto:** o
  titular vê quem o consultou (`app/.../ui/QuemMeConsultouViewModel.kt:19`).
- **Defeito aberto:** se **apenas** os tiles forem bloqueados (Wi-Fi de evento com filtro
  de DNS, Supabase acessível), `OnStyleLoaded` nunca dispara e o mapa fica **preto e
  mudo** — sem marcador e sem mensagem. É a única violação viva de "falha nunca é
  silêncio", e está declarada como tal.

---

### 6.9 Cofre de evidência e cadeia de custódia

**O problema.** Áudio gravado numa ocorrência é prova. Prova que pode ser adulterada sem
deixar rastro não vale nada — e prova truncada por um processo morto é indistinguível de
prova apagada de propósito, se ninguém tiver desenhado a diferença.

**Os caminhos estudados.**

1. **Arquivo simples em `filesDir`.** Legível por quem tiver acesso ao aparelho.
2. **Um `EncryptedFile` por quadro de 20 ms**, com o manifesto reescrito a cada quadro.
3. **Segmentos maiores**, cada um cifrado, com manifesto *append-only* e cadeia de hash.

**A decisão e o porquê.** Caminho 3. O caminho 2 chegou a existir e foi medido como
insustentável: `EncryptedFile.Builder.build()` deriva a chave a cada chamada, e reescrever
o manifesto por quadro é 50 escritas por segundo. Um detalhe descoberto no bytecode de
`security-crypto` decidiu parte do desenho: o **nome do arquivo entra como *associated
data*** do AES-GCM, o que amarra cada segmento ao seu lugar na sequência.

O manifesto é **TSV *append-only* com `fd.sync()` por linha**, não um JSON reescrito: um
formato que se reescreve inteiro é um formato que se perde inteiro quando o processo morre
no meio.

**A cadeia de hash e o detalhe que a torna útil em juízo.**
`sha256(hash_anterior ‖ bytes_do_segmento)` — adulterar **qualquer byte** quebra a cadeia a
partir daquele ponto, e a verificação devolve **o índice do primeiro segmento adulterado**,
não um booleano. E ela percorre **o maior** dos dois tamanhos (segmentos × hashes) de
propósito: iterar só sobre os segmentos faria uma cadeia **truncada no fim** — atacante
apaga os últimos arquivos e deixa o manifesto intacto — passar como íntegra.

Sobre isso existe uma **âncora de fim** assinada com HMAC-SHA256 e chave no
`AndroidKeyStore`, que separa três coisas que pareceriam iguais: gravação **em andamento**,
processo **morto antes de fechar**, e **truncamento deliberado**.

**O que custou, e está escrito na própria tela.**

- **A tela CONFERE e não EXPORTA.** Tirar segmentos e manifesto do aparelho continua
  exigindo `adb`. Verificado: não há `FileProvider`, `ACTION_SEND`, zip ou qualquer caminho
  de exportação em `core-evidence/src/main`.
- **`Confere` não é *inforjável*.** A ressalva sobre o R8 aparece na tela com as mesmas
  palavras-chave do relatório de impacto, com teste que **reprova o build** se ela sumir de
  qualquer um dos dois lados.
- **O cofre não guarda o rádio.** Só `Intent.IniciarGravacao` o alimenta
  (`ClaryonIntentExecutor.kt:291`); **nenhum caminho do PTT escreve nele**. É defeito
  conhecido nº 9 do [`ESTADO.md`](ESTADO.md).

**Como está construído.**

- `core-evidence/src/main/kotlin/com/claryon/evidence/EvidenceVault.kt:25` — o contrato
  (`beginRecording` / `append` / `finalize`).
- `core-evidence/src/main/kotlin/com/claryon/evidence/EncryptedEvidenceVault.kt:99` —
  `EncryptedFile` com `AES256_GCM_HKDF_4KB` (`:576`) sobre `MasterKey` com esquema
  `AES256_GCM` (`:108`), chave no Android Keystore.
- `core-evidence/src/main/kotlin/com/claryon/evidence/HashChain.kt:14` — puro
  `java.security`, testável em JVM. `verificar` em `:33`.
- `core-evidence/src/main/kotlin/com/claryon/evidence/Manifesto.kt:70` (versão 3,
  `manifest.txt`) e `AncoraDeFim.kt:57` + `AssinadorDoKeystore.kt:47`.
- Perícia como caminho de produto: `TelaDePerfil.kt:122` ("Periciar a custódia") →
  `MainActivity.kt:357` → `TelaDePericia.kt:61` → `PericiaViewModel.kt:72` →
  `EncryptedEvidenceVault.periciar()` (`:375`).
- **A perícia usa o cofre DO PROCESSO**, e por isso `CerebroDoCopiloto.cofre` é público:
  uma instância nova leria o mesmo diretório e daria os mesmos hashes, mas o mapa de sessões
  abertas é do objeto — a ocorrência **em curso** apareceria na tela como custódia
  interrompida.
- **A tela é acromática.** Custódia quebrada é grave e a tentação de pintá-la de vermelho é
  forte; a paleta reserva cor a "no ar" e a prioridade. Três níveis de tinta, e o orçamento
  cromático do projeto não subiu.

---

### 6.10 Consulta externa (Overpass/OSM)

**O problema, medido.** O corpus embarcado são **cinco leis federais, 1817 trechos**. Isso
responde bem o que está nessas cinco e **não responde nada fora delas** — e a lista do que
fica de fora é o trabalho diário do policial: *"como destravar minha Glock"* (manual de
fabricante, obra protegida), *"qual o hospital mais próximo"* (dado geoespacial, não
normativo), lei estadual, portaria de corporação.

**Os caminhos estudados.**

1. **Manter a proibição inteira** e recusar toda pergunta fora do corpus.
2. **Busca textual na web**, com um modelo de linguagem formatando a resposta.
3. **Consulta estruturada por categoria**, sem texto livre e sem modelo no caminho.
4. **Embarcar dados geoespaciais da região no APK**, para funcionar offline.

**A decisão e o porquê.** O §2 do `CLAUDE.md` é **regra nossa, não do edital** — o §8.1 do
edital permite explicitamente modelo em nuvem. A proibição foi decisão de projeto, e o dono
do projeto **a revogou em parte em 22/08**, sob condições escritas em
[`specs/consulta-externa.spec.md`](specs/consulta-externa.spec.md). O que passa a ser
permitido é **consulta textual derivada**; o que continua proibido, sem exceção: áudio,
frame, transcrição literal, e qualquer dado de terceiro, posição de par, placa ou
identificador de agente.

A cascata é:

```
pergunta
  ├─ 1. LOCAL, sempre primeiro    corpus · base veicular · posição da guarnição
  ├─ 2. EXTERNA ESTRUTURADA       geoespacial (OSM/Overpass)   ← construído
  ├─ 3. EXTERNA TEXTUAL           busca com trecho como fonte  ← NÃO existe
  └─ 4. RECUSA FALADA COM MOTIVO  comportamento de hoje, preservado inteiro
```

**Local antes de tudo**, porque offline é o caso normal em campo: o relato da PMERJ que
fundamenta este produto descreve região onde *"rádio digital aqui não funciona"*. Uma
cascata que começasse pela rede falharia exatamente ali.

**Estruturada antes de textual**, porque *"hospital mais próximo"* tem resposta exata —
nome, endereço, distância. Passá-la por um modelo de linguagem troca dado por prosa e abre
espaço para endereço alucinado. É como os assistentes grandes fazem: o modelo reconhece a
intenção e **chama a ferramenta**; ele formata, não sabe.

**O degrau 3 não foi construído, e o motivo é operacional:** busca textual exige chave, e
**chave em APK é chave vazada**; intermediar pelo Supabase acrescenta um salto e uma
dependência na hora da demonstração.

**O caminho 4 foi adiado** e registrado como primeiro candidato a v2: os dados da região no
APK fariam a camada funcionar sem rede, e custam tamanho num APK que já tem 384 MB.

**A decisão de produto mais fina deste bloco: a fala NÃO se rebaixa.** A primeira versão da
spec mandava dizer *"segundo a internet: …"*. Está errado, e o teto de 7 palavras torna o
motivo concreto: **gastar palavra falada com ressalva é caro**, e ressalva repetida vira
ruído que o agente aprende a ignorar. O sinal de credibilidade **já existe, e é a citação**
— corpus local diz *"Artigo 33 da Lei 11.343"*; fonte externa responde **sem citação**. A
ausência de citação é o sinal, e não custa sílaba nenhuma.

**E isto não é uma dependência: é um andaime.** Cada pergunta que a web responde é uma
pergunta que o corpus **deveria** responder, e o registro de uso é o mapa de para onde o
corpus precisa crescer — construído com uso real, não com palpite. A curva pretendida:
`v1` maioria fora do corpus → rede → ~2 s, e falha sem sinal; `vN` maioria já no corpus →
local → **618 ms**, e funciona sem sinal.

**O que custou.**

- **Vocabulário fechadíssimo:** três categorias, e só. `enum class LugarProcurado`
  (`core-net/.../ConsultaGeoespacial.kt:36`) — hospital, delegacia, posto de saúde.
- **Prazo de 2 s, e estourar é RECUSA, não espera** (`PRAZO_PADRAO_MS = 2_000L`, `:388`).
- **Sem geoespacial offline na v1.**
- **O registro de uso morre com o processo**: vive em RAM com teto de 50 e vai ao
  `logcat`. Sem arquivo, o andaime não funciona. Declarado como pendência.
- **A tela que a spec promete não existe**: o `StateFlow` está publicado em
  `DiarioDaConsultaExterna` e **nenhuma tela o observa**.

**Como a privacidade é garantida — e é por tipo, não por revisão de código.**

- **`HigieneDaConsulta` não reescreve texto: ela inspeciona e VETA.** Não existe
  `higienizar(texto): String` (`core-agent/.../HigieneDaConsulta.kt:100`). O que existe é
  `vazamentos(texto): Set<Vazamento>` (`:123`) detectando placa, matrícula/documento
  (incluindo `Regex("\\d{4,}")`) e **qualquer dígito** de número de endereço.
- O que sai pela rede é `class ConsultaHigienizada` de **construtor privado** (`:43`),
  construível **só a partir de `CategoriaDeLugar`**. Um `String` cru como parâmetro da
  camada externa seria a porta por onde a transcrição vazaria.
- A query Overpass é **montada só de constantes e de números**
  (`ConsultaGeoespacial.kt:291`): um filtro literal do enum, um raio `Int`, e um centro
  arredondado. **Não há por onde a fala entrar.**
- **A coordenada que sai é a MINHA, a 4 casas decimais** (`%.4f` com `Locale.ROOT`, `:421`
  — vírgula do pt-BR quebraria `around:R,LAT,LON`). Posição de par **nunca** sai.
- Isso é verificado lendo **o corpo HTTP num socket real**, com régua de lista fechada
  (`ConsultaGeoespacialTest`), mais um teste que envenena a transcrição e exige que ela não
  atravesse a fronteira de rede.
- **Dois registros separados, de propósito:** auditoria com carimbo preciso
  (`RegistroDeAuditoria.kt`) e uso com granularidade **de dia**
  (`RegistroDeUso.kt:69`, `dia: String` em `AAAA-MM-DD`). Sem agente, sem posição e sem hora
  exata, duas perguntas do mesmo turno não são ligáveis entre si — é a propriedade que
  separa "estatística de uso" de "histórico de um policial".

**Como está construído.** `app/src/main/kotlin/com/claryon/field/agent/LugarPelaRede.kt:53`
(`procurar` em `:82`), instanciado em `CopilotoDoAgente.kt:170` e consumido pelo executor
(`ClaryonIntentExecutor.kt:134` e `:463`). `ChamadorDaConsultaExternaTest` lê o código de
`src/main` e **reprova se a linha de ligação sumir** — é o único teste que consegue, porque
o valor padrão do parâmetro **recusa em silêncio** de propósito.

> Dois achados que só apareceram ao **ligar** a capacidade, e que ilustram por que este
> projeto insiste na régua do §3: (1) `BuscaDeLugar.SemPosicaoPropria` precisou existir —
> *"o hospital mais próximo"* é pergunta relativa e sem correção de GPS não tem centro;
> colapsá-la em "sem rede" mandaria o agente andar atrás de sinal que ele já tem. (2) O
> termo `"posto de saude"` continha um `"de"` que casava com a letra D no filtro de higiene,
> a fábrica lançava, e o agente que perguntasse por um posto **nunca teria resposta** — e o
> KDoc do filtro **afirmava** que esse caso estava coberto.

---

### 6.11 Etapa A — norma on-device (RAG extrativo)

**O problema.** Responder pergunta de norma no aparelho, sem rede, sem alucinar, e sem que
o agente precise conferir numa tela que não existe.

**Os caminhos estudados.**

1. **Embedder + índice vetorial**, que é o que o `ROADMAP.md` previa.
2. **Índice lexical BM25 em Kotlin puro.**
3. Para a resposta falada: **ler o artigo verbatim** × **citar o documento**.

**A decisão e o porquê.** Caminho 2, e a medição foi feita **antes** de escrever o
caminho 1. Um índice lexical custa **0 MB de modelo**, **112 ms** para indexar os 1744
trechos e **913 µs** por busca; o embedder custaria uma ponte JNI nova em C para o ONNX
Runtime — não há API Java dele neste projeto, o acesso é por `dlopen`/`dlsym` — mais
centenas de MB de pesos. **O baseline barato vem primeiro**, e o contrato
`interface BaseDeConhecimento` existe justamente para o mecanismo ser trocável: quando o
embedder entrar, ele troca uma classe e nada mais.

**A decisão que mais importa, e ela não é o índice: é a régua de confiança.** O escore BM25
normalizado responde *"quanto da pergunta casou"*. É a informação errada para decidir se
**fala**: um artigo errado que divide muitas palavras com a pergunta casa tanto quanto o
certo. A régua que decide precisa responder outra coisa — *o primeiro colocado destacou-se
dos outros?* Daí a confiança ser a **média geométrica** entre "casou bem" (`s₀`) e "ganhou
do segundo" (`s₀ − s₁`).

Medido sobre 88 perguntas mais 12 fora do corpus, sob duas restrições (zero pergunta fora
do corpus respondida, precisão ≥ 75%):

| régua | melhor limiar | responde certo | erra | precisão |
|---|---|---|---|---|
| escore cru | 0,85 | 6 | 2 | 75,0% |
| **confiança** | 0,24 | **24** | 7 | 77,4% |

**Quatro vezes mais perguntas respondidas com a mesma segurança.** E a AUC sozinha teria
enganado — 0,646 contra 0,698, diferença que pareceria pequena demais para justificar a
fórmula. É a curva, não o resumo dela, que mostra o ganho.

O limiar de produção é **0,30**, medido e não escolhido
(`BaseDeConhecimentoLexical.LIMIAR_MEDIDO`, `core-knowledge/.../BaseDeConhecimentoLexical.kt:128`):
**18 respostas certas, 5 erradas, 0 fora do corpus, 78,3% de precisão**. Em 0,25 a folga
até o primeiro "fora do corpus" seria 0,012 — coincidência com nome de margem; em 0,30 é
0,062.

**Abaixo do limiar é RECUSA, nunca "o mais próximo".** Um índice sempre tem um vizinho mais
próximo: perguntando sobre uma pistola emperrada, algum artigo do CTB vai ser o menos
distante, e a distância **não aparece na fala**. O agente ouviria um artigo de lei, com
número, dito com a mesma naturalidade de um acerto, e não tem display para conferir.
*"Não sei"* devolve a decisão para o agente, que sabe o que fazer sem o copiloto; o trecho
errado a toma no lugar dele.

**A pergunta-bandeira do roadmap não é respondida, e a recusa está CERTA.** *"Minha Glock
19 emperrou"* recebe confiança **0,070** e é recusada. Manejo de arma é manual de
fabricante; o que está embarcado é lei federal. O corpus responde *"posso apreender a moto
sem placa"*, não *"minha arma emperrou"*. Isso não é defeito do índice: é o aceite pedindo
prova com uma pergunta fora do domínio embarcado — e é o que motivou a §6.10.

**O que custou.** A fala é a **citação**, não o texto: `ConsultaDeNorma.consultar` devolve
`Pair<citacao, norma>` e o agente ouve *"Art. 306, Lei 9.503"* — quatro palavras. O
artigo 306 do CTB tem **71 palavras**, e não há leitura em que o teto de 7 palavras e a
promessa de "ler verbatim" estejam certos ao mesmo tempo. A exceção está proposta em
[`specs/leitura-de-norma.spec.md`](specs/leitura-de-norma.spec.md) e **espera decisão
humana** — com duas proteções que precisariam ser resolvidas, não removidas: o agente não
pode pular o que está sendo dito, e fala longa atrasa P1.

**Como está construído.**

- Corpus: `corpus/trechos.jsonl` — **1817 linhas**, das quais 73 marcadas `revogado: true`
  são descartadas do índice, restando **1744**. Distribuição: CPP 851 · CP 434 · CTB 391 ·
  Lei de Drogas 100 · Estatuto do Desarmamento 41.
- **Licença resolvida antes do código:** Lei 9.610/1998, art. 8º, IV — **textos de lei não
  são objeto de proteção autoral**, então legislação federal pode ir dentro do APK. POP de
  corporação é outra coisa e não entra até alguém de lá liberar
  ([`corpus/PROCEDENCIA.md`](corpus/PROCEDENCIA.md)).
- `core-knowledge/src/main/kotlin/com/claryon/knowledge/IndiceLexical.kt:61` — BM25 com
  `k1 = 1.2`, `b = 0.5`, Kotlin puro, zero dependência.
- `core-knowledge/.../PortaDoConhecimento.kt:25` — pura e sem estado; recebe o que o índice
  achou e decide.
- Costura em `app`: `app/src/main/kotlin/com/claryon/field/norma/ConsultaDeNorma.kt`,
  aquecida no boot (`ClaryonApp.kt:64`). Este arquivo **existe para ser a fronteira**: ele
  sabe o que é um `Trecho` e não conhece `Intent`, `ActionOutcome` nem `IntentExecutor` —
  e o executor recebe `suspend (String) -> Pair<String, String>?`, sem vocabulário para
  falar de conhecimento. Nenhum dos dois consegue, sozinho, transformar norma em ação.

> **Uma armadilha de extração que vale como lição.** A primeira contagem do CPP deu 242
> artigos onde ele tem 811. O download estava completo; a régua é que estava errada — ela
> não trocava `&nbsp;` por espaço, e `Art\.\s*\d+` não casa com entidade HTML. **Metade dos
> artigos usa `&nbsp;`.** Um extrator com o mesmo descuido produziria um corpus pela
> metade, e o copiloto diria "não encontrei" sobre norma vigente — que é pior que não ter a
> norma, porque o agente conclui que ela não se aplica.

---

### 6.12 Etapa B — o LLM local, embarcado e desligado

> **Esta seção descreve uma capacidade que está no aparelho, carrega no boot, e NÃO é
> chamada.** `RedacaoDoCopiloto.redigir` tem **zero chamadores em `src/main`** — verificado
> por `grep`, e há um teste que **reprova o build** se alguém a ligar. Isso é uma decisão de
> engenharia fundamentada em medição, e está aqui pelo mesmo motivo que tudo o mais: porque
> é o que existe.

**O problema.** A Etapa A responde com a **citação** do artigo. A Etapa B seria a camada de
redação por cima: o modelo recebe o trecho recuperado e o reescreve em fala curta.

**Os caminhos estudados.**

1. **Nenhum LLM.** A Etapa A já responde, e é entregável sozinha.
2. **LLM como redator**, sobre o trecho recuperado, com um guarda de lastro conferindo se a
   saída se apoia na fonte. (Pista 1.)
3. **Extração por gramática** — restringir a geração à gramática da própria fonte, em vez
   de gerar livre e conferir depois. (Pista 2.)
4. Para o motor: **llama.cpp** × MediaPipe LLM Inference × ONNX Runtime GenAI.
5. Para os pesos: **Llama 3.2 1B** × **Qwen2.5-1.5B** × Qwen3-1.7B × modelos de 3B.

**A decisão sobre o motor: llama.cpp** (decisão humana, 20/08; integrado em 21/08 em
`core-llm`). Build de fonte, estático, com uma divergência deliberada do exemplo oficial:
**`-DBUILD_SHARED_LIBS=OFF`** em vez de `ON`. O exemplo publica `libggml*.so` e usa
`GGML_BACKEND_DL`; aqui isso reintroduziria uma colisão de símbolos num `lib/arm64-v8a/`
que é diretório plano — o whisper.cpp e o llama.cpp trazem cópias do ggml de **revisões
diferentes**, e sem esconder a visibilidade uma chamada do llama poderia ligar na
implementação do whisper pela ordem de carga. Quebra em runtime, às vezes.

Conferido **no artefato**, não no papel: `libclaryonllm.so` exporta 4 símbolos, todos
`Java_com_claryon_llm_NativoDoRedator_*`; **zero** símbolo `ggml_*`/`llama_*` exportado; e
`unzip -l` no APK debug e release dá **zero libggml, zero libllama**. Custo:
**+8 650 901 B (+2,3%)** no APK release.

**A decisão sobre os pesos: `Qwen2.5-1.5B-Instruct-Q4_K_M`** (Apache-2.0,
**986 048 768 B**), decisão humana de 22/08, substituindo `Llama-3.2-1B-Instruct-Q4_K_M`.
**O motor NÃO mudou** — confundir as duas coisas seria caro: "tirar o Llama" aqui significa
tirar os **pesos** da Meta, não o llama.cpp, que roda GGUF de qualquer família. Três
motivos, em ordem de peso:

1. **Cobertura de português nos pesos.** É a hipótese que a medição deixou apontada e nunca
   testou: das 20 perguntas do banco de abordagem, **1 a 2** produziram resposta utilizável,
   e os defeitos lidos um a um eram de **compreensão de instrução em português** —
   meta-comentário, preâmbulo, número de artigo, tudo proibido no `system` em português.
2. **Licença e política de uso.** Conferido na API do Hugging Face, não de memória:
   `meta-llama/Llama-3.2-1B-Instruct` declara `license: llama3.2`;
   `Qwen/Qwen2.5-1.5B-Instruct` declara `license: apache-2.0`. O caso de uso-bandeira deste
   produto — *"estou com a minha Glock emperrada"* — é exatamente o que a AUP do Llama
   alcança. Apache-2.0 não tem AUP.
3. **Tamanho.** Qwen2.5-3B e Llama-3.2-3B estouram os 4 GB do aparelho de campo somados ao
   whisper, ao Piper e ao MapLibre. 1,5B em Q4_K_M é o maior salto que cabe.

**Qwen3-1.7B foi descartado** apesar de mais novo e também Apache-2.0: é modelo de
raciocínio híbrido, emite bloco `<think>` por padrão, e desligá-lo depende de o template
embutido no GGUF aceitar `enable_thinking`. Num orçamento de 2 500 ms com teto de 7 palavras
faladas, token de raciocínio é o pior gasto possível.

**A troca custou ZERO linha de Kotlin e ZERO linha de C++, e isso foi projetado.** Três
decisões anteriores pagaram isso adiantado: o template de chat vem do próprio GGUF
(`llama_model_chat_template`, `core-llm/src/main/cpp/redator_jni.cpp:129` — Llama 3 usa
`<|start_header_id|>`, Qwen usa ChatML, e **nenhuma dessas strings existe neste
repositório**); o arquivo chama-se `redator.gguf` e não o nome do modelo; e o portão de RAM
**multiplica bytes** em vez de consultar tabela por modelo, reescalando sozinho de 1 464
para 1 787 MiB de `availMem` exigidos.

**Por que está DESLIGADA — os números.** `OrcamentoDaEtapaBNoAparelhoTest`, 22/08, emulador
arm64 API 35, **configuração de produção literal** (`nThreads=4`, `nCtx=1024`,
`maxTokens=96`, `prazoMs=2500`), sobre as 20 perguntas cuja confiança fica acima do limiar
de 0,30 — isto é, exatamente as que chegariam à Etapa B:

| | resultado |
|---|---|
| produziram texto no prazo de produção | **11 de 20** (9 ficam mudas) |
| aprovadas pelo guarda de lastro | **7** |
| **utilizáveis, por leitura humana** | **1** |
| p50 · p90 da redação | 2 510 · 2 522 ms |
| carga do modelo | 2 435 ms · PSS +**1 474 MiB** (razão **1,91×** sobre o GGUF) |

Três achados que decidem, e nenhum deles se resolve trocando de modelo:

- **`aprova` e `utilizáveis` andam em sentidos opostos.** A régua de lastro premia
  casamento lexical, e **o jeito barato de casar é copiar** — as recusas novas, lidas uma a
  uma, eram eco da pergunta e meta-comentário.
- **O guarda é cego a NEGAÇÃO.** *"NÃO DEIXOU DE observar as cautelas"* passa com lastro
  **0,78** sobre um artigo que pune quem **deixa** de observar. A régua mede se as palavras
  vêm da fonte; a inversão de sentido usa o léxico da própria fonte. Está fixado em teste
  como **limitação conhecida**, não como bug a consertar por calibração. E um modelo mais
  fluente em português constrói negação **melhor** — o buraco pode ficar mais caro, não
  menos.
- **O p50 é a PAREDE, não o modelo.** O prefill de ~500 tokens custa **1 620 a 2 550 ms**
  contra prazo de 2 500, e `llama_decode` aborta **antes de o prompt entrar**. A mesma
  formulação rendeu 14/20 e depois 4/20 só por carga de máquina.

**A regra de produto que fecha a decisão:** num aparelho sem tela, **falar a lei errada com
confiança é pior do que não falar**. A Etapa A já responde com a citação exata, verificável.
Ligar a Etapa B exigiria ainda sobrepor a regra dura das 7 palavras — o que, pelo §7 do
`CLAUDE.md`, é decisão humana, e a spec correspondente
([`specs/redacao-por-llm-na-fala.spec.md`](specs/redacao-por-llm-na-fala.spec.md))
**recomenda não ligar**.

**⚠️ E há uma honestidade a mais aqui: nada disso foi remedido no Qwen.** A troca foi feita
**sem bancada**, por decisão explícita, com o prazo em cima. **Nenhuma tabela deste
repositório é do modelo novo.** Duas coisas pioram por aritmética: o prefill (986 MB e 28
camadas contra 807 MB e 16) e o portão de RAM. Trocar o modelo mudou **a aposta**, não o
resultado — e a primeira tarefa de quem retomar a Etapa B é remedir os cinco braços de
prompt e o PSS residente.

**O custo de imagem, declarado.** Este é um hackathon da Meta, e sai da solução o modelo da
Meta. O que fica da Meta é o que o edital de fato pontua no critério de aderência ao
toolkit: os óculos Ray-Ban Meta, o DAT 0.9.0, `GlassesFacade`, HFP/SCO e a câmera. O LLM
nunca foi item de toolkit. E há leitura favorável: **trocar um modelo por outro com um
`adb push`, sem tocar Kotlin nem C++, é demonstração de que a arquitetura não está presa a
fornecedor.**

**O que ESTÁ ligado, e roda a cada boot:** a decisão de degradação
(`PoliticaDeRedacao.decidir` via `RedacaoDoCopiloto.decidirNoBoot`, chamada em
`ClaryonApp.kt:56`) e a liberação sob pressão de memória (`ClaryonApp.kt:172`). As três
recusas foram observadas no logcat do app real, não só em JUnit:

```
SEM_MODELO         · sem GGUF em filesDir (estado de fábrica)
DESLIGADO_POR_FLAG · adb shell settings put global knowledge.llm 0
APARELHO_FRACO     · RAM total abaixo do piso de 3 GB, ou sem folga de 1,90× o GGUF
```

A flag vive em `Settings.Global`, e **não** em `SharedPreferences`, porque o app não pode
mudar a própria chave: escrever exige `WRITE_SECURE_SETTINGS`, que só o shell tem.

**Como está construído.**

- `app/src/main/kotlin/com/claryon/field/norma/RedacaoDoCopiloto.kt:67` —
  `ARQUIVO_DO_MODELO = "redator.gguf"` (`:82`), `arquivoDoModelo(context)` (`:148`),
  `decidirNoBoot` (`:166`), `redigir` (`:243`, **sem chamador**), `liberar` (`:257`).
- `core-llm/src/main/kotlin/com/claryon/llm/RedatorLlamaCpp.kt:136` e a ponte
  `NativoDoRedator` (`Redator.kt:17`) sobre
  `core-llm/src/main/cpp/redator_jni.cpp` (495 linhas).
- `core-llm/src/main/kotlin/com/claryon/llm/PoliticaDeRedacao.kt:29` — **pura**, para poder
  ter contra-teste: RAM alta e baixa **têm** de decidir diferente.
- O guarda: `GuardaDaRedacao.kt:79`; a Pista 2 (extração por gramática):
  `GramaticaDaFonte.kt:92`.
- A trava: `app/src/test/kotlin/com/claryon/field/norma/ChamadorDaRedacaoTest.kt:104`
  (`aRedacaoContinuaSemChamador`) varre `app/src/main` e `core-*/src/main`, exige que as
  três declarações existam (controle positivo) e que o mapa de chamadores esteja **vazio**.

> **Uma armadilha de medição que este bloco pagou duas vezes.** A primeira medição do
> prefill deu **5 a 6 tokens/s**. Causa lida em `compile_commands.json`: `ggml-cpu.c`,
> `quants.c` e `llama-model.cpp` saíam com `-g` e **nenhum `-O`, nenhum `-DNDEBUG`** — o
> AGP passa `CMAKE_BUILD_TYPE=Debug` na variante debug e o llama.cpp inteiro herda. Com
> `-DCMAKE_BUILD_TYPE=Release`, no mesmo emulador: **~280 tok/s de prefill, ~35× mais
> rápido**, e o `.so` caiu de 8,08 MB para 4,05 MB. É a mesma família de defeito de um
> achado anterior no `core-voice` (*"o STT leva 14,9 s"* descrevendo código que o produto
> não executa).

---

## 7. O que existe no código e não roda

Pela régua do §3, capacidade sem chamador alcançável é **escrita**, não construída. A lista
completa, com o motivo de cada item, está em
[`docs/CAPACIDADES_DESLIGADAS.md`](docs/CAPACIDADES_DESLIGADAS.md). O resumo:

**Desligado por decisão registrada** — ligar seria regressão ou exigiria decisão humana
sobre regra dura:

| Item | Motivo |
|---|---|
| `RedacaoDoCopiloto.redigir` (Etapa B) | §6.12 — 1 utilizável em 20; guarda cego a negação; teste reprova o build se ligarem |
| `EnergyVoiceActivityDetector` | substituído pelo Silero; fica por ser o único VAD em Kotlin puro, que sustenta testes de JVM sem `.so` |
| `PROMPT_DE_DOMINIO` do whisper | medido: o prior custa **1,8 ponto de WER** |
| `RotaSustentada.soltarAgora`, `liberarTudo` | derrubavam a rota por baixo do `AudioRecord` do rádio, que é dono de processo |
| `GlassesFacade.capturePhoto` | §6.6 — o caminho de produção é `withCamera`; é decisão de produto, não de engenharia |

**Desligado por descuido** — é aqui que dói, e está listado porque esconder seria repetir o
defeito de origem:

| Item | Consequência |
|---|---|
| Edge Function `ack` sem chamador Kotlin | `deliveries` nunca recebe `INSERT`; *"N unidades receberam"* seria contagem sobre tabela vazia |
| `P1_EMERGENCIA` não é emitida por nenhum caminho | a preempção de emergência está construída e é **inalcançável** |
| `SyncManager` / `SupabaseSyncGateway` sem chamador | `PedirApoio`, `Emergencia` e `AlertarOcorrencia` sempre viram `Despacho.Enfileirada` e não saem do aparelho |
| O cofre não recebe o rádio | §6.9 |
| `DiagnosticsScreen` nunca é composta | o painel de debug é inalcançável **mesmo no APK de debug** |
| `AudioCaptureException.codigo` não é lido | `ERROR_DEAD_OBJECT` (o HFP caiu) e `ERROR_INVALID_OPERATION` soam igual — ou seja, não soam |

**Dívida de empacotamento declarada:** `GlassesAudioRoute.paraTesteSomente` é pública, está
em `src/main` e vai no AAR de release. Isso contradiz uma decisão já tomada e verificada
por `javap`, que mandou a rota falsa para `src/debug`. Hoje a garantia de §6.1 depende, para
esse símbolo, de **convenção de nome** e não do compilador.

---

## 8. O que não foi medido

Coerente com a regra de não descrever capacidade não verificada.

**Nada foi medido em óculos reais.** A equipe não tocará nos Ray-Ban Meta antes de 18/09.
Todo o trabalho de HFP/SCO, câmera e ciclo de vida de stream foi feito às cegas, e a
mitigação foi **declarada, não improvisada**: `MockDeviceKit` para os caminhos de erro do
SDK, fone Bluetooth HFP como bancada real de rota de áudio, e uma lista fechada do que só
se mede com hardware em
[`docs/VERIFICACOES_COM_HARDWARE.md`](docs/VERIFICACOES_COM_HARDWARE.md).

**Todos os números deste README são de emulador arm64 API 35**, em Apple Silicon. Isso
importa em dois sentidos e nos dois está dito: as cenas de OCR são sintéticas e mais simples
que uma rua; e o emulador **não tem rádio Bluetooth**, então a parcela de latência da saída
de áudio (40–150 ms) e todo o comportamento do perfil de voz são propriedades de hardware e
firmware que nenhum software reproduz. **O MockDeviceKit também não simula áudio.**

Lacunas nominais:

1. **Recall da palavra de ativação por HFP.** O número que existe (3 de 4 locutores) é do
   microfone do celular a 48 kHz. O HFP é outro caminho — codec **CVSD a 8 kHz**, com
   quantização própria, microfone dos óculos e AGC no *uplink*. O que as bancadas chamam de
   "banda estreita" corta a banda e **não** simula o codec. Este projeto já pagou por essa
   diferença exata uma vez.
2. **Falso positivo com intervalo de confiança.** 3,04 h de fala retida dão, pela regra dos
   três, teto de 0,99/h. Fechar a meta de 0,5/h exige ~6 h.
3. **Bateria dos óculos com a `DeviceSession` aberta.** É o preço direto do dono de
   processo, e está declarado como tal.
4. **Comportamento térmico.** `THERMAL_HOT` está tratado no código, nunca provocado.
5. **Latência boca-a-ouvido** e o custo do RPC de preempção na janela real.
6. **`rowStride > width`** (padding de linha) e **rotação do sensor** no OCR. Falham para o
   lado seguro: imagem cisalhada ou girada não lê placa nenhuma, e nunca lê a errada.
7. **Toda a Etapa B sobre o Qwen** (§6.12).

---

## 9. Licenças e procedência dos modelos

| Componente | Versão / arquivo | Licença | Onde foi conferida |
|---|---|---|---|
| whisper.cpp | submódulo | **MIT** | `core-voice/src/main/cpp/whisper/LICENSE` |
| llama.cpp | vendorizado | **MIT** | `core-llm/src/main/cpp/llama/LICENSE` |
| Voz Piper `pt_BR-faber-medium` | int8 | dataset **CC0** | `MODEL_CARD` no diretório do modelo |
| `Qwen2.5-1.5B-Instruct-Q4_K_M` | 986 048 768 B | **Apache-2.0** | API do Hugging Face, conferida em 22/08 |
| MapLibre Native Android | 11.11.0 | **BSD-2-Clause** | fork do Mapbox GL v1 anterior à mudança de licença |
| Tiles OpenFreeMap | — | livre, sem chave, sem cota | — |
| Corpus de normas | 5 leis federais | **não protegido** | Lei 9.610/1998, art. 8º, IV — [`corpus/PROCEDENCIA.md`](corpus/PROCEDENCIA.md) |
| ML Kit Text Recognition | 16.0.1 | modelo Latin embarcado, roda offline | — |
| `org.json` (só testes de JVM) | 20240303 | domínio público | [`gradle/libs.versions.toml`](gradle/libs.versions.toml) |

**Dependência nova exige justificativa por tamanho, licença e alternativa nativa** antes de
entrar (`CLAUDE.md` §2). Dois exemplos do que essa regra evitou: o teste de renovação de
token usa um `ServerSocket` de quarenta linhas em vez de MockWebServer, porque mede o
mesmo; e a palavra de ativação não acrescentou dependência nenhuma, porque
`libonnxruntime.so` já entrava pelo AAR do sherpa-onnx.

---

## 10. Documentos do projeto

| Arquivo | Abra quando |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | **Fonte única da verdade**: produto, regras de negócio e de engenharia. Documento que o contradiga está errado por definição |
| [`ESTADO.md`](ESTADO.md) | *"Onde estamos?"* — funciona / quebrado / próximo. Teto de 60 linhas, reescrito a cada sessão |
| [`ROADMAP.md`](ROADMAP.md) | *"O que vem, em que ordem, e o que destrava o quê"*, com veredito de auditoria por item |
| [`DECISIONS.md`](DECISIONS.md) | *"Por que está assim?"* — arqueologia cronológica. Cada decisão não óbvia tem data, alternativa descartada e motivo |
| [`specs/`](specs/) | Uma por feature, aceite em EARS. Revisada **antes** do diff. Mudança de comportamento começa por diff de spec |
| [`docs/PADROES_DE_ENGENHARIA.md`](docs/PADROES_DE_ENGENHARIA.md) | Sequências de boot, tabela de armadilhas, design de áudio, energia, metas |
| [`docs/CAPACIDADES_DESLIGADAS.md`](docs/CAPACIDADES_DESLIGADAS.md) | O que existe no código e não roda, com motivo |
| [`docs/ADERENCIA_AO_TOOLKIT.md`](docs/ADERENCIA_AO_TOOLKIT.md) | Uso do DAT e dos três canais dos óculos, com arquivo e linha |
| [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md) | O que o edital e o material do curso exigem × o que existe |
| [`docs/RELATORIO_DE_IMPACTO_LGPD.md`](docs/RELATORIO_DE_IMPACTO_LGPD.md) | Risco, medida adotada, risco residual assumido (art. 38) |
| [`docs/PRONTIDAO_DE_HARDWARE.md`](docs/PRONTIDAO_DE_HARDWARE.md) | Aparelho de referência, orçamento de memória, degradação declarada |
| [`docs/IMPACTO_E_VALIDACAO_DE_CAMPO.md`](docs/IMPACTO_E_VALIDACAO_DE_CAMPO.md) | Entrevista com PM da PMERJ, autorizada, e o que a pesquisa ainda não é |
| [`docs/VERIFICACOES_COM_HARDWARE.md`](docs/VERIFICACOES_COM_HARDWARE.md) | O que só se mede com óculos e fone reais, com o comando de cada medição |
| [`docs/PALAVRA_DE_ATIVACAO.md`](docs/PALAVRA_DE_ATIVACAO.md) | As oito hipóteses medidas e o que cada uma matou |
| [`docs/ENERGIA.md`](docs/ENERGIA.md) | Modos, FGS por modo, freio térmico, WorkManager |
| [`docs/ENTREGA_SEGUNDO_FILTRO.md`](docs/ENTREGA_SEGUNDO_FILTRO.md) | O que foi submetido ao Segundo Filtro, para entrega e repositório não divergirem |
| [`docs/ARQUITETURA.mmd`](docs/ARQUITETURA.mmd) | Diagrama Mermaid com os cinco checkpoints do §8.1 marcados |
| [`docs/DIARIO_DE_BORDO.md`](docs/DIARIO_DE_BORDO.md) | Narrativa por marco. **Não contém estado atual** |

---

*Onde este documento afirma um número, ele foi medido, e o ambiente da medição está junto.
Onde não há medição, está escrito que não há. Onde uma capacidade existe e não está ligada,
está escrito que não está.*

