# Onde estamos — 2026-08-17 · o detector de ativação roda no aparelho

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · 50 msg/s → ~17 · P1 corta a fala em
  **11 ms** (aceite ≤200) · `RotaSustentada` mantém a rota SCO pela rajada.
- **VAD Silero**: silêncio → 0, senoide alta → 0, fala → 98%. **Troca de grupo por voz
  ligada**, sem casamento aproximado, com chamador verificado elo por elo.
- **STT na régua limpa: WER 3,4%, acurácia 96,6%** (meta ≥92%) · earcon **305 ms** (meta 500).
- **DETECTOR DE ATIVAÇÃO, no aparelho** (`DetectorDeAtivacao` + `ativacao_jni.c`):
  - **26 de 26** elocuções em fluxo contínuo — o número exato da bancada em Python, o
    que prova que o mel e o empilhamento reescritos em C reproduzem o treino.
  - **p50 3,5 ms** por decisão, passo de 80 ms → **4,4% de um núcleo**.
  - Latência (medida em Python): **mediana −20 ms** em relação ao fim da palavra.
  - **Zero dependência nova.** `libonnxruntime.so` já vinha no APK pelo AAR do sherpa e
    já exportava `OrtGetApiBase`; faltava chamador, não motor. Cabeça = 1156 bytes.

## O que está quebrado, e nós sabemos

1. **O detector NÃO tem chamador em `src/main` do app.** Ele é `core-voice/src/main` com
   teste instrumentado, e nada no rádio o invoca. Pela régua do §6 isto é **escrito**,
   não construído — e é o erro que este projeto já cometeu seis vezes.
2. **Falso positivo sem taxa medida.** O único negativo humano são **3,8 s** de fala.
   `0 disparos` ali é ausência de amostra, não garantia; `0/30` é `[0%; 11,6%]` com 95%.
   A métrica que decide é **falsos por hora** de fala espontânea, e ela não existe.
   Enquanto não existir, ligar o detector no rádio pode tomar o piso da guarnição.
3. **O modelo é de um locutor só**, com 27 elocuções aumentadas. Generalizou para três
   locutores inéditos (0,963 · 0,996 · 0,999) mas com `n=4` no teste.
4. **A via por transcrição tem teto estrutural** — 8 hipóteses, 6 refutadas. Marca exata
   33,3%, regra da rima 66,7% com 3 falsos em 30. Detalhe em
   [`docs/PALAVRA_DE_ATIVACAO.md`](docs/PALAVRA_DE_ATIVACAO.md).
5. **O Piper NÃO é determinístico** (48640/45355/44820 amostras na mesma frase) e como
   dado de treino ele **piora**: acrescentá-lo a dados humanos derruba a margem de
   +0,109 para −0,020. Diferenças de poucos pontos em qualquer bancada são ruído.
6. **`CaosDoDatTest` falha um teste por rodada, variando qual** (`Wearables SDK already
   initialized`); falha em `HEAD` limpo. Precisa isolamento de processo por classe.
7. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render`.
8. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida ·
   transcrição na origem (P1) não existe · **`+dotprod`** consertado (`sdot = 923`) mas
   falta máquina ociosa para re-medir · SIGILL latente sem FEAT_FP16.

**Pendências:** `security-crypto` `1.1.0-alpha06` · conferir se o documento cita WhatsApp (§14.1).

## O que vem a seguir

**O gargalo é gravação, não código.** Faltam ~10–20 min de fala espontânea do Guido, sem
dizer a palavra: com isso o falso positivo ganha taxa e o limiar sai de `0,5` — que hoje é
convenção, não medida — para um ponto escolhido sobre curva ROC.

1. **Medir falsos por hora.** Sem esse número o detector não entra no caminho do rádio.
2. **Ligar o detector**: `FonteUnicaDeMicrofone` → detector → earcon → o mesmo
   `IntentExecutor` do botão. Só depois de (1).
3. Mover os modelos (2,4 MB) para `app/src/main/assets` — hoje vivem só nas assets de teste.
4. Medir em arm64 real · re-medir `+dotprod` ocioso · isolar `CaosDoDatTest`.
