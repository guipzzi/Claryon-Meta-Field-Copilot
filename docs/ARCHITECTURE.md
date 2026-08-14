# Arquitetura — Claryon Field

Complemento ao [`README.md`](../README.md) e ao
[`GUIA_TECNICO.md`](GUIA_TECNICO.md). Foca nas sequências que não podem ser
invertidas, no orçamento de latência e nas fronteiras de módulo.

---

## Camadas

```
┌─ Óculos Ray-Ban Meta (Wayfarer) ── sensores + saída ────────────────────┐
│  câmera 12MP · array de 5 mics (beamforming) · alto-falantes open-ear   │
│  vídeo/foto → via DAT      |      mic/alto-falante → via BT (HFP/A2DP)   │
└──────────────────────────────────────┬──────────────────────────────────┘
                                        │ Bluetooth Classic (gargalo)
┌─ App companion Android/Kotlin ── todo o processamento vive aqui ─────────┐
│  core-glasses (DAT)   core-audio (HFP)   core-voice (wake·vad·stt·tts)   │
│  core-agent (router)  core-sound (earcons)  core-evidence  core-sync     │
│  app (orquestração + UI de diagnóstico)                                  │
└──────────────────────────────────────┬──────────────────────────────────┘
                                        │ HTTPS / mTLS (assíncrono, fora do caminho crítico)
                     Claryon Core (Supabase) · canal tático (a definir)
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
6. [sob demanda] session.addCamera(config) → camera.stream.start()
```
Inverter 4 e 5 → captura de voz intermitente. **HFP totalmente configurado antes
da sessão de streaming.**

**Refinamento da 0.9 quando há câmera** (a redação acima vem da 0.8): `addCamera`
exige uma sessão já criada, então a ordem exata para o pipeline **com câmera** é
`createSession` → `addCamera` → **HFP assentado** → `stream.start()`. O que não
pode acontecer, em nenhum dos dois casos, é o `stream.start()` preceder a rota de
áudio. Para o pipeline **só de voz**, o HFP sobe independentemente da sessão.
Ver `DECISIONS.md` (2026-08-13).

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
| Ação em rede (Supabase) | 200–600 ms (assíncrona, fora do caminho crítico) |
| Piper TTS (primeira nota) | 300–500 ms |
| **Total percebido — comando local** | **≈ 1,3–1,9 s** (meta < 2 s) |
| Fim da fala → earcon | ≤ 500 ms |

⚠️ **Estes números são alvos, não medições.** O contrato `Telemetry` está fixado
em `core-common`, mas **não há uma única chamada a `mark()`** em nenhum módulo —
instrumentar os estágios é dívida em aberto. Enquanto isso, nenhuma das metas de
latência ou de bateria do projeto tem número medido.

---

## Contratos fixados no M0

Estas assinaturas existem para permitir plano B sem reescrita
(ver [`README.md`](../README.md) e código-fonte):

| Módulo | Contrato | Implementação atual |
|---|---|---|
| core-glasses | `GlassesFacade` | `DatGlassesFacade` (DAT 0.9) · `MockDeviceController` (debug) |
| core-audio | `GlassesAudioManager` | `GlassesAudioManagerImpl` (AudioRecord/AudioTrack via HFP) |
| core-voice | `SttEngine` | `WhisperCppStt` — **único** implementador |
| core-voice | `SelfCapturingStt` | `AndroidOnDeviceStt` — contrato **separado** de propósito: ele captura o próprio áudio, então não cabe em `SttEngine`, que recebe PCM pronto (ver `DECISIONS.md`) |
| core-voice | `TtsEngine` | `PiperTts` (primária) · `AndroidTts` (fallback) |
| core-voice | `VoiceActivityDetector` | `EnergyVoiceActivityDetector` (RMS + hangover + teto de janela) |
| core-voice | `WakeWordDetector` | ⚠️ **sem implementação** — o acionamento é push-to-talk |
| core-agent | `IntentRouter` | `DeterministicIntentRouter` (palavras-chave normalizadas, sem LLM) |
| core-sound | `SoundQueue`, `LaconicityPolicy` | `SoundScheduler` (política pura) + `PrioritySoundQueue` (mecanismo) + `EarconSynthesizer` |
| core-evidence | `EvidenceVault` | `EncryptedEvidenceVault` (EncryptedFile + Keystore + `HashChain`) |
| core-sync | `SyncGateway` | `SupabaseSyncGateway` (PostgREST) · `FakeSyncGateway` (testes) |
| core-sync | `MessagingGateway` | ⚠️ **sem implementação** — canal de mensageria tática a definir |

