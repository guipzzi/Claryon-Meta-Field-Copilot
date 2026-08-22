# Avaliação das seis propostas da Fase 2

Recomendação técnica para decisão humana. Cada item diz **o que eu faria e por quê**,
com o impacto no produto — não um menu.

Ordenado por consequência, não pela numeração da spec.

---

## P-1 · Transcrever a fala de terceiros — **a decisão que governa as outras**

`CLAUDE.md` §2 proíbe "sem versão, sem flag, sem exceção". A spec ofereceu duas
saídas: assumir por escrito, ou restringir a Fase 2 ao botão.

### O que apurei antes de opinar

**Não existe portão acústico, e agora está verificado nos três artefatos:**

| Via | Veredito | Como confirmei |
|---|---|---|
| KWS em português | não existe | `javap` no AAR 1.13.5: os presets de KWS são chinês e inglês; não há preset *streaming* pt para emprestar |
| Botão dos óculos | **não é API pública** | `CaptureButtonState` e `DeviceState.getCaptureButtonState()` existem, mas em `com.facebook.wearable.mediastream.sessionx.data.models.device` — pacote **interno**. O `DeviceSession` público expõe só `getState()` e `getErrors()` |
| Botão do fone HFP | **existe e nunca foi considerado** | `grep` por `MediaSession\|MediaButton\|HEADSETHOOK` em `src/main` devolve **zero**; o conceito não aparece em nenhum `.md` do repo |

E um fato que muda o enquadramento: **a regra do §2 se justifica pelo beamforming**
("o beamforming isola quem veste os óculos"), e `gatilho-por-voz.spec.md:401-406`
declara que o beamforming é **premissa não medida** neste repositório. A regra
repousa numa suposição que ninguém verificou — então a escolha não é entre "limpo"
e "sujo": é entre exposições de tamanhos diferentes.

O próprio §2 já abre exceção para efêmero: *"O pré-roll do PTT vive em RAM e nunca
é persistido"*. Terceiro aparece no pré-roll hoje, e isso é aceito por escrito.

### O que eu faria: uma terceira saída que a spec não considerou

**Escuta contínua com escopo, não escuta permanente.** Ligada apenas enquanto o
agente **armou o modo mãos-livres** — e desarmada quando ele sai da tela da
guarnição, quando o app vai para segundo plano, e por um teto de tempo.

Por que isto é melhor que as duas saídas oferecidas:

- **Contra (a) "assumir e seguir":** transcrever o turno inteiro, sem supervisão,
  é uma exposição categoricamente diferente. Num edital com 20 pontos de
  considerações éticas e uma meia página de art. 38 da LGPD, "o app transcreve tudo
  o que ouve durante o turno" é a frase que se lê alto na banca. "Transcreve
  enquanto o agente ativa o modo, e descarta em milissegundos" é defensável.
- **Contra (b) "só botão":** mata a premissa do produto. A justificativa de D1 é
  operacional e é boa — *com uma mão na pistola e outra no volante, não há mão*.
- **Efeito colateral que vale por si:** com escopo, **P-4 desaparece**. O whisper
  pode continuar sendo liberado em `TRIM_MEMORY_UI_HIDDEN`, porque o modo
  mãos-livres não sobrevive ao segundo plano por construção.

**O que precisa estar escrito, e é enforçável em código, não promessa:**
nada persistido, nenhum log de conteúdo (nem em `DEBUG`), descarte no fim do ciclo,
supressão da própria saída ligada (Bloco 0), e um indicador **audível** de que o
modo está armado — o agente tem de saber quando o aparelho está ouvindo.

**O botão do fone entra como degrau intermediário**, não como substituto: ele
satisfaz o aceite literal ("nenhum toque na tela") e **não** satisfaz o objetivo
operacional, porque ainda exige uma mão. Vale como caminho de contingência se a
decisão sobre escuta contínua demorar — e custa pouco.

---

## P-6 · Semente do grupo corrente — **a que mais impacta o produto real**

Três saídas foram oferecidas: migração `0012` com `talk_groups.primario`; último
grupo persistido localmente; ou rádio abre fechado.

**Eu faria a migração `0012`.** Um agente **tem** uma guarnição primária — isso é
fato do domínio, não estado de aplicativo. Colocá-lo no cadastro é onde ele
pertence, e resolve de uma vez o mapa e o rádio apontarem para o mesmo grupo.

Contra "rádio abre fechado": faz **a primeira ação de todo turno depender do
gatilho por voz funcionar** — que é justamente a parte menos confiável do sistema.
É acoplar o caminho crítico ao componente mais frágil.

