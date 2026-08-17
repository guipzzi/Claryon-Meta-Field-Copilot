---
feature: gatilho-por-voz
capacidade: C1 (rádio tático) + C2/C3/C4 (comando por voz)
estado: proposta
autor: revisão humana pendente
criada: 2026-08-15
revista: 2026-08-16
substitui: versão de 2026-08-15 (modelo de dois estágios "Hey Claryon" + "na escuta")
sobrepoe:
  - "docs/PADROES_DE_ENGENHARIA.md § Rádio tático — 'nunca por palavra de ativação'"
depende_de:
  - fonte-unica-de-microfone-com-fanout
  - taxa-16khz-ponta-a-ponta
  - dono-unico-da-saida-de-audio
  - silero-vad-embarcado
---

# Gatilho por voz: comando e transmissão sem tocar na tela

## Objetivo

Operação mãos livres. O agente comanda o copiloto e abre transmissão de rádio sem
tocar na tela, porque em campo as mãos estão ocupadas — algemando, dirigindo, com
a arma em punho.

## O que mudou em relação à versão anterior desta spec, e por quê

A versão de 2026-08-15 propunha **dois estágios falados** ("Hey Claryon", janela de
4 s, "na escuta") casados por *keyword spotting* contra léxico fechado. Três fatos
apurados desde então derrubam aquele desenho:

