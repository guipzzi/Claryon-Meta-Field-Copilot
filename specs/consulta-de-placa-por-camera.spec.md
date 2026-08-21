---
feature: consulta-de-placa-por-camera
capacidade: C2 (consulta) + P3 (copiloto local) — atravessa Fase 4 e Fase 5
estado: proposta
autor: revisão humana pendente
criada: 2026-08-21
sobrepoe:
  - "core-agent/src/main/kotlin/com/claryon/agent/Utterance.kt — `PlacaConsultada` sai como earcon, NUNCA falado: 'o alto-falante open-ear vaza para o abordado'"
  - "app/src/main/kotlin/com/claryon/field/agent/ClaryonIntentExecutor.kt — 'Consulta a bases oficiais (Detran/Sinesp) está fora do escopo por dependência externa inviável no prazo'"
depende_de:
  - dono-de-processo-para-a-facade-do-dat
  - base-de-consulta-veicular
---

# Consulta de placa pela câmera dos óculos

## O fluxo pedido, e o que dele já existe

O pedido, em onze passos: gatilho → earcon → pergunta falada → earcon de captura →
interpretação → identifica consulta de placa → fala "ligando câmera" → captura
frames → OCR local → consulta base → **fala o resultado**.

Sete dos onze **já estão construídos e medidos**. Quatro colidem com decisão tomada
ou com capacidade inexistente, e é sobre esses quatro que este documento existe.

| passo | estado hoje |
|---|---|
| gatilho por voz | ✅ "Claryon", detector on-device, earcon em **305 ms** |
| ciclo abre e fecha por silêncio | ✅ VAD Silero, `CerebroDoCopiloto`, dono de processo |
| transcrição local | ✅ whisper.cpp, **WER 3,4%** |
| roteamento determinístico | ✅ `Intent.ConsultarPlaca(placa: String?)` **já existe** |
| earcon de recebido | ✅ fila de saída com prioridade |
| OCR no aparelho | ✅ **ligado em 21/08** — `CapturaDePlaca` → `OcrDeFrame` → `PlacaOcr`. Antes: existia, ML Kit no APK, **zero chamadores** |
| resultado como earcon | ✅ três earcons distintos por tipo de restrição |
| **"Hey Meta" como gatilho** | ❌ **não é nosso** |
| **IA interpretando o pedido** | ❌ colide com regra dura, e foi **medido** |
| **base de placas** | ✅ **existe desde 21/08** — `core-net/BaseVeicular.kt` + migração `0023`, com procedência em toda resposta. O que não existe é **convênio** com Detran/Sinesp |
| **resultado falado** | ⚠️ **a decisão mudou em 21/08**: earcon **e** fala curta (`SinalizarEFalar`), por decisão humana. Ver `Utterance.kt`; o vazamento do *open-ear* virou item medível da Fase 5 |

---

## Colisão 1 — "Hey Meta" não pode ser o nosso gatilho

"Hey Meta" é a palavra de ativação do **assistente da Meta**, não do nosso app.
Interceptá-la exigiria receber o áudio do microfone dos óculos antes dela, e o
`CLAUDE.md` §2 registra o resultado do `javap`: **não existe `session.audioStream`**
no DAT. O microfone e o alto-falante dos óculos chegam por **HFP/SCO**, como fone
Bluetooth comum — e enquanto o assistente da Meta estiver escutando o próprio
gatilho, ele é dono daquele canal.

Além do impedimento técnico, há o de produto: usar o gatilho da Meta faria o agente
não saber com quem está falando. Duas assistentes no mesmo par de óculos,
respondendo a chamadas parecidas, é ambiguidade em ocorrência.

**Proposta:** o gatilho continua **"Claryon"**, que já está medido (falso positivo
0,99/h, recall 3/4 locutores) e é o que a Fase 2 inteira sustenta. O fluxo abaixo é
idêntico ao pedido, trocando só a palavra.

---

## Colisão 2 — a IA não pode interpretar o pedido, e isso foi medido hoje

