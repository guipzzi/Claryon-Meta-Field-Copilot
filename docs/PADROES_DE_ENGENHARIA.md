# Padrões de Engenharia — Claryon Field

Regras duras do projeto: o que não pode ser invertido, o que é proibido, e as
armadilhas que já custaram tempo. **Leitura obrigatória antes de tocar no
código.** Onde este documento e a doc oficial do DAT divergirem, a doc oficial
vence.

App companion **Android/Kotlin** para óculos **Ray-Ban Meta** usando o **Meta Wearables Device Access Toolkit (DAT)**.
Copiloto de voz para agentes de segurança pública: o usuário fala, o app entende localmente, age, e responde por áudio no ouvido. Mãos livres, olhos no ambiente.

Entrega: Hackathon AI Glasses Brasil, **18/09/2026**.

---

## ⛔ REGRA ZERO — leia antes de tocar em qualquer API do DAT

O DAT está em **developer preview**, é versionado e mudou depois do corte de treinamento de qualquer modelo.

**Nunca escreva assinaturas do DAT de memória.** Antes de usar `Wearables`, `DeviceSession`, `Stream`, `StreamConfiguration`, `MockDeviceKit` ou qualquer símbolo do SDK:

1. Consulte o MCP `search_dat_docs` em `https://mcp.developer.meta.com/wearables`
2. Ou `https://wearables.developer.meta.com/llms.txt?full=true`
3. Ou o repo oficial `facebook/meta-wearables-dat-android` (samples > docs)

Registre em `DECISIONS.md` a versão do SDK e a fonte usada.
**Se não conseguir confirmar uma assinatura na documentação: PARE E PERGUNTE.** Não invente workaround.

Onde o material de apoio (Un12/Un13) divergir da doc oficial, **a doc oficial vence** — o material foi escrito contra a 0.8.0.

---

## As cinco restrições do hardware

1. **Sem display.** Única saída rica ao usuário é áudio. A tela existe só para configuração, diagnóstico e demo.
2. **O código não roda nos óculos.** Óculos = sensores + alto-falantes. Tudo roda no celular.
3. **Câmera pelo DAT, áudio pelo Bluetooth.** Não existe `session.audioStream`. Microfone e alto-falantes = `AudioManager` / `AudioRecord` / `AudioTrack` via HFP/A2DP.
4. **"Hey Meta" não é acessível.** A wake word é nossa, roda no celular sobre o áudio HFP.
5. **Bluetooth Classic é o gargalo.** Vídeo comprimido, resolução modesta; microfone mono a 8 kHz.

**Decisão de produto que é política de código:** o beamforming isola a voz de quem veste os óculos. Transcrevemos o **agente**, não o interlocutor. Isso é intencional — não "conserte".

---

## Sequências que não podem ser invertidas

### Boot
```
1. Wearables.initialize(context)                 // uma vez por processo
2. observar registrationState + registrationErrorStream
3. se != REGISTERED → deeplink Meta AI → retorno via claryonfield://
4. GlassesAudioManager.iniciar()                 // ⚠️ ANTES do passo 5
5. Wearables.createSession(AutoDeviceSelector) → session.start()
6. [sob demanda] session.addCamera(config) → camera.stream → stream.start()
```
Inverter 4 e 5 → captura de voz intermitente. HFP totalmente configurado **antes** da sessão de streaming.

### Ciclo de voz
```
WakeWord → [DESPERTAR, "BOMMM"] → PCM (HFP) → [CANAL_ABERTO, "bipbip"]
  → VAD fecha janela → [CANAL_FECHADO, "trimtrim" IMEDIATO]
  → SttEngine.transcribe() → IntentRouter → IntentExecutor.execute()
  → utteranceFor(ActionOutcome) → SoundQueue (earcon e/ou TTS)
```
Duas ordens que não podem ser invertidas:
1. O earcon de fechamento dispara quando o **VAD fecha a janela**, não quando o STT
   termina. E o de abertura sai quando o microfone de fato abre — não junto com o
   BOMMM: entre os dois há salto de corrotina, subida de SCO e carga do Whisper, e
   até 22/08 o agente não tinha como saber quando podia falar.
2. A **ação acontece antes** de existir qualquer frase — `utteranceFor` recebe o
   resultado, nunca a intenção. Ver "Honestidade" adiante.