Contra "último grupo persistido": guarda no aparelho uma decisão que é da
corporação. Um agente remanejado abriria no grupo errado, e o produto não teria
como saber que está errado.

---

## P-3 · Earcons novos — **sim, e é barato**

`Earcon.FALHA` já carrega três fatos distintos (`CanalOcupado`, `CanalPerdido`,
`LimiteDeDuracao`) e o desenho acrescentaria o quarto e o quinto. Num produto
**sem display**, o som é a única interface: cinco causas com um som só significa que
o agente não sabe o que aconteceu.

> **Atualização de 2026-08-22.** São **sete** hoje: entraram `SemRede`,
> `PedidoRecusado` e `CanalNaoDevolvido`, da bateria de caos com N pares. Quatro
> deles passaram a sair como `SinalizarEFalar` — earcon de categoria **mais** a
> causa em ≤7 palavras —, o que resolve a parte de "não sei o que aconteceu" sem
> gastar earcon novo: *"Canal ocupado."* e *"Sem sinal. Nada foi transmitido."*
> mandam o agente fazer coisas opostas. O argumento desta proposta **não caiu**: ele
> vale exatamente para os pares que a fala não separa, e continua valendo inteiro
> para abertura × fecho de canal, onde a fala custaria latência num sinal que
> precisa ser instantâneo.

Pior: no desenho atual **abertura e fecho de canal soariam idênticos**. O agente não
distingue "estou no ar" de "saí do ar" — e no produto isso é a diferença entre
falar para a guarnição e falar sozinho.

`CANAL_ABERTO`, `CANAL_FECHADO`, `CANAL_NEGADO`. A biblioteca fixa é regra de design
de áudio, e a regra existe para evitar proliferação — três earcons com semântica
operacional distinta não são proliferação, são a razão da biblioteca existir.

---

## P-5 · Teto de 30 s e âncora no BIP — **sim, sem discussão**

A spec diz 12 000 ms; o código e o `ROADMAP` dizem 30 000. Não é decisão de produto,
é **divergência de documento** — e o `ROADMAP` já lista a correção como pendente.

A âncora é o que importa de verdade: hoje o teto conta da invocação de `transmitir`,
e o aceite exige "contados **a partir do BIP**". Com a subida de rota SCO custando
centenas de ms em HFP real, a diferença é significativa e sempre **contra** o agente
— ele perde tempo de fala que o aceite lhe dá.

---

## P-2 · A frase de ativação — **`Aurora` venceu, mas falta o número que decide**

Medido: `Aurora` 3/3 e `Oriente` 3/3 contra `Claryon` 0/3 (controle). O princípio
que a medição estabeleceu — o traço discriminativo não pode estar na consoante
inicial — é sólido e explica também por que `Aurora` é **um único token** no
vocabulário do modelo (id 40663) enquanto "Claryon" não existe nele.

**Mas há uma tensão que a medição de sobrevivência não vê**, e ela é exatamente o
risco que domina esta fase: *palavra real do léxico tokeniza bem **e** aparece na
fala espontânea*. "A aurora boreal apareceu no noticiário" é uma frase possível.
`Oriente` é pior ainda — é forma verbal de *orientar*.

### ⚠️ MEDIDO — `Aurora` está DESQUALIFICADA

A simulação de falso positivo rodou e pegou exatamente a armadilha:

```
falado:      "A aurora boreal apareceu no noticiário."
transcrito:  "Aurora Burial apareceu no noticiário o"
                ↑ posição 1 — o decodificador comeu o artigo
```

**1 disparo em 8 enunciados** de fala que não era comando. O aceite exige **zero em
oito horas**. Não há ajuste de limiar que salve: o portão viu a palavra exata, na
posição exata, porque ela **foi dita**.

E o mecanismo é pior do que "palavra comum": o decodificador **descartou o artigo
inicial**, promovendo a palavra à primeira posição. Aceitar a ativação nas três
primeiras posições — que era a mitigação proposta para o modo de falha do Whisper
de ignorar as primeiras palavras — **agrava** este risco.

### E o recall em BANDA ESTREITA reprova pelo outro lado

A segunda simulação — 28 enunciados distintos, todos passados por 16 → 8 → 16 kHz,
que é o que o HFP entrega:

```
exigindo na 1ª palavra ....... 50,0 %  (14/28)
aceitando nas 3 primeiras .... 57,1 %  (16/28)
meta do aceite ............... 90 %
```

As falhas dizem por quê:

```
"Agora pedirá apoio."          "agora é modo ativo."
"Aura-hora. Consultar placa."  "Aurorondista, guarnição 3."
```

