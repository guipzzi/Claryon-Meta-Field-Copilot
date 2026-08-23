---
feature: redacao-por-llm-na-fala
capacidade: P3 (copiloto local) — Fase 4, Etapa B
estado: proposta
autor: revisão humana pendente
criada: 2026-08-22
sobrepoe:
  - "CLAUDE.md §4 — 'Máximo 7 palavras por resposta de TTS operacional', listado entre os invariantes que o compilador ou um teste sustentam"
  - "specs/leitura-de-norma.spec.md — 'THE SYSTEM SHALL NOT produzir Utterance.Ler a partir de texto que tenha passado por um modelo de linguagem'"
revisada: 2026-08-22 noite (duas pistas medidas: orçamento de prefill e extração por gramática)
revisada: 2026-08-22 fim de sessão (modelo trocado por decisão humana, sem bancada)
depende_de:
  - decisão sobre `specs/leitura-de-norma.spec.md` (as duas não se resolvem separadas)
  - ~~decisão sobre o modelo da Etapa B~~ — RESOLVIDA em 22/08 — `Qwen2.5-1.5B-Instruct-Q4_K_M`
  - remedição sobre o modelo novo — TODA a tabela desta spec é do modelo que saiu
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

## ⚠️ O modelo mudou depois desta medição, e nada aqui foi remedido

Em 22/08, no fim da sessão, `Llama-3.2-1B-Instruct-Q4_K_M` foi substituído por
`Qwen2.5-1.5B-Instruct-Q4_K_M` — decisão humana, registrada em `DECISIONS.md`, tomada
**sem bancada** e sabendo disso.

**Toda tabela desta spec é do modelo que saiu.** Elas ficam porque são o último estado
conhecido da Etapa B, e porque a maior parte do que elas mediram não é do modelo: o
guarda cego a negação é da régua, a truncagem é do prazo, o vazamento de andaime era do
prompt. Mas as três colunas que decidem — `utilizáveis`, `aprova`, `p50` — são do Llama,
e ninguém deve lê-las como sendo do Qwen.

Três consequências que a troca produz **antes** de qualquer medição, por aritmética:

1. **O prefill piora.** O modelo é 22% maior em disco (986 048 768 B contra
   807 694 464 B) e tem 28 camadas contra 16. O item que já estourava sozinho o prazo de
   2 500 ms estoura mais.
2. **O portão de RAM refuta mais aparelhos.** `PoliticaDeRedacao` multiplica o tamanho do
   arquivo por 1,90: a exigência sobe de 1 464 MiB para 1 787 MiB de `availMem`.
3. **A cegueira a negação não muda, e pode ficar mais cara.** Um modelo mais fluente em
   português constrói negação melhor, e negação bem construída é exatamente a alucinação
   que a régua de lastro deixa passar.

A recomendação desta spec — **não ligar** — segue de pé, e por motivos que a troca não
toca. O que a troca fez foi mudar a aposta de qual modelo vale remedir primeiro.

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

## As duas pistas, medidas em 22/08 à noite — e a que ganhou não tem modelo

`DuasPistasDaEtapaBTest`, emulador arm64 API 35, **mesmo** banco de 20 perguntas,
**mesmo** GGUF (`Llama-3.2-1B-Instruct-Q4_K_M`, 807 694 464 B), prazo de produção de
2 500 ms. Dez braços rodados **intercalados por pergunta** — laço externo é a
pergunta, interno é o braço —, porque rodar em bloco foi o que produziu a tabela
inválida de 22/08 de manhã. O braço de controle (repetição do braço de hoje no fim de
cada rodada) fechou em **7 e 6 textos**: a execução é reprodutível.

