---
feature: energia-por-modo
capacidade: transversal (P1 rádio · P2 posição · P3 copiloto) — checkpoint obrigatório "Eficiência de bateria" do edital §8.1
estado: proposta
autor: revisão humana pendente
criada: 2026-08-21
sobrepoe:
  - "docs/COMPLIANCE.md:61 — 'Modos Standby/Ativo/Ocorrência como política pura e testada […] ✅ montado — verificado em aparelho'. A auditoria de 21/08 mostra que a política nunca é exercitada em runtime: `MainActivity.kt:197` fixa `ATIVO` e nada mais escreve `_modo`"
  - "ESTADO.md — 'PTT: toque→1º quadro 31–48 ms (120)'. Esse número é medido COM o SCO já aberto. Qualquer modo que feche o SCO coloca esse aceite em risco, e a decisão é humana"
  - "docs/PADROES_DE_ENGENHARIA.md:253 — 'Modos: Standby (HFP fechado) | Ativo | Ocorrência'. O texto descreve um comportamento que não existe"
depende_de:
  - "P2 de docs/ENERGIA.md — tipar `DeviceSessionError` e medir o custo do SCO nos óculos. Sem esse número, metade das cláusulas abaixo é palpite"
  - "docs/VERIFICACOES_COM_HARDWARE.md:96 — tempo de estabelecimento do SCO, hoje 'esperado' e nunca medido"
---

# O modo tem de mudar

## O defeito, em uma linha

`ModoOperacao` tem três valores, uma política de energia completa pendurada neles e
teste de unidade verde — e em produção **é a constante `ATIVO`, do login ao fim do
turno**.

## A prova, não a lembrança

- `app/src/main/kotlin/com/claryon/field/MainActivity.kt:197` é o **único** ponto de
  `src/main` que sobe o serviço com um modo, e o argumento é o literal
  `ModoOperacao.ATIVO`.
- `MainActivity.kt:109` manda `STANDBY` + `ACAO_PARAR`, e
  `app/src/main/kotlin/com/claryon/field/service/CopilotService.kt:243-248` trata isso
  como **encerrar o serviço**. Não existe caminho para "rodando em Standby".
- O comando de voz *"modo ocorrência"* chega em `Intent.TrocarModo(OCORRENCIA)`
  (`core-agent/.../DeterministicIntentRouter.kt:57`) e termina em
  `app/src/main/kotlin/com/claryon/field/voice/CopilotoDoAgente.kt:188`:
  ```kotlin
  aoTrocarModo = { modo -> saida.modoTatico(modo == ModoOperacao.OCORRENCIA) },
  ```
  Só liga a supressão de informativos na fila de som. **Não chama
  `CopilotService.iniciar`.** Não muda `foregroundServiceType`, não muda a cadência de
  GPS, não liga câmera, não muda a escuta.
- Os três botões de modo vivem em `app/src/debug/.../DiagnosticsScreen.kt:191-193`.
- Consequência: `wakeWordAtiva`, `cameraPorPadrao` e `suprimeInformativos` de
  `PerfilDeEnergia` **não têm um único leitor** em `src/main` — quem decide a escuta é
  `hfpAberto` (`EscutaDeAtivacao.kt:134`).

Pela régua do `CLAUDE.md` §6, isso é **escrito, não construído**: sétima ocorrência do
padrão que o projeto nomeou. E é a capacidade que `docs/COMPLIANCE.md:61` apresenta ao
avaliador como o checkpoint de bateria.

## O que falta não é política. É sinal.

A política está pronta, é pura e tem teste (`core-agent/.../PowerPolicy.kt`,
`PoliticaDePosicao.kt`, `PowerPolicyTest.kt`). O que nunca existiu é **alguém que
mude o modo**. Botão de tela não serve: o celular está no bolso.

E o agente já emite sinais físicos, de graça, várias vezes por turno:

| Sinal | API | O que significa | Custo |
|---|---|---|---|
| Carregador conectado/desconectado | `ACTION_POWER_CONNECTED` / `_DISCONNECTED` | dentro da viatura × a pé | um `BroadcastReceiver` |
| Hastes dobradas | já observado: `session.state` → `STOPPED` (medido no MDK 0.9.0, `VERIFICACOES:150-152`) | óculos guardados | zero — já é coletado |
| Fora do rosto (*doff*) | reação da sessão, **exige "detecção de uso" ligada no app Meta AI** (Un13 §13.4.3.7; não há API para habilitar) | óculos na testa ou na gola | item de onboarding |
| Nível de bateria do celular | `BatteryManager.BATTERY_PROPERTY_CAPACITY` | quanto resta | hoje **zero** telemetria de bateria no app |
| Saúde térmica dos óculos | `Wearables.getDeviceState(id): StateFlow<DeviceState>` — "Always-on, no session required" (confirmado por `javap` em `mwdat-core-0.9.0`) | óculos esquentando | um coletor; hoje chamado **zero vezes** |

