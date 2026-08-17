# Onde estamos — 2026-08-17 · Fase 2 · latência resolvida · o gargalo é a palavra de ativação

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto é história e
vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · 17 instrumentados OK. O bench de
  STT reprova de propósito (item 2).
- **A latência era build, não tecnologia.** O `CMakeLists` só aplicava `-O3` fora do Debug, e
  os testes rodam no Debug: o ggml compilava sem otimização e com asserções. Somando
  `+dotprod` (55 kernels que estavam compilados fora): **STT de 42 s → 245 ms quente.**
  Os alvos do ggml são **três** e só `ggml-cpu` tem os kernels — achado no
  `compile_commands.json`, nada no CMakeLists sugeria.
- **As duas metas do ciclo têm amostra** (era "sem amostras" por fiação, não por bancada):
  earcon **~605 ms** (meta 500) · resposta **1905 ms** (meta 2000) · **TTS + rota + fila
  552 ms**, que era **1218** antes de `SaidaUnica.aquecer()` — mais que o whisper inteiro.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · 50 msg/s → ~17. P1 corta a fala do
  copiloto em **11 ms** (aceite ≤200); `RotaSustentada` mantém a rota SCO pela rajada.
- **VAD Silero** no lugar do RMS: silêncio → 0, senoide **alta** → 0, fala → 98%.
- **Troca de grupo por voz ligada**, chamador verificado por `grep` elo por elo:
  `VoiceCycle:92` → executor → `CanaisDoAgente` → `RadioTatico:303`. Sem casamento
  aproximado (proibição de spec), recusa que não revela existência de grupo (13 testes).
- **Régua de WER** (`Wer.kt`, 10 testes) e **verificador da corrente** de ponta a ponta.

## O que está quebrado, e nós sabemos

1. **`CaosDoDatTest` falha um teste por rodada, variando qual** (`Wearables SDK already
   initialized`). Falha em `HEAD` limpo. Precisa isolamento de processo por classe.
2. **O acerto subiu muito e o bloqueio mudou de lugar.** Escada medida no aparelho, 8
   amostras por `ΣE/ΣN`: `tiny` 62,5–100% → `base-q5_1` 38,5% → **`small-q5_1` 21,2%**
   (78,8% de acurácia) contra a meta de ≥92%. `guarnição` acerta **7/8** (era 0/8) e
   `Paiva` **2/2**. Dos 11 erros restantes, **6 são a palavra de ativação**, 4 são artigo
   inserido (inócuo — o roteador derruba artigos) e 1 é `guernicom`. Descontando artigo:
   13,5%; descontando também a ativação: **1,9%**.
   Custo: APK de **372 MiB** (modelo de 181 MiB). Acima do limite de APK do Play — para
   produto exige *asset delivery*; para o hackathon com `adb install`, serve.
3. **A palavra de ativação é o gargalo, e não é ortografia.** Grafias medidas: clarion ·
   **varyon** · **farion** · **parion** · **marcarion** · clarão. A plosiva inicial troca de
   modo e lugar (/k/→/v/,/f/,/p/,/m/) no mesmo áudio, o que **contradiz** o critério que a
   escolheu (`DECISIONS.md` 2026-08-14). Lista de variantes **não resolve** — cobrir essas
   cinco aceitaria qualquer dissílabo nasal. Saídas em
   [`specs/gatilho-por-voz.spec.md`](specs/gatilho-por-voz.spec.md): escolher **por medição**.
4. **A meta de earcon (≤500 ms) é inalcançável por construção:** dos ~605 ms medidos, **600
   são o hangover do Silero** — o caminho do earcon custa ~5 ms. Ou a meta muda, ou o
   hangover cai, e reduzi-lo corta fala em pausa intra-frase. É decisão de produto.
5. **A banda de 8 kHz não é a causa** (1,2× de WER, não 3×) e *bandwidth extension* piorou.
6. **O portão da palavra de ativação não existe.** A spec exige descartar em silêncio o que
   não começa por "claryon" (aceite A2); hoje qualquer fala com "mudar para X" trocaria de
   grupo. Depende do item 3.
7. `Stream.errorStream` não coletado · `STOPPED` não terminal · permissão de câmera do DAT
   nunca pedida · transcrição na origem (P1) não existe · `WakeWordDetector` sem impl.

## O que vem a seguir

1. **Escolher a palavra de ativação por medição** (item 3) — é o gargalo agora.
2. **Medir em arm64 real** — óculos + fone HFP. Falta o número de campo.
3. **Decidir o tamanho do APK** (372 MiB) e **a licença** de `specs/stt-portugues.spec.md`.
4. **Portão da ativação** · **Transcrição na origem** · **Etapa 5, prazo 22/08.**

**Pendências:** `security-crypto` `1.1.0-alpha06` · conferir se o documento submetido cita
WhatsApp (§14.1 veda mudar escopo) · APK de 372 MiB precisa *asset delivery* para produto.
