# Onde estamos — 2026-08-17 · a palavra de ativação tem teto estrutural, e ele foi medido

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · 50 msg/s → ~17 · P1 corta a fala em
  **11 ms** (aceite ≤200) · `RotaSustentada` mantém a rota SCO pela rajada.
- **VAD Silero**: silêncio → 0, senoide alta → 0, fala → 98%. **Troca de grupo por voz
  ligada**, sem casamento aproximado, com chamador verificado elo por elo.
- **STT passa a meta na régua limpa: WER 3,4%, acurácia 96,6%** (meta ≥92%). A latência
  era build, não tecnologia: o `CMakeLists` só aplicava `-O3` fora do Debug — 42 s → centenas de ms.
- **Earcon 605 → 305 ms**, dentro da meta de 500. Régua de WER (`Wer.kt`) e verificador
  da corrente de ponta a ponta.
- **A cauda do comando sai perfeita**: `mudar para a guarnição 4`, `encerrar gravação`,
  `solicitar reforço`. O que o whisper erra é só o nome inventado.

## O que está quebrado, e nós sabemos

1. **A ativação por voz tem teto estrutural — 8 hipóteses medidas, 6 refutadas.** Detalhe
   e números em [`docs/PALAVRA_DE_ATIVACAO.md`](docs/PALAVRA_DE_ATIVACAO.md). O essencial:
   - **KWS dedicado do sherpa está fora de domínio.** Controle canônico 3/3 em inglês real;
     o Piper pt-BR dizendo **"Alexa"**, a chave de fábrica, dá **0/4**. Grafia e banda
     estreita foram descartadas como causa (0/8 nas duas bandas, grade de 9 pontos).
   - **O prior nunca nomeou a marca.** Corrigido isso: **0% → ~35%**, com 0 falso positivo.
   - **O portão hoje:** marca exata **33,3%** com **0/30** de falso positivo; regra da rima
     `-on`/`-om` **66,7%** com **3/30**. A meta é 90% e zero. Nenhum chega.
   - A causa é fonética: /kl/ é oclusiva velar, transiente curto e agudo, e morre em 8 kHz —
     sai `varyon`, `faryon`, `haryon`, `quaryon`, `carion`. A rima sobrevive; o ataque não.
2. **O Piper NÃO é determinístico** — a mesma frase deu 48640, 45355 e 44820 amostras (VITS
   sorteia a duração de cada fonema). O whisper repete. **Consequência: a mesma condição
   medida duas vezes deu 29,2% e 41,7%** — diferenças de poucos pontos em qualquer bancada
   deste projeto são ruído, e daqui em diante exigem `n` maior e intervalo de confiança.
3. **Duas afirmações minhas foram refutadas e os documentos foram corrigidos:** "o
   decodificador erra sempre do mesmo jeito" (eram 18 formas em 18 rendições) e "a grafia da
   chave era a causa". `VariantesDoParTest` e `KwsDeClaryonTest` já contam a versão medida.
4. **`CaosDoDatTest` falha um teste por rodada, variando qual** (`Wearables SDK already
   initialized`); falha em `HEAD` limpo. Precisa isolamento de processo por classe.
5. **`+dotprod` consertado** (`sdot = 923`), falta máquina ociosa para re-medir. **SIGILL
   latente sem FEAT_FP16**: `libggml-cpu.so` tem 1765 instruções FP16; piso já declarado.
6. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render` — enquanto o
   TTS sintetiza (~1,5 s) o P1 não corta, e o instrumento não gera amostra.
7. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida ·
   transcrição na origem (P1) não existe · `WakeWordDetector` sem implementação · o portão
   não existe em produção: `grep -ri claryon` em `src/main` = zero.

**Pendências:** `security-crypto` `1.1.0-alpha06` · conferir se o documento cita WhatsApp (§14.1).

## O que vem a seguir

**Decisão humana pendente, e ela é de dependência.** Paridade com a Alexa não sai do caminho
por transcrição — o teto é estrutural. A via conhecida é **treinar um detector acústico** com
dados gerados pelo Piper (que agora sabemos variar sozinho, o que é bom para isso), inferindo
em ONNX. Custa `onnxruntime-android` — o AAR do sherpa traz só o `.so`, sem API Java — e uma
etapa de treino fora do repositório. `CLAUDE.md` §2 exige tamanho, licença e alternativa antes.

1. **Entregáveis de 22/08** (Fase 0) com a capacidade que **existe**: copiloto por botão, STT,
   TTS e troca de grupo falada. **Não** o gatilho por voz. O aceite (d) falha hoje.
2. **Se a dependência for aprovada:** gerar corpus, treinar, medir recall e falso positivo.
3. **Bench `ΣN ≈ 500`** · re-medir `+dotprod` ocioso · medir em arm64 real.