O passo *"processa a mensagem via IA para interpretar o pedido"* colide com a regra
dura do `CLAUDE.md` §2: **"LLM escolhendo ação"** é proibido, ele só preenche campos
de intenção já definida.

Em 21/08 a regra deixou de ser princípio e virou número, por dois caminhos
independentes:

- **252 dos 1817 artigos** de lei, entregues ao `DeterministicIntentRouter`, viram
  ação (`FronteiraDoConhecimentoEmAppTest`).
- **2 de 3 respostas geradas** pelo LLM, entregues ao mesmo roteador, viram ação:
  *"preciso chamar apoio para essa ocorrência de trânsito"* → `PedirApoio`;
  *"devo gravar a abordagem do condutor embriagado"* → `IniciarGravacao`
  (`RedatorNoAparelhoTest`).

Modelo pequeno repete a pergunta na resposta, e o roteador casa palavra-chave em
português. Não tem conserto por prompt.

**Proposta:** quem decide a intenção continua sendo o roteador determinístico —
*"consultar placa"*, *"verificar placa"*, *"checar placa"*, *"rodar placa"* já casam
hoje. O LLM ganha **um papel legítimo e estreito**, que é preencher campo de
intenção já decidida:

> o agente dita a placa em alfabeto fonético — *"tango bravo unido três delta sete
> zero"* — e o modelo normaliza para `TBU3D70`.

Isso é preenchimento de campo, não escolha de ação, e é exatamente o que a regra
permite. E tem contra-teste natural: **placa tem formato**. Saída que não casar com
Mercosul (`LLLNLNN`) ou com o padrão antigo (`LLLNNNN`) é erro de leitura, não
consulta — e vira recusa, nunca uma consulta com dado inventado.

---

## Colisão 3 — o resultado NÃO pode ser falado

Este é o ponto em que o pedido e o produto discordam de verdade, e a razão está
escrita no código:

```kotlin
is ActionOutcome.PlacaConsultada ->
    // NUNCA falado: o alto-falante open-ear vaza para o abordado.
    Utterance.Sinalizar(...)
```

O alto-falante dos Ray-Ban é **open-ear**: quem está a um metro ouve. Falar *"a
placa TBU3D70 possui busca e apreensão registrada"* durante uma abordagem **informa
ao abordado exatamente o que o agente acabou de descobrir** — no instante em que
essa informação muda o cálculo de quem pode estar armado. Não é preferência de
interface; é segurança do agente.

Por isso existem três earcons distintos hoje: `CONSULTA_SEM_RESTRICAO`,
`CONSULTA_RESTRICAO_ADMIN` e `CONSULTA_FURTO_ROUBO`. O agente aprende três sons; o
abordado não aprende nada.

**Proposta — manter o earcon como resposta primária, e resolver o resto pela tela:**

- **earcon imediato**, como hoje, que é o que serve em pé na abordagem;
- **detalhe na tela do celular**, que já é o lugar de informação sensível e não vaza;
- **`Intent.Detalhar` fala o detalhe** apenas quando o agente pedir — porque aí a
  decisão de expor a informação em voz é dele, na situação, e não do produto.

Se a leitura falada tiver de existir por exigência de demonstração, ela entra
**atrás de flag desligada por padrão**, e o documento diz por que ela é insegura em
campo. Fingir que não há custo seria pior.

---

## Colisão 4 — a base de consulta não existe

O executor hoje é honesto sobre isso:

> *"Consulta a bases oficiais (Detran/Sinesp) está fora do escopo por dependência
> externa inviável no prazo. Sem base, a resposta honesta é dizer que não dá — jamais
> um 'sem restrição' inventado."*

E nenhuma migração do servidor menciona placa. **Não há tabela, não há RPC, não há
convênio.** Um *"sem restrição"* inventado é o pior resultado possível deste fluxo:
o agente libera um veículo roubado porque o aparelho disse que estava limpo.

**Proposta, em duas camadas honestas:**

1. **Base de demonstração no servidor** — tabela `veiculos` com placas semeadas,
   RLS igual à das outras, e o cliente **exibindo que é base de demonstração** na
   tela. Serve para o fluxo existir ponta a ponta sem mentir sobre a origem.
