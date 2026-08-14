# Claryon Field

Copiloto de voz **hands-free** para agentes de segurança pública, sobre óculos
**Ray-Ban Meta (sem display)** e o **Meta Wearables Device Access Toolkit (DAT)**.
O agente fala, o app entende **localmente**, age (pede apoio no WhatsApp, grava
evidência, consulta placa) e responde por **áudio no ouvido**. Mãos livres,
olhos no ambiente.

App companion **Android/Kotlin** — todo o processamento roda no smartphone
pareado; os óculos são sensores e alto-falantes.

> Programa AI Glasses Brasil 2026 · Trilha **Produtividade** · Hackathon
> presencial em **18/09/2026**.

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

Configuração do plugin Claude Code + MCP (fazer numa sessão interativa):
<https://wearables.developer.meta.com/docs/develop/dat/ai-assisted-claude-code/>

---

## Arquitetura de módulos

```
claryon-field/
├── app/            UI Compose (onboarding, diagnóstico, demo) + orquestração
├── core-common/    Result, Logger, FeatureFlags, Telemetry        [Kotlin/JVM]
├── core-agent/     Modelo de intenções + IntentRouter (determinístico) [JVM]
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
encerramento), orçamento de latência e integração WhatsApp estão em
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) e no
[`docs/GUIA_TECNICO.md`](docs/GUIA_TECNICO.md).

---

## Setup reproduzível do zero

### Pré-requisitos

| Ferramenta | Versão | Observação |
|---|---|---|
| JDK | 17 | Ex.: `brew install openjdk@17` |
| Android SDK | Platform 35, Build-Tools 35.0.0, Platform-Tools | via Android Studio ou `android-commandlinetools` |
| NDK | 27.0.12077973 | para whisper.cpp (JNI, `.so` por ABI). `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"` |

> Modelos on-device (whisper `ggml-tiny`, Piper/sherpa-onnx pt-BR, Silero VAD,
> openWakeWord) são baixados pelo setup do M4 e **não** entram no Git.

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

### 3. Build

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)"
./gradlew build
```

O primeiro build baixa Gradle 8.9 (wrapper) e as dependências; depois disso, a
build precisa funcionar **offline** (o Wi-Fi de evento é ruim).

---

## Comandos

```bash
./gradlew build                     # compila tudo + testes unitários
./gradlew :app:assembleDebug        # gera o APK de debug
./gradlew :app:installDebug         # instala no dispositivo/emulador
./gradlew test                      # testes unitários (JVM)
./gradlew connectedAndroidTest      # testes instrumentados (MDK) — exige device
adb logcat -s ClaryonField          # logs do app
```

---

## Estado dos marcos

| Marco | Descrição | Estado |
|---|---|---|
| **M0** | Contexto e esqueleto: módulos, interfaces (§3.1/§3.2), `./gradlew build` verde | ✅ **concluído** — MCP `search_dat_docs` ativo (`.mcp.json`) |
| **M1** | Setup do DAT: GitHub Packages, `mwdat 0.9.0`, manifest, `claryonfield://` | ✅ **concluído** — `clean build` verde com os artefatos `mwdat-*` resolvidos |
| **M2** | Mock Device Kit: registro, sessão e câmera reais (sem hardware) | ✅ **concluído** — teste instrumentado + painel ao vivo (REGISTERED→STARTED→STREAMING) |
| **M3** | Pipeline de áudio HFP (`GlassesAudioManager`, AudioRecord/AudioTrack) | ✅ **concluído** — testes verdes; eco record→playback verificado (eco HFP final requer fone físico) |
| **M4** | Voz on-device | ✅ roteador + VAD + resample 8→16 kHz + **STT whisper.cpp** e **TTS Piper/sherpa-onnx VERIFICADOS** no emulador + fallbacks nativos. Falta: openWakeWord/Silero + wiring do ciclo push-to-talk num device |
| M5 | Agente e som (roteador, fila, earcons, laconicidade) | 🟡 roteador já adiantado no M4; falta fila/earcons |
| M6 | Visão e evidência (OCR de placa, cofre cifrado) | pendente |
| M7 | Rede (Supabase, WhatsApp, fila offline) | pendente |
| M8 | Energia e resiliência (FGS, WorkManager, freio térmico) | pendente |

Padrão de trabalho: **um marco por sessão**, com revisão humana entre eles.

---

## Proibições absolutas

- ❌ Reconhecimento facial, embeddings faciais ou base biométrica — em nenhuma versão.
- ❌ Transcrever, classificar ou indexar a fala de terceiros — áudio bruto é evidência.
- ❌ Enviar áudio, transcrição ou frame a serviço externo no caminho crítico.
- ❌ Credencial em arquivo versionado.
- ❌ Evidência fora de `EncryptedFile` + Android Keystore.
- ❌ LLM no caminho crítico de decisão operacional.