### Abertura do rádio (`RadioViewModel.abrir`)
```
1. CanaisDoAgente.registrarRadio(…, noAr = { radio != null })   // ⚠️ ANTES do passo 2
2. audio.iniciar() → GlassesAudioRoute                          // falha ⇒ return
3. RadioTatico(...) → entrarEmModoAtivo(rota) → radio = novo
```
Inverter 1 e 2 → **todo aparelho sem HFP fica sem fio de voz.** Foi o defeito
corrigido em 22/08: o registro morava depois do `return@launch` da falha de rota, e
com óculos não pareados, fone ausente ou emulador ele nunca rodava — *"Claryon,
guarnição 3 na escuta"* era recusado sem motivo com detector, whisper e roteador
funcionando. Nada no passo 1 precisa de HFP: são lambdas que rodam no celular. Quem
depende da rota é o rádio **funcionar**, e é isso — e só isso — que `noAr` responde,
lido no instante do comando. Trava: `FioDeVozSemRotaDeAudioTest`.

### Encerramento
```
clearCommunicationDevice()   // senão todo áudio do sistema fica preso em 8 kHz
stream.stop() → session.stop() → liberar interpretadores, AudioRecord, AudioTrack
```

---

## Módulos

```
app/            UI Compose: onboarding, diagnóstico, painel de demo
core-glasses/   DAT: registro, sessão, câmera, MDK  ← único ponto que toca o SDK
core-audio/     Roteamento HFP, AudioRecord, AudioTrack
core-voice/     WakeWord, VAD, STT, TTS (interfaces + impls)
core-agent/     IntentRouter determinístico, políticas de ação
core-knowledge/ Trecho de norma + limiar de recusa (RAG extrativo)  ← contrato; sem chamador
core-sound/     Earcons, fila de prioridade, protocolo de laconicidade
core-evidence/  Cofre cifrado, hash chain, cadeia de custódia
core-sync/      Fila offline durável, Supabase, WorkManager
core-net/       Transporte da rede tática: PTT ao vivo, alertas, posições  ← a construir
core-common/    Result types, logging, feature flags
```

`core-*` não dependem uns dos outros (exceto `core-common`). Orquestração fica em `app`.
Todo acesso ao DAT passa por `GlassesFacade` em `core-glasses` — quando a 0.9 quebrar assinaturas, você conserta um arquivo.

**Toda peça de risco tem interface com dois back-ends:**
- `SttEngine` → `WhisperCppStt` (primário) | `AndroidOnDeviceStt` (fallback)
- `TtsEngine` → `PiperTts` (primário) | `AndroidTts` (fallback)
- `MessagingGateway` → `WhatsAppDirectGateway` (demo) | `WhatsAppGroupGateway` (flag) | `FakeGateway` (testes)

---

## Armadilhas — checar antes de dizer "pronto"

