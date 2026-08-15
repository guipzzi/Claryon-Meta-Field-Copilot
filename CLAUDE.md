@AGENTS.md
@ESTADO.md

## Por que este arquivo é curto

Ele carrega duas coisas e nada mais: as regras permanentes (`AGENTS.md`) e o estado
da sessão (`ESTADO.md`). O material grosso **não é colado aqui de propósito**.

Antes, este arquivo inlinava `docs/PADROES_DE_ENGENHARIA.md` inteiro — 293 linhas
densas carregadas em toda sessão, inclusive nas que não tocavam em nada daquilo. É o
antipadrão que o próprio material do programa descreve: colar o documento inteiro faz
o modelo perder o meio dele e misturar versões de API.

Regra curta e permanente sempre carregada; trecho específico buscado só na tarefa que
precisa dele. O **gatilho** da busca está em `AGENTS.md` § "Antes de tocar" — é ele que
diz qual seção ler antes de mexer em áudio, em posição ou em fala. Sem gatilho, "leia
só o que a tarefa pede" não dispara: o agente não sabe o que não sabe.

- [`docs/INDICE.md`](docs/INDICE.md) — uma linha por documento e quando consultá-lo.
- [`specs/`](specs/) — uma spec por feature, aceite em EARS. Revisada **antes** do diff.
- [`DECISIONS.md`](DECISIONS.md) — por que está assim e o que foi descartado. É
  cronológico e tem mais de mil linhas: serve para arqueologia, **não** para saber onde
  estamos. Para isso existe `ESTADO.md`.

## Fluxo de trabalho

- Um marco por sessão. Ao concluir, apresente o critério de aceite atendido e pare para
  revisão humana.
- Mudança de comportamento começa por diff de spec, não por diff de código. Sobrepor
  regra dura é decisão humana: a spec entra como **proposta** e espera revisão.
- Commits pequenos, mensagem explicando o **porquê**. `DECISIONS.md` ganha uma linha por
  decisão não óbvia: data, alternativa descartada, motivo.
- **Ao fim de cada bloco: reescreva `ESTADO.md` e `git push origin master`.** O projeto
  vive em `~/Downloads` e não em `~/Desktop` — o iCloud já esvaziou o índice do git uma
  vez e derrubou todo o versionamento. O GitHub é o backup, não a nuvem da Apple.
