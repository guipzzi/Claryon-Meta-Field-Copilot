---
feature: redacao-por-llm-na-fala
capacidade: P3 (copiloto local) — Fase 4, Etapa B
estado: proposta
autor: revisão humana pendente
criada: 2026-08-22
sobrepoe:
  - "CLAUDE.md §4 — 'Máximo 7 palavras por resposta de TTS operacional', listado entre os invariantes que o compilador ou um teste sustentam"
  - "specs/leitura-de-norma.spec.md — 'THE SYSTEM SHALL NOT produzir Utterance.Ler a partir de texto que tenha passado por um modelo de linguagem'"
revisada: 2026-08-22 (remedição com o guarda consertado + cinco formulações de prompt)
depende_de:
  - decisão sobre `specs/leitura-de-norma.spec.md` (as duas não se resolvem separadas)
  - decisão sobre o modelo da Etapa B (o motor está resolvido; o modelo não)
  - orçamento de prefill — o prazo de 2 500 ms está colado no custo de entrar o prompt
  - régua de sentido — a cegueira a negação continua aberta e tem exemplo de produção
---

# Ligar `RedacaoDoCopiloto.redigir` à fala

## Por que isto é uma spec e não um diff

A tarefa pedida era *"ligue `redigir()` ao caminho de produção"*. Ela não pode ser
feita como diff, e o motivo não é dificuldade — é o `CLAUDE.md` §7: **sobrepor regra
dura é decisão humana.**

A regra dura aqui é o teto de **7 palavras** por fala operacional (§4), sustentado por
`UtteranceTest`, que varre todos os ramos de `utteranceFor`. Uma redação de duas
frases não cabe nele. Não existe versão desta ligação que respeite o invariante.

**E a premissa do pedido estava errada, o que muda a conta.** O pedido dizia *"hoje
uma pergunta de norma dentro do corpus é lida verbatim … se o guarda reprovar, cai
para verbatim, que é o comportamento correto e já existe"*. Não existe:

- `ConsultaDeNorma.consultar` devolve `Pair<citacao, norma>` — o texto do artigo
  **não chega a `app`**;
- `utteranceFor(ActionOutcome.NormaEncontrada)` fala `"Art. 306, Lei 9.503"`;
- leitura verbatim é **proposta**, em `specs/leitura-de-norma.spec.md`, desde 21/08.

Ou seja, ligar a Etapa B exigiria sobrepor a regra dura **duas** vezes na mesma
sessão: uma para a redação falar, outra para construir o degrau de queda que se
supunha existir. Nove KDoc de produção afirmavam que esse degrau existia; foram
corrigidos em 22/08 (ver a lista no fim).

## A medição que deveria decidir isto

`OrcamentoDaEtapaBNoAparelhoTest`, 22/08, emulador arm64 API 35 com 2,5 GB, modelo
`Llama-3.2-1B-Instruct-Q4_K_M` (807 694 464 B), **configuração de produção literal**
(`nThreads=4`, `nCtx=1024`, `maxTokens=96`, `prazoMs=2500`), sobre as **20** perguntas
do banco de abordagem cuja confiança fica acima do limiar de 0,30 — isto é, exatamente
as que chegariam à Etapa B.

### REMEDIDO em 22/08, com o guarda consertado nos três eixos

A tabela anterior desta spec foi levantada com o `GuardaDaRedacao` furado: a régua de
cifras não via extenso nem reatribuição de grandeza (`Grandezas`, `TresFurosDaReguaTest`)
e a fonte incluía a pergunta do agente (`EcoDaPerguntaNaoELastroTest`). Os três buracos
foram fechados e a medição foi refeita:

| | antes (guarda furado) | **agora (guarda consertado)** |
|---|---|---|
| com texto, prazo de produção | 13 | **11** |
| aprovadas pelo guarda | 10 | **7** |
| utilizáveis, por leitura humana | ≈2 | **1** |
| p50 · p90 da redação | 2 508 · 2 530 ms | **2 510 · 2 522 ms** |

