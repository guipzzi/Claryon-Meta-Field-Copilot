# Claryon Field

Copiloto de voz **hands-free** para agentes de segurança pública, sobre óculos
**Ray-Ban Meta (sem display)** e o **Meta Wearables Device Access Toolkit (DAT)**.
O agente fala, o app entende **localmente**, age (pede apoio pelo canal tático,
grava evidência, consulta placa) e responde por **áudio no ouvido**. Mãos livres,
olhos no ambiente.

App companion **Android/Kotlin** — todo o processamento roda no smartphone
pareado; os óculos são sensores e alto-falantes.

> Programa AI Glasses Brasil 2026 · Trilha **Produtividade** · Hackathon
> presencial em **18/09/2026**.

**Equipe Claryon** — coautores [@guipzzi](https://github.com/guipzzi) e
[@Lemos021](https://github.com/Lemos021). Os dois constam como `Co-authored-by:`
em todos os commits. Detalhes em [`AUTHORS.md`](AUTHORS.md).

---

## As cinco restrições que definem todo o projeto

1. **Sem display.** A única saída rica ao usuário é áudio. A tela do celular
   serve só para configuração, diagnóstico e demonstração.
2. **O código não roda nos óculos.** Óculos = sensores + alto-falantes; toda a
   lógica vive no celular.
3. **Câmera pelo DAT, áudio pelo Bluetooth.** Não existe `session.audioStream`:
   microfone/alto-falantes são acessados por `AudioManager`/`AudioRecord`/
   `AudioTrack` via HFP/A2DP.
4. **"Hey Meta" não é acessível.** A wake word ("Claryon") é nossa e roda no
   celular sobre o áudio HFP.
5. **Bluetooth Classic é o gargalo.** Vídeo modesto; microfone mono a 8 kHz.

Consequência de produto: o beamforming isola a voz de quem veste os óculos —
transcrevemos **o agente, não o interlocutor**. Intencional, não um defeito.

---

## Executar SQL no Supabase

Migrações e verificações rodam pela Management API, que **não depende da senha do
banco** — só de um personal access token:

```bash
python3 servidor/executar_sql.py servidor/migracoes/0001_esquema_inicial.sql
python3 servidor/executar_sql.py --somente-leitura -c "select count(*) from agents"
```

Gere o token em **Supabase → Account → Access Tokens**, com permissão
`database_write`, e guarde em `local.properties` (não versionado):

```
supabase_access_token=sbp_...
```

`--somente-leitura` é imposto pelo servidor, não pelo cliente: a sessão roda como
`supabase_read_only_user` e o Postgres recusa qualquer escrita. Use ao inspecionar
produção.

## ⛔ Pré-requisito absoluto antes de qualquer código do DAT (Regra Zero)

O DAT está em *developer preview* e mudou depois do corte de treinamento de
qualquer modelo. **Nenhuma assinatura do DAT deve ser escrita de memória.**
Antes de tocar em `Wearables`, `DeviceSession`, `Stream`, `StreamConfiguration`,
`MockDeviceKit` ou qualquer símbolo do SDK, consulte a fonte viva:

- MCP `search_dat_docs` em `https://mcp.developer.meta.com/wearables`
- ou `https://wearables.developer.meta.com/llms.txt?full=true`
- ou o repo oficial `facebook/meta-wearables-dat-android`

Registre versão + fonte em [`DECISIONS.md`](DECISIONS.md). Se não conseguir
confirmar uma assinatura: **pare e pergunte** — sem workaround inventado.

---

## Arquitetura de módulos

```
claryon-field/
├── app/            UI Compose (onboarding, diagnóstico, demo) + orquestração
├── core-common/    Result, Logger, FeatureFlags, Telemetry        [Kotlin/JVM]
├── core-agent/     Modelo de intenções + IntentRouter (determinístico) [JVM]
├── core-knowledge/ Trecho de norma + limiar de recusa (RAG extrativo)  [JVM]
├── core-glasses/   Fachada única sobre o DAT (registro, sessão, câmera) [Android]
├── core-audio/     Roteamento HFP/SCO, AudioRecord/AudioTrack      [Android]
├── core-voice/     WakeWord · VAD · STT · TTS (interfaces)         [Android]
├── core-sound/     Earcons, fila de prioridade, laconicidade       [Android]
├── core-evidence/  Cofre cifrado + cadeia de custódia              [Android]
└── core-sync/      Supabase, MessagingGateway, fila offline        [Android]
```

**Regra de dependência:** `app` depende de todos; os `core-*` **não dependem uns
dos outros**, exceto de `core-common`. A orquestração fica em `app`. Todo acesso
ao DAT passa por `GlassesFacade` — quando a preview quebrar assinaturas,
conserta-se um arquivo.

Detalhes das sequências que não podem ser invertidas (boot, ciclo de voz,
encerramento) e o orçamento de latência estão em
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) e no
[`docs/GUIA_TECNICO.md`](docs/GUIA_TECNICO.md).

Registro do projeto: [`DECISIONS.md`](DECISIONS.md) (uma entrada por decisão não
óbvia), [`docs/DIARIO_DE_BORDO.md`](docs/DIARIO_DE_BORDO.md) (narrativa do
desenvolvimento) e [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md) (conformidade com
o edital e o material do curso — **consultar antes de declarar um marco pronto**).

---

## Setup reproduzível do zero

### Pré-requisitos

| Ferramenta | Versão | Observação |
|---|---|---|
| JDK | 17 | Ex.: `brew install openjdk@17` |
| Android SDK | Platform 35, Build-Tools 35.0.0, Platform-Tools | via Android Studio ou `android-commandlinetools` |
| NDK | 27.0.12077973 | para whisper.cpp (JNI, `.so` por ABI). `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"` |

> Modelos on-device (whisper `ggml-tiny` e voz Piper/sherpa-onnx pt-BR) são
> baixados pelo passo 2 abaixo e **não** entram no Git.

### 1. `local.properties` (não versionado)

Aponte o SDK do Android (crie o arquivo na raiz):

```properties
sdk.dir=/caminho/para/o/android/sdk
```

A partir do M1, este arquivo também guarda o PAT `read:packages` do DAT
(chave `github_token`; alternativa: env `GITHUB_TOKEN`). **Nunca** versione credenciais.

### 2. Submódulos e modelos (voz on-device)

```bash
# whisper.cpp é um submódulo (core-voice/src/main/cpp/whisper)
git submodule update --init --recursive

# modelo STT (~77 MB, não versionado) — para o teste instrumentado do whisper
curl -L -o app/src/androidTest/assets/models/ggml-tiny.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin

# TTS Piper (sherpa-onnx): AAR pré-compilado (~49 MB) + voz pt-BR int8 (~21 MB)
mkdir -p core-voice/libs app/src/androidTest/assets/models
curl -L -o core-voice/libs/sherpa-onnx-1.13.5.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar
curl -L https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium-int8.tar.bz2 \
  | tar xj -C app/src/androidTest/assets/models
```

O **AAR do sherpa-onnx é obrigatório** para compilar (`core-voice`/`app`). Os
modelos de whisper/Piper só são usados pelos testes instrumentados (`Assume`
ignora se ausentes).

Sem o submódulo, a build nativa de `core-voice` falha. Sem o modelo, o teste
`WhisperCppSttTest` é apenas **ignorado** (não quebra o build).

### 2b. llama.cpp e o GGUF (Etapa B da Fase 4 — opcional)

`core-llm` não compila sem o llama.cpp vendorizado. Ele **não é submódulo ainda**
(decisão de custo em aberto: clone raso são 203 MB contra 43 MB do whisper), está
no `.gitignore` como trava, e é clonado à mão:

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp \
  core-llm/src/main/cpp/llama
```

Ausente, o CMake de `core-llm` falha com a instrução acima em vez de compilar pela
metade.

O modelo é o **`Qwen2.5-1.5B-Instruct-Q4_K_M`**, Apache-2.0. Ele substituiu o
`Llama-3.2-1B-Instruct-Q4_K_M` em 22/08 — o porquê está em `DECISIONS.md` e o
resumo é: cobertura de português nos pesos, e uma licença sem política de uso
aceitável que vede o caso de uso-bandeira deste produto. **O motor não mudou:**
continua sendo llama.cpp, que roda GGUF de qualquer família.

O **modelo do redator não vai no APK** — nunca em `assets/`, porque
`llama_model_load_from_file` faz `fopen` e asset não tem caminho no sistema de
arquivos. São **940,36 MiB** (986 048 768 B) contra um APK debug de 384,63 MiB.

`RedacaoDoCopiloto.arquivoDoModelo` procura em **dois** lugares, nesta ordem, com
nome fixo `redator.gguf`. Escolha um:

```bash
curl -L -o /tmp/redator.gguf \
  https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf

# (a) RECOMENDADO — uma linha, sem cópia, funciona também em release
adb push /tmp/redator.gguf \
  /sdcard/Android/data/com.claryon.field/files/redator.gguf

# (b) diretório privado — só em build debug, e duplica 940 MiB em disco
adb push /tmp/redator.gguf /data/local/tmp/redator.gguf
adb shell run-as com.claryon.field cp /data/local/tmp/redator.gguf files/redator.gguf
```

O nome no aparelho é `redator.gguf` e **não** o nome do modelo. Foi essa escolha
que fez a troca de família custar zero linha de Kotlin e zero linha de C++: o
template de chat vem do próprio GGUF (`llama_model_chat_template`, em
`redator_jni.cpp`), e o portão de RAM multiplica o tamanho do arquivo em vez de
carregar uma constante por modelo.

**(a) é o caminho do onboarding do dia do evento**, e foi medido em 22/08 no
emulador arm64 API 35: o llama.cpp carrega direto do armazenamento externo
(`preparar()` em 2 168 ms) — a dúvida era `mmap` sobre FUSE, e não se confirmou.
A cópia de (b) custou 908–1 187 ms **além** do push, e fica em disco para sempre.
Os dois números são do GGUF de 770 MiB; com os 940 MiB de hoje esperam-se ~22% a
mais, e isso é regra de três, não medição.

**(b) vence quando os dois existem**, de propósito: o armazenamento compartilhado
é gravável por quem tem acesso ao aparelho, e um GGUF trocado é um copiloto
trocado.

Sem o arquivo, o app decide `LerVerbatim(SEM_MODELO)` no boot e os testes de
`RedatorNoAparelhoTest` e `OrcamentoDaEtapaBNoAparelhoTest` são **pulados**
(`Assume`), não verdes.

> ⚠️ **A Etapa B está carregada, não ligada.** `RedacaoDoCopiloto.redigir` tem
> zero chamadores em `src/main`: com ou sem modelo, com a chave ligada ou
> desligada, o agente ouve a **citação** (`"Art. 306, Lei 9.503"`). Ligá-la
> sobrepõe o teto de 7 palavras do `CLAUDE.md` §4 e espera decisão humana em
> `specs/redacao-por-llm-na-fala.spec.md`, que recomenda **não ligar**.
>
> **A troca de modelo de 22/08 não altera essa recomendação, e não deve dar a
> impressão de que altera.** O Qwen entrou sem bancada, por decisão explícita de
> não gastar o dia em medição: o que existe medido — 1 a 2 respostas utilizáveis
> em 20, guarda cego a negação, prefill colado no prazo — é do Llama, e vale
> como o último estado conhecido até alguém remedir. Trocar o modelo mudou a
> **aposta**, não o resultado.

A Etapa B é **desligável no aparelho, sem recompilar**:

```bash
adb shell settings put global knowledge.llm 0   # desliga (LerVerbatim)
adb shell settings delete global knowledge.llm  # volta ao padrão (ligada)
```

Ela também se desliga sozinha em aparelho com menos de 3 GB de RAM total ou sem
folga de 1,9× o tamanho do GGUF — ver `PoliticaDeRedacao`.

### 3. Build

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)"
./gradlew build
```

O primeiro build baixa Gradle 8.11.1 (wrapper) e as dependências; depois disso, a
build precisa funcionar **offline** (o Wi-Fi de evento é ruim).

---

## Comandos

```bash
./gradlew build                     # compila tudo + testes unitários
./gradlew :app:assembleDebug        # gera o APK de debug
./gradlew :app:installDebug         # instala no dispositivo/emulador
./gradlew test                      # testes unitários (JVM)
./gradlew connectedAndroidTest      # testes instrumentados — exige device
adb logcat -s ClaryonField          # logs do app

# O teste do MockDeviceKit roda ISOLADO (ver o KDoc da classe: o decodificador
# do mock aborta quando divide processo com outra classe de teste).
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.claryon.field.MockDeviceKitStreamTest
```

> O emulador precisa ser **arm64-v8a** ou **x86_64**: são as ABIs compiladas
> (`abiFilters`), e num emulador `x86` os testes nativos falham por `.so` ausente.
> Numa máquina virgem, aceite as licenças antes: `sdkmanager --licenses`.

---

## Estado dos marcos

| Marco | Descrição | Estado |
|---|---|---|
| **M0** | Contexto e esqueleto: módulos, interfaces (§3.1/§3.2), `./gradlew build` verde | ✅ **concluído** — MCP `search_dat_docs` ativo (`.mcp.json`) |
| **M1** | Setup do DAT: GitHub Packages, `mwdat 0.9.0`, manifest, `claryonfield://` | ✅ **concluído** — `clean build` verde com os artefatos `mwdat-*` resolvidos |
| **M2** | Mock Device Kit: registro, sessão e câmera reais (sem hardware) | ✅ **concluído** — teste instrumentado + painel ao vivo (REGISTERED→STARTED→STREAMING) |
| **M3** | Pipeline de áudio HFP (`GlassesAudioManager`, AudioRecord/AudioTrack) | ✅ **concluído** — testes verdes; eco record→playback verificado (eco HFP final requer fone físico) |
| **M4** | Voz on-device | ✅ **concluído** — roteador + VAD + resample 8→16 kHz + **STT whisper.cpp** e **TTS Piper/sherpa-onnx verificados** no emulador + fallbacks nativos |
| **M5** | Agente e som (fila de prioridade, earcons, laconicidade, Modo Tático) | ✅ **concluído** — `SoundScheduler` (política pura) + `PrioritySoundQueue` + `EarconSynthesizer`, com testes |
| **M6** | Visão e evidência (OCR de placa, cofre cifrado) | ✅ **concluído** — OCR leu placa impressa; 30 segmentos cifrados → cadeia íntegra; **adulterar 1 byte → verificação aponta o segmento** |
| **M7** | Rede (Supabase + fila offline durável) | ✅ **concluído** — fila sobrevive à morte do processo; drenagem FIFO; despacho honesto (`Enviada` \| `Enfileirada`) |
| **M8** | Energia (FGS por modo, WorkManager, modos, freio térmico) | ✅ **concluído** — verificado em aparelho: Standby → serviço parado, Ativo → `0x90`, Ocorrência → `0xD0`, e degradação sem crash quando falta permissão |

**Todos os marcos do plano estão concluídos.** Padrão de trabalho: **um marco por
sessão**, com revisão humana entre eles.

**Todos os marcos do plano estão concluídos.** Padrão de trabalho: **um marco por
sessão**, com revisão humana entre eles. A tabela acima é histórica: marco concluído
não quer dizer capacidade alcançável pelo agente.

### O que ainda não existe

Esta seção mantinha uma lista própria de lacunas, e ela envelheceu mal — em
2026-08-15 três das seis linhas já eram falsas (o executor de intenções passou a
existir, os três módulos passaram a ser importados, os modelos foram para
`app/src/main/assets/`). Duas listas de lacunas garantem que uma esteja errada.

**O estado corrente vive em um lugar só: [`ESTADO.md`](ESTADO.md)** — o que funciona
hoje, o que está quebrado e conhecido, e o que vem a seguir, com `arquivo:linha` em
cada item. É reescrito ao fim de cada sessão.

---

## Proibições absolutas

- ❌ Reconhecimento facial, embeddings faciais ou base biométrica — em nenhuma versão.
- ❌ Transcrever, classificar ou indexar a fala de terceiros — áudio bruto é evidência.
- ❌ Enviar áudio, transcrição ou frame a serviço externo no caminho crítico.
- ❌ Credencial em arquivo versionado.
- ❌ Evidência fora de `EncryptedFile` + Android Keystore.
- ❌ LLM no caminho crítico de decisão operacional.
