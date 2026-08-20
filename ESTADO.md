# Onde estamos — 2026-08-20 · Fase 3 fechada, com o batimento e a idade real

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **687 testes JVM, 0 falhas, 0 pulados** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (meta 120) · P1 corta em **11 ms** (aceite ≤200) ·
  **STT: WER 3,4%** (meta ≥92% de acurácia) · earcon **305 ms** (meta 500).
- **CANAL PRIVADO POR JWT** (`0012`): membro entra, **não-membro recebe `Unauthorized`**, e o
  token é renovado pelo `exp` — o JWT vive 60 min e sem renovação o rádio cairia no turno.
- **O indicador de canal parou de mentir**: mostrava verde e mandou **168 quadros para um canal
  em que não entrou**. Agora `CanalRecusado` é evento e o PTT recusa.
- **REVOGAÇÃO** (`0014`) · **AUTORIA CONFERÍVEL** (`0013`+`0015`): o nome vem do cadastro e é
  conferido contra `floor_grants`; divergência vira *"Origem não confirmada"*.
- **DETECTOR DE ATIVAÇÃO no aparelho**: **26/26** em fluxo · p50 3,5 ms · falso positivo
  **428/h → 0** com negativo duro, recall intacto.
- **TRANSCRIÇÃO NA ORIGEM (P1)**, ponta a ponta com fala humana e servidor real: 5,0 s →
  acumulador **80 000 amostras** → whisper → o par headless recebeu **251 quadros** e o texto
  **idêntico**. Só o microfone é simulado.
- **DONO ÚNICO DA POSIÇÃO** (`0016`): as políticas abertas foram **exploradas** — POST direto
  gravou `updated_at = 2099`, permanentemente "fresco" para a guarnição.
- **LOG DE ACESSO** (`0017`+`0018`): consulta vira linha, mapa vira **sessão** com teto de 1 h,
  **nunca grava a resposta**. **RETENÇÃO** (`0019`) inteira, com job no `pg_cron`.
- **O PISO ERA LOCAL O TURNO INTEIRO.** `pisoDoCanal` lia o cache `tokenCorrente`, vazio logo
  após o login, e a decisão se toma uma vez em `abrir` — dois agentes podiam achar que detinham
  o canal. Achado **pela tela**, que teste nenhum acharia: o instrumentado constrói a sessão
  antes, o app não. Provado pelo positivo: durante 8 s de fala, `floor_grants` traz Bravo Um.
- **BATIMENTO ALCANÇÁVEL** — ele não existia. O `minDistance` suprime a entrega (*"the potential
  location update will not occur"*, AOSP): agente parado não recebia callback e a linha do
  batimento nunca rodava. Doía mais em Ocorrência, onde o agente chega e fica. Emulador, parado,
  3,5 min: **5 publicações com o conserto, 1 sem**. E 3 min de batimento contra 2 min de
  `OBSOLETO_S` deixavam todo agente parado esmaecido — agora 60 s em serviço.
- **IDADE REAL DA CORREÇÃO** (`0020`): `updated_at` é hora do UPLOAD e cinco funções a liam como
  idade — quem reconectava após 4 min sem sinal entrava como `idade_s = 0` e `agentes_no_raio` o
  contava como "está perto". O cliente manda **duração** (`elapsedRealtimeNanos`), nunca
  instante: `now() - greatest(0, idade)` não produz futuro **por construção**. Verificador `0009`
  17/17, com o par decisivo — a mesma linha dá 0 pelo filtro novo e 1 pelo antigo.
- **PORTA DE CORREÇÃO**: degradação relativa (teto fixo mataria o Standby), salto por **incerteza
  combinada** (distância crua mataria a rede) e **válvula de 3 recusas** — sem ela um salto
  verdadeiro congela o marcador, pior que sumir porque mente parado. E `ultimaPosicao()` decide
  idade antes de precisão.
- **Ferramentas:** `par_headless.mjs` · `sonda_de_politica.py` · dois agentes autenticados.

## O que está quebrado, e nós sabemos

1. **O detector NÃO tem chamador em `src/main` do app** — pela régua do §6 é **escrito**, não
   construído: o erro que este projeto já cometeu seis vezes.
2. **O falso positivo não tem intervalo útil.** `0` em 1,8 min dá limite superior de **~99/h**;
   a meta é **0,5/h**, e isso exige da ordem de **6 h** de leitura.
3. **O modelo é de um locutor só**, 27 elocuções; generalizou para três inéditos com `n=4`.
4. **A via por transcrição tem teto estrutural** — 8 hipóteses, 6 refutadas, marca 33,3%.
5. **`CaosDoDatTest` falha um teste por rodada**, variando qual; falha em `HEAD` limpo.
6. **Buraco no aceite (b):** a preempção de P1 não alcança a fase de `render`.
7. **Sem ambiente separado para experimento de servidor** — a pilha local exige Docker.
8. **`medida_em` é otimista pelo tempo de ida da requisição**, sempre nessa direção: centenas de
   ms contra limiares de 120 s, 0,4%. Registrado em `0020` para não virar achado.
9. **A porta de correção nunca viu GPS ruim de verdade** — testes sintéticos, emulador sempre 5 m.
10. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida · três
    cláusulas da Fase 2 presas a dispositivo HFP · `security-crypto` em alpha.

## O que vem a seguir

**A Fase 3 fechou inteira** — nove itens, o último o batimento com idade real. Agora: **ligar o
detector de ativação** (item 1 acima, o mais antigo dos quebrados), e então Fase 4 e Fase 5.
