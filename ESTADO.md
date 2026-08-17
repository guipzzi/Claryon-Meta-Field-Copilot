# Onde estamos — 2026-08-17 · Fase 2 · o STT é o gargalo, e agora está medido

**Reescrito ao fim de cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O que não
couber é história e vai para `DECISIONS.md`. Aqui só o que muda a próxima decisão.

## O que funciona hoje

- `./gradlew build` verde. **640 testes JVM, 0 falhas.** Instrumentados: 1 falha conhecida
  (item 1) e o bench de STT reprovando de propósito (item 2).
- **PTT ponta a ponta:** toque→1º quadro **31–48 ms** (meta 120) · 50 msg/s → ~17 ·
  `transmit`/`ack` deployadas, fio do canal populado.
- **Fonte única de microfone** com fan-out e **dono único de saída**. P1 corta a fala do
  copiloto em **11 ms** (aceite ≤200) — era 286 ms, dos quais 204 eram desmonte de rota SCO
  dentro do caminho crítico; `RotaSustentada` mantém a rota pela rajada de fala.
- **VAD Silero** (629 KB) no lugar do RMS: silêncio → 0, senoide **alta** → 0, fala → 98%.
- **Troca de grupo por voz ligada**, chamador verificado por `grep` elo por elo:
  `VoiceCycle:92` → executor → `CanaisDoAgente` → `RadioTatico:303`. Sem casamento
  aproximado (proibição de spec), recusa que não revela existência de grupo (13 testes).
- **Régua de WER** (`Wer.kt`, 10 testes): `(S+D+I)/N` com N da referência, então alucinação
  passa de 100%; corpus agrega `ΣE/ΣN`. **Verificador da corrente** de ponta a ponta.

## O que está quebrado, e nós sabemos

1. **`CaosDoDatTest` falha um teste por rodada, e varia qual.** Mock do DAT, que já precisava
   rodar isolado (`Wearables SDK already initialized`). Falha em `HEAD` limpo — não é
   regressão. Precisa de isolamento de processo por classe.
2. **O STT não atende a própria meta, e é o item que decide a Fase 2.** Medido no aparelho,
   fala do Piper em banda cheia (o melhor caso), 8 amostras agregadas por `ΣE/ΣN`:
   `ggml-tiny` dava WER de 62,5–100%; com **`base-q5_1` + `initial_prompt` está em 38,5%**
   (61,5% de acurácia) contra a meta de ≥92%. O APK **encolheu 17,2 MiB** na troca.
   Dos 20 erros: **7 são ruído de artigo** (o roteador já derruba artigos — inócuos),
   **6 são a palavra de ativação**, 4 são "guarnição" (que agora acerta 4/8, contra 0/8) e
   2 são o sobrenome "Paiva", que nunca sobrevive.
   **Ver [`specs/stt-portugues.spec.md`](specs/stt-portugues.spec.md) — há decisão de licença
   que é humana:** o modelo que resolve (NeMo FastConformer pt do sherpa, "guarnição" 4/4 e
   texto idêntico a 8 e 16 kHz) é **CC BY-NC 4.0, não comercial**.
3. **Latência de STT: o emulador não serve para concluir nada.** `audio_ctx` dimensionado
   pela fala levou o `tiny` de 48 s a 14,9 s (o Whisper roda 30 s de encoder mesmo para 2 s
   de áudio). Mas o `base` mediu **9 s a 176 s para trabalho equivalente** — variação de 19×,
   que é contenção de emulador, não sinal. Meta do ciclo: 2 000 ms. **Só arm64 real responde.**
4. **A banda de 8 kHz NÃO é a causa** — refutada por medição publicada (1,2× de WER, não 3×).
   Não construir *bandwidth extension*: no mesmo experimento ela piorou o resultado.
5. **A palavra de ativação não sobrevive à ORTOGRAFIA, e o portão dela não existe.** O STT
   escreve **"Clarion"** (3/8), "varion" (1/8) ou **omite** (2/8) — e "Clarion" não é erro de
   som: "Claryon" com "y" não é padrão grafêmico do português. Comparação exata reprovaria
   escuta correta. Saídas na spec do gatilho; as 2 omissões nenhuma lista resolve.
6. **`fim da fala → earcon` e `→ resposta falada` seguem sem amostra.** Os marcos saem de
   `SaidaUnica` e o bench usa telemetria própria — instrumento existe, não está ligado ali.
7. `Stream.errorStream` nunca é coletado (perdemos `HINGE_CLOSED`, `THERMAL_HOT`,
   `BATTERY_LOW`); `STOPPED` não é terminal; a permissão de câmera do DAT nunca é pedida.
8. Transcrição na origem (P1) não existe. `WakeWordDetector` é interface sem implementação.

## O que vem a seguir

1. **Decidir a licença do STT** (item 2). Bloqueio humano, não técnico.
2. **Medir em arm64 real** — óculos + fone HFP. Sem isso, itens 2 e 3 são de emulador.
3. **Portão da palavra de ativação**, depois que o item 2 fechar.
4. **Transcrição na origem** (Pilar 1) · **Entregáveis da Etapa 5 — prazo 22/08.**

**Pendências:** `security-crypto` `1.1.0-alpha06` · isolar `CaosDoDatTest` · conferir se o
documento submetido menciona WhatsApp (§14.1 veda mudar escopo).
