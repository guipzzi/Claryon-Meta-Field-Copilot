# Padrões de Engenharia — Claryon Field

Regras duras do projeto: o que não pode ser invertido, o que é proibido, e as
armadilhas que já custaram tempo. **Leitura obrigatória antes de tocar no
código.** Onde este documento e a doc oficial do DAT divergirem, a doc oficial
vence.

App companion **Android/Kotlin** para óculos **Ray-Ban Meta** usando o **Meta Wearables Device Access Toolkit (DAT)**.
Copiloto de voz para agentes de segurança pública: o usuário fala, o app entende localmente, age, e responde por áudio no ouvido. Mãos livres, olhos no ambiente.

Entrega: Hackathon AI Glasses Brasil, **18/09/2026**.

---

## ⛔ REGRA ZERO — leia antes de tocar em qualquer API do DAT

O DAT está em **developer preview**, é versionado e mudou depois do corte de treinamento de qualquer modelo.

**Nunca escreva assinaturas do DAT de memória.** Antes de usar `Wearables`, `DeviceSession`, `Stream`, `StreamConfiguration`, `MockDeviceKit` ou qualquer símbolo do SDK:

1. Consulte o MCP `search_dat_docs` em `https://mcp.developer.meta.com/wearables`
2. Ou `https://wearables.developer.meta.com/llms.txt?full=true`
3. Ou o repo oficial `facebook/meta-wearables-dat-android` (samples > docs)

Registre em `DECISIONS.md` a versão do SDK e a fonte usada.
**Se não conseguir confirmar uma assinatura na documentação: PARE E PERGUNTE.** Não invente workaround.

Onde o material de apoio (Un12/Un13) divergir da doc oficial, **a doc oficial vence** — o material foi escrito contra a 0.8.0.

---

## As cinco restrições do hardware

1. **Sem display.** Única saída rica ao usuário é áudio. A tela existe só para configuração, diagnóstico e demo.
2. **O código não roda nos óculos.** Óculos = sensores + alto-falantes. Tudo roda no celular.
3. **Câmera pelo DAT, áudio pelo Bluetooth.** Não existe `session.audioStream`. Microfone e alto-falantes = `AudioManager` / `AudioRecord` / `AudioTrack` via HFP/A2DP.
4. **"Hey Meta" não é acessível.** A wake word é nossa, roda no celular sobre o áudio HFP.
5. **Bluetooth Classic é o gargalo.** Vídeo comprimido, resolução modesta; microfone mono a 8 kHz.

**Decisão de produto que é política de código:** o beamforming isola a voz de quem veste os óculos. Transcrevemos o **agente**, não o interlocutor. Isso é intencional — não "conserte".

---

## Sequências que não podem ser invertidas

### Boot
```
1. Wearables.initialize(context)                 // uma vez por processo
2. observar registrationState + registrationErrorStream
3. se != REGISTERED → deeplink Meta AI → retorno via claryonfield://
4. GlassesAudioManager.iniciar()                 // ⚠️ ANTES do passo 5
5. Wearables.createSession(AutoDeviceSelector) → session.start()
6. [sob demanda] session.addStream(config) → stream.start()
```
Inverter 4 e 5 → captura de voz intermitente. HFP totalmente configurado **antes** da sessão de streaming.

### Ciclo de voz
```
PCM (HFP) → WakeWord → [earcon "ouvi você" IMEDIATO] → VAD fecha janela
  → SttEngine.transcribe() → IntentRouter → executor → TtsEngine → SoundQueue
```
O earcon dispara quando o **VAD fecha a janela**, não quando o STT termina.

### Encerramento
```
clearCommunicationDevice()   // senão todo áudio do sistema fica preso em 8 kHz
stream.stop() → session.stop() → liberar interpretadores, AudioRecord, AudioTrack
```

---

## Módulos

```
app/            UI Compose: onboarding, diagnóstico, painel de demo
core-glasses/   DAT: registro, sessão, câmera, MDK  ← único ponto que toca o SDK
core-audio/     Roteamento HFP, AudioRecord, AudioTrack
core-voice/     WakeWord, VAD, STT, TTS (interfaces + impls)
core-agent/     IntentRouter determinístico, políticas de ação
core-sound/     Earcons, fila de prioridade, protocolo de laconicidade
core-evidence/  Cofre cifrado, hash chain, cadeia de custódia
core-sync/      Supabase, MessagingGateway, WorkManager
core-common/    Result types, logging, feature flags
```

`core-*` não dependem uns dos outros (exceto `core-common`). Orquestração fica em `app`.
Todo acesso ao DAT passa por `GlassesFacade` em `core-glasses` — quando a 0.9 quebrar assinaturas, você conserta um arquivo.

**Toda peça de risco tem interface com dois back-ends:**
- `SttEngine` → `WhisperCppStt` (primário) | `AndroidOnDeviceStt` (fallback)
- `TtsEngine` → `PiperTts` (primário) | `AndroidTts` (fallback)
- `MessagingGateway` → `WhatsAppDirectGateway` (demo) | `WhatsAppGroupGateway` (flag) | `FakeGateway` (testes)

---

## Armadilhas — checar antes de dizer "pronto"