| Armadilha | Correção |
|---|---|
| HFP depois do stream | Sequenciar conforme o boot acima |
| `foregroundServiceType` faltando | Declarar no manifest **e** em `startForeground()` (Android 14+) |
| Iniciar FGS em background | `ForegroundServiceStartNotAllowedException` — iniciar de tela visível |
| `setCommunicationDevice()` → `false` | Tratar; testar `MODE_IN_COMMUNICATION` se `MODE_NORMAL` não subir |
| Registro perdido | Dev Mode: só 1 app de terceiros por vez. Detectar `AVAILABLE` inesperado |
| URI scheme ausente | `claryonfield://` com VIEW + DEFAULT + BROWSABLE. Nunca genérico |
| `speak()` antes de `onInit` | Enfileirar até `ready` |
| `SpeechRecognizer` fora da main thread | Crash. Sempre `destroy()` |
| `ERROR_NO_MATCH` como erro grave | É "não entendi" — earcon e seguir |
| Todos os frames em fila | `conflate`: um por vez, descartar os do meio |
| Inferência em `while(true)` | Sempre por gatilho de evento |
| Whisper como streaming | É **lote**: fechar a janela e só então transcrever |
| Pedir `HIGH`/30 fps | `LOW`/`MEDIUM` com FPS baixo dá qualidade por frame **melhor** |
| `capturePhoto()` sem stream ou em paralelo | Exige stream ativo; uma por vez |
| Tentar reviver sessão encerrada | *Cascading stop* — criar sessão nova |
| `getThermalHeadroom()` = `NaN` como 0 | `NaN` = sem informação; manter só o teto de taxa |
| Testar áudio no MDK | **MDK não simula áudio.** Use fone Bluetooth com HFP |
| Toque na haste como gatilho de qualquer coisa | É **gesto de sistema**: tap pausa/retoma o stream, tap-and-hold encerra a sessão. Callback de toque só existe com a capacidade de display, que os Ray-Ban não têm. Fora do produto desde 2026-08-15: acionamento é por voz e pelo botão do app, e nada mais. Ver `DECISIONS.md` |
| Modelo só no pacote de teste | No aparelho da organização a IA local não existiria. Empacotar em `assets/models/` |
| Duas capturas simultâneas | Dois `AudioRecord` na mesma fonte: a segunda falha ou rouba o fluxo da primeira. Fonte única com fan-out |
| PAT commitado | `local.properties` (já no `.gitignore`) ou `GITHUB_TOKEN` |
| Mesmo termo em dois classificadores | "Apoio" era ocorrência **e** `Intent.PedirApoio`: o comportamento passou a depender da ordem do `when`. Um termo, um dono |
| Duas réguas para a mesma decisão | "Policial baleado" era emergência por um caminho e prioridade normal pelo outro. Escalada de prioridade é função única e compartilhada |
| Permissão pedida sem estar no manifest | Negada em silêncio, para sempre. Sem erro de compilação, sem exceção, sem log. Há teste no aparelho que compara catálogo × manifest |
| `shouldShowRequestPermissionRationale` como verdade | Devolve `false` para "nunca pedimos" **e** para "negou em definitivo". Registrar localmente o que já foi pedido |
| Pedir permissão no `onCreate` | Diálogo sem contexto é diálogo negado, e negado duas vezes exige ir aos ajustes do Android. Motivo primeiro |
| Supor que o runner concede permissões | **Não concede.** O emulador começa com tudo negado — que é o estado real de primeira instalação |
| `pm revoke` no pacote sob teste | Mata o processo; o teste vira "encerrado inesperadamente". Combinações de negativa ficam na JVM |
| Idade de posição guardada em campo | Envelhece errado: o campo continua dizendo "5 s" enquanto o relógio anda. Derivar na leitura |
| Relógio de par adiantado | Idade negativa é lida como recentíssima pela política de obsolescência — o pior erro possível. `coerceAtLeast(0)` |

---

## Proibições absolutas

- ❌ **Reconhecimento facial, embeddings faciais ou base biométrica.** Nenhuma versão, nenhuma flag
- ❌ Transcrever, classificar ou indexar a fala de terceiros — áudio bruto é evidência, não dado analisável
- ❌ Enviar áudio, frame ou **transcrição literal** para serviço externo — absoluto para os três.
  **Revogado em parte em 22/08 pelo dono do projeto:** consulta **textual derivada**, reconstruída
  a partir da intenção e nunca da fala, é permitida sob as condições de
  [`../specs/consulta-externa.spec.md`](../specs/consulta-externa.spec.md) — vocabulário fechado,
  higiene que remove placa/matrícula/nome/indicativo, prazo de 2 s, local sempre primeiro,
  procedência registrada. Fora dessa spec a proibição vale inteira, e dado de terceiro, posição de
  par e identificador de agente continuam sem caminho nenhum para fora
- ❌ Credencial em arquivo versionado
- ❌ Evidência fora de `EncryptedFile` + Android Keystore
- ❌ **LLM escolhendo ação.** O modelo de linguagem pode **propor o preenchimento de
  campos** de uma intenção previamente definida, e só isso. Ele **nunca escolhe qual ação
  executar**, nunca produz texto que vá direto ao TTS, e sua saída é sempre validada contra
  esquema estrito. Falha de validação → pedido de repetição, **nunca ação por adivinhação**.
  A escolha da ação é sempre do roteador determinístico e auditável

---

## Honestidade — garantida por assinatura, não por disciplina

**Toda resposta falada deriva do resultado da ação, nunca do comando recebido.**
Não existe caminho no código em que a frase seja escolhida antes de a ação ser executada.