| braço | texto | aprova | contíguo | **utilizáveis** | **alucinações** | p50 | p90 |
|---|---|---|---|---|---|---|---|
| **A0 hoje** (F1, artigo inteiro) | 7 | 2 | 0 | **1** | **2** | 2 513 | 2 546 |
| P1a recorte do artigo | 20 | 9 | 0 | 1 | 10 | 1 674 | 2 121 |
| P1b recorte + guloso | 20 | 10 | 0 | **2** | 10 | 1 836 | 2 468 |
| P1c instrução em inglês | 7 | 3 | 0 | **0** | **4** | 2 519 | 2 571 |
| P2a gramática, artigo no prompt | 8 | 6 | 6 | 0 | **0** | 2 521 | 2 585 |
| **P2b gramática, sem artigo no prompt** | **20** | 20 | **20** | **2** | **0** | 1 595 | 2 279 |
| P2c gramática de 7 palavras | 7 | 5 | 5 | 0 | **0** | 2 518 | 2 563 |
| P2d gramática + recorte | 19 | 15 | 17 | 1 | **0** | 2 217 | 2 523 |
| **S0 sem LLM nenhum** | **20** | 19 | **20** | **3** | **0** | **5** | **8** |

`utilizáveis` e `alucinações` são leitura humana das 148 gerações, pelo critério
escrito **antes** de existir saída para ler (está no KDoc do teste): frase ou
sintagma completo · tudo o que afirma está no trecho com o mesmo sentido · responde o
que foi perguntado · não fala sobre a tarefa · não induz a erro por omissão de
condicionante adjacente. `alucinação` é a subclasse em que a saída **afirma** sobre a
norma algo que a fonte não sustenta.

### O que cada pista provou

**Pista 1 conserta o ORÇAMENTO e piora a honestidade.** Encurtar o artigo pela
pergunta (`RecorteDaFonte`, 207 → 70 palavras, 519 → 221 tokens de prompt) leva
`com texto` de **7 para 20 em 20** e derruba os abortos no prefill de 13 para **zero**.
É a maior mudança de qualquer coluna nesta tabela. E é também a mais perigosa: com
mais gerações completas vêm mais afirmações, e as afirmações deste modelo são
inventadas — `alucinações` vai de 2 para **10**, incluindo *"A quantidade mínima de
droga para lavrar flagrante é 0,5 gramas"* (a lei não fixa nenhuma) e *"O valor da
multa é de 2.000,00 e a suspensão do registro do veículo para um período de dois
anos"*. **Dar mais tempo a este modelo compra mais mentira por segundo.**

