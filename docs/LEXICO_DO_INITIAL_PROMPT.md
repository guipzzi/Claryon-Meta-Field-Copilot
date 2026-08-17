# O léxico do `initial_prompt` — e por que ele contamina a régua

Documento curto, com um trabalho só: deixar **visível** o que hoje está enterrado
numa string do C, porque essa string decide o resultado de toda medição de STT
deste projeto.

## O que está em uso, hoje

Em `core-voice/src/main/cpp/jni.c`, `params.initial_prompt`:

```
Central, guarnicao, ocorrencia, viatura, deslocamento, apoio, Sargento, Claryon.
```

Oito palavras. Elas **não** são uma lista de palavras obrigatórias — o
`initial_prompt` do whisper.cpp empilha tokens no decoder e enviesa a
probabilidade da próxima palavra. É *prior*, não filtro.

## Por que ele existe

Medido no aparelho: sem prior, com as pistas espectrais de /s/ e /ɐ̃w/ perdidas
pelo HFP de 8 kHz, o decodificador escolhe por probabilidade — e "guarnição"
perdia para "nissan", "agora nisso são", "agora a inição". Ter a palavra no prior
é o que a faz ganhar.

`no_context = true` **não** anula o prompt: em `whisper.cpp:6937-6940` o
`prompt_past.clear()` roda **antes** do bloco 6961-6979 que empilha o prompt. A
ordem é limpa-depois-empilha. *Verificado na fonte, não suposto.*

## ⚠️ A contaminação, e ela é minha

As frases do bench `VerificadorDoGatilhoTest.qualidadeDoSttNoComando` são:

| # | Frase de teste | Palavras que também estão no prompt |
|---|---|---|
| 1 | "Claryon, mudar para guarnição 4." | `Claryon`, `guarnicao` |
| 2 | "Claryon, mudar para guarnição 3." | `Claryon`, `guarnicao` |
| 3 | "Central, a guarnição está a caminho da ocorrência." | `Central`, `guarnicao`, `ocorrencia` |
| 4 | "Claryon, onde está a guarnição do Sargento Paiva?" | `Claryon`, `guarnicao`, `Sargento` |

**Todas as quatro frases têm de duas a três palavras dentro do prior.** O WER de
21,2% medido em 2026-08-17 é, portanto, o **melhor caso do prompt** — não uma
medida do STT. Um modelo pior com o mesmo prior pode parecer melhor que um modelo
bom sem ele.

Isso não invalida a *escada* (tiny → base → small foi medida com o mesmo prompt em
todas, então a comparação relativa vale). Invalida o **valor absoluto** contra a
meta de ≥92%.

## ⚠️ MEDIDO em 2026-08-17: o prompt não ajuda, e talvez atrapalhe

Com `initial_prompt` virando parâmetro do JNI, o braço de controle que este
documento prescrevia finalmente rodou (`BracosDoPromptTest`, 8 amostras por braço,
o **mesmo áudio** nos três):

| Braço | WER geral | frases que colidem com o prior | frases limpas |
|---|---|---|---|
| **A0 — sem prompt** | **12,5%** | 19,2% | 6,7% |
| A1 — sem acento | 14,3% | 23,1% | 6,7% |
| A2 — com acento | 14,3% | 23,1% | 6,7% |

Três leituras, e as três importam:

1. **O prior custa 1,8 ponto em vez de render.** Não ajudou nem nas frases cujas
   palavras estão literalmente dentro dele.
2. **Acento é irrelevante: A1 e A2 são idênticos.** A hipótese do tokenizador
   (`whisper.cpp:3288` parte palavra acentuada no acento) pode até ser verdadeira,
   mas **não muda o resultado** — e portanto não justifica mexer no prompt.
3. Uma "correção" que eu havia feito — trocar o prompt para acentuado — não teve
   efeito nenhum. Foi mudança escrita com confiança e valor zero.

**Ressalva de tamanho, e ela é séria:** são 8 amostras por braço. Com `ΣN` desta
ordem o intervalo de confiança é largo demais para *provar* que o prompt prejudica.
O que estes números sustentam é mais modesto e ainda assim decisivo: **não há
evidência de que ele ajude**, e o ônus da prova é de quem quiser mantê-lo.

## Como descontaminar

Duas medições, sempre reportadas juntas:

1. **Régua limpa** — frases cujo vocabulário de conteúdo **não** aparece no
   prompt. É o número que vai contra a meta do `ROADMAP`.
2. **Régua de operação** — as frases do domínio, com o prompt ligado. É o número
   que descreve o produto em uso, e é legitimamente melhor.

E um **teste de controle**: as mesmas frases com e sem prompt, na mesma rodada. A
diferença entre os dois é quanto o prior vale de verdade — número que hoje ninguém
tem, e que decide se o prompt merece o custo de recomputar seus tokens a cada
iteração do decoder (há um `// TODO: do not recompute the prompt` em
`whisper.cpp:7123`).

## Regra permanente

**Palavra que entra no `initial_prompt` sai das frases da régua limpa, e
vice-versa.** As duas listas vivem neste arquivo justamente para que a colisão
seja visível em uma tela — foi por não estarem lado a lado que ela passou.

Quem mexer no prompt do `jni.c` atualiza este documento na mesma mudança.
