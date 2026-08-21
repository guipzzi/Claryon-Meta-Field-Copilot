---
feature: dono-de-processo-para-a-facade-do-dat
capacidade: pré-requisito de P3 (câmera dos óculos) — bloco 1 da Fase 6
estado: proposta
autor: revisão humana pendente
criada: 2026-08-21
sobrepoe:
  - "app/src/main/kotlin/com/claryon/field/ui/OculosViewModel.kt:81 — `onCleared()` chama `facade.stopCameraStream()` + `facade.stopSession()`: 'Sem parar a sessão, o stream dos óculos segue transmitindo por Bluetooth depois que a tela morre'. Esta spec MOVE esse encerramento para o fim de turno e explica por que o motivo original deixa de valer."
  - "app/src/debug/kotlin/com/claryon/field/ui/DiagnosticoViewModel.kt:39 e :140 — segunda construção de `DatGlassesFacade`, com o mesmo `onCleared()`."
depende_de: []
destrava:
  - consulta-de-placa-por-camera
---

# Dono de processo para a fachada do DAT

## O defeito, em uma linha

```kotlin
class OculosViewModel(app: Application) : AndroidViewModel(app) {
    private val facade = DatGlassesFacade(viewModelScope)
```

`DatGlassesFacade` recebe o escopo **no construtor** e roda **tudo** dentro dele: o
`stateIn(scope, Eagerly)` do registro e da contagem de dispositivos, o coletor de
`session.state`, o coletor de `session.errors`, o coletor de `stream.state`, o
coletor de `stream.errorStream` e o vigia de primeiro frame de `withCamera`.

`viewModelScope` é cancelado no `onCleared()`. Então, hoje, **a fachada inteira
morre com a Activity** — e leva junto exatamente as coisas que a Fase 6 precisa que
sobrevivam: a sessão, o stream e a causa tipada do erro.

Um fluxo de câmera que dura 5 s e precisa acontecer com o celular no bolso não
existe. É o mesmo defeito de classe que o ciclo de voz tinha antes de virar
`CerebroDoCopiloto`, e a correção é a mesma forma.

## O que a medição acrescentou ao enunciado do problema

`grep -rn "startSession()" app/src/main` devolve **zero**.

A sessão do DAT **nunca é aberta em produção**. Ela é aberta pelo painel de
diagnóstico (`app/src/debug`) e por nove testes instrumentados, e por mais nada. A
consequência direta é que o `stopSession()` do `onCleared()` que esta spec propõe
mover é, hoje, **uma chamada sobre `activeSession == null`** — um encerramento que
nunca encerrou nada.

Isso muda o desenho: não basta mudar o dono. **Sem um abridor em `src/main`, o dono
novo nasce tão morto quanto o antigo** — o §6 do `CLAUDE.md` conta seis vezes em que
isso já aconteceu neste projeto. Por isso esta spec propõe as duas metades juntas:
quem é dono, e quem abre.

## O que se propõe

### 1. `SessaoDosOculos`, dono de processo

Um `object` em `app/src/main/kotlin/com/claryon/field/oculos/`, na mesma forma de
`AudioDoAgente`, `SaidaUnica`, `EscutaDoAgente` e `CopilotoDoAgente`:

- um `CoroutineScope(SupervisorJob() + Dispatchers.Default)` de processo;
- **uma** `DatGlassesFacade`, construída com esse escopo, criada preguiçosamente na
  primeira chamada;
- os coletores de `sessionErrors` e `streamErrors` rodando **nesse** escopo, com a
  última causa publicada em `motivo: StateFlow<String?>`;
- `instalar(substituta)` como costura de teste, igual às demais.

**Por que objeto de processo e não injeção**, com o mesmo argumento que
`AudioDoAgente` registra: o recurso disputado é global do aparelho. Existe **uma**
conexão DAT por processo, e o SDK recusa `createSession` com outra ativa. Dois
donos é o defeito que `AudioDoAgente` já teve com o `AudioManager` — e ele existe
hoje, de fato: `OculosViewModel` e `DiagnosticoViewModel` constroem **uma fachada
cada**.

### 2. O abridor: um vigia de registro, não uma chamada solta

`startSession()` tem teto de 12 s (`PRAZO_DE_SESSAO_MS`). Uma consulta de placa que
espere a sessão subir já perdeu o momento — o aceite de `consulta-de-placa-por-camera`
dá **5 s** para o OCR inteiro. Logo, a sessão precisa estar **de pé antes do
pedido**, e abrir sob demanda não serve.

O vigia roda no escopo do processo enquanto o turno estiver aberto:

```
enquanto o turno estiver aberto:
    espere: registro == REGISTERED  E  sessão ∉ {STARTING, STARTED}
    tente subir a sessão uma vez, publicando o motivo em caso de falha
    espere o INTERVALO mínimo entre tentativas
```

Três propriedades que o desenho compra, e cada uma tem um modo de falha atrás:

- **Não tenta sem óculos.** Sem registro, o `first { }` suspende e nada toca o SDK:
  emulador e aparelho sem óculos não pagam nada.
- **Não martela.** Sem o intervalo mínimo, uma sessão que recusa subir viraria
  tentativa em laço sobre o Bluetooth do agente, o turno inteiro.
- **Não é sondagem.** Com a sessão de pé, o `first { }` fica suspenso; ele só acorda
  quando o `StateFlow` muda. Zero trabalho no caso normal.

### 3. `stopSession()` sai do `onCleared()` e vai para o fim de turno