**A instrução em inglês está REFUTADA.** É o conselho mais repetido sobre modelo
pequeno e nunca tinha sido medido aqui. Ela não mudou `com texto` (7, igual ao de
hoje) e **dobrou** as alucinações — inclusive reproduzindo, palavra por palavra, a
inversão de negação do Art. 13 que esta spec já registrava (*"A pessoa com
deficiência mental **não deixou de** observar as cautelas"*) e respondendo
*"Quatro tiros."* a *"quantos tiros posso dar em legítima defesa"*.

**Pista 2 conserta a ALUCINAÇÃO, e conserta por construção.** Nos quatro braços com
gramática, **56 gerações não vazias, zero alucinações** — e não porque o guarda pegou:
porque o `-INFINITY` entra no logit antes do sorteio. A verificação é de máquina, não
de leitura: `GramaticaDaFonte.eTrechoContiguo` confere cada saída contra a fonte e
recusa recombinação, supressão do meio e palavra enxertada — os três modos que o
`GuardaDaRedacao` aprova com lastro 1,00. O contra-teste está na mesma coluna: o braço
de hoje, medido pela mesma régua, dá **0 de 7** contíguos.

### O custo da gramática, medido: compilar é grátis, AMOSTRAR não é

Era a pergunta que podia matar a Pista 2 antes de qualquer tabela de qualidade, porque
a GBNF deriva do trecho e **muda a cada consulta** — não há como compilá-la no boot.

| gramática | compilar p50 | p90 | GBNF | regras | inícios |
|---|---|---|---|---|---|
| 7 palavras, fronteira de cláusula | **0,29 ms** | 0,61 ms | 9,1 kB | 265 | 38 |
| 12 palavras, fronteira de cláusula | **0,46 ms** | 0,91 ms | 16,0 kB | 450 | 38 |
| 7 palavras, qualquer palavra | 2,28 ms | 3,65 ms | 56,1 kB | 1 598 | 231 |
| 12 palavras, qualquer palavra | 2,72 ms | 3,76 ms | 98,1 kB | 2 708 | 231 |

**Compilar é ruído** — 0,46 ms num orçamento de 2 500. A Pista 2 **não** morre por aí.

O custo real está no outro lugar, e é grande: **`llama_sampler_sample` passa de 0,8 ms
para 32 ms por token** quando há gramática — 40×. A causa é estrutural e está no
artefato vendorizado, não no nosso código: `llama_grammar_apply_impl`
(`llama-grammar.cpp:1353`) decodifica UTF-8 do pedaço de **todos os 128 256
candidatos**, a cada token, e depois roda o rejeitador uma vez por pilha ativa.

É por isso que a poda por alcançabilidade e a fronteira de cláusula não são detalhe: a
tabela acima mostra 38 pilhas contra 231, e o `GramaticaDaFonte` só escreve as regras
alcançáveis a partir dos inícios — 450 em vez das 2 708 da grade inteira.

**E é por isso que P2a reprova e P2b passa, com a mesma gramática.** Com o artigo no
prompt, o prefill de 512 tokens custa 1 877 ms e sobram 600 ms para uma amostragem de
32 ms/token: 8 de 20 falam, com **3 tokens** de mediana. Sem o artigo no prompt — a
fonte viaja na **jaula**, não no contexto — o prefill cai para 92 tokens e 551 ms,
sobram quase 2 s, e **20 de 20** falam com 22 tokens de mediana. A gramática só cabe no
orçamento se a Pista 1 pagar por ela.

### O defeito que a primeira execução achou, e o conserto que ele pediu

Sem piso de palavras, a gramática admite **parar depois de qualquer palavra**: a pilha
esvazia e o EOG vira sorteável. Um modelo de 1B toma essa saída o tempo todo — sete das
vinte saídas do braço P2b foram *"Penalidade"*, *"não"*, *"Apresentado"*, *"1º"*,
*"a 2"*, *"25-"*. Contíguas, aprovadas pelo guarda com lastro 1,00, e sem informação
nenhuma.

`GramaticaDaFonte.MINIMO_PADRAO = 3` fecha a saída: nas duas primeiras palavras a
regra não tem a alternativa de terminar. Medido: `contíguo` de P2b foi de 19 para
**20 de 20** e `aprova` de 19 para 20 — e o defeito de fragmento sumiu.

### A refutação que o dono precisa ouvir: o LLM não está pagando o próprio custo

`S0-sem-llm` é o mesmo produto da Pista 2 **sem modelo nenhum**: como a gramática
reduz a saída a *"escolher um item de uma lista de trechos contíguos"*, a lista é
enumerável e a escolha cabe em 40 linhas de Kotlin (`SelecaoDeTrecho`, casamento
lexical com a pergunta, heurística declarada e não ajustada ao banco).

Ele **empata ou ganha em todas as colunas**:

- `utilizáveis` **3 de 20**, contra 2 do melhor braço com modelo e 1 do de hoje;
- `alucinações` **0**, pelo mesmo motivo estrutural;
- **p50 de 5 ms e p90 de 8 ms**, contra 1 595 e 2 279 — **três ordens de grandeza**;
- e **zero** dos 807 MB de GGUF, zero dos 1,47 GiB residentes, zero prefill.

Isto não é argumento contra a gramática: é a gramática levada até o fim. Ela não
tornou o modelo confiável — ela **tirou do modelo tudo o que dependia de ele ser
confiável**, e o que sobrou para ele fazer, uma função determinística faz melhor e de
graça. Nenhuma das três leituras acima é grande: **3 de 20 continua reprovando o
aceite**. A diferença é que o baseline reprova sem inventar nada e sem custar nada.

### Por que os três números de `utilizáveis` são todos baixos, e onde está o teto

Lendo as 148 gerações, o gargalo mudou de lugar. Não é mais invenção — nos braços de
gramática ela é zero. É **recorte na fronteira errada**, e ele tem duas causas
independentes:

1. **O teto de palavras corta a resposta no meio.** *"infração de natureza gravíssima,
   punida com multa no valor"* para uma palavra antes de `R$ 293,47`;
   *"Entende-se em legítima defesa quem, usando moderadamente dos meios necessários,
   repele"* para antes do complemento. A resposta certa existia na jaula e o teto a
   partiu.
2. **A Etapa A entrega o artigo errado, e nenhuma pista conserta isso.** *"só tinha
   estojo e projétil no porta-luvas"* recupera o Art. 264 (arremessar projétil contra
   veículo) e *"entregou uma arma pro moleque de dezesseis"* recupera o Art. 18
   (importar/exportar). Com gramática, o copiloto passa a citar **verbatim a pena do
   crime errado** — 8 a 16 anos —, o que é pior que ficar mudo e **não é alucinação**:
   é recuperação ruim falada com autoridade.

