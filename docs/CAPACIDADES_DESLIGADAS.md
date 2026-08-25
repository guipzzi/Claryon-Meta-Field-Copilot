# Capacidades desligadas — o que existe no código e não roda

**Levantado em 2026-08-25, por varredura do repositório inteiro.**

Este documento existe por causa de uma regra do [`CLAUDE.md`](../CLAUDE.md) §6:

> **Construído significa ter chamador em `src/main` alcançável em runtime.**
> Classe testada sem chamador é *escrita*, não construída.

O projeto já foi mordido por isso **nove vezes**. A última custou caro: as Edge
Functions foram escritas, testadas e nunca chamadas, então `transmissions` nunca
recebia INSERT e o fio do canal era permanentemente vazio em produção — e ninguém
soube por semanas, porque todo teste estava verde.

Um teste verde prova que o caminho feliz **existe**. Não prova que ele é
**alcançável**. Este documento é a lista do que não é.

## Como ler

| Estado | Significado |
|---|---|
| **VIVO** | tem chamador em `src/main` alcançável a partir de um ponto de entrada real |
| **DESLIGADO** | existe em `src/main`, nenhum caminho de runtime chega até lá |
| **BANCADA** | só `test`, `androidTest` ou `src/debug` — e isso é legítimo |

Cuidado com falso negativo de `grep`: injeção por lambda, `by lazy`, objeto
anônimo, referência de função (`::f`) e `viewModel()` do Compose escondem o
chamador. Toda linha abaixo foi conferida até um ponto de entrada real
(`ClaryonApp`, `MainActivity`, `CopilotService`).

---

## 1. Desligado por decisão — e a decisão está registrada

Estes não são descuido. Cada um tem motivo escrito, e ligá-los seria regressão ou
exigiria decisão humana sobre uma regra dura.

### A Etapa B do copiloto de norma

`RedacaoDoCopiloto.redigir` ([`app/.../norma/RedacaoDoCopiloto.kt`](../app/src/main/kotlin/com/claryon/field/norma/RedacaoDoCopiloto.kt))
tem **zero chamadores** em `src/main`. Com ele caem `GuardaDaRedacao`,
`FormulacaoDoPrompt`, `GramaticaDaFonte`, `SelecaoDeTrecho` e `RecorteDaFonte`.

O modelo **carrega** no boot — `PoliticaDeRedacao.decidir` roda, e `preparar()`
mapeia o GGUF quando a decisão é `Redigir`. O que não existe é caminho até
*gerar texto*. A corrente se rompe entre `ClaryonIntentExecutor`, que responde
pela Etapa A, e `redigir()`.

**Por quê:** medimos **1 a 2 respostas utilizáveis em 20**, e a régua de lastro
não detecta inversão de sentido — *"NÃO DEIXOU DE observar as cautelas"* passa
com 0,78 sobre um artigo que pune quem **deixa** de observar. Num produto sem
tela, falar a lei errada com confiança é pior que não falar. Há um teste que
**falha se alguém ligar**. Ver [`DECISIONS.md`](../DECISIONS.md) (22/08) e
[`specs/redacao-por-llm-na-fala.spec.md`](../specs/redacao-por-llm-na-fala.spec.md).

### `EnergyVoiceActivityDetector`

VAD por RMS, substituído pelo **Silero VAD**. Fica porque é o único VAD em Kotlin
puro do projeto e sustenta testes JUnit que rodam sem `.so`. Trocá-lo por Silero
nesses testes exigiria instrumentado.

### `PROMPT_DE_DOMINIO` do whisper

Produção roda **sem** `initial_prompt`. Medido em 17/08: o prior **custa 1,8
ponto de WER** (12,5% sem prompt contra 14,3% com), e não ajudou nem nas frases
cujo vocabulário está dentro dele. A constante fica como braço de bancada.
[`specs/fase-2-gatilho-por-voz.spec.md`](../specs/fase-2-gatilho-por-voz.spec.md)
ainda **proíbe** pôr a palavra de ativação ali — enviesar o decodificador a favor
dela fabrica falso positivo.

### `RotaSustentada.soltarAgora` e `GlassesAudioManagerImpl.liberarTudo`

Soltam a rota SCO sem carência. Foram removidos do encerramento de turno de
propósito: derrubavam a rota por baixo do `AudioRecord` do rádio, que é dono de
processo e não morre com a tela. Ligar de novo é reintroduzir o defeito.

### `GlassesFacade.capturePhoto` e `TraducaoDeFoto`

Caminho de foto única, construído por antecipação. A câmera de produção usa
`withCamera` — stream de 2 frames. É um segundo modo de aquisição e depende de
decisão de produto, não de engenharia. O KDoc de `TraducaoDeFoto` declara a
própria falta de chamador, que é o comportamento certo.