```
roteador → Intent → IntentExecutor.execute() → ActionOutcome → utteranceFor(outcome)
                                               ↑ a ação acontece AQUI
```

- `utteranceFor` aceita **apenas** `ActionOutcome`. **Nunca** acrescentar sobrecarga que
  aceite `Intent` — há teste que falha se alguém acrescentar
- `IntentExecutor` nunca lança: toda falha vira `ActionOutcome.Falhou` com causa tipada
- Entregue ≠ enfileirado. `Despacho.Enviada | Enfileirada` é escolha do compilador, não do
  programador. Sem rede, o agente ouve *"Sem rede. Na fila."* — jamais "apoio solicitado"
- Contagem desconhecida não vira zero nem número inventado: `ApoioTransmitido(null)` fala
  *"Apoio enviado."*, e só com contagem real fala *"Quatro unidades receberam."*
- Capacidade que não existe devolve falha honesta. Consulta a base oficial está fora do
  escopo → `CONSULTA_INDISPONIVEL`, nunca um "sem restrição" inventado

**Falha nunca é silêncio.** Todo caminho de erro tem earcon próprio. Num sistema sem
display, silêncio é indistinguível de aplicativo morto.

## Rota de áudio — pré-condição de tipo

Capturar exige `GlassesAudioRoute`, e o único jeito de obter uma é rotear de fato
(`TYPE_BLUETOOTH_SCO` ativo). **Gravar pelo microfone do celular não compila.**

- O microfone do celular é omnidirecional e capta terceiros; o beamforming dos óculos isola
  quem os veste. Com PTT, gravar pela fonte errada **difunde** a fala do interlocutor para a
  guarnição inteira — a violação deixa de ser local
- A rota é reconferida no início da captura: HFP cai em campo (óculos dobrados, fone
  desligado, ligação entrando), e o sistema escolhe um substituto silenciosamente
- Reprodução de PTT sempre por **SCO, nunca A2DP** — o A2DP acrescenta 100–200 ms de buffer

## Rádio tático (C1) — regras duras

- **A palavra de ativação NUNCA abre canal sozinha.** Quem abre é a transcrição íntegra em
  português contra léxico fechado, com o grupo resolvido e o piso concedido — o detector
  acústico só antecipa o earcon. A frase é *"guarnição N na escuta"*, com o número, e o
  casamento é integral: qualquer palavra extra recusa. Desligando o detector, o sistema
  continua correto e perde só a sensação de resposta imediata. Deixar o detector decidir
  sozinho faria o produto difundir para a guarnição inteira com tráfego de rádio ambiente.
  (Isto aqui dizia "transmissão é sempre push-to-talk explícito, nunca por palavra de
  ativação", e estava meio errado — a metade errada era a que importava, porque o fluxo
  aprovado em D1 é mãos livres do início ao fim, inclusive abrir transmissão.)
- **Áudio ao vivo, em quadros Opus de 20 ms, enquanto o agente fala.** Nunca gravar arquivo
  inteiro e depois enviar. O Storage é arquivamento assíncrono, jamais o caminho ao vivo
- **A captura não bloqueia esperando a rede.** `AudioRecord` começa no instante do toque;
  concessão de canal e estado do socket correm em paralelo. Rede lenta atrasa a entrega,
  **nunca perde fala**
- **Pré-roll:** buffer circular de 600 ms, **só em RAM, nunca persistido**. Ao pressionar, um
  VAD retroativo localiza o início real da fala e transmite a partir dali — nunca de um recuo
  fixo. Se o PTT não for pressionado, o conteúdo se perde por definição
- **Detector de palavra de ativação desligado** enquanto qualquer áudio sai pelos
  alto-falantes e enquanto o PTT está ativo. Alto-falante *open-ear* a centímetros do
  microfone: sem isso, o produto conversa consigo mesmo. Isto deixou de ser promessa em
  20/08: `EscutaDeAtivacao` filtra pelo `SupressorDeSaidaPropria` de processo — o mesmo do
  rádio — e por `RadioTatico.transmitindo`, e **reinicia o anel do detector nas duas bordas
  da mudez**. Sem o reinício ele emendaria os dois lados e avaliaria uma janela que nunca
  existiu no mundo, que é falso positivo por construção