1. **Não existe modelo de KWS em português neste projeto — e nem há de onde tirar
   um.** `KeywordSpotterKt.getKwsModelConfig` conhece **dois** presets, não um:
   `sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01` (chinês) e
   `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (inglês). O `tableswitch`
   do bytecode fecha a contagem — `getKwsModelConfig(2)` devolve `null` — e os 16
   `.so` do AAR não escondem um terceiro (`strings` devolve zero). *Verificado por
   `javap -c` em `core-voice/libs/sherpa-onnx-1.13.5.aar`, sha256
   `6419cd8b…a82b`, em 2026-08-17.*

   **O que fecha a porta de vez:** KWS exige transducer **online** (streaming), e
   `OnlineRecognizerKt` tem 39 presets, **nenhum** em português. Os dois presets pt
   que existem no AAR (`stt_pt_fastconformer`) são **offline** — não servem de KWS
   por construção. Não existe "pegar emprestado um modelo pt do próprio AAR": as
   saídas reais são embarcar KWS em inglês com grafia fonética, ou treinar pt-BR.

   Um KWS fora do idioma não pode ser a **única** prova de intenção de transmitir.
2. **O único motor de fala em português já EMBARCADO é o whisper-tiny**, e ele está
   fixado em pt (`core-voice/src/main/cpp/jni.c:190`, `params.language = "pt"`).
   É ele quem tem de verificar, não o KWS.

   "Embarcado", e não "existente": o AAR conhece outros caminhos pt para ASR
   **offline** (dois `stt_pt_fastconformer`, mais omnilingual/qwen3/whisper
   multilíngues). Nenhum foi medido e nenhum está no APK — mas a frase antiga
   induzia a conclusão de que não havia alternativa, e há.
3. **A janela de 4 s entre estágios cria um modo de falha próprio** — "disse o
   estágio 1, o estágio 2 chegou tarde" — e obriga fala antinatural. O que os dois
   estágios protegiam era *confirmação independente*; isso se obtém melhor com dois
   **modelos** independentes sobre um enunciado só.

O modelo novo é: **um enunciado isolado, verificado por transcrição em português
contra léxico fechado.** O que continua valendo da versão anterior: earcon antes do
primeiro quadro, earcon recorrente enquanto o canal estiver aberto, teto duro de
duração, recusa honesta.

**Isto continua sendo proposta.** Sobrepor regra dura é decisão humana.

## O que este documento sobrepõe, e o que ele não sobrepõe

A regra dura vigente diz: *"Transmissão é sempre push-to-talk explícito. Nunca por
palavra de ativação: um falso positivo difundiria para a guarnição inteira"*
(`docs/PADROES_DE_ENGENHARIA.md` § Rádio tático).

**Redação nova proposta:**

> Transmissão exige intenção explícita e verificada. Push-to-talk satisfaz. Voz
> satisfaz quando: (a) o enunciado do gatilho for um segmento de fala **isolado**,
> (b) sua transcrição em português casar **integralmente** um léxico fechado de
> abertura, (c) um earcon soar antes do primeiro quadro, (d) houver teto duro de
> duração medido em relógio de parede, e (e) existir parada que não dependa do
> microfone. Detecção por palavra sozinha, sem verificação por transcrição,
> continua proibida para transmissão.

**O que este documento NÃO sobrepõe:** nada da seção § Rádio tático além da frase
acima; nada de § Rota de áudio; nada de § Proibições absolutas. Em particular, a
regra "detector de palavra de ativação desligado enquanto qualquer áudio sai pelos
alto-falantes e enquanto o PTT está ativo" **permanece intacta e é reforçada** pelo
item 21 abaixo.

**Comando por voz não sobrepõe regra nenhuma.** Nenhuma regra dura proíbe executar
`Intent.ConsultarPlaca` ou `Intent.ConsultarPosicao` por voz — elas não difundem
nada. O caminho de comando entra primeiro e independe desta revisão humana.

## Comportamento

### Os dois caminhos, e por que são separados

| | Comando (C2/C3/C4) | Transmissão (C1) |
|---|---|---|
| Frase | "Claryon, `<comando>`" | "Claryon, abrir canal" |
| O que um falso positivo custa | uma consulta indevida, local | o piso da guarnição |
| Difunde áudio? | não | sim |
| Verificação | whisper pt + roteador determinístico | whisper pt + léxico fechado **integral** |
| Precisa desta revisão humana? | **não** | **sim** |

### Vocabulário

| Papel | Frase | Justificativa |
|---|---|---|
| Ativação | **"Claryon"** | Decidido em `DECISIONS.md` 2026-08-14: plosiva + líquida + vogais abertas + nasal, tudo abaixo do corte de 4 kHz do HFP em banda estreita. **⚠️ Medido em 2026-08-17: o STT escreve "Clarion", não "Claryon" — ver abaixo** |

#### ✅ Medido em 2026-08-17: a palavra tem de começar por VOGAL

`PalavraDeAtivacaoTest`, 3 sínteses por candidata, `ggml-small-q5_1`, frase de
comando real. O critério foi a primeira palavra transcrita ser a candidata:

| Candidata | Acertos | Grafias produzidas | Início |
|---|---|---|---|
| `Claryon` *(controle)* | **0/3** | farion · cladion | consoante |
| **Aurora** | **3/3** | aurora | **vogal** |
| `Andorinha` | 0/3 | dandorinha | consoante¹ |
| `Bandeirante` | 0/3 | flamirante · vamperante | consoante |
| **Oriente** | **3/3** | oriente | **vogal** |

¹ *"Andorinha" começa por vogal na grafia, mas o decodificador inseriu um /d/
espúrio no ataque — o que reforça a leitura: o problema é a fronteira inicial da
palavra, e uma vogal aberta e longa a defende melhor que uma vogal breve seguida
de nasal.*

**O princípio que a medição estabelece, e que a análise a priori errou:** o traço
discriminativo não pode estar na consoante inicial. As três candidatas que falharam
tiveram o *onset* corrompido ou um segmento espúrio inserido; as duas que passaram
decidem a própria identidade por sequência vocálica e sonorantes. Isso é coerente
com a banda útil do HFP em CVSD (300–3400 Hz): o *burst* de plosiva e a fricativa
alta são justamente o que se perde, e a vogal é o que sobrevive.

**Recomendação: `Aurora`.** Empata com `Oriente` em 3/3, e ganha no critério que a
medição não cobre: "oriente" é também forma verbal de *orientar*, e a spec exige
que a palavra de ativação seja **rara na fala espontânea** — um falso positivo custa
o piso da guarnição. `Aurora` é substantivo/nome próprio e não é locução de
protocolo policial.

**O que esta medição NÃO decide, e é o número que realmente governa:** a **taxa de
falso positivo** em fala espontânea de rádio. Mede-se transcrevendo áudio de
operação normal e contando quantas vezes a palavra aparece sem ninguém a ter dito.
Enquanto esse número não existir, `Aurora` é a **melhor candidata medida**, não a
decisão final. Falta também o caminho HFP real de 8 kHz — foi o Piper a 16 kHz que
aprovou "Claryon" em 14/08.

#### ⚠️ Por que a hipótese anterior (ortografia) foi descartada

Medido no aparelho com `ggml-base-q5_1`, 8 amostras: a palavra de ativação falhou em
**6** delas — `clarion` (3×), `varion` (1×) e **omitida por completo** (2×).

**⚠️ CORREÇÃO de 2026-08-17, com mais amostras: não é ortografia.** A primeira
leitura desta seção dizia que `clarion` era só grafia portuguesa de /klaɾiˈõ/ — o
modelo acertando o som e escrevendo à portuguesa. Com o conjunto completo de
amostras, essa explicação **cai**:

| grafia observada | consoante inicial |
|---|---|
| clarion | /k/ |
| **varyon** | /v/ |
| **farion** | /f/ |
| **parion**, parão | /p/ |
| **marcarion** | /m/ |
| clarão | /k/ |

A plosiva inicial **troca de lugar de articulação e de modo** entre rodadas do
mesmo áudio. Isso não é escolha de grafia: é o *onset* não sobrevivendo. E
contradiz frontalmente o critério que escolheu a palavra (`DECISIONS.md`
2026-08-14: *"plosiva + líquida + vogais abertas + nasal, tudo abaixo do corte de
4 kHz"*) — a plosiva é exatamente a parte que se perde.

Consequência: **aceitar uma lista de variantes ortográficas não resolve.** Uma
lista que cubra clarion, varyon, farion, parion e marcarion aceita praticamente
qualquer dissílabo terminado em nasal — deixa de ser palavra de ativação e passa a
ser um portão aberto, com o custo que a própria spec atribui a um falso positivo:
o piso da guarnição.

Consequência para o aceite A1/A2, que exige a transcrição **começar por "claryon"**:
a comparação exata reprova uma escuta correta. As saídas, em ordem de preferência:

1. **Trocar a palavra de ativação**, e escolher a nova por **medição**. ✅ **FEITO —
   ver a tabela abaixo.**
2. **Aceitar variantes ortográficas** — descartado como solução isolada pela tabela
   acima, mas ainda necessário como complemento: mesmo a palavra vencedora vai ter
   grafia alternativa, e a lista tem de ser **explícita e curta**, nunca distância
   de edição (que segue proibida em `troca-de-grupo-por-voz.spec.md`).
3. **Não depender da palavra de ativação para o caminho de COMANDO.** O botão
   "Perguntar ao copiloto" já existe e é confiável. A palavra de ativação é
   requisito só do caminho mãos-livres — e vale perguntar se ela precisa existir
   antes de o resto estar sólido.

As **duas omissões** são falha de verdade e nenhuma lista de variantes resolve: o
modelo não produziu nada no lugar. Com 2 em 8, o portão de ativação recusaria uma em
quatro tentativas legítimas do agente — e recusar quem falou certo é o modo de falhar
que faz o produto ser desligado.
| Abertura de canal | **"Claryon, abrir canal"** | Não é locução de protocolo — operador diz "câmbio", "cópia", "prossiga", "QAP", "na escuta"; nunca "abrir canal". Sem sibilante: /b/, /ɾ/, /k/, /n/, /l/ sobrevivem ao corte de banda |
| Fecho | **"câmbio"** | **Não é detectada por modelo nenhum.** Ver item 12 |

**"Na escuta" sai do vocabulário.** A versão anterior a usava como estágio 2. É
locução corrente de canal policial brasileiro — dita no ar o tempo todo. Uma frase
cujo trabalho inteiro é *não ocorrer* não pode ser das mais frequentes do domínio.

### Como o gatilho é reconhecido — a cascata

Tudo roda sobre **uma** captura de microfone a 16 kHz, com fan-out (ver
`fonte-unica-de-microfone-com-fanout`).

| # | Estágio | Sempre ligado | Pode abrir canal |
|---|---|---|---|
| E1 | `com.k2fsa.sherpa.onnx.Vad` (Silero) — segmenta fala | sim | não |
| E2 | Filtro aritmético de duração do segmento | sim | não |
| E3 | `WhisperCppStt` (`ggml-tiny`, pt) sobre o segmento fechado | quase nunca | **sim, só ele** |
| E4 | `KeywordSpotter` como pré-filtro de bateria | **opcional** | não |

**E4 é otimização, não requisito.** Seu único trabalho é reduzir invocações do E3.
O sistema tem de estar correto com E4 desligado — e é assim que ele entra em
produção. Ver risco aceito 1.

### A propriedade que fecha o laço acústico

**A transmissão começa no segmento de fala *seguinte* ao do gatilho.** O enunciado
do gatilho é um segmento inteiro do Silero; ele não é fatiado, não é deslocado por
`timestamp`, e não é transmitido. Consequência: **áudio produzido pelo Claryon e
difundido na guarnição nunca contém a frase de abertura** — logo, um rádio de
viatura reproduzindo a transmissão de outro agente não consegue abrir canal aqui.

Isto vale por **segmentação**, não por aritmética de deslocamento sobre um buffer
circular. Não há offset a errar. É a razão de o item 6 exigir que o enunciado do
gatilho esteja **sozinho** no segmento.

### Critérios de aceite (EARS)

**A — Comando por voz** (não depende de revisão humana)

1. `Quando` o detector de fala fechar um segmento `e` a transcrição em português
   começar por "claryon" (normalizada: minúscula, sem acento), `o sistema deverá`
   rotear o restante por `DeterministicIntentRouter`, executar por
   `IntentExecutor` e responder por `utteranceFor(ActionOutcome)`.
2. `Se` a transcrição não começar por "claryon", `então o sistema deverá` descartar
   o segmento **em silêncio**, sem earcon e sem log de conteúdo.
3. `Quando` um comando for aceito, `o sistema deverá` emitir `OUVI_VOCE` antes de
   executar a ação, e a resposta falada `deverá` derivar do `ActionOutcome`, nunca
   da `Intent`.
4. `Enquanto` houver transmissão de rádio em curso, `o sistema deverá` manter o
   caminho de comando **desligado**.

**B — Abertura da transmissão**

5. `Quando` o detector de fala fechar um segmento `e` a duração do segmento estiver
   entre **0,6 s e 2,5 s**, `o sistema deverá` submetê-lo ao verificador; fora
   dessa faixa, `deverá` descartá-lo sem invocar o verificador e sem emitir som.
6. `Quando` a transcrição do segmento, normalizada, for **exatamente** "claryon
   abrir canal" (tolerando pontuação e espaço), `o sistema deverá` armar a
   transmissão. `Se` a transcrição contiver a frase de abertura **mais qualquer
   outra palavra**, `então o sistema deverá` recusar a abertura, emitir `FALHA` e
   dizer *"Repita. Só a frase."*
7. `Enquanto` a transmissão estiver armada e antes do primeiro quadro sair, `o
   sistema deverá` verificar, nesta ordem: rota em SCO, transporte conectado, piso
   concedido. `Se` qualquer uma falhar, `então o sistema deverá` recusar e dizer a
   causa (itens 23 a 25).
8. `Quando` todas as pré-condições do item 7 forem satisfeitas, `o sistema deverá`
   emitir o earcon `CANAL_ABERTO` **antes** de o primeiro quadro sair.
9. `Quando` a transmissão começar, `o sistema deverá` enviar quadros Opus de 20 ms
   ao vivo enquanto o agente fala. Gravar arquivo inteiro e enviar depois é
   proibido.
10. `Quando` a transmissão começar, `o sistema deverá` incluir apenas o pré-roll
    capturado **depois** do fim do segmento do gatilho. O buffer de pré-roll
    `deverá` ser esvaziado no instante da verificação (item 6), e nenhum áudio
    anterior a esse instante pode ir ao ar.
11. `Enquanto` a transmissão aberta por voz estiver em curso, `o sistema deverá`
    usar prioridade **P2_APOIO**. Abrir em **P1_EMERGENCIA por voz é proibido**:
    P1 toma o canal de quem fala, e voz não pode tomar canal de ninguém.

**C — Encerramento**

12. `Quando` o detector de fala não detectar fala por **700 ms contínuos**, `o
    sistema deverá` encerrar a transmissão e emitir `ACAO_EXECUTADA`. A palavra
    "câmbio" é **hábito operacional, não gatilho**: ela existe para produzir essa
    pausa, e nenhum modelo a detecta.
13. `Se` a transmissão atingir **12 000 ms** de relógio de parede contados do
    primeiro quadro, `então o sistema deverá` encerrá-la e emitir `FALHA`, nunca
    `ACAO_EXECUTADA`.
14. `O sistema deverá` avaliar o teto do item 13 **fora** do consumidor de quadros,
    por relógio independente, de modo que ele dispare mesmo se a fonte de áudio
    parar de emitir.
15. `Enquanto` a transmissão estiver aberta, `o sistema deverá` emitir o earcon
    `NO_AR` a cada **4 s** até os 8 s e a cada **2 s** depois. A aceleração é o que
    distingue "ainda estou falando" de "esqueci que estava aberto".
16. `O sistema deverá` agendar cada emissão de `NO_AR` para a primeira pausa que o
    detector de fala marcar dentro de uma janela de +1,5 s; `se` nenhuma pausa
    ocorrer, `então deverá` emitir no relógio fixo.
17. `Enquanto` a transmissão estiver aberta, `o sistema deverá` manter o bloco de
    fala da tela disponível como parada, e um toque nele `deverá` encerrar a
    transmissão emitindo `FALHA`. Esta é a única parada que não depende do
    microfone e **não sai do produto**.
18. `Quando` a transmissão encerrar por qualquer motivo, `o sistema deverá` liberar
    o piso e esvaziar o pré-roll, inclusive sob cancelamento e sob rede caída.
19. `Se` o piso for perdido durante a transmissão, `então o sistema deverá`
    encerrá-la imediatamente e emitir `FALHA` — seguir falando é falar para o vazio.
20. `Quando` uma concessão nascer por voz, `o sistema deverá` pedi-la com TTL de
    **15 s**, e não os 30 s do caminho de toque: o cliente que morre com o canal
    aberto por voz devolve o canal ao grupo em metade do tempo.

**D — Convivência com a própria saída**

21. `Enquanto` qualquer áudio estiver saindo pelos alto-falantes, `o sistema
    deverá` manter o verificador de abertura desligado, e `deverá` alimentar o
    detector de fala com **silêncio digital** no lugar dos quadros suprimidos — nem
    com a nossa saída (que o acordaria), nem removendo-os (que deformaria a
    contagem de silêncio do detector).
22. `O sistema deverá` abrir e fechar a janela de supressão nos instantes **reais**
    de início e fim da reprodução, e não no instante de enfileiramento.

**E — Recusa honesta**

23. `Se` a rota de áudio não estiver em SCO, `então o sistema deverá` recusar a
    abertura e dizer *"Sem rota."*
24. `Se` o canal estiver ocupado por outro agente, `então o sistema deverá` recusar
    a abertura e emitir o earcon de falha.
25. `Se` não houver rede, `então o sistema deverá` recusar a abertura e dizer
    *"Sem rede."* — a transmissão ao vivo **não** vai para fila offline.
26. `Se` o verificador falhar ou não produzir texto, `então o sistema deverá`
    descartar o segmento **em silêncio**. Falso negativo de gatilho não merece
    earcon: o agente repete a frase.

### Não-funcionais

| Métrica | Alvo | Como medir |
|---|---|---|
| Fim do enunciado do gatilho → `CANAL_ABERTO` | ≤ 1 200 ms | `Telemetry.mark(VERIFICACAO_OK)` − `mark(SEGMENTO_FECHADO)` + `mark(CANAL_ABERTO)` |
| Fim do enunciado do gatilho → primeiro quadro no ar | ≤ 1 500 ms | `mark(PRIMEIRO_QUADRO)` − `mark(SEGMENTO_FECHADO)` |
| Fim da fala → fecho da transmissão | ≤ 900 ms | `mark(FECHO_POR_SILENCIO)` − último quadro com fala |
| Falsos positivos de **abertura de canal** | **≤ 1 por 8 h** | turno simulado: 8 h de fala natural + tráfego de rádio de viatura reproduzido no ambiente. Conta-se `mark(CANAL_ABERTO)` |
| Falsos positivos do caminho de **comando** | ≤ 1/hora | 30 min de fala natural × 2, conta-se `mark(INTENT_ROUTED)` |
| Verificações descartadas por hora | registrar, sem alvo | `mark(VERIFICACAO_DESCARTADA)`. É o número que decide se E4 é necessário |
| Invocações do verificador por hora, modo Ativo | ≤ 400 | `mark(VERIFICACAO_INICIADA)`. Acima disso, E4 entra |
| Bateria do celular, modo Ativo com o gatilho ligado | ≤ 12%/h | mesma meta já declarada em § Metas mensuráveis; medida com e sem o gatilho |
| Transmissões encerradas por teto (item 13) | ≤ 5% | `mark(FECHO_POR_TETO)` ÷ `mark(CANAL_ABERTO)`. Acima disso, o teto está curto demais |

Marcos novos a acrescentar em `Telemetry.Stage`: `SEGMENTO_FECHADO`,
`VERIFICACAO_INICIADA`, `VERIFICACAO_OK`, `VERIFICACAO_DESCARTADA`, `CANAL_ABERTO`,
`PRIMEIRO_QUADRO`, `FECHO_POR_SILENCIO`, `FECHO_POR_TETO`, `PARADA_POR_TOQUE`.

Hoje `Telemetry.mark` **não tem chamador nenhum** e a interface não é referenciada
fora do próprio arquivo (`core-common/.../Telemetry.kt`). Nenhuma métrica desta
tabela é observável antes de isso mudar.

### Fora de escopo

- **Fecho por palavra detectada.** "Câmbio" não aciona modelo nenhum nesta versão.
  Um detector de fecho barato precisaria do mesmo KWS fora do idioma, e um falso
  fecho no meio de "solicito apoio, suspeito arma—" trunca mensagem operacional.
- **Cancelar uma transmissão já iniciada.** Continua não existindo desfazer: os
  quadros já saíram e já tocaram no ouvido dos pares. O que existe é **parar**
  (itens 13 e 17), e o produto chama isso de parar, não de abortar.
- **Endereçar talk group por nome arbitrário.** O léxico é fechado e fixo.
- **Prioridade P1 por voz.** Ver item 11. `Intent.Emergencia` continua alcançável
  pelo caminho de comando, que despacha estruturado sem tomar o canal de ninguém.
- **Transcrever ou arquivar a fala transmitida por este caminho.**
- **Modo Standby.** `PowerPolicy` já descreve Standby com `wakeWordAtiva = false` e
  HFP fechado, mas nada tira o serviço de Ativo. Enquanto Standby for inalcançável,
  o gatilho custa HFP aberto o turno inteiro.

### Riscos aceitos

1. **O pré-filtro de bateria (E4) não existe em português, e não há terceira via.**
   Os dois presets de KWS do AAR são chinês e inglês, e não há preset **streaming**
   em pt para pegar emprestado (ver o item 1 acima). Se a taxa de invocação do
   verificador ficar acima do alvo, as saídas são, nesta ordem: subir o piso de
   duração do item 5; embarcar o KWS em inglês e escrever "claryon" na grafia
   fonética dele (recall não medido); treinar um KWS pt-BR (fora do prazo).
   **Não há caminho em que a ausência de E4 quebre a correção** — ela só custa
   bateria.

   *Nota de artefato:* `KeywordSpotter` aceita caminho de arquivo arbitrário — o
   preset é conveniência, não lista branca (o construtor ramifica para
   `newFromFile(KeywordSpotterConfig)` quando o `AssetManager` é nulo). Que o
   nativo aceite qualquer zipformer2-transducer de KWS é **inferência**, não está
   no artefato.
2. **A verificação depende do whisper-tiny em pt.** `Transcript.confidence` é
   sempre `null` neste projeto (`WhisperCppStt.kt:84`), então não há limiar de
   confiança a ajustar: a decisão é casamento exato de texto normalizado contra
   léxico fechado. Alucinação do tiny sobre ruído terminal é mitigada pelo item 5
   (faixa de duração) e pelo corte do hangover na saída do detector.
3. **Fecho por silêncio pode cortar a mensagem no meio.** 700 ms é curto para quem
   pensa no meio da frase. Reabrir custa a cascata inteira (~1,5 s) e o canal pode
   ter sido tomado no intervalo. Aceito porque a direção segura da falha é fechar,
   e porque "câmbio" como hábito torna a pausa intencional.
4. **O rádio da viatura é invisível ao supressor, e continuará sendo.**
   `SupressorDeSaidaPropria` conhece só o som que **nós** emitimos. Um rádio de
   viatura é outro aparelho. O que o desenho faz é remover o **valor** do
   vazamento: "abrir canal" não é vocabulário de rádio, e o gatilho nunca vai ao
   ar. O que fica descoberto: um humano dizendo "Claryon, abrir canal" perto do
   agente abre o canal do agente. Não há mitigação acústica para isso.
5. **O earcon recorrente custa fala.** Cada emissão descarta a captura durante a
   reprodução mais 80 ms de margem. O item 16 agenda a emissão na pausa que o
   detector já está marcando, o que reduz o custo em fala normal — mas não o zera.
6. **O beamforming é premissa não medida neste repositório.** A doc oficial do DAT
   afirma que em HFP o *wearable* isola a voz de quem o veste. Nada aqui foi
   medido com os óculos na cabeça: nem o isolamento, nem o comportamento do AGC do
   *uplink*. Toda a discriminação portador × ambiente desta spec é **do Silero**,
   que classifica fala × não-fala, e **não** de nível relativo — deliberadamente,
   porque um limiar de nível calibrado sem o hardware seria número inventado num
   caminho de segurança.

### Como testar

**JVM (determinístico, sem hardware) — itens 1 a 7, 11 a 20, 26**

A máquina de estados do gatilho é pura e recebe relógio por parâmetro, como
`GatilhoPtt` e `ControleDePiso`. Cada item acima vira ao menos um teste:

- `LexicoDeAberturaTest` — item 6, incluindo os casos de recusa: "claryon abrir
  canal agora", "abrir canal", "claryon, abrir canal." (aceito), "claryon abre
  canal" (recusado).
- `MaquinaDoGatilhoTest` — itens 5, 7, 11, 12, 13, 15, 18, 19, 20 com relógio
  falso e segmentos sintéticos.
- `TetoForaDoColetorTest` — item 14: fonte de quadros que **para de emitir** e o
  teto ainda dispara. Este é o teste que a implementação atual de `SessaoPtt` não
  passa: o teto de duração e a renovação de piso vivem **dentro** do
  `pcmAoVivo.collect` (`core-net/.../SessaoPtt.kt:142` e `:148`), logo não rodam
  sem quadro chegando.
- `PreRollDoGatilhoTest` — item 10: o buffer é esvaziado na verificação e o que
  sai não contém amostra anterior ao fim do segmento do gatilho.
- `RecusaHonestaTest` — itens 23 a 26, um por causa.

**Instrumentado com fone Bluetooth HFP real — itens 8, 9, 10, 21, 22**

O `MockDeviceKit` **não simula áudio** (verificado por `javap`; ver `DECISIONS.md`
2026-08-15). Fone Bluetooth com HFP é a única bancada honesta:

- `SileroNoAparelhoTest` — o `Vad` carrega de asset, aceita quadros de 320
  amostras a 16 kHz e fecha segmento em silêncio real.
- `SupressaoRealTest` — item 22: a janela abre e fecha nos instantes reais de
  reprodução; medido comparando o log da fila com o log do supressor.
- `GatilhoPontaAPontaTest` — frase falada ao fone → `CANAL_ABERTO` → primeiro
  quadro; mede os dois alvos de latência.

**Com os óculos (fica na fila de `docs/VERIFICACOES_COM_HARDWARE.md`)**

- Isolamento do beamforming e comportamento do AGC do uplink.
- Falsos positivos de abertura em turno de 8 h com tráfego de rádio no ambiente.
- Recall do gatilho em rua: 50 pronúncias × 3 pessoas, com vento e trânsito.
- Bateria em modo Ativo, com e sem o gatilho ligado.

**Regra de aceite do bloco:** nenhum destes itens vale como "pronto" enquanto o
caminho não for **alcançável pelo agente** no app entregue. Construir, testar e não
ligar já aconteceu cinco vezes neste projeto.