**O guarda passou a reprovar mais, e o número exato é este.** Comparar as colunas acima
mistura duas mudanças, porque `com texto` também caiu. A comparação limpa é dentro da
mesma execução, com o mesmo texto julgado pelas duas composições de fonte: das **9**
aprovações que a composição antiga daria, a de hoje mantém **7** — **−22%**. No prazo de
medição, 12 → 10, **−17%**.

**Não houve regressão: nenhuma redação honesta foi perdida.** As quatro recusas novas,
lidas uma a uma no log, são todas eco ou meta-comentário:

- *"Vendeu simulacro de pistola na loja de brinquedo."* — a pergunta, com ponto final;
- *"Comprei o carro com chassi adulterado, respondo ao ag"* — idem, truncada;
- *"O TRECHO DA NORMA trata sobre o crime de adulterar placa da quantos anos de cadeia…"*;
- *"A adaptação da norma à pergunta feita pelo agente é simples: o agente responde que
  não sabe quem vende máquina de adulterar placa."*

**Carga:** 2 435 ms · PSS 269 329 → 1 779 122 kB (Δ **1 474 MiB**, razão **1,91×** sobre
o GGUF — confirma o `FOLGA_SOBRE_O_MODELO = 1,90`) · devolvida por inteiro no
`liberar()` (PSS volta a 264 752 kB).

### O p50 de produção não é do modelo nem do prompt — é da MÁQUINA

O p50 de 2 510 ms é o **prazo**: a maioria das gerações bate na parede. E a parede é mais
baixa do que se supunha, porque ela morde **antes da geração**: o log nativo mostra
prefill de ~500 tokens custando **1 620 a 2 550 ms**, e `llama_decode` devolvendo `2`
(abortado pelo prazo) sem que o prompt tenha entrado.

A consequência foi medida com um bloco de controle — a **mesma** formulação rodada duas
vezes na mesma execução, uma no início e outra no fim:

```
prazo de 2 500 ms .... 14 de 20 com texto no primeiro bloco · 4 de 20 no último
prazo de 30 000 ms ... 13 de 20 no primeiro bloco · 13 de 20 no último (idênticas)
```

Com prazo folgado o bloco de controle reproduziu o primeiro **saída por saída**. Com o
prazo de produção, o mesmo prompt e o mesmo modelo variaram de 14 para 4 porque a máquina
que hospeda o emulador ficou mais carregada — o prefill caiu de 287 para 196 tokens/s.

**Isto é o resultado mais importante desta remedição.** Não se trata de ruído a
descontar: com o orçamento de 2 500 ms colado no custo do prefill, *se a Etapa B fala* é
decidido pela ocupação do aparelho, não pela pergunta. Num palco, com o app gravando,
transmitindo e sintetizando ao mesmo tempo, é o pior lado dessa faixa que vale.

### O que as aprovações contêm, lidas uma a uma

Isto não é automatizável com honestidade — é classificação humana sobre o log, como o
`RedacaoDeAbordagemNoAparelhoTest` já registrava. O critério usado, escrito antes da
leitura: **frase completa · tudo o que afirma está no trecho · responde o que foi
perguntado · não fala sobre a tarefa · não induz o agente a erro se dita sozinha, sem
tela.** Das aprovadas:

- **eco da própria pergunta**, sem uma palavra do artigo: *"O número do motor foi
  remarcado."* · *"A pessoa com deficiência mental pegou a arma do responsável."*
- **vazamento do andaime do prompt**: *"O agente responde: … TRECHO DA NORMA: Art. 16.
  Possuir, deter, portar…"* · *"Pergunta do agente: … Resposta: …"*
- **meta-comentário**: *"O TRECHO DA NORMA trata sobre…"*
- **não-resposta com autoridade**: *"…o agente responde que não sabe quem vende
  máquina de adulterar placa."*
- **consequência inventada**: *"…o que fez com que a autoridade competente procedesse
  à sua detenção e ao inquérito."*
- **palavra inexistente**: *"O alcoólatempo é crime."*

Utilizáveis, na remedição: **1 de 20** — *"A multa gravíssima é de R$ 293,47."* A segunda
"utilizável" de antes era a pena do Art. 311, e ela some do balanço não porque a régua
mudou, mas porque foi lida com mais rigor: o texto era *"O TRECHO DA NORMA trata sobre o
crime de adulterar placa da quantos anos de cadeia, que é punido com reclusão, de 3 a 6
anos, e multa."* O miolo está certo; a frase não é dizível a ninguém.