- **Toda janela de supressão tem fim declarado — não existe `abrir` sem duração.**
  (22/08.) Havia uma segunda forma: uma janela sem fim previsto, aberta no anúncio de fala
  recebida e fechada em `EventoRecepcao.Terminou`. Quem fechava não era quem tocava, e o
  receptor leva 2 s para concluir que uma fala foi cortada pela rede — nesses 2 s não saía
  som nenhum e a captura do **próximo** agente a apertar o PTT era descartada inteira, com
  a barra no ar e nenhum tom. Quem reproduz fluxo de duração desconhecida registra **bloco
  a bloco**, com a duração de cada bloco decodificado; a margem de 80 ms emenda um no outro
- **O interrompido por P1 descobre pelo ANÚNCIO, não pela renovação.** (22/08.) O anúncio
  de fala do emissor P1 é difundido para o grupo inteiro, inclusive para quem está no ar —
  o sinal já estava no fio e ninguém o lia. Ele é o **gatilho** de uma confirmação imediata
  com o árbitro; quem corta a fala é a resposta do árbitro, nunca o anúncio. Sem isso a
  janela de duas vozes no fio era o intervalo de renovação: medido em **232 quadros,
  4 640 ms**. Tratar o anúncio como decisão daria a qualquer cliente forjado o poder de
  calar a guarnição
- **As três recusas de canal têm falas diferentes porque pedem gestos opostos.** (22/08.)
  Canal ocupado se resolve **esperando**; pedido sem resposta se resolve **andando** até
  pegar sinal; recusa de autorização se resolve conferindo credencial. O earcon é o mesmo —
  a categoria é a mesma —, e o que separa é a causa curta
- **Devolução de canal tem desfecho, e ele é lido.** `liberar` devolve
  `ResultadoDaLiberacao`, e `NaoDevolvido` vira tom antes de `Encerrada`. Um
  `liberar_canal` perdido deixa a guarnição muda até o TTL de 30 s, e só quem causou pode
  agir
- **Fim de fala recebida diz COMO acabou — e o fato chega ao OUVIDO e ao BALÃO.**
  `EventoRecepcao.Terminou` carrega `FimDaFala.ENCERRADA_PELO_EMISSOR | CORTADA_NO_MEIO`.
  `perdidos` vem zero justamente no caso truncado — o receptor não sabe contar quadros que
  nunca existiram —, então contar nunca bastou para distinguir. Até 22/08 o corte virava só
  earcon mais `FALA_DO_COLEGA_CORTADA`, e a tela desenhava a fala truncada campo por campo
  igual à inteira: quem estava de capacete não recebia o fato por caminho nenhum. Hoje
  `RadioTatico.aoFalaCortada` → `RadioViewModel.marcarCorteDaRede` → `comCorteDaRede` marca
  o balão, e ele sai com régua tracejada terminal, `"cortada"` no rodapé e a frase na
  leitura em voz. **A marca vive em DISCO LOCAL e é reaplicada a cada recarga**:
  `transmissions` não tem coluna para o motivo do fim, e sem reaplicar a marca sumiria no
  poll seguinte, dez segundos depois. A RAM não bastou — o serviço é `START_STICKY` e o
  sistema o recria, e depois disso o aparelho **tinha** o fato de primeira mão e o esquecia.
  Não vai ao servidor de propósito: o corte é **conclusão do receptor**, e dois receptores
  da mesma transmissão discordam com razão — quem está no túnel conclui `CORTADA_NO_MEIO`,
  quem está no descampado recebeu tudo. Escrever no servidor faria a condição de rede de um
  aparelho virar fato global. `CortesConhecidos` poda por validade (12 h, um turno) e por
  teto (200 ids), porque marca acumulada por turnos vira lixo que ninguém limpa
- **Piso local se declara em voz na abertura do rádio.** Sem sessão não há arbitragem do
  servidor, e dois aparelhos podem se achar donos do mesmo canal. A degradação fica (o
  rádio precisa funcionar em túnel); o silêncio sobre ela, não

## Localização e mapa (C2/C5)

- Assinatura do canal de posições **só enquanto a tela do mapa está visível**. Difundir
  posição de todos para todos o turno inteiro drena bateria para uma tela fechada 95% do tempo