| Armadilha | Correção |
|---|---|
| HFP depois do stream | Sequenciar conforme o boot acima |
| `foregroundServiceType` faltando | Declarar no manifest **e** em `startForeground()` (Android 14+) |
| Iniciar FGS em background | `ForegroundServiceStartNotAllowedException` — iniciar de tela visível |
| `setCommunicationDevice()` → `false` | Tratar; testar `MODE_IN_COMMUNICATION` se `MODE_NORMAL` não subir |
| Registro perdido | Dev Mode: só 1 app de terceiros por vez. Detectar `AVAILABLE` inesperado |
| URI scheme ausente | `claryonfield://` com VIEW + DEFAULT + BROWSABLE. Nunca genérico |
| `speak()` antes de `onInit` | Enfileirar até `ready` |
| `SpeechRecognizer` fora da main thread | Crash. Sempre `destroy()` |
| `ERROR_NO_MATCH` como erro grave | É "não entendi" — earcon e seguir |
| Todos os frames em fila | `conflate`: um por vez, descartar os do meio |
| Inferência em `while(true)` | Sempre por gatilho de evento |
| Whisper como streaming | É **lote**: fechar a janela e só então transcrever |
| Pedir `HIGH`/30 fps | `LOW`/`MEDIUM` com FPS baixo dá qualidade por frame **melhor** |
| `capturePhoto()` sem stream ou em paralelo | Exige stream ativo; uma por vez |
| Tentar reviver sessão encerrada | *Cascading stop* — criar sessão nova |
| `getThermalHeadroom()` = `NaN` como 0 | `NaN` = sem informação; manter só o teto de taxa |
| Testar áudio no MDK | **MDK não simula áudio.** Use fone Bluetooth com HFP |
| PAT commitado | `local.properties` (já no `.gitignore`) ou `GITHUB_TOKEN` |

---

## Proibições absolutas

- ❌ **Reconhecimento facial, embeddings faciais ou base biométrica.** Nenhuma versão, nenhuma flag
- ❌ Transcrever, classificar ou indexar a fala de terceiros — áudio bruto é evidência, não dado analisável
- ❌ Enviar áudio, transcrição ou frame para serviço externo no caminho crítico
- ❌ Credencial em arquivo versionado
- ❌ Evidência fora de `EncryptedFile` + Android Keystore
- ❌ LLM no caminho crítico de decisão operacional (latência imprevisível, não auditável)

---

## Design de áudio — regras duras

- Máximo **7 palavras** por resposta de TTS em contexto operacional (há teste automatizado)
- Sem cortesia: nada de "por favor", "desculpe", "tudo bem"
- Números falados dígito a dígito: "A-B-C-1-D-2-3"
- Resultado de consulta sensível sai como **earcon codificado**, nunca falado — alto-falante open-ear vaza som para quem está ao lado
- Falha nunca é silêncio. Todo erro tem earcon próprio
- Fila de prioridade: nível 1 (emergência) interrompe tudo; nível 3 é suprimido em Modo Tático

---

## Energia

- Gatilho, nunca loop. Cascata: wake word (barata, sempre) → VAD → STT (caro, quase nunca)
- Câmera desligada por padrão. Stream só sob intenção explícita, e desce ao terminar
- Modos: **Standby** (HFP fechado) | **Ativo** (HFP aberto, câmera sob demanda) | **Ocorrência** (tudo ligado, janela curta)
- `ForegroundService` com `connectedDevice|microphone|camera` para o pipeline contínuo
- `WorkManager` com `requiresCharging` + `UNMETERED` + `requiresBatteryNotLow` para upload e modelos
- `getThermalHeadroom()` antes de rajadas; `sample` no Flow como teto de taxa

---

## Quando parar e perguntar

- Assinatura de API do DAT não confirmável na doc
- Necessidade de credencial ou de hardware físico
- Qualquer nova dependência de terceiros (justificar tamanho, licença, alternativa nativa)
- Qualquer decisão que afete latência, bateria ou privacidade
- Descoberta de que um marco anterior está quebrado

---

## Fluxo de trabalho

- **Um marco por sessão.** Ao concluir, apresentar o critério de aceite atendido e parar para revisão humana
- `DECISIONS.md`: uma linha por decisão não óbvia — data, alternativa descartada, motivo
- Commits pequenos, mensagem explicando o *porquê*
- `README.md` com setup reproduzível do zero, incluindo NDK e download de modelos
- **O build precisa funcionar offline** após a primeira sincronização. Wi-Fi de evento é ruim

---

## Metas mensuráveis (instrumentar desde o início)

| Métrica | Alvo |
|---|---|
| Fim da fala → earcon | ≤ 500 ms |
| Fim da fala → resposta (local) | ≤ 2,0 s |
| Fim da fala → resposta (rede) | ≤ 3,0 s |
| STT em 20 comandos operacionais com ruído | ≥ 92% |
| Falsos positivos da wake word | ≤ 1/hora |
| Bateria do celular, modo Ativo | ≤ 12%/h |

Métrica adicionada no fim nunca é adicionada.

---

## Comandos

```bash
./gradlew build                     # compilar tudo
./gradlew :app:installDebug         # instalar
./gradlew connectedAndroidTest      # testes instrumentados (MDK)
adb logcat -s ClaryonField          # logs
adb shell dumpsys batterystats --reset && adb shell dumpsys batterystats > bs.txt
```