Duas aprovações merecem registro separado, porque são o modo de falha mais caro:

- *"A pessoa com deficiência mental **não deixou de** observar as cautelas necessárias
  para impedir o ato criminoso."* — o Art. 13 pune quem **deixa** de observar. A frase
  inverte a lei e passa com lastro 0,78, porque a inversão usa o léxico da fonte. É a
  cegueira a negação, agora com exemplo do banco de produção.
- *"O alcoólatempo é crime."* — lastro **1,00**, palavra inexistente.

### O terceiro buraco do guarda — FECHADO em 22/08

Os dois conhecidos estavam registrados: **cegueira a negação** (aberta, com o exemplo
acima) e a régua de cifras (fechada por `Grandezas`). O terceiro era estrutural:

> `RedatorLlamaCpp.redigir` montava a fonte do guarda como
> `trecho + procedencia + **pergunta**`. Um modelo que **repete a pergunta** — e o
> `ROADMAP.md` já registra que este modelo faz exatamente isso — obtinha lastro
> **1,00** sem tocar no artigo.

O eco não é alucinação e por isso a régua não tinha como vê-lo: é texto com lastro
perfeito e informação zero, dito ao agente com a autoridade de uma consulta à lei. A
pergunta saiu da fonte, `EcoDaPerguntaNaoELastroTest` guarda a composição, e
`OrcamentoDaEtapaBNoAparelhoTest` mede a diferença a cada execução — o mesmo texto
julgado pelas duas composições.

**E o conserto paga mais com o prompt novo:** a formulação que entrou em produção no
mesmo dia devolve o foco à pergunta, o modelo ecoa mais, e a régua pega **5 de 13** em
vez de 2 de 9. As duas mudanças se sustentam: sem esta, a de prompt seria uma piora.

## O prompt, medido — e a refutação que ele produziu

Os quatro defeitos acima têm cara de defeito de modelo e **três deles são de prompt**. O
teste `asFormulacoesDePromptSaoComparadasSobreOMesmoBanco` roda cinco formulações sobre o
mesmo banco, mudando **uma** coisa por vez, com prazo de 30 000 ms para que a carga da
máquina vire latência e não mudez:

| formulação | texto | aprova | eco | andaime | trunc | **utilizáveis** |
|---|---|---|---|---|---|---|
| F0 andaime em caixa alta (antes) | 13 | 10 | 2 | 4 | 2 | **1** |
| **F1 sem rótulo (produção hoje)** | **17** | **8** | **5** | **0** | **0** | **2** |
| F2 sem a pergunta | 18 | 16 | 0 | 0 | 0 | 1 |
| F3 proibições explícitas | 17 | 14 | 0 | 0 | 6 | 1 |
| F4 uma frase | 16 | 14 | 0 | 0 | 4 | 1 |
| F0 controle (repetição) | 13 | 10 | 2 | 4 | 2 | 1 |

O bloco de controle reproduziu o primeiro **saída por saída**, então a tabela mede o
prompt e não a execução.

**O que o prompt conserta, ele conserta inteiro.** O vazamento do andaime vai de 4 para
0 tirando duas strings do contexto: o modelo não inventava `TRECHO DA NORMA`, ele
**copiava**. O meta-comentário some junto, pelo mesmo motivo. O eco vai a 0 quando a
pergunta sai do contexto (F2 em diante) — não há o que ecoar.

**E o que ele não conserta, não conserta de jeito nenhum: `utilizáveis` fica entre 1 e 2
de 20 nas cinco.** Cada formulação troca um defeito por outro:

- **F2** perde o foco — sem a pergunta, o modelo resume o artigo em vez de responder.
  *"qual o valor em reais da multa gravíssima"* deixa de render `R$ 293,47` e passa a
  render a lista das quatro categorias de infração;