2. **A base real é dado da corporação**, como o gazetteer. O desenho deixa a fonte
   trocável por uma interface, e o documento diz que a integração com Detran/Sinesp
   é do contratante — não do produto.

---

## O fluxo proposto, passo a passo

```
1  "Claryon, verifica a placa desse carro"
2  → earcon de escuta (305 ms medido)              [existe]
3  → VAD fecha por silêncio                        [existe]
4  → whisper transcreve no aparelho                [existe]
5  → DeterministicIntentRouter                     [existe]
      → Intent.ConsultarPlaca(placa = null)
6  → placa nula ⇒ subfluxo de captura              [FEITO 21/08 — CapturaDePlaca]
7  → fala "Aponte para a placa." (4 palavras)      [FEITO — teste de laconicidade]
8  → GlassesFacade.withCamera                      [LIGADO 21/08 — tinha 0 chamadores]
9  → N frames em ≤5 s, descartados após o OCR      [FEITO — FramesEfemerosTest]
10 → PlacaOcr (ML Kit, já no APK)                  [LIGADO 21/08 — tinha 0 chamadores]
11 → valida formato Mercosul/antigo                [FEITO — no executor, portão único]
12 → consulta a base                               [LIGADO — ConsultaNaBaseVeicular]
13 → ActionOutcome.PlacaConsultada(placa, restricao)
14 → earcon codificado + fala curta                [existe; a decisão mudou em 21/08]
15 → detalhe na tela                               [ABERTO — só `Detalhar` por voz]
```

### O que 21/08 mediu, e que este documento não previa

- **O plano Y basta, e a croma não importa.** `VideoFrame` não diz qual layout o
  `MediaCodec` devolveu, e adivinhar seria errar em silêncio. A conversão lê **só o
  plano Y** — idêntico em I420, YV12, NV12 e NV21 —, o que torna o passo 10
  independente de uma informação que o SDK não expõe.
- **O OCR não é o gargalo — a taxa da câmera é.** Medido em quatro processos
  separados (504×896, emulador arm64, conteúdo distinto a cada repetição): frame sem
  placa ~9 ms, frame com placa 31–83 ms de mediana, pior caso **113 ms**. O perfil de
  OCR pede 7 fps, ou seja um frame a cada ~143 ms: a inferência acompanha
  praticamente tudo o que a câmera entrega. Leitura completa de uma placa nítida:
  **2 frames, 67–180 ms**.
- **O passo 9 não cabia no teto do ciclo de voz.** `CopilotoDoAgente` envolvia
  `runOnce()` em 8 s, e esse prazo cobre a fala + o whisper + **a ação**. Uma frase
  de 2,5 s mais 5 s de captura estoura, e o agente ouviria "Não entendi, repita."
  depois de apontar corretamente para a placa. O teto subiu para 14 s.
- **Aceite ⇒ aceite: o caminho de falha da captura não cabe nos 4 s da Fase 4.**
  Quando a placa **não** é encontrada, gasta-se a janela inteira: 945 ms de ciclo +
  5 000 ms de captura ≈ 5,9 s. Os dois critérios são inconsistentes por construção,
  e é decisão humana qual deles cede.

### O que precisa ser construído antes do passo 8

~~`DatGlassesFacade` é construído com `viewModelScope` em `OculosViewModel`: **a
facade morre com a Activity**~~ — **feito em 21/08**, e a spec própria existe:
[`dono-de-processo-para-a-facade-do-dat`](dono-de-processo-para-a-facade-do-dat.spec.md).
A fachada é uma por processo (`SessaoDosOculos`), o `stopSession()` mudou do
`onCleared()` para o fim de turno, e o `SessaoSemTelaTest` roda sem construir
ViewModel nenhum. **Achado no caminho:** `startSession()` não tinha um único chamador
em `src/main` — a sessão do DAT nunca era aberta em produção —, então o bloco
precisou trazer o abridor junto, ou trocaria uma capacidade morta por outra.

