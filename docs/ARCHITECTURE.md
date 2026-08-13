# Arquitetura — Claryon Field

Complemento ao [`README.md`](../README.md) e ao
[`GUIA_TECNICO.md`](GUIA_TECNICO.md). Foca nas sequências que não podem ser
invertidas, no orçamento de latência e nas fronteiras de módulo.

---

## Camadas

```
┌─ Óculos Ray-Ban Meta (Wayfarer) ── sensores + saída ────────────────────┐
│  câmera 12MP · array de 5 mics (beamforming) · alto-falantes open-ear   │
│  vídeo/foto → via DAT      |      mic/alto-falante → via Bт (HFP/A2DP)   │
└──────────────────────────────────────┬──────────────────────────────────┘
                                        │ Bluetooth Classic (gargalo)
┌─ App companion Android/Kotlin ── todo o processamento vive aqui ─────────┐
│  core-glasses (DAT)   core-audio (HFP)   core-voice (wake·vad·stt·tts)   │
│  core-agent (router)  core-sound (earcons)  core-evidence  core-sync     │
│  app (orquestração + UI de diagnóstico)                                  │
└──────────────────────────────────────┬──────────────────────────────────┘
                                        │ HTTPS / mTLS (assíncrono, fora do caminho crítico)
                     Claryon Core (Supabase) · WhatsApp (Cloud/Groups API)
```

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
Inverter 4 e 5 → captura de voz intermitente. **HFP totalmente configurado antes
da sessão de streaming.**

### Ciclo de voz
```
PCM (HFP) → WakeWord → [earcon "ouvi você" IMEDIATO] → VAD fecha janela
  → SttEngine.transcribe() → IntentRouter → executor → TtsEngine → SoundQueue
```
O earcon dispara quando o **VAD fecha a janela**, não quando o STT termina — o
agente sabe que foi ouvido em ~400 ms mesmo que a ação leve 2 s.

### Encerramento
```
clearCommunicationDevice()   // senão o áudio do sistema fica preso em 8 kHz
stream.stop() → session.stop() → liberar interpretadores, AudioRecord, AudioTrack
```

---

## Áudio: Bluetooth do sistema, NÃO o DAT (confirmado na doc 0.9)

O DAT cuida da **câmera**. O **áudio** (microfone e alto-falante dos óculos) é
acessado pelos **perfis Bluetooth do sistema** — `AudioManager`/`AudioRecord`/
`AudioTrack` no Android. Não existe API de áudio no SDK.

| Perfil | Direção | Qualidade | Uso |
|---|---|---|---|
| **A2DP** | saída | alta (44,1/48 kHz estéreo) | mídia / TTS de alta qualidade |
| **HFP** | bidirecional | **8 kHz mono** | captura de voz (microfone) |

- **A2DP e HFP são mutuamente exclusivos.** Ativar o HFP (para o microfone)
  derruba o A2DP e a **saída também cai a 8 kHz** enquanto a sessão HFP durar.
- **Ordem oficial com câmera + HFP (0.9):** `addCamera` → iniciar HFP e esperar a
  rota assentar → `stream.start()`. Iniciar o stream antes do HFP faz a rota de
  áudio falhar silenciosamente.
- Beamforming isola a voz de quem veste os óculos (comportamento esperado).

---

## Orçamento de latência (alvos)

| Estágio | Alvo |
|---|---|
| Captura + HFP | 100–200 ms (fixo pelo link) |
| VAD fecha a janela | ~300 ms |
| Whisper tiny quantizado (lote) | 500–900 ms |
| Roteador de intenções | < 50 ms |
| Ação local | < 100 ms |
| Ação em rede (Supabase → WhatsApp) | 200–600 ms (assíncrona) |
| Piper TTS (primeira nota) | 300–500 ms |
| **Total percebido — comando local** | **≈ 1,3–1,9 s** (meta < 2 s) |
| Fim da fala → earcon | ≤ 500 ms |

Instrumentado desde o M3 via `core-common/Telemetry` — medir, não estimar.

---

## Contratos fixados no M0

Estas assinaturas existem para permitir plano B sem reescrita
(ver [`README.md`](../README.md) e código-fonte):

| Módulo | Contrato | Implementações previstas |
|---|---|---|
| core-glasses | `GlassesFacade` | DAT real (M1/M2) · MockDeviceKit (M2) |
| core-audio | `GlassesAudioManager` | AudioRecord/AudioTrack via HFP (M3) |
| core-voice | `SttEngine` | `WhisperCppStt` (primária) · `AndroidOnDeviceStt` (fallback) |
| core-voice | `TtsEngine` | `PiperTts` (primária) · `AndroidTts` (fallback) |
| core-voice | `WakeWordDetector`, `VoiceActivityDetector` | openWakeWord · Silero VAD (M4) |
| core-agent | `IntentRouter` | roteador determinístico (M5) |
| core-sound | `SoundQueue`, `LaconicityPolicy` | fila de prioridade + earcons (M5) |
| core-evidence | `EvidenceVault` | EncryptedFile + Keystore + hash chain (M6) |
| core-sync | `MessagingGateway` | `WhatsAppDirectGateway` · `WhatsAppGroupGateway` (flag) · `FakeGateway` |

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
