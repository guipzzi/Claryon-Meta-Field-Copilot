# Compliance & Guidelines — Claryon Field

Verificação de conformidade do que foi construído (M0/M1) e **checklist vivo** de
guidelines extraídos do material teórico do curso (CEIA/Meta — Un12 Edge-AI/Android
e Un13 DAT) e do edital. Cada item de milestone futuro deve ser conferido aqui
antes de dizer "pronto".

> Fonte dos guidelines: leitura integral de `Un12_Material_de_apoio_Meta.pdf`
> (94 p.) e `Un13_Material_de_apoio_Meta.pdf` (64 p.). Un10/Un11 são conceituais
> (bancos vetoriais, memória de agente LLM, RAG em Python) e **não se aplicam** ao
> app determinístico on-device. Un14 é encerramento editorial que reendossa a
> Regra Zero e as restrições de hardware.

---

## A. Veredito sobre M0 e M1

**Conclusão: em conformidade.** O material valida quase todas as nossas escolhas;
não há contradição. As lacunas apontadas são de *implementação futura* (M3/M4),
não defeitos do que já existe.

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
| Silero VAD · openWakeWord · Piper/sherpa-onnx | Un12 p.96,97,101 — citados nominalmente | ✅ valida |
| Roteador determinístico, sem LLM no caminho crítico | Un12 p.98; Un10/11 (RAG/LLM) fora de escopo | ✅ valida |
| Cascata wake→VAD→STT, gatilho-nunca-loop | Un12 p.57,97 — bursty/race-to-sleep | ✅ valida |
| ForegroundService (connectedDevice\|microphone\|camera) | Un12 p.55,59 | ✅ valida |
| WorkManager (charging/UNMETERED/battery) | Un12 p.55,60 | ✅ valida |
| `getThermalHeadroom`, NaN≠0, `sample` como teto | Un12 p.58,60 | ✅ valida |

---

## B. Checkpoints obrigatórios do edital (§8.1) — como atendemos

| Checkpoint | Nossa resposta | Onde |
|---|---|---|
| **Uso de IA** | 4 componentes de IA 100% locais: openWakeWord, Silero VAD, whisper.cpp, ML Kit OCR | core-voice, core-agent (M4–M6) |
| **Câmera ou microfone** | Microfone (HFP) = canal primário; câmera (DAT) sob demanda | core-audio, core-glasses (M3, M6) |
| **Output por áudio** | Earcons + TTS lacônico (≤7 palavras); resultado sensível = earcon codificado | core-sound (M5) |
| **Privacidade e dados** | On-device no caminho crítico; sem reconhecimento facial; fala de terceiros não transcrita; evidência cifrada | core-evidence, políticas (M6) |
| **Eficiência de bateria** | Cascata, modos Standby/Ativo/Ocorrência, câmera desligada por padrão, freio térmico | core-* (M8) |

---

## C. Checklist vivo por milestone (conferir antes de "pronto")

### M2 — Mock Device Kit + registro
- [ ] `Wearables.initialize(context)` **uma vez, no `onCreate()` de uma classe `Application`** (não em Activity). Falta criar essa classe. *(Un13 p.34)*
- [ ] Nenhuma chamada ao SDK antes de `initialize` (guardar contra `NOT_INITIALIZED`). *(Un13 p.34)*
- [ ] Observar `registrationState` **e** o fluxo separado de erro de registro. *(Un13 p.31)*
- [ ] Detectar "registro perdido" (state volta a `AVAILABLE` sozinho — Dev Mode 1-app-por-vez). *(Un13 p.32)*
- [ ] Sessão: tratar até STOPPED (terminal); não reviver STOPPED; em PAUSED não reiniciar. *(Un13 p.35,36)*
- [ ] `createSession` trata `NO_ELIGIBLE_DEVICE`/`SESSION_ALREADY_EXISTS`; coletar `session.errors`. *(Un13 p.33,35)*
- [ ] MDK atrás de flag `DEBUG`; vídeo de mock em H.265; tratar foto rotacionada 90°. *(Un13 p.62,63)*
- [ ] **Reconfirmar via `search_dat_docs`** os enums reais 0.9: `RegistrationState`, `DeviceSessionState`, `StreamState`, erros — o material é 0.8 e linka 0.6. *(Regra Zero)*

### M3 — Áudio HFP
- [ ] Roteamento SCO (`TYPE_BLUETOOTH_SCO`) confirmado ativo **antes** de qualquer stream. *(Un13 p.53)*
- [ ] Tratar `setCommunicationDevice()==false` e lista vazia (mensagem, não falha silenciosa); fallback `MODE_IN_COMMUNICATION`. *(Un13 p.54)*
- [ ] `AudioRecord` fonte `VOICE_COMMUNICATION`, mono, PCM 16-bit, `Dispatchers.IO`. *(Un13 p.54; Un12 p.28)*
- [ ] **`clearCommunicationDevice()` sempre no encerramento.** *(Un13 p.56)*
- [ ] ⚠️ **RISCO: HFP entrega 8 kHz; Whisper espera 16 kHz.** Implementar resample e medir acurácia real. *(§D abaixo)*