Fica aberto do lado da câmera: o custo em bateria de uma sessão ociosa por turno
não é mensurável sem óculos, e está em `docs/VERIFICACOES_COM_HARDWARE.md`.

Do lado do DAT o caminho está pronto desde 21/08: `PermissaoDaCameraDoDat` pede a
permissão pelo contrato oficial, `errorStream` é coletado antes do `start()`
(obrigatório: `replay=0`, erro anterior à assinatura **some**), e `STOPPED` só é
terminal depois de `STARTED`/`STREAMING` — porque o SDK **retenta** e parar cedo
aborta a retentativa.

---

## Privacidade — o que este fluxo NÃO pode fazer

O `CLAUDE.md` §2 proíbe **reconhecimento facial ou base biométrica**, sem versão e
sem flag. Uma abordagem tem gente no enquadramento, e a câmera aponta para a altura
da placa.

- Os frames vivem **em RAM** e são descartados assim que o OCR devolve, do mesmo
  jeito que o pré-roll do PTT.
- Nenhum frame é persistido, indexado ou enviado — nem para o servidor do projeto.
- O OCR roda **no aparelho** (ML Kit já está no APK, `libmlkit_google_ocr_pipeline.so`).
- O que sai do fluxo é **uma string de sete caracteres**, nunca imagem.
- Se algum frame precisar virar evidência, isso é outro caminho: `EncryptedFile` +
  Keystore, com manifesto de custódia — nunca subproduto silencioso de uma consulta.

---

## Aceite (EARS)

- **WHEN** o agente pede consulta de placa **AND** não dita a placa, **THE SYSTEM
  SHALL** falar a instrução de apontar em ≤ 7 palavras **AND** abrir a câmera dos
  óculos.
- **WHILE** a câmera está aberta para consulta, **THE SYSTEM SHALL** descartar todo
  frame em ≤ 1 s após o OCR **AND SHALL NOT** persistir nenhum.
- **IF** o OCR não produzir string que case com Mercosul (`LLLNLNN`) ou com o padrão
  antigo (`LLLNNNN`) em ≤ 5 s, **THEN THE SYSTEM SHALL** sinalizar falha de leitura
  **AND SHALL NOT** consultar a base.
- **WHEN** a consulta retorna, **THE SYSTEM SHALL** responder por earcon codificado
  **AND SHALL NOT** falar a restrição.
- **IF** a base estiver indisponível, **THEN THE SYSTEM SHALL** dizer que não
  conseguiu consultar **AND SHALL NOT** responder "sem restrição".
- **THE SYSTEM SHALL NOT** produzir `Intent` a partir de saída de modelo de
  linguagem — o LLM só normaliza a placa ditada, e o formato é conferido depois.

## Esforço estimado, e o que ele depende

| bloco | esforço | depende |
|---|---|---|
| dono de processo para a facade | 1 sessão | spec própria |
| subfluxo de captura + descarte de frames | 1 sessão | acima |
| ligar `PlacaOcr` com validação de formato | 0,5 sessão | acima |
| base de demonstração no servidor + RLS | 0,5 sessão | nada |
| normalização de placa ditada pelo LLM | 0,5 sessão | Etapa B |
| detalhe na tela + `Detalhar` falando | 0,5 sessão | nada |

**Nada disto cabe antes de 18/09 junto com o que já está aberto.** O bloco que
entrega mais com menos é a **base de demonstração + `Detalhar`**: faz a consulta
existir ponta a ponta com placa **ditada**, sem câmera, e o caminho da câmera entra
depois. É o mesmo padrão da Etapa A antes da Etapa B — o produto nunca fica
dependendo da parte mais vistosa funcionar.

## O que decidir

1. **Gatilho:** confirma "Claryon" no lugar de "Hey Meta"?
2. **Resultado falado:** earcon como hoje (recomendado) — ou fala atrás de flag, com
   o custo de segurança declarado?
3. **Base:** demonstração semeada no servidor, ou esperar dado da corporação?
4. **Escopo até 18/09:** placa ditada primeiro (recomendado), ou câmera junto?
