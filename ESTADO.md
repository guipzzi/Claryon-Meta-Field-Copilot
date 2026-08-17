# Onde estamos — 2026-08-17 · o STT passa a meta · o gargalo é a palavra de ativação

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto é história e
vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · 50 msg/s → ~17 · P1 corta a fala em
  **11 ms** (aceite ≤200) · `RotaSustentada` mantém a rota SCO pela rajada.
- **VAD Silero**: silêncio → 0, senoide alta → 0, fala → 98%. **Troca de grupo por voz
  ligada**, sem casamento aproximado, com chamador verificado elo por elo.
- **Régua de WER** (`Wer.kt`) e **verificador da corrente** de ponta a ponta.
- **A latência era build, não tecnologia.** O `CMakeLists` só aplicava `-O3` fora do Debug e
  os testes rodam no Debug: o ggml compilava sem otimização. **STT de 42 s para centenas de
  ms.** Os alvos do ggml são três e só `ggml-cpu` tem os kernels.
- **As duas metas do ciclo têm amostra** (era fiação, não bancada) e a telemetria parou de
  mentir: `whisper (custo real)` e `fim da fala → transcrição pronta` são transições
  separadas, e a diferença entre elas é exatamente o hangover.
- **Earcon: 605 → 305 ms, dentro da meta de 500.** O hangover era `0.6f`, copiado do
  `EnergyVoiceActivityDetector(hangoverMs = 600)` — parâmetro de detector RMS num detector
  neural; o default do binding é `0.25f`. 0,3 s fica acima do piso da juntura intra-frase em
  pt-BR (250 ms, Oliveira 2002) e casa com o *endpointer transacional* da Alexa (P50 300 ms).
- **A régua limpa passa a meta: WER 3,4%, acurácia 96,6%** (meta ≥92%), com frases sem
  colisão com o `initial_prompt`. Os dois erros são `baldio`, palavra rara.

## O que está quebrado, e nós sabemos

1. **`CaosDoDatTest` falha um teste por rodada, variando qual** (`Wearables SDK already
   initialized`). Falha em `HEAD` limpo. Precisa isolamento de processo por classe.
2. **O ganho do `+dotprod` está SEM atribuição; o commit `ef5cd1b` afirmou 4× errado.**
   `llvm-objdump`: **`sdot = 0`** naquele binário — `FetchContent` torna `ggml-cpu` um alvo
   único e as opções acumulam, então o último `-march` vencia. Consertado (`sdot = 923`), mas
   o emulador saturado deu 3171/685/2724 ms na mesma rodada. Precisa de máquina ociosa.
3. **SIGILL latente sem FEAT_FP16:** o `libggml-cpu.so` compartilhado tem 1765 instruções
   FP16, e o `libwhisper.so` — escolhido quando **não** há `fphp` — depende dele. Piso agora
   declarado no `CMakeLists`; a saída real é `GGML_CPU_ALL_VARIANTS`, não tomada.
4. **A régua de operação (21,2%) é contaminada e estatisticamente cega:** 20 das 52 palavras
   estão no `initial_prompt`, e com `ΣN = 52` o IC95% é **[10,1%; 32,3%]**. Precisa de
   `ΣN ≈ 500`. O prompt estava **sem acento** e o BPE difere — corrigido.
   **A premissa do APK estava errada:** 372 MiB não excede o limite do Play (via AAB o base
   admite 500 MB). Decisão: manter tudo no APK e cortar o x86_64 do release (→ ~295 MiB).
5. **A palavra de ativação é o gargalo, e não é ortografia.** Grafias medidas: clarion ·
   **varyon** · **farion** · **parion** · **marcarion** · clarão. A plosiva inicial troca de
   modo e lugar (/k/→/v/,/f/,/p/,/m/) no mesmo áudio, o que **contradiz** o critério que a
   escolheu (`DECISIONS.md` 2026-08-14). Lista de variantes **não resolve**. Saídas em
   [`specs/gatilho-por-voz.spec.md`](specs/gatilho-por-voz.spec.md): escolher **por medição**.
6. **Resposta falada fora da meta: 2144 ms contra 2000.** A decomposição de "TTS + rota"
   estava errada: ≥290 ms é o earcon `ACAO_EXECUTADA` tocando **serialmente** antes da
   síntese; a rota contribui zero; `generateWithCallback` daria 0 ms
   (`max_num_sentences = 1`); e os "332 ms de rede" não eram rede.
7. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render`. Enquanto o
   TTS sintetiza (~1,5 s) o P1 não corta, e o instrumento não gera amostra disso.
8. **O portão da ativação não existe:** hoje qualquer fala com "mudar para X" trocaria de
   grupo. Depende do item 5. A banda de 8 kHz **não** é a causa de nada (1,2× de WER).
9. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida ·
   transcrição na origem (P1) não existe · `WakeWordDetector` sem implementação.

## O que vem a seguir

**A Fase 2 não vence em 22/08:** o `ROADMAP` a agenda para **30/08–05/09**. O prazo de 22/08
é da **Fase 0** — documento e deck no template, meia página de risco do art. 38 da LGPD, e
reler a proposta atrás de menção a WhatsApp. O aceite (d) da Fase 0 falha hoje e é decisão
humana de uma linha: reescrever o critério ou os comentários das Edge Functions.

1. **Entregáveis de 22/08** (Fase 0), descrevendo a capacidade que **existe**: copiloto por
   botão com STT, TTS e troca de grupo falada, 100% local. Não o gatilho por voz.
2. **Escolher a palavra de ativação por medição** — é o gargalo do acerto.
3. **Bench com `ΣN ≈ 500`** e os braços com/sem prompt; **re-medir `+dotprod`** em máquina
   ociosa; **medir em arm64 real** com óculos e fone.

**Pendências:** `security-crypto` `1.1.0-alpha06` · conferir se o documento submetido cita
WhatsApp (§14.1 veda mudar escopo) · isolar `CaosDoDatTest`.
