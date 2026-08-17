# Onde estamos — 2026-08-17 · Fase 2 · o gargalo é a palavra de ativação

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto é história e
vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · 50 msg/s → ~17. P1 corta a fala do
  copiloto em **11 ms** (aceite ≤200); `RotaSustentada` mantém a rota SCO pela rajada.
- **VAD Silero** no lugar do RMS: silêncio → 0, senoide **alta** → 0, fala → 98%.
- **Troca de grupo por voz ligada**, chamador verificado por `grep` elo por elo. Sem
  casamento aproximado (proibição de spec); recusa não revela existência de grupo.
- **Régua de WER** (`Wer.kt`, 10 testes) e **verificador da corrente** de ponta a ponta.
- **A latência era build, não tecnologia.** O `CMakeLists` só aplicava `-O3` fora do Debug e
  os testes rodam no Debug: o ggml compilava sem otimização e com asserções ativas.
  **STT de 42 s para a casa das centenas de ms.** Os alvos do ggml são **três** e só
  `ggml-cpu` tem os kernels — achado no `compile_commands.json`.
- **As duas metas do ciclo têm amostra** (era "sem amostras" por fiação, não por bancada):
  earcon **~605 ms** (meta 500) · resposta **1905–2360 ms** (meta 2000) · **TTS + rota + fila
  ~540 ms**, que era **1218** antes de `SaidaUnica.aquecer()` — mais que o whisper inteiro.

## O que está quebrado, e nós sabemos

1. **`CaosDoDatTest` falha um teste por rodada, variando qual** (`Wearables SDK already
   initialized`). Falha em `HEAD` limpo. Precisa isolamento de processo por classe.
2. **O ganho do `+dotprod` está SEM atribuição, e o commit `ef5cd1b` afirmou 4× errado.**
   `llvm-objdump` mostrou **`sdot = 0`** naquele binário: a flag não chegava aos kernels
   porque `FetchContent` torna `ggml-cpu` um alvo **único** e `target_compile_options`
   acumula — `quants.c` saía com dois `-march` e o último vencia. Consertado (`sdot = 923`),
   mas o emulador saturado deu **3171/685/2724 ms na mesma rodada** e não dá para atribuir.
   Precisa de máquina ociosa. O esquema de três `libwhisper` era ficção: diferiam 16 bytes.
3. **SIGILL latente em aparelho sem FEAT_FP16.** O `libggml-cpu.so` compartilhado tem 1765
   instruções FP16 vetoriais, e o `libwhisper.so` — escolhido justamente quando
   `/proc/cpuinfo` **não** tem `fphp` — depende dele. Piso agora **declarado** no
   `CMakeLists` (ARMv8.2 + FP16 + DotProd); a saída real é `GGML_CPU_ALL_VARIANTS`, não tomada.
4. **O acerto subiu muito e o bloqueio mudou de lugar.** Escada medida, 8 amostras por
   `ΣE/ΣN`: `tiny` 62,5–100% → `base-q5_1` 38,5% → **`small-q5_1` 21,2%** (78,8%) contra
   meta ≥92%. `guarnição` acerta **7/8** (era 0/8) e `Paiva` **2/2**. Dos 11 erros, **6 são a
   palavra de ativação**, 4 são artigo inserido (inócuo — o roteador derruba artigos) e 1 é
   `guernicom`. Descontando artigo: 13,5%; descontando também a ativação: **1,9%**.
   ⚠️ **A régua está contaminada:** o `initial_prompt` contém as palavras das frases do bench.
   Custo: APK de **372 MiB**, acima do limite do Play — exige *asset delivery* para produto.
5. **A palavra de ativação é o gargalo, e não é ortografia.** Grafias medidas: clarion ·
   **varyon** · **farion** · **parion** · **marcarion** · clarão. A plosiva inicial troca de
   modo e lugar (/k/→/v/,/f/,/p/,/m/) no mesmo áudio, o que **contradiz** o critério que a
   escolheu (`DECISIONS.md` 2026-08-14). Lista de variantes **não resolve**. Saídas em
   [`specs/gatilho-por-voz.spec.md`](specs/gatilho-por-voz.spec.md): escolher **por medição**.
6. **A meta de earcon (≤500 ms) é inalcançável por construção:** dos ~605 ms, **600 são o
   hangover do Silero** — o caminho custa ~5 ms. Ou a meta muda, ou o hangover cai, e
   reduzi-lo corta fala em pausa intra-frase. É decisão de produto.
7. **A banda de 8 kHz não é a causa** (1,2× de WER, não 3×); *bandwidth extension* piorou.
8. **O portão da ativação não existe:** hoje qualquer fala com "mudar para X" trocaria de
   grupo. Depende do item 5.
9. `Stream.errorStream` não coletado · `STOPPED` não terminal · permissão de câmera do DAT
   nunca pedida · transcrição na origem (P1) não existe · `WakeWordDetector` sem impl.

## O que vem a seguir

1. **Escolher a palavra de ativação por medição** (item 5) — é o gargalo.
2. **Re-medir `+dotprod` em máquina ociosa** (item 2) e **descontaminar a régua** (item 4).
3. **Medir em arm64 real** — óculos + fone HFP · **decidir APK de 372 MiB e a licença**.
4. **Portão da ativação** · **Transcrição na origem** · **Etapa 5, prazo 22/08.**

**Pendências:** `security-crypto` `1.1.0-alpha06` · conferir se o documento submetido cita
WhatsApp (§14.1 veda mudar escopo) · isolar `CaosDoDatTest`.
