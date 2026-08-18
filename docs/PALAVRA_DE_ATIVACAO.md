# A palavra de ativação — o que foi medido em 17/08, e o que decide

> A pergunta que abriu o dia foi a certa: **"a Alexa é chamada e responde. Por que
> Claryon não?"**. A resposta é arquitetural, e as medições abaixo a fecham.

## A resposta curta

**A Alexa não transcreve.** Ela roda um detector treinado no padrão acústico de uma
palavra específica, que devolve um escore. Nunca pergunta *"que palavra foi essa?"*;
pergunta *"quanto isso se parece com o único padrão que eu conheço?"*.

Este projeto vinha detectando a ativação **através de um transcritor de propósito
geral**, que escolhe entre 51.865 tokens com um modelo de linguagem puxando para
palavras frequentes do português. É a ferramenta errada para a tarefa, e todas as
medições abaixo são consequência disso.

## O que foi medido, em ordem, e o que cada uma matou

| # | Hipótese | Resultado | Veredito |
|---|---|---|---|
| 1 | KWS dedicado do sherpa-onnx resolve | controle canônico **3/3**; Piper pt-BR dizendo **"Alexa"** (chave de fábrica) **0/4** | **fora de domínio** |
| 2 | A grafia da chave estava errada (`CLARYON` → `CLARION`) | 6 grafias × 2 bandas = **0/8 em todas** | refutada |
| 3 | A banda estreita de 8 kHz é a culpada | banda cheia dá **0** igual | refutada |
| 4 | Afrouxar limiar e bônus faz aparecer | grade de 9 pontos até 0,02 / 5,0 → **0/8** | refutada |
| 5 | O decodificador erra sempre do mesmo jeito → banco de formas | 18 rendições, **18 formas, nenhuma repetida** | **refutada** |
| 6 | O prior nunca nomeou a marca | **0% → ~35%**, com 0 falso positivo | **confirmada** |
| 7 | O ataque se perde por falta de silêncio à frente | 0/200/500/1000 ms: sem efeito resolvível | refutada |
| 8 | A rima sobrevive mesmo quando o ataque morre | recall **33% → 67%**, mas **3 falsos positivos em 30** | parcial |

## O achado que invalida precisão, e vale para toda a bancada

**O Piper não é determinístico.** `RepetibilidadeDaBancadaTest` sintetizou a mesma
frase três vezes: **48640, 45355 e 44820 amostras** — a duração varia até 8%. VITS
tem *stochastic duration predictor*, e sorteia a duração de cada fonema.

O whisper, no mesmo buffer, repete o texto exatamente.

Consequência dura: **a mesma condição medida duas vezes deu 29,2% e 41,7%.** Não são
dois resultados — são um, com ruído. Diferenças de poucos pontos entre braços não
são achado, e daqui em diante exigem `n` maior e intervalo de confiança. O que
sobrevive é só o que está fora do intervalo: o salto de **zero** para ~35%.

Há um lado bom, e ele importa para a decisão: um TTS que sorteia a rendição é
**gerador de dados variados de graça** — exatamente o que falta para treinar uma
palavra de ativação tendo só uma voz.

## O estado do portão hoje, medido com treino e teste separados

| portão | recall (teste) | falso positivo | meta |
|---|---|---|---|
| marca exata, `claryon` | **33,3%** (6/18) | **0/30** | 90% / 0 |
| rima `-on`/`-om`, ≥6 caracteres | **66,7%** (12/18) | **3/30** | 90% / 0 |

Os três falsos positivos da rima vieram todos de *"Elétron e próton"* — negativo
plantado de propósito para atacar a regra. Ele funcionou.

**Nenhum dos dois chega à meta.** E a estrutura do erro explica por quê: o ataque
/kl/ é oclusiva velar, transiente curto e de alta frequência, a primeira coisa que
morre em banda estreita. Sai `varyon`, `faryon`, `haryon`, `quaryon`, `parion`,
`carion`, `karyon`. A rima é vogal aberta, líquida e nasal, tudo abaixo de 4 kHz, e
sobrevive quase sempre. O whisper acerta **a cauda do comando inteira** —
`mudar para a guarnição 4`, `encerrar gravação`, `solicitar reforço` — e erra só o
nome, porque o nome é a única coisa que não está no vocabulário dele.