**É esta a mudança de comportamento**, e o §7 do `CLAUDE.md` é a razão de este
documento existir antes do diff.

O KDoc que se sobrepõe diz:

> *"Sem parar a sessão, o stream dos óculos segue transmitindo por Bluetooth depois
> que a tela morre, sem nenhum indicador — e um novo `DatGlassesFacade` tentaria
> `createSession` com a anterior ainda viva."*

As duas metades **deixam de valer por motivos diferentes**:

- *"um novo `DatGlassesFacade`"* — não há mais "um novo". Existe uma por processo, e
  é justamente o que esta spec fixa. A frase descrevia o defeito, não o contrato.
- *"segue transmitindo sem nenhum indicador"* — é o custo real, e continua real.
  Trocado por: (i) a sessão só existe entre `abrir()` e `encerrar()`, que é o
  **turno declarado pelo agente**, o mesmo recorte que já autoriza saber onde ele
  esteve (`0019`); (ii) o *stream* de câmera continua escopado por `withCamera`, que
  o desliga no `finally`, então "transmitindo" no sentido de vídeo permanece sendo
  medido em segundos, não em horas; (iii) o indicador existe: é a notificação do
  `CopilotService`, que vive exatamente pelo mesmo intervalo.

**O que se perde, escrito por inteiro:** o agente que abre o app, conecta os óculos
e nunca toca em "Encerrar turno" fica com uma `DeviceSession` aberta enquanto o
processo viver. Antes, girar a tela ou sair do app fechava. **Isso é intencional** —
é a capacidade sendo pedida —, mas o custo em bateria de uma sessão ociosa **não foi
medido e não é mensurável sem óculos reais**. Entra em
`docs/VERIFICACOES_COM_HARDWARE.md` como item, não como suposição.

### 4. Onde o turno abre e fecha

| momento | quem chama | por quê ali |
|---|---|---|
| abre | `MainActivity` → `Operacao`, no mesmo `DisposableEffect` que sobe o `CopilotService` | é o instante em que o agente entra em operação, e é **tela visível** — a mesma pré-condição que o FGS exige |
| **não** fecha | `onDispose` da mesma composição | fechar aqui **é o defeito**: é o `onCleared()` com outro nome |
| fecha | `MainActivity.aoEncerrarTurno` | é a única ação em que o agente declara que parou de trabalhar — onde `CopilotService.parar` já está |

**Alternativa descartada:** pendurar o fechamento em `CopilotService.onDestroy`.
Cobriria mais caminhos (o serviço morre por `parar()` **e** por decisão do sistema),
mas o segundo caso é quase sempre morte de processo, que leva a sessão junto de
qualquer forma — e o `CopilotService` é território de outro trabalho em curso.
Fica registrado como o próximo lugar a ligar, não como omissão.

## Aceite (EARS)

- **WHEN** a operação abre **AND** os óculos estão registrados, **THE SYSTEM SHALL**
  abrir a sessão do DAT **AND SHALL** publicar a causa tipada se ela não subir.
- **WHILE** o turno estiver aberto, **THE SYSTEM SHALL** manter a sessão, o stream e
  a coleta de `errorStream` vivos **independentemente de existir Activity ou
  ViewModel**.
- **WHEN** a Activity é destruída sem que o turno seja encerrado, **THE SYSTEM SHALL
  NOT** parar a sessão do DAT.
- **WHEN** o agente encerra o turno, **THE SYSTEM SHALL** parar a câmera **AND** a
  sessão, nessa ordem.
- **THE SYSTEM SHALL** ter **no máximo uma** `DatGlassesFacade` por processo —
  verificável por varredura de fonte, não por disciplina.
- **IF** os óculos não estiverem registrados, **THEN THE SYSTEM SHALL NOT** chamar
  `createSession` — nem uma vez.
- **WHILE** a sessão estiver de pé, **THE SYSTEM SHALL NOT** tentar subir outra.

## Como se prova

| afirmação | instrumento |
|---|---|
| a sessão vive sem ViewModel nenhum construído | `SessaoSemTelaTest` (instrumentado): fala direto com o dono de processo, sem Activity e sem ViewModel, e exige que `abrir()` **relate** — sessão que roda até o fim e diz o motivo é sessão viva |
| a fachada é uma por processo | mesma classe: identidade entre chamadas |
| **nenhuma tela pode ser dona** | `FachadaDoDatTemDonoUnicoTest` (JVM): varre `app/src/main`, `app/src/debug` e `core-glasses/src/main` e reprova qualquer `DatGlassesFacade(` fora do dono. **Com o defeito de volta, ele falha** — que é o critério do §6, pergunta 3 |
| a varredura pega violador de verdade | contra-teste com violador sintético no mesmo arquivo |
| a sessão de pé em hardware | só com óculos reais — ver `docs/VERIFICACOES_COM_HARDWARE.md` |

## O que decidir

1. **Fim de turno é só o botão "Encerrar turno"?** Ou o fechamento também entra em
   `CopilotService.onDestroy`, cobrindo o `parar()` vindo de qualquer origem?
2. **A sessão sobe com a operação, ou só quando a câmera for pedida?** A proposta é
   "com a operação", pelo custo de 12 s. O contra-argumento é bateria, e ele só se
   resolve com número de hardware.
3. **Standby deve derrubar a sessão?** `PowerPolicy.perfil(STANDBY).hfpAberto` é
   `false` e o `CopilotService` não pede `MICROPHONE` nesse modo. A simetria sugere
   que sim; nenhum número sustenta ainda.