**`Aurora` colapsa em "agora".** Em pt-BR são quase homófonas — /awˈɾɔɾɐ/ contra
/aˈgɔɾɐ/ — e "agora" é uma das palavras mais frequentes da fala falada. O modelo de
linguagem escolhe a frequente, exatamente como fez com "Honda" → "onda".

**A banda estreita é a variável decisiva, e isso é o achado que generaliza.** Em
banda cheia `Aurora` deu **3/3**; em banda estreita, **50%**. A ordem das candidatas
muda com a banda — e foi por isso que a análise de 14/08 aprovou "Claryon" e errou:
ela foi feita em banda cheia. **Nenhuma medição de palavra de ativação vale se não
for em 8 kHz.**

**A tensão está agora demonstrada nos dois eixos, e é estrutural:**

| Propriedade | `Claryon` | `Aurora` |
|---|---|---|
| tokeniza como unidade | ✘ (não existe no vocabulário) | ✔ (um token, id 40663) |
| sobrevive em banda **cheia** | 0/3 | 3/3 |
| sobrevive em banda **estreita** | não medido | **✘ — 50%** |
| **rara na fala espontânea** | ✔ | **✘ — 1/8** |

Palavra inventada não é transcrita; palavra real é transcrita **e aparece sozinha**.
As duas propriedades que o produto precisa são antagônicas no mesmo eixo.

**O que eu faria:** parar de procurar palavra única e usar **frase de duas
palavras improvável em coocorrência** — cada uma comum o bastante para tokenizar,
o par raro o bastante para não ocorrer. O aceite já aponta esse caminho ao exigir
casamento **integral** ("Claryon, abrir canal") em vez de palavra solta. O critério
de escolha passa a ser: **P(par | fala operacional) ≈ 0**, medido no mesmo bench.

Isso também explica, em retrospecto, por que o desenho de dois estágios foi
abandonado cedo demais: o problema nunca foi o número de estágios, foi tentar
carregar toda a discriminação numa palavra só.

---

## P-4 · `TRIM_MEMORY_UI_HIDDEN` — **provavelmente desnecessária**

Ela existe só porque a escuta contínua precisaria sobreviver à tela apagada. Com o
escopo proposto em P-1, o modo mãos-livres **termina** quando o app vai para segundo
plano, e o `EscutaDoAgente` pode continuar liberando o whisper como hoje.

Se a decisão em P-1 for escuta permanente, então P-4 volta — e traz junto um
orçamento de memória (whisper `small-q5_1` + Piper + **duas** instâncias nativas de
Silero + MapLibre) que provavelmente exige *foreground service*. Isso é trabalho
real e risco de LMK, num processo que o projeto já sabe que o sistema mata primeiro.

**Decidir P-1 primeiro torna P-4 uma não-questão.**

---

## Resumo

| # | Recomendação | Impacto se ignorada |
|---|---|---|
| **P-1** | escuta contínua **com escopo** (terceira via) | define se a Fase 2 existe, e o que se assina na LGPD |
| **P-6** | migração `0012`, `talk_groups.primario` | primeira ação do turno depende do componente mais frágil |
| **P-3** | criar os três earcons | agente não distingue estar no ar de ter saído |
| **P-5** | 30 s, ancorado no BIP | documento mente e o agente perde tempo de fala |
| **P-2** | **`Aurora` reprovada (1/8)** — trocar por **par de palavras** | falso positivo toma o piso da guarnição |
| **P-4** | some se P-1 for com escopo | *foreground service* e risco de LMK sem necessidade |

---

## ✅ P-2 medida: o par funciona, mas `Hey Claryon` não

Cinco candidatas, **banda estreita 8 kHz**, casamento exigindo o par **em sequência**:

| Par | recall | falso positivo |
|---|---|---|
| **Hey Claryon** | **0/6** | 0/8 |
| Escuta Claryon | 0/6 | 0/8 |
| **Atenção Aurora** | **5/6 — 83,3%** | **0/8** |
| Alerta Aurora | 2/6 — 33,3% | 0/8 |
| Copiloto escuta | 0/6 | 0/8 |

### Por que `Hey Claryon` falha: o par não conserta OOV

```
"Hey Claryon, mudar para guarnição 3"  →  "Eclareon, mudar para a guarnição 3"
"Escuta Claryon, mudar para..."        →  "Escuta Clarion, mudar para..."
```

O "Hey" **funde** com "Claryon" e vira "Eclareon". E onde não funde, "Claryon"
continua virando "Clarion" — porque **a palavra não existe no vocabulário do
modelo**, e nada que venha antes muda isso.

Isto delimita o que o par resolve: **o par ataca o eixo da raridade, não o da
sobrevivência.** Uma palavra OOV continua OOV emparelhada.

### O que o par resolveu, e resolveu por completo