O item 2 é o mais importante desta medição para quem for decidir a Fase 4: **fechar a
alucinação do gerador expõe o erro do recuperador**, que estava escondido atrás da
mudez e do texto inventado.

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

**A medição de 22/08 à noite responde dois dos três, e de um jeito que ninguém tinha
proposto.** O **orçamento** tem conserto: encurtar o prompt leva `com texto` de 7 para
20 em 20 e zera os abortos no prefill. A **régua de sentido** deixa de ser necessária no
caminho da gramática, porque não há o que julgar — a saída é citação da fonte por
construção, e o `GuardaDaRedacao` vira redundante ali. O que sobra em pé é o
**modelo**: com a alucinação fechada, `utilizáveis` fica em 2 de 20 com ele e **3 de 20
sem ele**. Ou seja, a recomendação de não ligar continua — mas o motivo mudou de *"ele
mente"* para *"ele não acrescenta"*.

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
3. **Qual modelo?** ~~O motor está resolvido; este modelo reprova.~~ **A pergunta mudou
   de forma em 22/08 à noite, e agora é: cabe MODELO NENHUM?** Com a gramática na cadeia,
   a tarefa que sobra para o LLM é escolher um item de uma lista finita de trechos
   contíguos — e `SelecaoDeTrecho`, 40 linhas determinísticas com **p50 de 5 ms**, escolhe
   **3 utilizáveis em 20** contra os **2** do melhor braço com modelo. Antes de discutir
   qual modelo, decidir se cabe algum: hoje ele não paga o próprio custo. Cinco
   formulações e uma troca de idioma medidas; nenhuma leva `utilizáveis` acima de 2.
4. **O orçamento de 2 500 ms cabe?** **Cabe, e agora está medido como.** O prefill
   domina e é proporcional ao prompt: 519 tokens → 2 505 ms e **13 abortos em 20**;
   221 tokens → 1 252 ms e **zero** abortos; 92 tokens → 551 ms e **zero** abortos. Às
   três saídas conhecidas soma-se uma quarta, que só existe com gramática: **tirar o
   trecho do prompt inteiro**, porque a fonte passa a viajar na jaula. Encurtar
   (`RecorteDaFonte`) e pipelinar continuam valendo; subir o prazo continua fora.
5. **A cegueira a negação entra na régua?** **No caminho da gramática a pergunta não se
   coloca:** a régua não precisa ver negação porque a negação não pode ser produzida —
   suprimir o *"deixar de"* do Art. 13 exigiria um salto que a contiguidade não admite.
   Verificado por máquina em `oQueAGramaticaProduzENecessariamenteTrechoDaFonte`:
   **20 de 20** saídas com gramática são trecho contíguo da fonte, contra **0 de 20** na
   geração livre. A cegueira continua aberta, e **só**, para o caminho livre — que a
   instrução em inglês acabou de reproduzir palavra por palavra.
6. **NOVO — a Etapa A é boa o bastante para virar citação verbatim?** Fechar a
   alucinação do gerador **expôs o erro do recuperador**: *"só tinha estojo e projétil no
   porta-luvas"* recupera o Art. 264 (arremessar projétil contra veículo) e *"entregou uma
   arma pro moleque de dezesseis"* recupera o Art. 18 (importar/exportar). Com gramática,
   o copiloto fala **a pena verbatim do crime errado** — 8 a 16 anos — com a autoridade de
   uma citação. Não é alucinação, nenhuma das duas pistas conserta, e é o próximo gargalo.
