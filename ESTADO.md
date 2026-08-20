# Onde estamos — 2026-08-20 · Fase 3 fechada: rede, posição e retenção

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **648 testes JVM, 0 falhas** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · P1 corta em **11 ms** (aceite ≤200) ·
  **STT: WER 3,4%** (meta ≥92% de acurácia) · earcon **305 ms** (meta 500).
- **CANAL PRIVADO POR JWT** (`0012`): membro entra, **não-membro recebe `Unauthorized`**,
  e o token é renovado pelo `exp` — o JWT vive 60 min e sem renovação o rádio cairia.
- **O indicador de canal parou de mentir**: mostrava verde e mandou **168 quadros para um
  canal em que não entrou**. Agora `CanalRecusado` é evento e o PTT recusa.
- **REVOGAÇÃO** (`0014`): `agents.ativo` em `current_agent_id()` — com o mesmo JWT,
  `ativo=false` derruba cadastro, consulta e canal juntos.
- **AUTORIA CONFERÍVEL** (`0013`+`0015`): o nome vem do cadastro e é conferido contra
  `floor_grants`; divergência vira *"Origem não confirmada"* e "não sei" não derruba.
- **DETECTOR DE ATIVAÇÃO no aparelho**: **26/26** em fluxo · p50 3,5 ms · zero dependência ·
  falso positivo **428/h → 0** com negativo duro, recall intacto.
- **TRANSCRIÇÃO NA ORIGEM (P1)**, verificada ponta a ponta com fala humana e servidor real:
  5,0 s do agente → acumulador **80 000 amostras = os 5,0 s exatos** → whisper → o par
  headless recebeu **251 quadros** e o texto **idêntico**. Só o microfone é simulado.
- **POSIÇÃO COM DONO ÚNICO** (`0016`): as políticas de INSERT/UPDATE estavam abertas e foram
  **exploradas** — POST direto gravou `updated_at = 2099`, permanentemente "fresco" para a
  guarnição. E o mapa parou de publicar: ~720 escritas/h que apagavam `speed_mps` e
  recarimbavam correção de 2 min como `idade_s = 0`.
- **LOG DE ACESSO** (`0017`+`0018`): consulta vira linha, mapa vira **sessão** com teto de
  1 h. **Nunca grava a resposta.** `quem_me_consultou()` sem parâmetro.
- **RETENÇÃO EM DUAS CAMADAS** (`0019`), inteira: turno com índice único parcial,
  `publicar_posicao` **recusando fora de turno**, encerramento por inatividade, trilha
  particionada por dia **sem índice geográfico**, `rastro_do_par` de 30 min só em grandeza,
  e job agendado — `pg_cron` não existia. A 1ª execução removeu **19 transmissões vencidas**.
- **Ferramentas:** `par_headless.mjs` · `sonda_de_politica.py` · dois agentes de teste
  autenticados (`123456789`, `987654321`) — o aceite pede dois JWTs distintos.

## O que está quebrado, e nós sabemos

1. **O detector NÃO tem chamador em `src/main` do app.** Pela régua do §6 isto é **escrito**,
   não construído — o erro que este projeto já cometeu seis vezes.
2. **O falso positivo ainda não tem intervalo útil.** `0` em 1,8 min retidos dá limite
   superior de **~99/h** com 95%; a meta é **0,5/h**, e isso exige da ordem de **6 h**.
3. **O modelo é de um locutor só**, 27 elocuções; generalizou para três inéditos com `n=4`.
4. **A via por transcrição tem teto estrutural** — 8 hipóteses, 6 refutadas, marca exata
   33,3%. Ver [`docs/PALAVRA_DE_ATIVACAO.md`](docs/PALAVRA_DE_ATIVACAO.md).
5. **`CaosDoDatTest` falha um teste por rodada**, variando qual; falha em `HEAD` limpo.
6. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render`.
7. **Sem ambiente separado para experimento de servidor.** A sonda recusa produção sem
   `--producao "motivo"`; a pilha local exige Docker, que não sobe nesta máquina.
8. **`medida_em` não existe**: `updated_at` é hora do UPLOAD, e todo consumidor a lê como
   idade da medição. Próximo item.
9. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida ·
   SIGILL latente sem FEAT_FP16.

**Pendências:** três cláusulas da Fase 2 presas a dispositivo HFP (ver
`docs/VERIFICACOES_COM_HARDWARE.md`) · `security-crypto` `1.1.0-alpha06`.

## O que vem a seguir

**A Fase 3 fechou.** Oito itens: JWT no canal, piso remoto, revogação, autoria,
transcrição na origem, dono único da posição, log de acesso e retenção em duas camadas.
O que resta da fase é o **batimento com idade real** (`medida_em`), que depende do dono
único e agora está destravado.

1. **`medida_em` sem confiar no relógio do cliente.** `now()` do servidor existe porque
   celular adiantado publicaria posição "do futuro". O caminho é o cliente mandar a **idade**
   da correção (`elapsedRealtimeNanos`, monotônico) e o servidor fazer `now() - idade`.
2. **Ligar o detector de ativação** no app — hoje sem chamador.
3. Fase 4 (conhecimento de domínio on-device) e Fase 5 (UX e ensaio).