## O que isto decide

**Paridade com a Alexa não sai do caminho por transcrição.** O teto é estrutural, não
de ajuste: nenhum parâmetro do whisper vai fazê-lo reconhecer com 90% uma palavra
inventada que ele nunca viu, em 8 kHz. Para chegar lá é preciso o que a Alexa tem —
**um detector acústico treinado na palavra**.

Não existe preset de KWS por texto em português (só inglês e mandarim), então a via é
**treinar**, e a receita é conhecida: gerar milhares de rendições de "Claryon" com o
Piper — que agora sabemos variar sozinho a cada síntese, e do qual há mais de uma voz
pt-BR —, aumentar com ruído e resposta de sala, e treinar um classificador pequeno
sobre um *embedding* de fala. Inferência em ONNX no aparelho.

**Isto é decisão humana e entra como proposta, não como diff**: acrescenta dependência
(`onnxruntime-android`; o AAR do sherpa traz só o `.so` nativo, sem API Java) e uma
etapa de treinamento fora do repositório. `CLAUDE.md` §2 exige tamanho, licença e
alternativa nativa antes de qualquer dependência nova.

Enquanto a decisão não vem, o que **existe e funciona** é o copiloto por botão, com
STT, TTS e troca de grupo faladas — que é exatamente o escopo dos entregáveis de
22/08 (Fase 0). O gatilho por voz não entra neles.

## O teste que autoriza treinar — feito em 17/08, e passou

Antes de qualquer dependência no Android, a pergunta que decide: **o embedding
pré-treinado do openWakeWord (inglês) separa "Claryon" das vizinhas em português?**
Era o risco que matou o KWS do sherpa, e repeti-lo custaria semanas.

Corpus: 60 rendições de `Claryon.` isolado e 35 palavras negativas × 60 — vizinhas de
*clar-*, rimas em `-on` (`cordon`, `cânon`, `elétron`, `batom`) e o vocabulário de
rádio. Divisão dura: positivos por rendição, e **dez palavras negativas inteiras fora
do treino**. Cabeça de regressão logística sobre o embedding congelado.

| | banda cheia | banda estreita (HFP) |
|---|---|---|
| recall com **zero** falso positivo | **100%** (20/20) | **100%** (20/20) |
| escore médio positivo × negativo | 1,000 × 0,244 | 1,000 × 0,248 |
| pior positivo × pior negativo | 0,998 × **0,997** | 0,998 × **0,997** |

**A conclusão que vale é a primeira linha: o embedding transfere.** A banda estreita
não custa quase nada, o que já é o contrário do que acontecia por transcrição.

**A ressalva que vale é a terceira:** a margem é de um milésimo, e quem chega lá é
`cordon` — plantada de propósito por rimar. Uma cabeça linear sobre embedding cru é o
classificador mais fraco possível e não houve aumento de dados; ainda assim, tratar
`100%` como resultado seria repetir o erro do dia. O que está provado é **viabilidade**,
não desempenho.

### O primeiro erro desta medição, registrado porque quase passou

A primeira rodada também deu 100%, e estava contaminada: três dos quatro positivos
eram frases de 1,3 a 2,2 s e **todo** negativo era palavra isolada de até 0,85 s.
Média e máximo sobre um número variável de janelas carregam o comprimento, e o
classificador achou o atalho antes de olhar para a palavra. A correção foi recortar
todo clipe em exatamente 1,0 s centrado na energia e manter só `Claryon.` isolado
como positivo. Os números acima são os de depois.

## Voz humana, 17/08 — e a reversão da minha própria recomendação

Chegaram gravações de quatro pessoas (Guido, Carla, Bruna, Pedro). Segmentadas por
energia: **31 elocuções de "Claryon"**, das quais **27 são de um único locutor**, mais
11 de `"na escuta"` como controle. É pouco, e o desequilíbrio impede o protocolo de
deixar-um-locutor-de-fora em forma plena. Mas dá para responder as duas perguntas que
decidem, e as respostas se contradizem de um jeito útil.