### A assimetria que inverte a intuição

O celular tem nível legível, é recarregável na viatura e existe outro na guarnição. Os
óculos **não têm API de nível** (`DeviceState` tem um campo só, e é térmico —
`javap`), não são recarregáveis no rosto, e são o único sensor. Toda decisão deve
empurrar custo para o celular.

Daí a cláusula contra-intuitiva desta spec: **dentro da viatura o celular está
carregando, então a energia dele é de graça — e é a dos óculos que precisa descansar.**
A política de dentro da viatura é o inverso do que a intuição escreve.

## O que se propõe

**Um dono de modo, três fontes de sinal, e nenhuma decisão silenciosa.**

1. **`GovernadorDeModo`** — dono de processo, ao lado de `SessaoDosOculos` e
   `CerebroDoCopiloto`, **não** um ViewModel. É ele que escreve `CopilotService._modo`,
   e passa a ser o único que escreve.
2. **Entradas:** carregador, estado da sessão dos óculos (dobra/rosto), nível de
   bateria do celular, e a intenção falada do agente.
3. **A intenção falada vence tudo.** *"Modo ocorrência"* passa a **de fato** trocar o
   modo do pipeline, não só suprimir informativo — que é o que o comando sempre
   prometeu.
4. **Toda transição é audível, uma vez, em ≤ 7 palavras.** Um copiloto que
   emburrece em silêncio é pior que um copiloto desligado: o agente descobre
   perguntando algo e não recebendo resposta.
5. **Nunca degradar no meio de uma ação.** Com PTT no ar ou ciclo de voz aberto, a
   transição é adiada — o padrão que `EscutaDeAtivacao.silenciarPor` já usa.

### O que ainda NÃO se propõe, e por quê

**Fechar o SCO dentro da viatura fica fora desta spec até existir número.** Hoje
toque→1º quadro é **31–48 ms** com o SCO já aberto (meta 120); estabelecer SCO é
"500–1500 ms **esperados**" (`VERIFICACOES:96`) e nunca foi medido. Escrever uma regra
que feche o SCO sem esse número é trocar um aceite medido por uma esperança.

Então a spec entra em duas camadas, e a segunda espera medição:

- **Camada 1 (sem risco para P1):** o modo muda; mudam cadência e provedor de GPS,
  tipos do FGS, câmera e supressão de informativo. **O SCO fica aberto em todas as
  variantes.**
- **Camada 2 (depende de medir o SCO):** o SCO fecha quando os óculos estão dobrados
  ou fora do rosto, e possivelmente na viatura. **Só entra se o tempo de
  estabelecimento medido couber no orçamento de P1, ou se o produto aceitar,
  explicitamente e por decisão humana, um PTT mais lento em troca de autonomia.**

A Camada 2 tem um argumento que não é de energia e vale registrar aqui:
`VERIFICACOES:167-170` já anota que, **fora do rosto, o beamforming que isola quem
veste deixa de valer, e um PTT apertado difunde a conversa ao redor** — hoje sem
mitigação. "Sem rosto, sem SCO" fecha um vazamento de energia e um de privacidade com
o mesmo diff, e o edital valida os dois **no mesmo checkpoint, às 16h00 de 18/09**.

## Aceite (EARS)

### Camada 1

- **WHILE** o turno estiver aberto, **THE SYSTEM SHALL** ter exatamente **um**
  escritor de `CopilotService._modo` — verificável por varredura de fonte, não por
  disciplina.
- **WHEN** o carregador é desconectado **AND** o modo corrente é o de viatura,
  **THE SYSTEM SHALL** transicionar para `ATIVO` **AND SHALL** anunciar a transição
  uma vez, em ≤ 7 palavras.
- **WHEN** o carregador é conectado, **THE SYSTEM SHALL** transicionar para o modo de
  viatura **AND SHALL NOT** reduzir a cadência de posição abaixo do plano de `ATIVO`
  — na viatura a energia do celular está sendo reposta.
- **WHEN** o agente diz *"modo ocorrência"*, **THE SYSTEM SHALL** aplicar
  `PowerPolicy.perfil(OCORRENCIA)` ao pipeline — FGS, cadência de posição e supressão
  de informativo — **AND NOT** apenas `saida.modoTatico(true)`.
- **WHILE** houver PTT no ar **OR** ciclo de voz aberto, **THE SYSTEM SHALL NOT**
  aplicar transição de modo; **THE SYSTEM SHALL** aplicá-la ao término.