**Todas as cinco candidatas deram 0/8 de falso positivo** — inclusive as que usam
`Aurora`, que sozinha disparava em "a aurora boreal". Exigir o par em sequência
elimina o eixo que reprovou a palavra única. Esse era o risco que dominava a fase,
e ele está resolvido pelo desenho.

### `Atenção Aurora` é a melhor medida, e o motivo é instrutivo

`Aurora` sozinha em banda estreita: **50%**. Precedida de "Atenção": **83,3%**. A
palavra de contexto ajuda o decodificador a resolver a ambiguidade com "agora" — o
modelo de linguagem passa a ter evidência de que ali começa um vocativo, não um
advérbio. A única falha foi exatamente `"Atenção agora, mudar para G4"`, o mesmo
colapso, agora raro.

### O padrão que atravessa tudo o que foi medido hoje

O modo de falha dominante do decodificador é **a fronteira de palavra**, não o
fonema:

```
Andorinha    → Dandorinha     (inseriu)
Copiloto     → O piloto       (partiu)
Hey Claryon  → Eclareon       (fundiu)
Aurora       → agora          (trocou pela vizinha frequente)
```

Isso explica em retrospecto por que a posição 1 é o pior lugar possível para a
palavra de ativação: é a fronteira com o silêncio, a menos ancorada de todas.

### O que falta: 83,3% contra a meta de 90%

E o número é do **melhor caso** — Piper, sem sotaque, sem hesitação, sem AGC de
*uplink*. Duas saídas, e a segunda ficou barata porque o falso positivo zerou:

1. **Mais candidatas na mesma família** — palavra de contexto + palavra distintiva,
   agora sabendo que o contexto é o que salva a segunda palavra.
2. **Lista curta de variantes do par**, justificada por medição e não por esperança:
   com 0/8 de falso positivo há orçamento para aceitar também `atenção agora`. Ela
   precisa ser medida contra fala operacional antes de entrar — "atenção, agora
   vamos" é frase plausível, e seria trocar um eixo pelo outro.

---

## ⚠️ A lista de variantes: metade funciona, e a outra metade refuta a premissa

A revisão pediu para manter `Hey Claryon` e "treinar" o sistema a reconhecer as
grafias que o decodificador produz. Eu disse que os erros eram **sistemáticos** — em
6 amostras, `Eclareon` aparecia 2 vezes e `Clarion` de forma consistente.

Com **10 amostras e comandos mais variados**, a premissa cai:

```
recall só com a grafia canônica .. 0,0%   (0/10)  ← "hey claryon" literal NUNCA aparece
recall com 7 variantes ........... 40,0%  (4/10)
meta ............................. 90%
falso positivo ................... 0/10   ✅
```

### O que funcionou, e está validado

**Falso positivo zero**, mesmo com sete variantes e mesmo contra armadilhas
deliberadas: *"ele clareou a situação"*, *"a claridade do dia"*, *"é clara a
necessidade de apoio"*. A lista ancorada no início da transcrição **não dispara**
em fala operacional. O eixo que matou `Aurora` está resolvido pelo desenho.

### O que refutou a premissa

As grafias não cobertas são **todas diferentes entre si**:

```
ecarion · e carao · e carion · eca o
```

Não há um conjunto pequeno e estável para listar. Cada amostra nova tende a
produzir uma grafia nova, e o motivo é estrutural: **para uma palavra fora do
vocabulário, não existe sequência de tokens "certa" para o decodificador
convergir.** Ele improvisa uma aproximação fonética a cada vez, e a aproximação
**deriva com o contexto seguinte** — que é justamente o que varia entre comandos.

Perseguir essas grafias é uma lista que cresce sem convergir — a "peneira" que a
spec já alertava, e cada entrada nova é superfície de falso positivo. Os 0/10 de
hoje não sobreviveriam a uma lista de trinta entradas.

### A saída que preserva o que a revisão quer

**A marca não precisa ser a palavra de ativação.** A marca da Alexa é Amazon; a da
Siri é Apple. São coisas diferentes, e confundi-las é o que está custando aqui.

- **`Claryon` continua sendo o nome do produto** — na tela, no documento, no deck.
- **O gatilho falado passa a ser um par que sobrevive** — `Atenção Aurora` mediu
  **83,3%** com 0/8 de falso positivo, contra 40% do `Hey Claryon` com lista.

O que se perde é a marca *dita em voz alta*. O que se ganha é o dobro do recall e
uma lista que não cresce.

Se a decisão for manter `Hey Claryon` falado assim mesmo, o número honesto para o
`ROADMAP` é **40%**, não 90% — e a meta precisa ser reescrita, porque meta
inalcançável é pior que meta revisada.