### 1. Um detector treinado só com Piper dispara em gente? **Não.**

| | escore médio |
|---|---|
| 31 positivos **humanos** | **0,03** |
| 11 controles humanos | 0,00 |

Um clipe em 31 passou de 0,5. Em qualquer limiar operacional o detector fica mudo.

E aqui um número quase me enganou, registrado porque é do tipo que mente: a rotina
imprimiu *"recall com zero falso positivo: 58,1%"*. Como os controles marcaram
exatamente `0,000`, qualquer positivo com escore `0,0001` "passava". Era estatística
de **ranking**, não de detecção. O que vale é a média de 0,03.

### 2. O embedding representa a palavra em voz humana? **Sim, e generaliza.**

Treino **só com o Guido** (27 positivos, 3 negativos), teste em três locutores que o
treino nunca viu:

| clipe | escore |
|---|---|
| Carla | **0,963** |
| Pedro | **0,996** |
| Bruna | **0,999** |
| Bruna (segmento parcial) | 0,325 |
| 8 controles `na escuta` | ≤ **0,216** |

Separação limpa com **um** locutor no treino. O embedding transfere para o português
**e** entre pessoas — o que o teste com Piper não conseguia mostrar porque estava
medindo outra coisa.

### 3. O sintético ajuda como reforço? **Não: ele destrói.**

Mesmo teste, variando só o que entra no treino:

| treino | margem (pior positivo − pior negativo) |
|---|---|
| só Guido, humano | **+0,109 · separa** |
| só Piper | −0,001 · não separa |
| Guido **+** Piper | −0,020 · **não separa** |
| Guido + só os negativos do Piper | −0,052 · **não separa** |

Acrescentar Piper a dados humanos que funcionavam **quebra** o que funcionava. A voz
sintética ocupa uma região do espaço de embedding distante da voz real, e a fronteira
aprendida lá não vale aqui.

### A recomendação anterior está revertida

Em 17/08 eu escrevi, acima nesta mesma página, que a via era *"gerar milhares de
rendições com o Piper"*. **A medição derrubou isso.** O corpus sintético serviu para
provar que o embedding transfere para o português, e esse trabalho valeu; como dado
de treino ele é inútil no melhor caso e nocivo no caso medido.

O que decide agora é **gravação de pessoas**, e a boa notícia é a barra: 27 clipes de
um locutor já generalizaram para três inéditos acima de 0,96.

## O que ainda não foi medido, e sem o que nada disso vira aceite

- **Fala humana real.** Tudo acima é Piper: um detector treinado no Piper e avaliado
  no Piper mede se ele reconhece o Piper. Quatro locutores foram oferecidos pela
  revisão humana, e o protocolo exige **deixar um locutor inteiro de fora a cada
  rodada** — treinar e testar nas mesmas quatro vozes mede memorização.
- **30 pronúncias reais por HFP**, de pessoas, com sotaque e hesitação.
- **Falso positivo em fala espontânea** com `n` grande o bastante para ter intervalo
  de confiança. `0/30` não é "zero": é `[0%; 11,6%]` com 95%.
- **Rádio VHF real** tocando no ambiente por horas.

## Onde está o código

| Arquivo | O que prova |
|---|---|
| `KwsDeClaryonTest` | o KWS por texto está fora de domínio — com controle canônico 3/3 que autoriza a conclusão |
| `BancoDeFormasTest` | o banco de formas não existe: 18 rendições, 18 grafias |
| `PromptDeAtivacaoTest` | o prior que nomeia a marca leva 0% a ~35% |
| `SilencioDeAtaqueTest` | silêncio à frente não explica o ataque perdido |
| `RepetibilidadeDaBancadaTest` | o Piper sorteia; o whisper não |
| `PortaoPelaRimaTest` | a rima dobra o recall e abre falso positivo |
| `DespejoDeCorpusTest` | gera o corpus em WAV, nas duas bandas, com o Piper do projeto |
| `ferramentas/ativacao/honesto.py` | o teste de separabilidade, já sem o atalho da duração |
