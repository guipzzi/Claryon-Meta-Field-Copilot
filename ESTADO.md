# Onde estamos — 2026-08-18 · Fase 3: canal privado, revogação e autoria conferível

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · P1 corta a fala em **11 ms** (aceite ≤200).
- **STT na régua limpa: WER 3,4%, acurácia 96,6%** (meta ≥92%) · earcon **305 ms** (meta 500).
- **CANAL PRIVADO POR JWT** (`0012`). Provado por matriz: membro entra · **não-membro recebe
  `Unauthorized`** · canal público deixava qualquer um entrar. O token vai no `phx_join` e
  **é renovado pelo `exp`** — o JWT vive **60 min** (medido), e sem renovação o rádio cairia
  no meio do turno.
- **O indicador de canal parou de mentir.** O app mostrava verde e mandou **168 quadros para
  um canal em que não entrou**, porque `conectado()` era só "o socket abriu". Agora
  `CanalRecusado` é evento e o PTT recusa — verificado no emulador, antes e depois.
- **REVOGAÇÃO INSTITUCIONAL** (`0014`): `agents.ativo` dentro de `current_agent_id()`.
  Provado com o **mesmo JWT** — `ativo=false` derruba cadastro, consulta e canal juntos.
- **AUTORIA CONFERÍVEL** (`0013` + `0015`): o indicativo saiu do fio — vem do cadastro do
  grupo e é conferido contra `floor_grants`, onde `pedir_canal` carimba o autor pelo JWT.
  Divergência vira *"Origem não confirmada"*; **"não sei" não derruba**.
- **DETECTOR DE ATIVAÇÃO no aparelho**: **26/26** em fluxo · **p50 3,5 ms** · zero dependência.
- **Falso positivo do detector: 428/h → 0** na metade retida de 3,65 min de leitura, com
  recall intacto (9/9) — treinando com negativo duro, não mexendo no limiar.
- **TRANSCRIÇÃO NA ORIGEM (P1) — verificada ponta a ponta com fala humana e servidor real.**
  5,0 s de gravação do agente → canal privado por JWT → acumulador **80 000 amostras = os
  5,0 s exatos** → whisper → `fala.transcricao`. O par headless recebeu anúncio, **251
  quadros**, fim e o texto **idêntico**: *"Clareon, Guarney são 1 na escuta…"*. Só o
  microfone é simulado; do `SessaoPtt` em diante nada é.
- **Ferramentas:** `par_headless.mjs` · `sonda_de_politica.py` (restaura e recusa produção).

## O que está quebrado, e nós sabemos

1. **O detector NÃO tem chamador em `src/main` do app.** Pela régua do §6 isto é **escrito**,
   não construído — o erro que este projeto já cometeu seis vezes.
2. **O falso positivo ainda não tem intervalo útil.** `0` em 1,8 min retidos dá limite
   superior de **~99/h** com 95%; a meta é **0,5/h**, e isso exige da ordem de **6 h**.
3. **O modelo é de um locutor só**, 27 elocuções. Generalizou para três inéditos (0,963 ·
   0,996 · 0,999), mas com `n=4`.
4. **A via por transcrição tem teto estrutural** — 8 hipóteses, 6 refutadas. Marca exata
   33,3%. Detalhe em [`docs/PALAVRA_DE_ATIVACAO.md`](docs/PALAVRA_DE_ATIVACAO.md).
5. **O Piper NÃO é determinístico** e como treino **piora**: derruba a margem de +0,109 a −0,020.
6. **`CaosDoDatTest` falha um teste por rodada**, variando qual; falha em `HEAD` limpo.
7. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render`.
8. **Não há ambiente separado para experimento de servidor.** A sonda recusa produção sem
   `--producao "motivo"`, mas a pilha local exige Docker, que não sobe nesta máquina.
9. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida ·
   transcrição na origem (P1) não existe · SIGILL latente sem FEAT_FP16.

**Pendências:** `security-crypto` `1.1.0-alpha06` · três cláusulas da Fase 2 presas a
dispositivo HFP (ver `docs/VERIFICACOES_COM_HARDWARE.md`).

## O que vem a seguir — Fase 3

Cinco fechados (JWT, piso, revogação, autoria, **transcrição na origem**). Faltam:

1. **Dono único da escrita de posição**, e depois batimento com idade real do servidor.
2. **Log de acesso** nas duas portas — autor de `current_agent_id()`, nunca do protocolo.
3. Retenção em duas camadas — **ou entra inteira** (turno + job + log na mesma sessão)
   **ou não entra**, pela regra de sequenciamento do próprio roadmap.