7. **NOVO — o teto de 7 palavras do §4 corta a resposta?** Medido: com teto de 7 a
   gramática entrega *"São vedadas a fabricação, a venda, a"*; com 12, chega a
   *"Pena – reclusão, de 3 (três) a 6 (seis) anos, e multa."* — 12 palavras exatas. Pena
   de artigo do CP não cabe em 7. Se a Etapa B entrar por extração, o teto do §4 precisa
   de número novo, e ele é **12** — medido, não escolhido.

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

## O que a sessão de 22/08 à noite construiu

Nenhuma linha de produção mudou de comportamento: `redigir()` continua com **zero
chamadores em `src/main`**, `RedacaoDoCopiloto` continua desligada, e
`ChamadorDaRedacaoTest` continua falhando de propósito se alguém ligar sem esta spec.
O que entrou é **instrumento de medição e a alavanca que ele mediu**.

**Novos:**

- `core-llm/GramaticaDaFonte.kt` — a GBNF que só admite trecho contíguo da fonte, com
  poda por alcançabilidade, piso e teto de palavras, e `eTrechoContiguo` como
  verificador de máquina da afirmação;
- `core-llm/RecorteDaFonte.kt` — encurta o artigo pela pergunta preservando ordem e
  levando o vizinho da direita junto (a condicionante vem depois do preceito);
- `core-llm/SelecaoDeTrecho.kt` — o mesmo produto **sem modelo**, que é o controle que
  decide se o LLM paga o próprio custo;
- `core-llm/test/GramaticaDaFonteTest.kt` (11), `RecorteDaFonteTest.kt` (5),
  `SelecaoDeTrechoTest.kt` (4);
- `app/androidTest/bench/DuasPistasDaEtapaBTest.kt` — a bancada de dez braços
  intercalados, com o custo de compilar a gramática medido à parte e o critério de
  `utilizável` escrito no KDoc **antes** de existir saída para ler.

**Alterados:**

- `core-llm/cpp/redator_jni.cpp` — `nativoRedigirComOpcoes` (cadeia por chamada,
  gramática, temperatura/`min_p`/penalidade/semente), `nativoUltimasMetricas`
  (prefill, compilação da gramática, amostragem, tokens, `decode_rc`) e
  `nativoMedirGramatica`. `nativoRedigir` passou a delegar ao mesmo `gerar()` e
  **continua usando a cadeia montada no `nativoCarregar`** — o caminho de produção não
  mudou de forma nenhuma;
- `core-llm/RedatorLlamaCpp.kt` — `redigirNaBancada`, `ultimasMetricas`,
  `medirGramatica`, e dois parâmetros opcionais que, ausentes, não mudam nada;
- `core-llm/FormulacaoDoPrompt.kt` — F5 (instrução em inglês), F6 e F7 (extração com e
  sem o trecho no contexto), e `TODAS_MEDIDAS`;
- `core-llm/test/FormulacaoDoPromptTest.kt` — as varreduras passaram a cobrir as três
  novas, e F7 ganhou a fronteira escrita (ela é a única que não entrega o trecho, e
  não entra em `TODAS` por isso).

**O que ficou NÃO MEDIDO nesta sessão:**

- **a matriz com prazo folgado** (`asDuasPistasSaoMedidasComOPrazoFolgado` existe e não
  rodou). Sem ela não dá para separar, nos braços que ficaram mudos, *"o modelo não
  sabe"* de *"o prefill comeu o prazo"* — para P2a e P2c a suspeita registrada é a
  segunda, porque os dois geraram 3 e 13 tokens de mediana com prefill de 2 506 ms;
- **aparelho real.** Tudo acima é emulador arm64 com 2,5 GB. O custo de 32 ms por token
  da gramática é de CPU e deve escalar com o aparelho, mas isso é hipótese declarada;
- **corpus além das 20 perguntas** com confiança ≥ 0,30 do banco de abordagem.