- Marcador esmaece após 2 min sem atualização. **Mostrar posição velha como atual é pior que
  não mostrar** — é requisito de segurança, não polimento
- **Reciprocidade:** dentro do talk group, quem vê é visto. Não existe modo de observar sem
  ser observado. Assimetria de visibilidade entre pares é vigilância; simetria é coordenação
- A consulta por voz devolve **distância, rumo e estado** — nunca coordenadas brutas. O
  aparelho de um agente jamais recebe a posição de outro. O cálculo roda **no servidor**
  (`public.consultar_posicao`): filtrar no cliente exigiria entregar a coordenada primeiro,
  e a garantia viraria promessa
- **Função exposta nunca aceita a identidade de quem pergunta como parâmetro.** Com
  `solicitante_id` na assinatura, um agente legítimo varre os pares do talk group e
  **trilatera a posição absoluta de qualquer um usando só distâncias** — o dado que a API
  pode devolver. O solicitante vem do JWT. `private.posicao_relativa` aceita o parâmetro, e
  é exatamente por isso que ela fica em `private`
- **Espelho do mapa e consulta por voz são coisas separadas.** O espelho local
  (`CanalDePosicoes`) só existe com o mapa visível e alimenta **apenas o mapa**. Fazer a
  consulta por voz depender dele a quebraria com a tela apagada, que é o caso normal

## Design de áudio — regras duras

- Máximo **7 palavras** por resposta de TTS em contexto operacional (há teste automatizado)
- Sem cortesia: nada de "por favor", "desculpe", "tudo bem"
- Números falados dígito a dígito: "A-B-C-1-D-2-3"
- Resultado de consulta sensível sai como **earcon codificado + fala curta** (≤7
  palavras). Até 21/08 a regra era "nunca falado" — alto-falante open-ear vaza som para quem está ao lado
- Falha nunca é silêncio. Todo erro tem earcon próprio
- **A gramática do canal tem três tempos, e os papéis não se invertem.** (22/08.)
  `"Claryon"` → `DESPERTAR` (BOMMM, golpe de sino inarmônico) · canal abre →
  `CANAL_ABERTO` (bipbip, dois chirps subindo) · o agente para de falar, ou 30 s →
  `CANAL_FECHADO` (trimtrim, dois chirps descendo). **Despertar é IDENTIDADE** — som
  que só existe neste produto, e é o que a marca registra; **abrir e fechar canal é
  CONVENÇÃO**, copiada do chirp do Nextel/iDEN porque o policial já sabe o que
  significa. Convenção poupa treinamento, identidade cria marca. Inverter custa as
  duas coisas: uma marca que ninguém reconhece e um par de sons a aprender
- **Todo earcon vive entre 400 e 3400 Hz.** O elo até os óculos é HFP/SCO de banda
  estreita — acima de ~3,4 kHz nada chega, e planejar parcial ali é planejar
  silêncio. Abaixo de ~400 Hz mora o ruído de viatura (motor, rolamento,
  ventilação). O `FALHA` varria até 300 Hz e tinha **54 % da energia debaixo do
  motor**, justamente no sinal que avisa que algo deu errado
- **Dois earcons se separam por MORFOLOGIA, não por frequência.** Sob banda estreita
  e ruído grave, o que sobrevive é quantos elementos há, separados por quanto
  silêncio, e se cada um sobe, desce ou fica plano. Altura absoluta é a primeira
  pista a cair. Cada earcon tem uma assinatura `(nº de elementos, contornos,
  ataque)` **única e calculada do PCM** — declarar a assinatura ao lado do
  sintetizador faria o teste conferir a si mesmo
- **Vocabulário sonoro tem teste de distinguibilidade PAR A PAR, e ele mede o
  ataque.** Até 22/08 nada guardava isso, e o resultado foi previsível:
  `GRAVANDO` e `CONSULTA_FURTO_ROUBO` eram **idênticos bit a bit por 115 ms** (os
  dois abriam com `tone(500.0, …)`), e `ACAO_EXECUTADA` e `CONSULTA_RESTRICAO_ADMIN`
  tinham a mesma morfologia a 4,5 semitons. Os primeiros ~120 ms têm régua própria
  porque é neles que o agente decide se o som é para ele — um par que só diverge no
  fim já cobrou a atenção inteira antes de dizer o que era
