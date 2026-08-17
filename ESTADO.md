# Onde estamos — 2026-08-17 · Fase 2 · o STT é o gargalo, e agora está medido

**Reescrito ao fim de cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O que não
couber é história e vai para `DECISIONS.md`. Aqui só o que muda a próxima decisão.

## O que funciona hoje

- `./gradlew build` verde. **640 testes JVM, 0 falhas.** Instrumentados: 1 falha conhecida
  (item 1) e o bench de STT reprovando de propósito (item 2).
- **PTT ponta a ponta:** toque→1º quadro **31–48 ms** (meta 120). `AgrupadorDeQuadros`
  50 msg/s → ~17. `transmit`/`ack` deployadas, fio do canal populado.
- **Fonte única de microfone** com fan-out e **dono único de saída**. P1 corta a fala do
  copiloto em **11 ms** (aceite ≤200) — era 286 ms, e os 204 ms eram desmonte de rota SCO
  dentro do caminho crítico. `RotaSustentada` mantém a rota através da rajada de fala.
- **VAD Silero** (629 KB) no lugar do RMS: silêncio → 0 segmentos, senoide **alta** → 0,
  fala real → 1 segmento cobrindo 98%.
- **Troca de grupo por voz ligada**, chamador verificado por `grep` elo por elo:
  `VoiceCycle:92` → executor → `CanaisDoAgente` → `RadioTatico:303`. Sem casamento
  aproximado (proibição de spec), recusa que não revela existência de grupo (13 testes).
- **Régua de WER honesta** (`Wer.kt`, 10 testes): `(S+D+I)/N` com N da referência, então
  alucinação passa de 100%. Corpus agrega `ΣE/ΣN`, não média de taxas.
- **Verificador da corrente**: Piper → Silero nativo → roteador → política real → rádio →
  fala, com asserção por elo.

## O que está quebrado, e nós sabemos

1. **`CaosDoDatTest` falha um teste por rodada, e varia qual.** Mock do DAT, que já
   precisava rodar isolado (`Wearables SDK already initialized`). Falha em `HEAD` limpo —
   não é regressão de diff. Precisa de isolamento de processo por classe.
2. **O STT não atende a própria meta, e é o item que decide a Fase 2.** Medido no aparelho
   com fala do Piper em **banda cheia** — o melhor caso: `ggml-tiny` deu WER de **62,5% a
   100%**. Falham as duas palavras de que o produto depende: **"guarnição"** → "nissan"/
   "agora nisso são", e **"Claryon"** → "parão"/"marcarion" — a **palavra de ativação**.
   Aplicado nesta sessão: `ggml-base-q5_1` (APK **−17,2 MiB** e WER pt 20,1→13,0 no paper),
   `initial_prompt` com o léxico do domínio, `suppress_nst`, `single_segment`.
   **Ver [`specs/stt-portugues.spec.md`](specs/stt-portugues.spec.md) — há uma decisão de
   licença que é humana:** o modelo que resolve (NeMo FastConformer pt do sherpa, acertou
   "guarnição" 4/4 e sobrevive ao 8 kHz) é **CC BY-NC 4.0, não comercial**.
3. **Latência de STT fora do orçamento:** 48 s → **14,9 s** no emulador ao dimensionar
   `audio_ctx` pela fala (o Whisper roda 30 s de encoder mesmo para 2 s). Meta do ciclo:
   2 000 ms. **Falta medir em arm64 real** — não há benchmark público em Android.
4. **A banda de 8 kHz NÃO é a causa** — refutada por medição publicada (1,2× de WER, não 3×).
   Não construir *bandwidth extension*: no mesmo experimento ela piorou o resultado.
5. **O portão da palavra de ativação não existe.** A spec exige descartar em silêncio o que
   não começa por "claryon" (aceite A2); hoje qualquer fala com "mudar para X" trocaria de
   grupo. Depende do item 2 — o portão é inútil enquanto "Claryon" não é reconhecido.
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
