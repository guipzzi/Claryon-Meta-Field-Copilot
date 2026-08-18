# Onde estamos — 2026-08-18 · canal privado por JWT, e o indicador parou de mentir

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · P1 corta a fala em **11 ms** (aceite
  ≤200) · `RotaSustentada` mantém a rota SCO pela rajada.
- **STT na régua limpa: WER 3,4%, acurácia 96,6%** (meta ≥92%) · earcon **305 ms** (meta 500).
- **CANAL PRIVADO POR JWT** (migração `0012` aplicada). Provado por matriz, não por
  argumento: membro entra · **não-membro recebe `Unauthorized`** · canal público deixava
  qualquer um entrar. `TransporteRealtime` leva o token no `phx_join` e **o renova pelo
  `exp`** — o JWT vive **60 min** (medido), e sem renovação o rádio cairia no meio do turno.
- **O indicador de canal parou de mentir.** Ao ligar o privado apareceu defeito pior que o
  fechado: o app mostrava **verde** e mandou **168 quadros em 4 s para um canal em que não
  entrou**. `conectado()` era só "o socket abriu", e `interpretar` descartava o `phx_reply`
  de recusa. Agora `CanalRecusado` é evento, `conectado()` exige join aceito, o PTT recusa
  e a tela diz *"Canal negado: Unauthorized…"* — verificado no emulador, antes e depois.
- **DETECTOR DE ATIVAÇÃO no aparelho** (`DetectorDeAtivacao` + `ativacao_jni.c`): **26 de 26**
  elocuções em fluxo — o número exato da bancada em Python · **p50 3,5 ms** por decisão
  (4,4% de um núcleo) · **zero dependência nova**, a `libonnxruntime.so` já vinha no APK.
- **Falso positivo do detector: 428/h → 0** na metade retida de 3,65 min de leitura, com
  recall intacto (9/9). A correção foi treinar com negativo duro, não mexer no limiar.
- **Par headless** (`servidor/par_headless.mjs`, zero dependência): o segundo ouvinte do
  aceite, sem segundo aparelho. Fecha também a cláusula pendente da Fase 2.

## O que está quebrado, e nós sabemos

1. **O detector NÃO tem chamador em `src/main` do app.** Ele é `core-voice/src/main` com
   teste instrumentado, e nada no rádio o invoca. Pela régua do §6 isto é **escrito**, não
   construído — o erro que este projeto já cometeu seis vezes.
2. **O falso positivo ainda não tem intervalo útil.** `0` em 1,8 min retidos dá limite
   superior de **~99/h** com 95%; a meta do roadmap é **0,5/h** para o que só toca earcon,
   e isso exige da ordem de **6 h** de fala com zero disparo.
3. **O modelo é de um locutor só**, com 27 elocuções aumentadas. Generalizou para três
   locutores inéditos (0,963 · 0,996 · 0,999), mas com `n=4` no teste.
4. **A via por transcrição tem teto estrutural** — 8 hipóteses, 6 refutadas. Marca exata
   33,3%. Detalhe em [`docs/PALAVRA_DE_ATIVACAO.md`](docs/PALAVRA_DE_ATIVACAO.md).
5. **O Piper NÃO é determinístico** e como dado de treino **piora**: acrescentá-lo a dados
   humanos derruba a margem de +0,109 para −0,020.
6. **`CaosDoDatTest` falha um teste por rodada, variando qual**; falha em `HEAD` limpo.
7. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render`.
8. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida ·
   transcrição na origem (P1) não existe · SIGILL latente sem FEAT_FP16.

**Pendências:** `security-crypto` `1.1.0-alpha06` · três cláusulas da Fase 2 presas a
dispositivo HFP (ver `docs/VERIFICACOES_COM_HARDWARE.md`).

## O que vem a seguir — Fase 3

O item 1 da fase está fechado e verificado, e ele destrava outros três.

1. **Indicativo derivado do JWT**, nunca do payload: hoje é string livre não verificada, e
   um P1 forjado em nome de outra guarnição toma o canal por desenho.
2. **Coluna `ativo` em `agents`** dentro de `current_agent_id()` — revogação institucional.
3. **Transcrição na origem** (P1): acumulador do PCM que foi ao ar, whisper no `finally`,
   quarto evento `fala.transcricao`.
4. Retenção em duas camadas — **ou entra inteira** (turno + job + log de acesso na mesma
   sessão) **ou não entra**, pela regra de sequenciamento do próprio roadmap.