- **F3 e F4** viram transcrição: *"Art. 311. Adulterar ou suprimir identificação de
  veículo automotor…"*, com o número de artigo que a instrução proibia, truncado no meio
  em 6 e 4 casos. Lastro 1,00, guarda aprova, agente ouve meia regra.

**A descoberta que mais importa para quem decidir: `aprova` e `utilizáveis` andam em
sentidos opostos.** F2 aprova 16 e rende 1; F0 aprova 10 e rende 1. A régua de lastro
premia casamento lexical com a fonte, e a forma mais barata de casar com a fonte é
copiá-la. **A taxa de aprovação do guarda não é indicador de utilidade — é indicador de
cópia.** Nenhuma decisão sobre a Etapa B deve usar aquela coluna.

**F1 entrou em produção**, e a justificativa é só esta: ela domina F0 sem trocar um
defeito por outro — andaime 4 → 0, truncagem 2 → 0, textos 13 → 17, utilizáveis 1 → 2. As
8 aprovações contra 10 são melhoria, não perda: das 10 de F0, 4 eram vazamento de andaime
e 2 eram meta-comentário. O eco sobe de 2 para 5 e o guarda consertado pega os cinco.

## O que se propõe — e a recomendação é NÃO ligar ainda

**Recomendação registrada: manter `redigir()` desligada enquanto o modelo for este** —
e a remedição de 22/08 **reforçou** a recomendação em vez de amaciá-la.

