---
feature: leitura-de-norma
capacidade: P3 (copiloto local) — Fase 4, Etapa A
estado: proposta
autor: revisão humana pendente
criada: 2026-08-21
sobrepoe:
  - "CLAUDE.md §4 — 'Máximo 7 palavras por resposta de TTS operacional', listado entre os invariantes que o compilador ou um teste sustentam"
  - "core-agent/src/main/kotlin/com/claryon/agent/Utterance.kt:41 — 'toda fala respeita ≤ 7 palavras, sem cortesia (há teste que varre todos os ramos deste when)'"
depende_de:
  - índice do corpus (Etapa A, item 3)
---

# Ler a norma em voz alta

## O conflito, dito sem rodeio

O `ROADMAP.md` descreve a Etapa A como *"Piper lendo o trecho recuperado **verbatim**,
com o número do documento citado"*. O `CLAUDE.md` §4 lista **≤ 7 palavras por resposta
de TTS operacional** entre os invariantes duros.

O artigo 306 do CTB tem 71 palavras. Não há leitura possível dos dois textos em que
os dois estejam certos ao mesmo tempo.

Em 21/08 a implementação foi feita **respeitando o invariante**: `ConsultarNorma`
devolve `ActionOutcome.NormaEncontrada(citacao, norma)` e o agente ouve *"Art. 306,
Lei 9.503"* — quatro palavras. É resposta honesta: diz **onde** está sem fingir que
leu. Mas não é o que o roadmap prometeu, e a diferença precisa ser decidida por
gente, não resolvida por diff.

## Por que o invariante existe, e onde ele NÃO se aplica

A regra das 7 palavras não é estética. Ela protege duas coisas concretas:

1. **O agente não pode pular.** Sem display, não há como saltar o que está sendo
   dito. Uma frase longa ocupa o canal auditivo de quem pode estar entrando numa
   ocorrência, e o alto-falante dos óculos é o mesmo por onde chega o rádio tático.
2. **Fala longa atrasa P1.** A preempção de P1 corta em 11 ms hoje (medido), mas o
   que ela corta é a fala corrente — e cortar no meio de um artigo de lei deixa o
   agente com meia norma na cabeça, que é pior do que nenhuma.

Repare que a regra diz **"operacional"**. Consultar norma não é despacho, alerta nem
confirmação de ação: é o agente parando para perguntar. O contexto de uso é outro, e
é isso que abre espaço para a exceção — mas só se ela vier com as duas proteções
acima resolvidas, não removidas.

## O que se propõe

Uma variante nova de `Utterance`, **`Ler`**, distinta de `Falar`, com três garantias
que `Falar` não precisa ter:

- **É interrompível pelo agente**, por comando de voz curto e por P1. Interromper
  `Ler` não é falha: é o uso esperado.
- **Só é produzível a partir de `ActionOutcome.NormaEncontrada`.** Nenhum outro
  resultado pode gerá-la, e há teste sobre isso — senão a exceção vira porta.
- **Nunca carrega texto gerado.** Na Etapa B o LLM reescreve o trecho; o que `Ler`
  aceita é o `texto` do `Trecho` como saiu do corpus. Texto de modelo entra por
  outro caminho, com sua própria decisão.

O teste de laconicidade passa a varrer `Falar` e `SinalizarEFalar` com teto 7, e
`Ler` com teto próprio, medido em segundos de fala e não em palavras — porque o que
machuca é o tempo de canal ocupado, não a contagem.

## Aceite (EARS)

- **WHEN** o agente pergunta pela norma **AND** o índice devolve trecho acima do
  limiar, **THE SYSTEM SHALL** falar a citação em ≤ 7 palavras **AND THEN** ler o
  texto do trecho.
- **WHILE** uma leitura de norma está em curso, **WHEN** chega fala de prioridade P1,
  **THE SYSTEM SHALL** interromper a leitura em ≤ 200 ms **AND** não retomá-la
  sozinha.
- **WHILE** uma leitura de norma está em curso, **WHEN** o agente diz "chega" ou
  "para", **THE SYSTEM SHALL** interromper a leitura.
- **IF** o trecho recuperado excede 45 s de fala sintetizada, **THEN THE SYSTEM
  SHALL** ler apenas o *caput* e dizer que há parágrafos.
- **THE SYSTEM SHALL NOT** produzir `Utterance.Ler` a partir de qualquer
  `ActionOutcome` que não seja `NormaEncontrada`.
- **THE SYSTEM SHALL NOT** produzir `Utterance.Ler` a partir de texto que tenha
  passado por um modelo de linguagem.

## O que decidir

1. **A exceção entra?** Se não, a citação em 4 palavras é a resposta final da Etapa
   A, e o `ROADMAP.md` precisa perder a palavra "verbatim" — porque hoje ele promete
   o que a regra dura proíbe, e um dos dois está mentindo.
2. **O teto de 45 s** é chute meu e precisa de medição: quanto tempo de artigo lido
   um agente tolera antes de a informação virar ruído? Isso se mede com gente, não
   com teste.
3. **"Chega"/"para" viram comando novo no roteador.** São palavras comuns em rádio.
   Medir o falso positivo delas contra o corpus e contra fala espontânea, do mesmo
   jeito que o gatilho foi medido, antes de aceitar.