⚠️ **Os contratos marcados como implementados são verificados isoladamente.**
`core-sound`, `core-evidence` e `core-sync` ainda **não são importados** por
`app/src/main`, e não há executor de intenções ligando o roteador às ações — ver
"O que ainda não existe" no [`README.md`](../README.md).

---

## Som e prioridade (M5)

Política e mecanismo separados, para a política ser testável sem áudio:

- **`SoundScheduler`** (puro): ordena por prioridade (`EMERGENCIA` → `RESPOSTA` →
  `INFORMATIVO`), suprime informativo em **Modo Tático** e decide se um som novo
  deve interromper o que toca (só emergência interrompe).
- **`PrioritySoundQueue`** (mecanismo): laço com `Channel` + `Mutex`; a
  reprodução roda em escopo-filho com `SupervisorJob` — uma falha de `AudioTrack`
  não pode matar a fila, senão os earcons de emergência morrem junto.
- **`EarconSynthesizer`**: sintetiza cada earcon por seno (varredura ascendente
  para "ouvi você", descendente para falha, tom de 2 s para "gravando").
- **`LaconicityPolicy`**: ≤ 7 palavras e sem cortesia, com teste automatizado.
  Cortesia é casada por **locução** ("por favor", "tudo bem") — "por", "tudo" e
  "bem" isolados são fala operacional legítima.

## Visão e evidência (M6)

- **`PlacaValidator`** (puro, core-agent): Mercosul `[A-Z]{3}[0-9][A-Z][0-9]{2}` e
  antigo `[A-Z]{3}[0-9]{4}`, com fronteira de token — é a **mesma** fonte de
  verdade para o comando por voz e para o OCR.
- **`PlacaOcr`** (app): ML Kit Text Recognition on-device (modelo Latin
  embarcado, roda offline). O frame é insumo efêmero: **só o texto validado
  sobrevive** à inferência.
- **`EncryptedEvidenceVault`**: um `EncryptedFile` (AES-256-GCM, chave no
  Keystore) **por segmento** — é o que permite apontar exatamente qual segmento
  foi adulterado. `HashChain` (SHA-256 encadeado) é a segunda camada, que também
  apanha troca, remoção e reordenação. O manifesto fica **em claro**: hashes não
  são segredo, e assim um terceiro verifica a custódia sem a chave.

## Rede e fila offline (M7)

- **`FileOutbox`**: fila durável, um arquivo por item, escrita atômica
  (`.tmp` + rename). Sobrevive à morte do processo — que é o ponto inteiro.
- **`OutboxDrainer`**: FIFO; **para na primeira falha** (se a rede caiu, insistir
  só gasta bateria); descarta item veneno após N tentativas, **avisando**.
- **`TacticalDispatcher`**: devolve `Despacho.Enviada` **ou** `Enfileirada`. A
  regra "não mente dizendo que enviou" é um **tipo**, não um comentário.
- **`SupabaseSyncGateway`**: PostgREST via OkHttp, com
  `Prefer: resolution=merge-duplicates` (reenvio idempotente).

## Energia e modos (M8)

- **`PowerPolicy`** (puro): `perfil(modo)` diz o que fica ligado;
  `tiposDeServico(modo)` diz quais `foregroundServiceType` o modo exige.
- **Regra sutil e importante:** o **manifest declara a união** dos tipos
  (`connectedDevice|microphone|camera`), enquanto `startForeground()` declara o
  **subconjunto exato do modo corrente** — em Standby o serviço não segura
  microfone nem câmera. E os tipos são interseccionados com as **permissões de
  runtime concedidas**: subir FGS com tipo `camera` sem a permissão concedida é
  `SecurityException` e mata o processo.
- **`ThermalGovernor`**: `NaN` de `getThermalHeadroom()` significa *sem
  informação* — mantém o teto vigente e **não** autoriza rajada. Tratar `NaN`
  como 0 faria o app acelerar justamente quando não sabe nada.

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
