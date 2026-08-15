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
PCM (HFP) → WakeWord → VAD fecha janela → [earcon "ouvi você" IMEDIATO]
  → SttEngine.transcribe() → IntentRouter → IntentExecutor.execute()
  → utteranceFor(ActionOutcome) → SoundQueue (earcon e/ou TTS)
```
Duas ordens que não podem ser invertidas:
1. O earcon dispara quando o **VAD fecha a janela**, não quando o STT termina.
2. A **ação acontece antes** de existir qualquer frase — `utteranceFor` recebe o
   resultado, nunca a intenção. Ver "Honestidade" adiante.

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
- ❌ Enviar áudio, transcrição ou frame para serviço externo no caminho crítico
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

- **Transmissão é sempre push-to-talk explícito.** Nunca por palavra de ativação: um falso
  positivo difundiria para a guarnição inteira
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
  microfone: sem isso, o produto conversa consigo mesmo

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
- Resultado de consulta sensível sai como **earcon codificado**, nunca falado — alto-falante open-ear vaza som para quem está ao lado
- Falha nunca é silêncio. Todo erro tem earcon próprio
- Fila de prioridade: nível 1 (emergência) interrompe tudo; nível 3 é suprimido em Modo Tático

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