### M4 — Voz on-device
- [ ] `WhisperCppStt` em **lote**: só transcreve após o VAD fechar a janela; nunca alimenta PCM incremental. *(Un12 Nota 20 p.101)*
- [ ] Fallback `AndroidOnDeviceStt` usa `createOnDeviceSpeechRecognizer`+`isOnDeviceRecognitionAvailable` (API 31+) ou `EXTRA_PREFER_OFFLINE` — **nunca** o `SpeechRecognizer` padrão (vaza áudio p/ servidor). *(Un12 p.99,101)*
- [ ] `SpeechRecognizer` na main thread + `destroy()`; `ERROR_NO_MATCH` = earcon, não erro. *(Un12 p.100)*
- [ ] `AndroidTts.speak()` só após `onInit`; enfileirar até `ready`. *(Un12 p.100)*
- [ ] Assinaturas de whisper.cpp e sherpa-onnx confirmadas nos repos oficiais antes de codar (Regra Zero análoga). *(Un12 p.101)*
- [ ] Validar tudo em **modo avião** (nenhum byte sai). *(Un12 p.98)*

### M6 — Visão e evidência
- [ ] Frame → pré-proc (tamanho/normalização do export) → inferência `Dispatchers.Default` → threshold/NMS; `conflate` no Flow. *(Un12 p.86,87)*
- [ ] Conferir `dataType()` do tensor (não alimentar INT8 com float). *(Un12 p.89)*
- [ ] ⚠️ OCR de placa a distância degrada (texto pequeno/ângulo) — medir cenário real; talvez pré-detectar/recortar a placa. *(Un12 p.80)*
- [ ] `capturePhoto()` exige stream ativo, uma por vez (`CaptureError.CaptureInProgress`). *(Un13 p.43)*

### M8 — Energia
- [ ] `foregroundServiceType` no manifest **e** em `startForeground()`; FGS só de tela visível. *(Un12 p.55,59)*
- [ ] `sample` como teto de taxa; `getThermalHeadroom` antes de rajadas (NaN = sem info). *(Un12 p.58,60)*
- [ ] Medir com Power Profiler + `dumpsys batterystats` / Battery Historian. *(Un12 p.61)*
- [ ] **Reativar o Android Lint** (desligado no M1 por bug AGP 8.7.2 × Kotlin 2.2). Ver DECISIONS.

---

## D. Riscos e lacunas surfados na leitura (não são defeitos de M0/M1)

1. **8 kHz (HFP) → 16 kHz (Whisper).** O microfone dos óculos chega mono a 8 kHz; o Whisper espera 16 kHz. Exige resample e pode pressionar a meta de 92% de acurácia do STT. **Validar com áudio HFP real, não com fone de melhor qualidade.** *(Un12 p.93; Un13 p.52)*
2. **`SpeechRecognizer` padrão vaza áudio para servidor.** O fallback nativo só é aceitável na variante on-device explícita; se o pacote pt-BR não estiver no aparelho, não há STT local nativo → plano B é depender só do whisper.cpp. *(Un12 p.99,101)*
3. **`initialize` na `Application`, não na Activity.** Ainda não temos classe `Application`; criar no M2 (importante porque FGS/WorkManager recriam processo). *(Un13 p.34)*
4. **OCR de placa a distância** pode não atingir acurácia útil com ML Kit puro; orçar medição e possível recorte prévio. *(Un12 p.80)*
5. **Dois runtimes nativos de inferência** (sherpa-onnx/ONNX para TTS; LiteRT/.tflite para visão) + whisper.cpp — peso de APK e NDK a dimensionar no M4/M6. *(Un12 p.101)*
6. **Divergência de versão do material (0.8/0.6) vs. SDK 0.9.** Todo enum/assinatura do DAT citado no material é **mapa conceitual**, não fonte — reconfirmar no `search_dat_docs` e registrar em `DECISIONS.md`. *(Un13, Regra Zero)*

---

## E. Confirmação de acesso à documentação (2026-08-13)

| Fonte | Estado |
|---|---|
| MCP `search_dat_docs` (docs vivas 0.9) | ✅ ativo (`.mcp.json`), usado nesta sessão |
| `llms.txt?full=true` | ✅ HTTP 200 |
| Repo `facebook/meta-wearables-dat-android` | ✅ público, atualizado 2026-08-09 |
| GitHub Packages (artefatos `mwdat-*`) | ✅ token resolve |
| Material do curso (Un1–Un14 + edital) | ✅ 15 PDFs lidos/mapeados |