- **WHEN** qualquer transição de modo é aplicada, **THE SYSTEM SHALL** emitir um
  anúncio audível **exatamente uma vez** por transição.
- **THE SYSTEM SHALL NOT** transicionar para `STANDBY` como forma de encerrar o
  serviço — `STANDBY` passa a ser um modo em que o serviço **roda**, e o encerramento
  passa a ser uma ação própria.
- **WHILE** o modo for `STANDBY`, **THE SYSTEM SHALL** continuar publicando posição
  pelo plano de Standby (5 min / 250 m / `NETWORK_PROVIDER`) — sumir do mapa em pausa
  criaria a expectativa errada.

### Camada 2 — só entra com o número na mão

- **IF** o tempo medido de estabelecimento do SCO couber no orçamento de P1,
  **THEN WHEN** os óculos forem dobrados **OR** retirados do rosto, **THE SYSTEM
  SHALL** fechar a rota de áudio **AND SHALL** anunciar o fechamento por earcon.
- **WHEN** a rota é fechada por ausência de rosto, **THE SYSTEM SHALL** reabri-la ao
  primeiro sinal de retorno **AND SHALL NOT** exigir ação do agente.
- **THE SYSTEM SHALL NOT** fechar a rota de áudio **WHILE** o modo for `OCORRENCIA`,
  qualquer que seja o estado dos óculos.

## Como se prova

| afirmação | instrumento |
|---|---|
| **o modo muda em produção** | teste instrumentado que roda um turno simulado sem construir ViewModel nenhum e conta transições de `CopilotService.modo`. **Aceite: > 0.** Hoje é 0 e o teste falha — que é exatamente o critério do §6, pergunta 3 |
| **um só escritor de `_modo`** | teste JVM que varre `app/src/main` e `app/src/debug` e reprova qualquer escrita fora do `GovernadorDeModo`, com contra-teste de violador sintético — o molde de `FachadaDoDatTemDonoUnicoTest` |
| **a voz troca o modo de verdade** | contra-teste: *"modo ocorrência"* tem de mudar `tiposDeServico` **e** o `PlanoDePosicao`. Um teste que só verifique `modoTatico(true)` passaria com o defeito de hoje de volta |
| **os modos custam diferente** | `adb shell dumpsys batterystats --reset`, 30 min em cada modo, mAh por uid. **Aceite: os modos diferem.** Se não diferirem, a política não faz nada e esta spec morre — e é melhor descobrir aqui |
| **a transição é audível uma vez** | teste sobre a fila de som: N transições ⇒ N anúncios, nunca N+1 nem 0 |
| **não degrada no meio da ação** | teste com PTT no ar: a transição chega, não é aplicada, e é aplicada no `onRelease` |
| **tempo de estabelecimento do SCO** | `System.nanoTime()` entre `setCommunicationDevice()` e o primeiro quadro, com fone HFP real. **Este número é o portão da Camada 2** |
| custo do SCO nos óculos | só com óculos reais: corrida A/B até a morte, com a causa tipada de P2 — `docs/ENERGIA.md` §3.3-P2 |

## O que decidir — decisão humana, não diff

1. **`STANDBY` vira modo de verdade?** Hoje ele é "serviço parado". Torná-lo um modo
   que roda exige separar "parar" de "baixar cadência", e muda o contrato de
   `CopilotService.parar()`. Sem isso, a escada de energia tem dois degraus, não três.

2. **Na viatura, o que descansa?** A proposta é: o celular gasta à vontade (está
   carregando) e os óculos descansam. Mas descansar os óculos significa fechar o SCO,
   e isso é Camada 2. Enquanto o número não existir, "modo viatura" muda pouco — e
   vale perguntar se vale a pena existir sem a Camada 2.

3. **Agente de carona.** Ele está em serviço com o celular na tomada. "Ligado na
   tomada" não pode virar "surdo". A sobreposição por voz cobre? Ou o modo de viatura
   precisa de um gatilho a mais (velocidade, ignição) para não confundir carona com
   motorista?

4. **Quantos degraus o agente aguenta ouvir?** Cada transição custa uma fala. Um turno
   de 12 h com entra-e-sai de viatura pode dar dezenas de transições. Precisa de
   histerese — e o valor dela é decisão de produto, não de engenharia.

5. **A Camada 2 pode custar latência de P1?** Se o SCO medido levar 800 ms, o PTT
   depois de desdobrar os óculos custa 800 ms. Isso é aceitável em troca de autonomia?
   **É a única pergunta desta spec que sobrepõe um aceite já medido, e por isso ela
   fica aqui e espera.**
