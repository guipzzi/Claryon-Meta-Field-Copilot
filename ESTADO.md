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
  mentir: `whisper (custo real)` e `fim da fala → transcrição pronta` são separadas.
- **Earcon: 605 → 305 ms, dentro da meta de 500.** O hangover era `0.6f`, copiado do
  `EnergyVoiceActivityDetector(hangoverMs = 600)` — parâmetro de detector RMS num detector
  neural; o default do binding é `0.25f`. 0,3 s fica acima do piso da juntura intra-frase em
  pt-BR (250 ms, Oliveira 2002) e casa com o *endpointer transacional* da Alexa (P50 300 ms).
- **A régua limpa passa a meta: WER 3,4%, acurácia 96,6%** (meta ≥92%). Os dois erros são
  `baldio`, palavra rara.

## O que está quebrado, e nós sabemos

1. **`CaosDoDatTest` falha um teste por rodada, variando qual** (`Wearables SDK already
   initialized`). Falha em `HEAD` limpo. Precisa isolamento de processo por classe.
2. **`+dotprod` sem atribuição** (`ef5cd1b` afirmou 4× errado): `objdump` deu `sdot = 0`,
   as opções acumulam no alvo único `ggml-cpu`. Consertado (`sdot = 923`), falta máquina
   ociosa. **SIGILL latente sem FEAT_FP16:** o `libggml-cpu.so` tem 1765 instruções FP16 e o
   `libwhisper.so` (escolhido sem `fphp`) depende dele; piso agora declarado.
3. **A régua de operação (21,2%) é contaminada e estatisticamente cega:** 20 das 52 palavras
   estão no `initial_prompt`, e com `ΣN = 52` o IC95% é **[10,1%; 32,3%]**. Precisa de
   `ΣN ≈ 500`. O prompt estava **sem acento** e o BPE difere — corrigido.
   **A premissa do APK estava errada:** 372 MiB não excede o limite do Play (via AAB o base
   admite 500 MB). Decisão: manter tudo no APK e cortar o x86_64 do release (→ ~295 MiB).
4. **A palavra de ativação foi MEDIDA: `Aurora` 3/3 contra `Claryon` 0/3** (controle). O
   traço discriminativo **não pode estar na consoante inicial** — as três que falharam
   tiveram o *onset* corrompido; as duas que passaram começam por vogal. Falta o número que
   decide: **falso positivo**. O portão não existe: `grep -ri claryon` em `src/main` = zero.
5. **Resposta falada: 2144 ms contra 2000.** A decomposição estava errada: ≥290 ms é o
   earcon `ACAO_EXECUTADA` tocando **serialmente** antes da síntese; a rota contribui zero;
   `generateWithCallback` daria 0 ms (`max_num_sentences = 1`); os "332 ms de rede" não eram.
6. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render` — enquanto o
   TTS sintetiza (~1,5 s) o P1 não corta, e o instrumento não gera amostra.
7. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida ·
   transcrição na origem (P1) não existe · `WakeWordDetector` sem implementação.

**Pendências:** `security-crypto` `1.1.0-alpha06` · conferir se o documento submetido cita
WhatsApp (§14.1 veda mudar escopo) · isolar `CaosDoDatTest`.

## O que vem a seguir

**A Fase 2 não vence em 22/08:** o `ROADMAP` a agenda para **30/08–05/09**. O prazo de 22/08
é da **Fase 0** — documento e deck no template, meia página de risco do art. 38 da LGPD, e
reler a proposta atrás de menção a WhatsApp. O aceite (d) falha hoje: decisão humana.

1. **Entregáveis de 22/08** (Fase 0), com a capacidade que **existe**: copiloto por botão,
   STT, TTS e troca de grupo falada. Não o gatilho por voz.
2. **Decisão sobre `Aurora`** e, se aprovada, medir o **falso positivo** em fala espontânea.
3. **Bench `ΣN ≈ 500`** · **re-medir `+dotprod`** ocioso · **medir em arm64 real**.