Ligar hoje trocaria uma resposta curta, honesta e sempre correta (*"Art. 306, Lei
9.503"*) por uma que, no melhor prompt medido:

- fica **muda em 9 de 20** perguntas — e esse 9 vira 16 quando o aparelho está ocupado,
  porque o prazo de 2 500 ms está colado no custo do prefill;
- quando fala, tem **1 a 2 chances em 20** de dizer algo utilizável;
- e nas outras 5 a 6 aprovações diz coisas como *"A pessoa com deficiência mental **não
  deixou de** observar as cautelas"* (a lei invertida) ou *"O alcoólatempo é crime."*

A conta que decide não é "quantas vezes acerta". É **quantas vezes um policial ouviria a
lei ao contrário, sem tela para conferir, com a autoridade de uma consulta ao corpus.**
Hoje isso é mais frequente que o acerto.

**O que mudaria a recomendação, e o que não mudaria.** Trocar o prompt **não** muda:
está medido, cinco formulações, `utilizáveis` entre 1 e 2 nas cinco. Trocar de modelo
pode mudar a coluna `utilizáveis`, mas não fecha a cegueira a negação — que é do guarda,
não do gerador — nem o problema do prefill, que é aritmética de tokens por segundo. Antes
de reabrir a decisão, os três precisam de resposta: **modelo**, **régua de sentido** e
**orçamento de prefill**.

Se ainda assim a ligação for aprovada, o aceite tem de ser este:

## Aceite (EARS)

- **WHEN** o agente pergunta pela norma **AND** o índice devolve trecho acima do
  limiar **AND** `PoliticaDeRedacao` decidiu `Redigir`, **THE SYSTEM SHALL** falar a
  citação em ≤ 7 palavras **AND THEN** falar a redação.
- **THE SYSTEM SHALL** falar a citação **antes** da redação, e não depois: a citação
  existe < 1 ms após o STT, a redação custa p50 2 510 ms. Pipelinar é o que transforma
  2,5 s de silêncio em resposta imediata seguida de detalhe — e degrada bem, porque um
  P1 que preempta a redação não apaga a citação que o agente já recebeu.
- **IF** o redator devolve `null` — prazo, guarda ou motor —, **THEN THE SYSTEM SHALL**
  falar apenas a citação, sem sinal de falha: não falhou nada.
- **THE SYSTEM SHALL NOT** falar redação cujo lastro dependa da pergunta do agente —
  isto é, o guarda **SHALL** julgar contra `trecho + procedencia`, sem a pergunta.
- **THE SYSTEM SHALL NOT** encaminhar o texto gerado ao `DeterministicIntentRouter`,
  em nenhum caminho (garantia já estrutural: `FronteiraDoRedatorTest` e
  `FronteiraDoRedatorEmAppTest`).
- **THE SYSTEM SHALL** manter o ciclo em ≤ 4 s do fim da fala até a **primeira**
  amostra falada — que passa a ser a da citação, não a da redação.

## O que decidir

1. **A exceção ao teto de 7 palavras entra?** É a mesma pergunta de
   `specs/leitura-de-norma.spec.md`, e as duas **não se resolvem separadas**: aquela
   spec proíbe `Utterance.Ler` de carregar texto de modelo, então aprovar só ela deixa
   a Etapa B sem caminho de fala mesmo assim.
2. ~~**O guarda perde a pergunta da fonte?**~~ **DECIDIDO e MEDIDO em 22/08: sim.** O
   medo — *"vai reprovar redação legítima que usa o vocabulário da pergunta"* — não se
   concretizou: as quatro recusas novas são eco puro e meta-comentário, listadas acima
   uma a uma. Custo: −22% de aprovações. Benefício: as aprovações que sobraram têm
   lastro na norma, não na pergunta.
3. **Qual modelo?** O motor está resolvido; este modelo reprova, e **agora se sabe que
   não é o prompt**: cinco formulações medidas, `utilizáveis` entre 1 e 2 nas cinco.
   Trocar de família significa trocar tokenizador e prompt (`ROADMAP.md`), e a licença do
   Llama continua adiada por decisão explícita.
4. **O orçamento de 2 500 ms cabe?** Hoje não, e o problema é anterior à geração: o
   prefill de ~500 tokens custa 1,6 a 2,5 s sozinho. As três saídas conhecidas — encurtar
   o trecho recuperado, subir o prazo (e sair do aceite de 4 s), ou pipelinar a citação
   antes da redação — são decisões diferentes, e **só a terceira já está no aceite acima**.
5. **A cegueira a negação entra na régua?** Ela produziu, no banco de produção, *"A
   pessoa com deficiência mental **não deixou de** observar as cautelas necessárias"* —
   a lei invertida, aprovada com lastro 0,78. Enquanto isso não tiver resposta, a taxa
   de aprovação do guarda continua sem significar segurança.

## Correções de 22/08 que esta spec acompanha

Nove sítios de produção afirmavam que a queda da Etapa B era a leitura verbatim do
artigo. Corrigidos para dizer **citação**: `core-llm/Redator.kt`,
`core-llm/GuardaDaRedacao.kt` (×2), `core-llm/PoliticaDeRedacao.kt`,
`core-llm/RedatorLlamaCpp.kt`, `app/norma/RedacaoDoCopiloto.kt` (×3),
`core-common/FeatureFlags.kt`, `docs/PRONTIDAO_DE_HARDWARE.md`.

Ficaram por corrigir, fora da fronteira de arquivo desta sessão:
`app/ClaryonApp.kt:168` e `ROADMAP.md:476`.

## Correções da remedição (22/08, segunda sessão)

Os números `13 com texto · 10 aprovadas · ≈2 utilizáveis` foram levantados com o guarda
furado e estavam copiados em cinco sítios. Todos remedidos e reescritos:
`core-llm/GuardaDaRedacao.kt`, `core-llm/RedatorLlamaCpp.kt`,
`core-llm/test/EcoDaPerguntaNaoELastroTest.kt` (×2), `app/norma/RedacaoDoCopiloto.kt`,
`app/test/norma/ChamadorDaRedacaoTest.kt` (×2, KDoc e mensagem de falha).

Arquivos novos: `core-llm/FormulacaoDoPrompt.kt` (as cinco formulações, com a medição no
KDoc) e `core-llm/test/FormulacaoDoPromptTest.kt` (8 testes sobre as propriedades
estruturais — andaime fora do contexto de produção, instrução sem material do pedido,
procedência nunca no caminho do modelo, e o controle positivo de cada uma).

Fora da fronteira desta sessão, e **desatualizados por esta medição**:
`ROADMAP.md:684-689` (fala em "25 de 268 gerações inventam número" e na régua que
"reprovou zero de 268" — a régua foi consertada e o corpus de 268 não foi remedido) e o
`ESTADO.md`, cujo item 1 de "O que está quebrado" ainda cita a mediana de 4 680 ms e a
régua de cifras como aberta.