- **Todo earcon precisa de chamador em `src/main`** (`ChamadorDosEarconsTest`).
  Earcon sintetizado e nunca tocado é a mesma família de defeito do §6 do
  `CLAUDE.md`, e é a mais fácil de cometer: nasce numa entrada de `enum`, ganha um
  ramo no `when`, passa nos testes de síntese, e nunca sai por um alto-falante
- Fila de prioridade: nível 1 (emergência) interrompe tudo; nível 3 é suprimido em Modo Tático
- **"Interrompe tudo" inclui a SÍNTESE, não só o que já está soando.** Uma fala do
  copiloto passa ~1 s dentro do Piper antes de virar som, e uma leitura de norma passa
  muito mais. Até 22/08 o P1 que chegava nessa janela não cortava nada e ainda esperava a
  fala inteira tocar (~10,9 s contra os ≤ 200 ms do aceite). O item em curso na
  `PrioritySoundQueue` é **síntese + reprodução** num job só
- **`cancel()` de corrotina não interrompe JNI.** O Piper é nativo: quem corta não *para* a
  síntese, **desiste de esperar por ela** — a síntese fica órfã numa corrotina irmã e o PCM
  que chegar depois não tem quem o toque. Qualquer conserto de preempção que dependa de o
  `render` obedecer ao `cancel` funciona no teste e mente em campo. Corolário: todo `render`
  da fila tem de **suspender**, nunca bloquear a thread do laço

---

## Energia

- Gatilho, nunca loop. Cascata: wake word (barata, sempre) → VAD → STT (caro, quase nunca)
- Câmera desligada por padrão. Stream só sob intenção explícita, e desce ao terminar
- Modos: **Standby** (HFP fechado) | **Ativo** (HFP aberto, câmera sob demanda) | **Ocorrência** (tudo ligado, janela curta)
- `ForegroundService` com `connectedDevice|microphone|camera` para o pipeline contínuo
- `WorkManager` com `requiresCharging` + `UNMETERED` + `requiresBatteryNotLow` para upload e modelos
- `getThermalHeadroom()` antes de rajadas; `sample` no Flow como teto de taxa

---

## Quando parar e perguntar

- Assinatura de API do DAT não confirmável na doc
- Necessidade de credencial ou de hardware físico
- Qualquer nova dependência de terceiros (justificar tamanho, licença, alternativa nativa)
- Qualquer decisão que afete latência, bateria ou privacidade
- Descoberta de que um marco anterior está quebrado

---

## Fluxo de trabalho

- **Um marco por sessão.** Ao concluir, apresentar o critério de aceite atendido e parar para revisão humana
- **Antes de declarar marco concluído, ler o código procurando cenários de falha concretos.**
  *Teste verde prova que o caminho feliz funciona; não prova que os outros caminhos existem.*
  Foi assim que 21 defeitos apareceram em código já revisado e com suíte verde
- `DECISIONS.md`: uma linha por decisão não óbvia — data, alternativa descartada, motivo
- Commits pequenos, mensagem explicando o *porquê*
- `README.md` com setup reproduzível do zero, incluindo NDK e download de modelos
- **O build precisa funcionar offline** após a primeira sincronização. Wi-Fi de evento é ruim

---

## Metas mensuráveis (instrumentar desde o início)

| Métrica | Alvo |
|---|---|
| Fim da fala → earcon | ≤ 500 ms |
| Fim da fala → resposta (local) | ≤ 2,0 s |
| Fim da fala → resposta (rede) | ≤ 3,0 s |
| STT em 20 comandos operacionais com ruído | ≥ 92% |
| Falsos positivos da wake word | ≤ 1/hora |
| Bateria do celular, modo Ativo | ≤ 12%/h |

Métrica adicionada no fim nunca é adicionada.

---

## Comandos

```bash
./gradlew build                     # compilar tudo
./gradlew :app:installDebug         # instalar
./gradlew connectedAndroidTest      # testes instrumentados (MDK)
adb logcat -s ClaryonField          # logs
adb shell dumpsys batterystats --reset && adb shell dumpsys batterystats > bs.txt
```