---

## 2. Desligado por descuido — e é aqui que dói

Estes **não** têm decisão por trás. São capacidade que parece existir e não
existe. Estão listados porque esconder seria repetir o defeito de origem.

### O `ack` nunca é chamado, e a confirmação falada não tem lastro

`supabase/functions/` tem duas Edge Functions: `transmit` e `ack`. Só `transmit`
tem chamador Kotlin ([`core-net/.../RegistroDeTransmissao.kt`](../core-net/src/main/kotlin/com/claryon/net/RegistroDeTransmissao.kt)).

**Consequência:** a tabela `deliveries` **nunca recebe INSERT**. Qualquer
confirmação do tipo *"N unidades receberam"* é contagem sobre tabela vazia.

### A emergência não é transmissível pela origem

`PrioridadeTransmissao.P1_EMERGENCIA` existe, o transporte a respeita, o controle
de piso preempta por ela e o receptor toca o earcon certo. Mas **nenhum caminho
em `src/main` transmite com ela**: `RadioViewModel.aoPressionar` cai no default
`P2_APOIO`. Todas as ocorrências de `P1_EMERGENCIA` em produção são definição,
comparação na recepção ou regra de piso — nunca emissão.

**Consequência:** a preempção de emergência está construída e é inalcançável.

### O outbox não drena

`SyncManager.instalar`, `agendarDrenagemTatica` e `agendarDrenagemPesada` têm zero
chamadores em `src/main`, e `SupabaseSyncGateway` nunca é instanciado. O gateway
que de fato é injetado é `SemTransporteGateway`, que **nunca entrega**.

**Consequência:** `Intent.PedirApoio`, `Intent.Emergencia` e
`Intent.AlertarOcorrencia` sempre viram `Despacho.Enfileirada`. Ficam em disco e
não saem do aparelho.

### O cofre não guarda o rádio

Só `Intent.IniciarGravacao` alimenta o `EncryptedEvidenceVault`. Nenhum caminho
do PTT escreve nele. Conferir é caminho de produto (Perfil → Periciar);
**exportar não** — tirar segmento e manifesto ainda exige `adb`.

### `ensureRegistered` e a `DiagnosticsScreen`

`ensureRegistered` produz a mensagem *"Não registrado. Dispare o registro pelo app
Meta AI"* e tem zero chamadores: a mensagem nunca é gerada em produção. A
`DiagnosticsScreen` nunca é composta, então todo o painel de debug — registro,
`startCameraStream`, `MockDeviceController` — é inalcançável **mesmo no APK de
debug**.

### `AudioCaptureException.codigo` não é lido

O KDoc promete "mapeamento erro → earcon". Não existe: ninguém faz `catch`, e a
distinção entre `ERROR_DEAD_OBJECT` (o HFP caiu) e `ERROR_INVALID_OPERATION`
nunca chega ao ouvido do agente. As duas soam igual — ou seja, não soam.

---

## 3. Morto de verdade — remoção segura

Zero referências em todo o repositório, incluindo testes:

| Símbolo | Onde |
|---|---|
| `SttSelector` · `firstAvailable()` | `core-voice/.../SttEngines.kt` |
| `SEM_PROMPT` | `core-voice/.../LibWhisper.kt` |
| `EscutaDeAtivacao.pararEEsperar()` | o KDoc afirma um teste que não existe |
| `DatGlassesFacade.startUnregistration()` | — |
| `MockDeviceController.definirFeedDeVideo()` | — |
| `CanalDePosicoes` · `TransmissaoDiferida` | `core-net/` |

---

## 4. Dívida de empacotamento

**`GlassesAudioRoute.paraTesteSomente` é pública, está em `src/main` e vai no AAR
de release.** Isso contradiz uma decisão já tomada e verificada por `javap`, que
mandou a rota falsa para `src/debug`. O KDoc afirma que "gravar pelo microfone do
celular deixa de compilar" — hoje essa garantia depende de **convenção de nome**,
não do compilador.

`MockDeviceController` está em `src/main` de `core-glasses`, protegido por
empacotamento (`compileOnly` + `debugImplementation` no `app`), mas o TODO de
movê-lo para `src/debug` segue aberto. O precedente existe e está pronto para
copiar.

---

## Por que este documento é público

Porque a alternativa é pior. Um repositório entregue a banca avaliadora é lido por
quem sabe procurar chamador, e descobrir sozinho que uma capacidade anunciada não
roda vale menos que encontrar a lista escrita pelos próprios autores. A régua que
produziu esta lista é a mesma que sustenta os números do
[`ESTADO.md`](../ESTADO.md): se ela não fosse aplicada aqui, não haveria motivo
para acreditar nela lá.
