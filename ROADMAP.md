# Roadmap — Claryon Field

Fases de **16/08/2026 a 18/09/2026**. Cada fase termina com algo demonstrável e um
critério de aceite verificável — não é opinião sobre estar pronto.

Trilha do edital: **Produtividade**. IA **local**, no aparelho do agente. Escopo pode
crescer por melhoria; §14.1 veda mudança de **domínio**, não de detalhe.

---

## Critério de "construído"

Tem chamador em `src/main` alcançável pelo agente **em runtime, sem passar por tela de
diagnóstico**. Classe testada sem chamador é *escrita*, não construída. Tela de
diagnóstico não conta como caminho: `DiagnosticsScreen` é hoje a única que chama
`cicloDeVoz` e não é composta — por isso o ciclo de voz está morto apesar de pronto.

Três perguntas fecham qualquer item:

1. Que gesto ou fala do agente chega até esta linha de código?
2. O que aparece na tela ou no ouvido quando ela falha?
3. Qual comando prova que ela rodou? (`adb logcat -s ClaryonField`, `dumpsys`, consulta SQL,
   `Telemetry`.)

---

## Caminho crítico

Oito passos. Cada um é pré-condição do seguinte.

**1. 16 kHz ponta a ponta.** `RadioTatico.kt:88` declara `sampleRateHz: Int = 8_000` e
`RadioViewModel` não sobrescreve, contra 16 kHz da captura. A voz transmitida sai uma
oitava abaixo com o dobro da duração. **O Pilar 1 está quebrado hoje no caminho feliz.**
Meia sessão, maior alavanca do projeto.

**2. Porta de entrada do ciclo de voz.** Whisper, Piper, `DeterministicIntentRouter`,
`LexicoDeOcorrencias` e `ClaryonIntentExecutor` estão prontos e inalcançáveis. Um botão
resolve hoje; a voz resolve na Fase 2. "Construir, testar e não ligar já aconteceu cinco
vezes aqui" (`AGENTS.md`).

**3. Fonte única de microfone com fan-out.** Os passos 1 e 2 **não coexistem** sem ela:
`RadioTatico` já coleta o mesmo `Flow` frio duas vezes e `cicloDeVoz` abre uma terceira
captura. É a primeira dependência declarada em `specs/gatilho-por-voz.spec.md`.

**4. Dono único da saída, com fila de prioridade unificada.** Rádio e ciclo de voz têm
hoje duas filas que não se enxergam: um P1 do rádio não interrompe fala do copiloto
(item 3 do `ESTADO.md`). E o `AudioTrack` ainda nasce e morre por quadro de 20 ms.

**5. Telemetria instrumentada.** Sobe do fim para aqui. A Fase 2 tem meta **numérica** de
latência, e `Telemetry.mark` (`core-common/.../Telemetry.kt`) não tem um único chamador.
Sem ele, "tipo Alexa" continua sendo adjetivo.

**6. Gatilho por voz e talk group por voz.** É a decisão D1, aprovada. Depende de 3, 4 e 5.
Muda o produto de "app de toque" para o que a premissa promete.

**7. Servidor honesto: JWT no canal, piso remoto, transcrição na origem, turno e retenção.**
Hoje o piso é resolvido em RAM do próprio processo, qualquer portador do APK entra no
canal pela chave anon, as três Edge Functions não têm chamador em Kotlin, e o indicativo
é string livre não verificada — personificação é possível.

**8. Conhecimento de domínio on-device.** RAG extrativo primeiro, LLM depois. É a única
frente do plano que pode ser cortada inteira sem quebrar nada — e por isso é a última.

**Corte de emergência.** Se restarem três dias úteis: passos 1, 2, 4 e 7 entregam os três
pilares em nível demonstrável. Passo 6 sem passo 5 é aceitável em demonstração e
indefensável em documento — a meta vira afirmação sem medida.

---

## Fases

### FASE 0 — MVP mínimo demonstrável e a entrega da Etapa 5 (16/08 a 22/08, prazo duro)

**Objetivo.** Chegar em 22/08 com um app que faz o que o documento diz, e escrever o
documento sobre o que funciona — não sobre o que está planejado. O template não foi
preenchido antes de propósito (D5): descrever capacidade inexistente é a forma mais cara
de errar num critério de viabilidade técnica.

**Não há vídeo nesta fase.** O edital não pede vídeo — a palavra não aparece nele. Era
proposta minha, não requisito. Se a organização vier a pedir, ele é subproduto de meia
sessão sobre um app que já funciona; não é entrega e não dita prioridade.

**Itens**

- [P1] Passar `sampleRateHz = 16_000` na construção de `RadioTatico` (`RadioTatico.kt:88`
  é o default de 8 kHz que ninguém sobrescreve) e alinhar o codec — esforço: 0,5 sessão —
  depende: nada.
- [P1] `AudioTrack` único e serial na recepção, substituindo a criação por quadro de 20 ms
  em `GlassesAudioManagerImpl` — esforço: 1 sessão — depende: 16 kHz.
- [P3] Porta de entrada do copiloto por **botão** em `TelaDeGuarnicao` chamando
  `cicloDeVoz()`. Não é o desenho final (o final é a Fase 2), é o caminho alcançável que
  prova que C2/C3/C4 existem — esforço: 0,5 sessão — depende: nada.
- [P1] Chamar a Edge Function `transmit` a partir do Kotlin. Hoje `grep "functions/v1"
  --include=*.kt` devolve zero, logo `transmissions` nunca recebe INSERT e
  `HistoricoDoCanal.falas()` devolve lista vazia **sempre**: o fio do canal mostra só
  inserções otimistas que somem na recarga — esforço: 1 sessão — depende: nada.
- [SEG] Deletar `supabase/functions/locate (apagada)` e derivar identidade do JWT em `transmit.ts`
  e `ack.ts`. `locate.ts:21-23` aceita `solicitante_id` do corpo e chama
  `private.posicao_relativa` com `service_role`, reabrindo na borda a trilateração que a
  migração 0006 fechou no banco — violação direta de regra dura do `AGENTS.md` — esforço:
  0,5 sessão — depende: nada. Melhor relação risco/esforço do projeto.
- [UX] Devolver o âmbar ao uso único, que é a regra escrita no próprio
  `ui/tema/Cores.kt` ("o âmbar tem um significado só: você está no ar"). Hoje `Cores.NoAr`
  aparece em `MapaDeRuas.kt:420,432,455`, `TelaDoMapa.kt:245,246,255`, `TelaDeLogin.kt:203,240`
  e `TelaDeGuarnicao.kt:172` — esforço: 0,5 sessão — depende: nada. Entra aqui porque custa
  quase nada e conserta toda captura de tela que for para o documento.
- [REFAT] `CopilotService`: `stopSelf()` em `:80` e `:89` acontecem antes de qualquer
  `startForeground()` (que só existe em `:133`/`:135`), e `parar()` usa `startService` em
  `:215` em vez de `startForegroundService` — esforço: 0,3 sessão — depende: nada. Duas
  linhas, e o sintoma é crash de ciclo de vida em aparelho que a equipe não escolheu.
- [TRANSVERSAL] Reler a proposta da Etapa 1 e conferir se ela menciona WhatsApp ou IA em
  nuvem. §14.1 veda mudança de domínio; melhoria dentro do domínio está confirmada com os
  avaliadores (D6). O documento é continuidade com detalhamento, nunca pivô — esforço: 0,5
  sessão — depende: nada.
- [TRANSVERSAL] Escrever o documento e o deck no template da organização (20 a 22/08): os
  três pilares, a arquitetura em camadas, IA 100% local com os modelos que estão de fato no
  APK, a política de dados em duas camadas, e a tabela "o que o servidor vê / o que não vê"
  **com os itens ruins na coluna HOJE** — esforço: 2,5 sessões — depende: todos os itens
  acima, porque o documento descreve o que roda.
- [TRANSVERSAL] Meia página de análise de risco voluntária (art. 38 da LGPD): risco
  identificado, medida adotada, risco residual assumido — esforço: 0,3 sessão — depende:
  nada. Artefato curto que separa nota mediana de nota alta em Considerações éticas (20 pts).

**Aceite.** Instalação limpa num aparelho zerado. (a) Uma fala transmitida por PTT é
reproduzida em tom e duração corretos — verificável por espectrograma comparando entrada
e saída, com o pico de F0 dentro de 5% do original. (b) Um toque no botão "Copiloto"
produz resposta falada sem outro toque. (c) O fio do canal exibe a fala vinda de
`transmissions` **depois de recarregar a tela** — prova de que a Edge Function foi
chamada. (d) `grep -r "locate" supabase/functions/` não devolve arquivo. (e) `grep -rn
"Cores.NoAr" app/src/main` só aparece em código de transmissão. (f) Documento e deck
enviados até 22/08, cada afirmação de capacidade correspondendo a um caminho alcançável.

**Destrava.** A continuidade do projeto. Sem passar no Segundo Filtro (23 a 29/08,
resultado em 31/08), nenhuma fase seguinte é executada. É a única fase cujo custo de
falha é total.

---

### FASE 1 — Barramento de áudio único e o que mede as metas · itens feitos, aceite (d) em aberto

**Objetivo.** Uma fonte de microfone, um destino de áudio, uma fila de prioridade, e
instrumentação que transforma meta em número. É a janela do Segundo Filtro: não há
entregável externo, e é o melhor momento para mexer na fundação.

**Itens**

- [P1+P3] `FonteUnicaDeMicrofone` em `core-audio`: um `AudioRecord` por rota, `SharedFlow`
  com fan-out para N consumidores (pré-roll, transmissão ao vivo, VAD, gatilho, cofre),
  contagem de referência e reconferência de rota **durante** o stream, não só na abertura —
  esforço: 2 sessões — depende: nada.
- [P1] Dono único da saída: um `AudioTrack` de longa duração com **fila de prioridade
  unificada** entre rádio e ciclo de voz, para que um P1 do rádio interrompa a fala do
  copiloto (pendência remanescente do item 3 do `ESTADO.md`) — esforço: 1,5 sessão —
  depende: barramento.
- [P1] Codec Opus fora da thread principal: `withContext(Dispatchers.Default)` e escopo
  próprio em vez de `viewModelScope` — esforço: 0,5 sessão — depende: barramento.
  Verificável por StrictMode com `detectCustomSlowCalls`.
- [P1] `AgrupadorDeQuadros`: 3 quadros de 20 ms por mensagem. Hoje são 50 mensagens/s de
  ~300 B para 30 B de voz. `Transmissao.kt:28` **afirma que a peça existe** e ela não existe
  no repositório — corrigir código ou documentação, nunca deixar as duas divergindo —
  esforço: 1 sessão — depende: nada.
- [TRANSVERSAL] Instrumentar `Telemetry.mark` e `TelemetriaDoRadio.registrar/contar`:
  latência de PTT, latência de gatilho, latência de ciclo de voz, idade e precisão por
  publicação de posição, resultado HTTP, contagem de despertares do rádio. Exportar p50/p95
  por um comando de diagnóstico — esforço: 1,5 sessão — depende: barramento. **Antecipado do
  fim do roadmap para aqui**: a Fase 2 tem meta numérica e sem isto ela não tem aceite.
- [REFAT] Quebrar `DiagnosticsViewModel` (798 linhas, em produção, instanciado por
  `MainActivity.kt:52`): extrair `MapaViewModel`, `CopilotoViewModel` e `EvidenciaViewModel`;
  o que sobrar de diagnóstico vai para trás de `BuildConfig.DEBUG` — esforço: 2 sessões —
  depende: porta de entrada da Fase 0.
- [P1] `RadioTaticoTest` em `app/src/test`: taxa de amostragem, contagem de `AudioRecord`
  abertos e serialização da reprodução. Hoje não há nenhum teste sobre `radio/`, que é
  exatamente onde vivem os três defeitos — esforço: 1 sessão — depende: os itens acima.

**Aceite.** Com PTT e ciclo de voz disparados ao mesmo tempo, `adb logcat -s ClaryonField`
registra **exatamente um** `AudioRecord` aberto e **um** `AudioTrack` vivo. Um P1 chegando
durante fala do copiloto corta a fala em ≤ 200 ms, medido por `Telemetry`. StrictMode não
acusa violação na Main durante 30 s de transmissão contínua. Contagem de mensagens cai de
~50/s para ~17/s medida em `TransporteRealtime`. `./gradlew :app:testDebugUnitTest` verde
com o novo `RadioTaticoTest`. `Telemetry` exporta p50 e p95 de latência de PTT.

**Destrava.** Tudo. É a primeira dependência declarada em `specs/gatilho-por-voz.spec.md`
(`fonte-unica-de-microfone-com-fanout`), e sem ela gatilho por voz, transcrição na origem
e cofre de evidência competem pelo mesmo microfone.

---

### FASE 2 — Gatilho por voz aprovado e talk group falado (30/08 a 05/09)

**Objetivo.** Entregar o fluxo que o usuário aprovou (D1): mãos livres do início ao fim,
incluindo abrir transmissão. A justificativa é operacional e está registrada — numa
abordagem, com uma mão na pistola e outra no volante, não há mão para tocar nos óculos.

**Fluxo aprovado, textual:**

```
"Hey Claryon"  +  "guarnição 3 na escuta"
  → resolve o talk group "guarnição 3" contra a lista fechada dos grupos DO AGENTE
  → BIP de confirmação: está gravando e transmitindo
  → transmissão ao vivo em quadros de 20 ms
  → teto duro de 30 s contados A PARTIR DO BIP
  → fecha por detecção de silêncio da voz do agente
```

Duas propriedades desse desenho são invariantes, não preferências:

1. **"guarnição N na escuta" ≠ "na escuta".** O número do grupo torna a frase específica e
   a tira do vocabulário corrente de rádio policial. O casamento é **integral**: frase mais
   qualquer palavra extra = recusa.
2. **O KWS nunca abre canal.** Ele antecipa o earcon; quem abre é a transcrição íntegra em
   português contra léxico fechado, com o grupo resolvido e o piso concedido. Com o KWS
   desligado o sistema continua correto e perde só a sensação de resposta imediata. Se
   alguém "otimizar" deixando o KWS decidir sozinho, o produto passa a abrir canal com
   tráfego de rádio ambiente.

**Latência vira número (D2), com os dois relógios separados:**

| Marca | Alvo |
|---|---|
| fim de "Hey Claryon" → **início** do earcon `OUVI_VOCE` | p95 ≤ **500 ms** |
| fim do enunciado completo → BIP de canal aberto | p95 ≤ **1 200 ms** |
| fim do enunciado completo → primeiro quadro de 20 ms no ar | p95 ≤ **1 500 ms** |
| falso aceite que **abre canal** | ≤ 1 por 8 h |
| falso aceite que só toca earcon | ≤ 0,5 por hora |
| recall do gatilho em 30 pronúncias reais, por fone HFP | ≥ 90% |

Os 500 ms não são escolha de conforto. **Verificado por mim no artefato**
(`core-voice/libs/sherpa-onnx-1.13.5.aar`, pool de constantes de
`KeywordSpotterKt.class`): os presets referenciados por `getKwsModelConfig` são
`sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01` e
`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, e **os dois** carregam
`encoder/decoder/joiner-epoch-12-avg-2-chunk-16-left-64.onnx`. A doc do sherpa atribui
320 ms de latência algorítmica ao chunk-16 contra 160 ms ao chunk-8 — **número que ainda
não reconferi na fonte nesta sessão e que precisa de confirmação antes de virar linha de
spec**. Se ele se confirmar, um alvo de 300 ms é impossível por construção com estes
artefatos, e descer dele exige um modelo chunk-8 que os presets do AAR não trazem.

**Correção de fato na spec, antes de qualquer diff.** `specs/gatilho-por-voz.spec.md`
afirma que o único preset do AAR é chinês. São dois, e o segundo é inglês. Isso derruba um
risco aceito da spec como está escrita e transforma o KWS de otimização hipotética em
caminho real — ainda que com recall desconhecido em pt-BR.

**Itens**

- [TRANSVERSAL] Revisão da spec com a aprovação D1 escrita e datada dentro dela, mais as
  três correções: dois presets de KWS (não um), teto de **30 s** no item 13 — que hoje diz
  12 000 ms enquanto `SessaoPtt.kt:234` já declara `DURACAO_MAXIMA_MS = 30_000L` — e faixa
  de duração do enunciado subindo de 0,6–2,5 s (`spec:149`) para 1,2–4,0 s, porque a frase
  ficou mais longa — esforço: 0,5 sessão — depende: nada. **A aprovação precisa estar na
  spec, não só na conversa**: `CLAUDE.md` diz que sobrepor regra dura é decisão humana, e
  `docs/PADROES_DE_ENGENHARIA.md:190` continua dizendo o contrário até ser editado.
- [P3] Silero VAD substituindo o detector por energia RMS. `SileroVadModelConfig` está no
  AAR (verificado: a classe existe em `com/k2fsa/sherpa/onnx/`). **Duas instâncias, não
  uma**: a do gatilho quer segmentos curtos, a da transmissão precisa tolerar 30 s via
  `maxSpeechDuration`. Confirmar a assinatura por `javap` antes de escrever — esforço: 1
  sessão — depende: barramento.
- [P3] Contexto do Whisper quente entre invocações: hoje `cicloDeVoz` faz `Modelos.whisper()`
  e `release()` por ciclo, recarregando 77,7 MB (`ggml-tiny.bin` tem 77 691 713 B em
  `app/src/main/assets/models/`). Vira `object` de processo com liberação por política
  térmica — esforço: 1 sessão — depende: quebra do `DiagnosticsViewModel` — esforço: 1
  sessão.
- [P3] Verificador do gatilho: VAD abre janela → whisper pt transcreve → casamento integral
  contra léxico fechado → grupo resolvido → earcon → piso → BIP → quadros — esforço: 2
  sessões — depende: VAD + contexto quente + telemetria.
- [P3] KWS como adiantamento do earcon, atrás de flag, com o preset inglês e a grafia
  fonética de "Claryon". Último item da fase porque é o único que sai sem quebrar nada —
  esforço: 1 sessão — depende: verificador funcionando.
- [P1] Seleção de talk group por voz, em três diffs: migração `0012` com coluna
  `rotulo_falado text` única por `unit_id` em `talk_groups` (nunca derivar o número por
  substring de `nome` — `'GTA-3 Alfa'` casaria "3" por acidente); carga do mapa
  `{rotulo_falado → id}` no login, que a RLS já limita ao que o agente pode ver; e
  `RadioTatico.trocarDeGrupo(id)` reconectando o transporte **sem tocar em `AudioDoAgente`**
  — esforço: 2 sessões — depende: verificador.
- [P1] Matar o canal fixo: `CANAL_DEMO` e `NOME_DO_CANAL` em `MainActivity.kt:236-237` e o
  fallback morto `TALK_GROUP_PADRAO = "demo"` em `RadioViewModel.kt:450` — esforço: 0,3
  sessão — depende: seleção por voz.
- [P3] Recusa honesta e audível: falar um grupo a que o agente não pertence responde "você
  não é da guarnição 3", não silêncio. Descarte silencioso é para gatilho não reconhecido;
  autorização negada merece resposta — esforço: 0,3 sessão — depende: seleção por voz.
- [P3] Fecho por silêncio, escrito com honestidade na spec: *o sistema detecta ausência de
  fala, não ausência da fala do agente* — o isolamento depende do beamforming dos óculos.
  Parada por toque continua existindo e é a única que não depende do microfone — esforço:
  0,5 sessão — depende: VAD.
- [P1] Teto de 30 s avaliado **fora** do `collect` de quadros. Hoje ele é avaliado dentro,
  então uma fonte que para de emitir mantém o canal tomado indefinidamente — e subir de 12
  para 30 s dobra a janela de dano sem consertar a causa — esforço: 0,5 sessão — depende:
  nada.
- [P3] `WakeWordDetector` implementado de fato e `PowerPolicy` religada, tornando o modo
  **Standby** alcançável (item 5 do `ESTADO.md`) — esforço: 1 sessão — depende: KWS ou VAD.
- [P3] Gazetteer de logradouros em produção: `configurarGazetteer` só é chamado em teste —
  esforço: 0,5 sessão — depende: nada.

**Aceite.** Aparelho no bolso, fone HFP no ouvido, nenhum toque na tela. Dizer "Hey
Claryon, guarnição 3 na escuta" produz earcon, BIP e transmissão que um segundo ouvinte
recebe; parar de falar fecha o canal; falar 40 s corridos fecha aos 30 s por teto. Dizer
"guarnição 9 na escuta", grupo a que o agente não pertence, produz recusa falada e
**nenhum quadro no ar** — verificável por contagem de mensagens no transporte. Trinta
pronúncias reais gravadas por HFP dão recall ≥ 90%. `Telemetry` exporta p95 das três
marcas da tabela acima, e os três números entram no `ESTADO.md` medidos, não prometidos.
Oito horas de rádio ambiente gravado e reproduzido não abrem canal nenhuma vez.

**Destrava.** A premissa do produto. E o checkpoint de IA do dia 18/09 na forma que o
programa prefere: local, funcional e comprovável.

---

### FASE 3 — Servidor honesto: rede, posição e retenção em duas camadas (06/09 a 11/09)

**Objetivo.** Transformar o PTT de aparelho solitário em rede multiusuário, e a posição de
"última coordenada" em capacidade com base legal, prazo e prestação de contas. Rede e
posição estão na mesma fase porque são a mesma camada — protocolo, RLS e migração.

**Regra de sequenciamento, antes da lista.** A tabela de trilha entra **na mesma sessão**
em que entram a porta de turno, o job de retenção e o log de acesso. Nunca antes. Criar a
trilha sem os três controles deixa o sistema estritamente pior do que está hoje: passa a
existir rastro contínuo do deslocamento de agentes sem recorte de turno, sem prazo
executado e sem registro de quem leu. Se o calendário apertar, o certo é **não criar a
tabela** e declarar a política por escrito — banca pontua política e controle, não a
existência de uma tabela.

**Itens**

- [P1/SEG] Canal Realtime privado amarrado ao JWT do agente. Hoje o transporte autoriza só
  pela chave anon do APK e o protocolo não envia `access_token`: qualquer portador do APK
  entra em `realtime:tg-<uuid>` e recebe todos os quadros e indicativos. **Confirmar a API
  de canal privado/`setAuth` na doc oficial do Supabase antes de qualquer diff** — esforço:
  1,5 sessão — depende: nada. Maior risco do sistema pelo menor esforço.
- [P1] `ClienteDePisoRemoto` no lugar de `ClienteDePisoLocal`: o `floor_grants` atômico de
  `0005_controle_de_piso.sql` existe, está concedido e nunca foi usado. Sem isso não há
  rede, há aparelhos falando por cima — esforço: 1 sessão — depende: JWT no canal.
- [SEG] Indicativo derivado do JWT no protocolo, nunca do payload. Hoje ele é string livre
  não verificada, então personificação é possível — e um P1 forjado em nome de outra
  guarnição toma o canal por desenho — esforço: 1 sessão — depende: JWT no canal.
- [SEG] Coluna `ativo` em `agents`, conferida dentro de `private.current_agent_id()`
  (`0002_rls.sql:37-45`). Toda política de linha, todo RPC e o controle de piso passam por
  essa função: um UPDATE derruba o agente de canal, piso, posição e consulta na mesma
  transação. Revogação institucional é item que a banca procura por nome — esforço: 0,5
  sessão — depende: nada.
- [P1] Acumulador do PCM transmitido em `SessaoPtt`, derivado dos dois pontos únicos por
  onde o áudio passa (pré-roll e `collect` ao vivo). A invariante é transcrever **os bytes
  que foram ao ar**, não os que foram capturados — esforço: 1 sessão — depende: barramento.
- [P1] Whisper disparado no `finally` de `SessaoPtt`, **fora** do `withTimeoutOrNull` e em
  escopo de aplicação, para não competir com a codificação ao vivo nem morrer ao sair da
  tela — esforço: 1 sessão — depende: acumulador + contexto quente.
- [P1] Quarto evento `fala.transcricao` no protocolo (hoje há três) e roteamento no
  receptor chaveado por `transmissaoId`, **fora** do laço de reprodução — o texto não pode
  viajar no anúncio, que sai antes da fala — esforço: 1,5 sessão — depende: acumulador.
- [P2/REFAT] Dono único da escrita de posição: `ColetorDePosicao` como único escritor.
  Resolve num diff o custo de 720 escritas/h, a escrita redundante, o apagamento de
  `speed_mps` e a falsificação de frescor — esforço: 1 sessão — depende: quebra do
  `DiagnosticsViewModel`.
- [P2] Batimento alcançável com o agente parado, idade real da correção carimbada no
  servidor (`medida_em`, não hora do upload), porta de precisão com teste de salto, e
  `ultimaPosicao()` escolhendo a **melhor** correção e não a mais nova — esforço: 2 sessões
  — depende: dono único.
- [P2] Arredondamento de distância dentro de `consultar_posicao` e `posicoes_do_grupo`. O
  arredondamento para 50/100 m existe em `locate.ts` e está morto; a função viva devolve
  precisão métrica crua — esforço: 0,3 sessão — depende: nada.
- [SEG] **Camada 1 — corregedoria.** `private.turnos` com índice único parcial de turno
  aberto por agente, `public.iniciar_turno()`/`encerrar_turno()`, `publicar_posicao`
  **recusando escrita fora de turno aberto**, encerramento automático por inatividade, e
  `private.trilha_de_posicao` particionada por dia, sem GRANT para `authenticated` e sem
  índice geográfico — esforço: 2 sessões — depende: dono único. Sem o encerramento
  automático, "esqueci de encerrar" vira 24 h de rastreamento e a defesa jurídica inteira
  cai; ele é parte do controle, não refinamento.
- [SEG] **Camada 2 — janela de 30 minutos para pares.** `public.rastro_do_par(indicativo)`
  devolvendo série de distância e azimute dos últimos 30 min, com a idade de cada ponto
  declarada, sujeita à mesma reciprocidade que a consulta de posição já pratica — esforço:
  1 sessão — depende: camada 1.
- [SEG] Job de retenção executando os dois prazos e o `expira_em` de `transmissions`, que
  hoje é campo lógico sem executor. Prazos como constante única numa migração, alteráveis
  em uma linha. **Confirmar a assinatura de `cron.schedule` na doc do Supabase antes do
  diff** — esforço: 1 sessão — depende: camada 1.
- [SEG] Registro de acesso nas duas portas: linha por consulta em `consultar_posicao`,
  sessão em `abrir_mapa`/`fechar_mapa` para a porta de alto volume. **Nunca gravar a
  resposta**, e o autor sai de `private.current_agent_id()`, jamais do indicativo do
  protocolo — log com autor forjável produz prova falsa e é pior que log nenhum — esforço:
  1 sessão — depende: indicativo do JWT.
- [SEG] `public.quem_me_consultou()`: o titular vê quem o consultou — esforço: 0,3 sessão —
  depende: log de acesso. Converte conformidade em característica de produto.

**Aceite.** Dois pares autenticados com JWTs distintos no mesmo talk group: A fala, B ouve
áudio inteligível, **os dois** exibem a mesma string de transcrição com o mesmo
`transmissaoId`. B aperta o PTT durante a fala de A e recebe recusa de piso vinda do
servidor. Um terceiro cliente com o APK e sem sessão válida recebe erro ao entrar no canal.
Um agente marcado `ativo = false` perde canal, piso e consulta na mesma transação. Fora de
turno aberto, `publicar_posicao` **recusa** — verificável por SQL. `cron.job_run_details`
mostra a retenção executada. `servidor/verificacoes/` roda verde.

**Aceite com um aparelho só (D7).** O segundo par é uma sessão headless com JWT distinto
rodando no emulador ou num script Deno: ela prova protocolo, piso remoto, RLS e roteamento
de transcrição — tudo menos qualidade de áudio, que continua sendo provada por
espectrograma no aparelho físico. **A fase não fica bloqueada por hardware**, mas o item de
áudio entre dois aparelhos reais fica marcado como pendente até o segundo celular chegar.

**Destrava.** O Pilar 1 completo e o argumento central de privacidade: a transcrição nasce
no aparelho de origem, viaja idêntica para todos, e nenhum áudio vai a serviço externo.

---

### FASE 4 — Conhecimento de domínio on-device (12/09 a 14/09)

**Objetivo.** Responder "minha Glock 19 emperrou, como faço?" com IA rodando no aparelho do
agente. É a frente mais vistosa do plano e a menos crítica: entra depois de tudo que
quebra o produto se faltar.

**A ordem entrega valor antes de risco, e as duas etapas são independentes:**

- **Etapa A — RAG extrativo, sem LLM nenhum.** Embedder + índice local + Piper lendo o
  trecho recuperado *verbatim*, com o número do documento citado. Custa poucas centenas de
  MB, latência quase zero, zero alucinação, e **responde a pergunta hoje**. É entregável e
  demonstrável sozinha.
- **Etapa B — LLM como camada de redação por cima do A.** O modelo recebe o trecho e o
  reescreve em três frases faladas. Se decepcionar em pt-BR, desliga por flag e a Etapa A
  continua de pé.

**O produto nunca fica dependendo do LLM funcionar.** Esse é o ponto do desenho, e é a
resposta direta ao padrão que o `AGENTS.md` registra: a Etapa A tem caminho alcançável
antes de qualquer linha de código de LLM existir.

**Dois fatos verificados neste repositório mudam a escolha de motor e precisam estar aqui:**

1. **O ggml deste projeto é compilado como biblioteca compartilhada.**
   `core-voice/build/intermediates/merged_native_libs/release/.../lib/arm64-v8a/` contém
   `libggml.so`, `libggml-base.so` e `libggml-cpu.so`. O `lib/arm64-v8a/` dentro do APK é
   um diretório plano, e llama.cpp compilado pelo mesmo CMake produz **os mesmos três nomes
   de arquivo**. Ou o merge de jniLibs falha, ou um `pickFirst` faz whisper.cpp e llama.cpp
   linkarem contra uma única revisão de ggml, com ABI incompatível e crash só em runtime.
   Isso não é "alguns MB de disco duplicado": é renomear os alvos do llama.cpp ou unificar
   revisões de ggml, e unificar arrisca o STT que hoje funciona. **"É uma tarde" está
   errado.**
2. **Llama 3.2 1B e 3B são texto puro.** O pedido inclui interpretação de foto e vídeo
   depois; visão só existe em 11B/90B, que não cabem no aparelho. Escolher 1B hoje é
   escolher trocar de família, tokenizador e prompt depois — não "trocar um arquivo".
   Somem-se a licença própria do Llama (não é open source) e a política de uso aceitável
   que veda armas, num produto cujo caso de uso-bandeira é manejo de pistola: isso é
   parecer jurídico, e ele vem antes do código, não depois.

Por isso o motor **não está decidido aqui** — está na lista de decisões em aberto, com os
dois candidatos e o que precisa ser confirmado antes de qualquer linha em `build.gradle.kts`.

**Itens**

- [P3] `core-knowledge`, módulo novo que depende só de `core-common` e **não** declara
  dependência de `core-agent` — esforço: 0,5 sessão — depende: nada.
- [P3] Corpus curado com o número do documento em cada trecho, de material de licença
  compatível com embarque em APK. **Este é o item de maior risco de cronograma da fase** e
  não é tarefa de engenharia — esforço: 1 sessão de curadoria — depende: decisão sobre a
  fonte.
- [P3] Embedder + índice vetorial local + recuperação por similaridade com **limiar**.
  Abaixo do limiar, o copiloto diz que não sabe — esforço: 2 sessões — depende: corpus.
- [P3] Etapa A alcançável por voz: "Hey Claryon, Glock 19 emperrou" → recupera → Piper lê o
  trecho citando o documento — esforço: 0,5 sessão — depende: índice + Fase 2.
- [P3] Teste que prova que a saída do modelo **nunca** alcança `ClaryonIntentExecutor`.
  Fronteira de módulo é necessária e não suficiente: `app` importa os dois e é lá que a
  `String` do LLM e o executor se encontram. A garantia tem de ser um teste em `app`, do
  mesmo jeito que a garantia de posição é do servidor e não do cliente — esforço: 0,5 sessão
  — depende: Etapa A.
- [P3] Etapa B atrás de flag, com o motor decidido pela decisão em aberto e o modelo em
  `filesDir` (nunca em `assets/`: o loader recebe caminho de arquivo, e asset não tem
  caminho no sistema de arquivos — comprimido ou não) — esforço: 3 sessões — depende:
  decisão do motor + Etapa A. **Primeiro item cortado se o calendário apertar.**
- [P3] Degradação por flag ou RAM disponível no boot: aparelho fraco fica na Etapa A, que já
  está pronta por construção. Nenhum caminho novo, nenhum código morto — esforço: 0,3 sessão
  — depende: Etapa B.
- [TRANSVERSAL] Regra dura reescrita **como spec proposta em `specs/`, com aceite em EARS**,
  não como diff direto no `AGENTS.md`: o LLM continua proibido de escolher ação, e ganha uma
  exigência a mais — só fala sobre o que recuperou, e sem recuperação acima do limiar diz que
  não sabe — esforço: 0,5 sessão — depende: nada. Revisão humana antes do diff.

**Aceite.** Modo avião, sem rede nenhuma. "Hey Claryon, minha Glock 19 emperrou" produz
resposta falada que cita o número do documento de origem, em ≤ 4 s do fim da fala, medido
por `Telemetry`. Uma pergunta fora do corpus produz "não encontrei procedimento para isso"
e não uma invenção — verificável com 10 perguntas fora de domínio. `./gradlew build` verde
com o módulo novo e **um** conjunto de `libggml*.so` no APK, verificável por
`unzip -l app-release.apk | grep ggml`. O teste de fronteira falha se alguém ligar a saída
do LLM ao executor.

**Destrava.** O argumento de Produtividade e o checkpoint de IA. Não destrava nenhum outro
item do roadmap — de propósito.

---

### FASE 5 — UX/UI, travamento e ensaio (15/09 a 17/09)

**Objetivo.** Refinar o que a plateia vê, e travar. Refinamento vem por último porque tela
de app que ainda vai mudar é retrabalho — os itens de UX que custam quase nada já entraram
na Fase 0.

**Os modelos combinados, sem reabrir discussão.** Dark-only, densidade de painel de
instrumento, estrutura feita de fios de 1 px e não de caixas, âmbar com um significado só.
Está tudo escrito em `ui/tema/Cores.kt` — a fase é fazer o app obedecer o próprio sistema.
**Três coisas foram rejeitadas três vezes e não voltam:** colchetes decorativos, alvos
circulares e âncoras assimétricas. `TelaDoMapa.kt:245-255` desenha exatamente um alvo
circular com `drawCircle` e uma linha de rumo — é o primeiro a sair.

**Itens**

- [UX] Auditoria do sistema atual com a skill `audit-design-system`, produzindo a lista de
  divergências entre `Cores.kt`/`Tema.kt` e o que as sete telas de fato usam — esforço: 0,5
  sessão — depende: nada.
- [UX] Remover o alvo circular do mapa (`TelaDoMapa.kt:245-255`) e substituir por marca de
  rumo em fio, coerente com o resto — esforço: 0,5 sessão — depende: auditoria.
- [UX] Tela de guarnição como painel: canal ativo nomeado pelo `rotulo_falado` real (não
  mais `"GTA-3 Alfa"` fixo), estado do piso, estado da rota de áudio, e quem está falando —
  esforço: 1 sessão — depende: Fase 2 e Fase 3.
- [UX] Estados de falha visíveis e honestos: hoje a UI mostra `ENFILEIRADA` sem fila alguma.
  Todo estado exibido tem de corresponder a estado que existe — esforço: 1 sessão — depende:
  auditoria.
- [UX] Escala tipográfica de dado tabular: indicativo, distância, rumo e idade alinhados por
  coluna, com tabular figures. É o que faz a tela ler como instrumento e não como app de
  mensagem — esforço: 0,5 sessão — depende: auditoria.
- [UX] Movimento com a skill `motion-design`, e só onde carrega informação: o pulso do "no
  ar", a transição de piso concedido/negado, o esmaecimento do marcador por idade. Nada
  decorativo — esforço: 1 sessão — depende: itens acima.
- [UX] Teste de captura por tela para não regredir depois — esforço: 0,5 sessão — depende:
  itens acima.
- [SEG] Permissão de câmera do DAT pedida em produção (item 8 do `ESTADO.md`): hoje nunca é
  pedida, e em hardware real a leitura de placa quebra no primeiro uso — exatamente no
  onboarding, que é a única janela — esforço: 0,5 sessão — depende: nada. **Prioridade
  absoluta dentro da fase.**
- [SEG] Coletar `Stream.errorStream` e tratar `STOPPED` como terminal (item 7 do
  `ESTADO.md`): sem `camera.stop()` o próximo `addCamera` falha, e sem os erros tipados não
  se sabe por que a demonstração parou — esforço: 1 sessão — depende: nada.
- [SEG] Assinatura do manifesto de custódia com chave no Keystore, assinatura incremental
  sobre o hash corrente — esforço: 1 sessão — depende: cofre instanciado.
- [REFAT] Limpar ramos mortos e documentação que afirma capacidade inexistente — lista
  completa na seção seguinte — esforço: 1 sessão — depende: fases anteriores.
- [TRANSVERSAL] Criar `docs/INDICE.md`, que `CLAUDE.md` e `AGENTS.md` citam e **não existe
  no repositório** — esforço: 0,3 sessão — depende: nada. O gatilho de leitura de
  `AGENTS.md` aponta hoje para um arquivo ausente.
- [TRANSVERSAL] Ensaio cronometrado dos dois checkpoints obrigatórios, cada um em ≤ 10 min,
  com roteiro escrito e aparelho já pareado — esforço: 1 sessão — depende: tudo.
- [TRANSVERSAL] Ensaio do pitch, reescrita final de `ESTADO.md`, `git push origin master` —
  esforço: 1 sessão — depende: tudo.

**Aceite.** App instalado do zero num aparelho limpo executa os dois roteiros de checkpoint
em ≤ 10 min cada, cronometrado, com a permissão de câmera do DAT pedida e concedida no
primeiro uso. `grep -rn "Cores.NoAr" app/src/main` só devolve código de transmissão.
Nenhum `drawCircle` decorativo permanece. Nenhum estado exibido na UI corresponde a
capacidade inexistente — verificável por grep dos termos removidos. `docs/INDICE.md` existe
e cada linha aponta para arquivo que existe.

**Destrava.** O dia 18/09. A janela real de código no evento é de ~5h30 com dois cortes
obrigatórios e almoço: nada novo se constrói lá. Quem chega construindo, perde.

---

### 18/09 — o dia

Sem itens. Execução do roteiro, os dois checkpoints, o pitch. A única coisa que se faz de
código é corrigir o que quebrar em hardware que a equipe nunca tocou — e para isso a Fase 5
deixou `errorStream` coletado e permissão de câmera pedida, que são os dois pontos que só
falham lá.

---

## Refatoração que não dá para adiar

- **Taxa de amostragem divergente.** `RadioTatico.kt:88` declara `sampleRateHz: Int = 8_000`
  e `RadioViewModel` não sobrescreve, contra 16 kHz da captura. Não é degradação, é quebra:
  a voz sai uma oitava abaixo com o dobro da duração. Nenhuma demonstração do Pilar 1 é
  possível enquanto isso estiver no lugar. Meia sessão.
- **Múltiplos donos do microfone.** Dois `collect` sobre o mesmo `Flow` frio em
  `RadioTatico`, mais uma terceira captura em `cicloDeVoz`; o próprio KDoc do
  `DiagnosticsViewModel` admite que a segunda "falha ao inicializar ou rouba o fluxo da
  primeira". Cada feature nova do roadmap agrava o defeito em vez de conviver com ele.
- **`AudioTrack` construído e liberado por quadro de 20 ms**, com padding artificial, em
  corrotinas concorrentes. São 50 tracks por segundo tocando fora de ordem: a fala recebida
  é entrecortada por construção, independentemente da rede.
- **Duas filas de prioridade que não se enxergam** (item 3 do `ESTADO.md`). Um P1 do rádio
  não interrompe a fala do copiloto. Num produto de segurança pública, prioridade que não
  interrompe não é prioridade.
- **Codec Opus na thread principal**, com `TIMEOUT_US` de 20 ms bloqueando a Main a cada
  chamada. Um ANR durante checkpoint é reprovação ao vivo.
- **`DiagnosticsViewModel` com 798 linhas fazendo trabalho de produção**, instanciado por
  `MainActivity.kt:52`, detendo mapa, publicador de posição, cofre, despachante e o ciclo de
  voz morto. Toda feature de voz ou de mapa entra nesta classe e a piora; refatorar depois
  de mais 500 linhas custa três vezes mais.
- **Dois escritores de posição** que não se enxergam, a 720 escritas/h, um apagando
  `speed_mps` do outro e regravando correção velha como `now()`. Um diff resolve quatro
  defeitos, e frescor falso é exatamente o que o esmaecimento do mapa existe para impedir.
- **`while(true)` do mapa sem contenção de exceção**, enquanto `RadioTatico` já resolveu o
  mesmo problema com `semDerrubarOProcesso`. O modo de falha é o pior possível: a corrotina
  morre, a reabertura é recusada, e a tela **congela exibindo marcadores velhos como se
  fossem ao vivo**.
- **`CopilotService` com `stopSelf()` em `:80` e `:89` antes de qualquer
  `startForeground()`** (que só existe em `:133`/`:135`), e `parar()` usando `startService`
  em `:215`. Duas linhas, e o aparelho do dia é de OEM que a equipe não escolhe.
- **`locate.ts` aceitando `solicitante_id` do corpo** e chamando `private.posicao_relativa`
  com `service_role`. Anula na borda a garantia que a migração 0006 construiu no banco.
  Meia sessão para apagar; a nota inteira se a banca abrir `supabase/functions/`.
- **Indicativo como string livre não verificada** no protocolo. Personificação é possível
  hoje, e `RadioTatico` confia cegamente em `autorIndicativo` e `prioridade` para disparar o
  earcon que toma o canal.
- **Documentação que afirma capacidade inexistente.** `Transmissao.kt:28` cita
  `AgrupadorDeQuadros` como existente e ele não está no repositório;
  `docs/PADROES_DE_ENGENHARIA.md:190` continua dizendo "nunca por palavra de ativação"
  depois de a decisão ter sido tomada em contrário; `specs/gatilho-por-voz.spec.md:149`
  fixa 0,6–2,5 s para um enunciado que ficou mais longo e `:179` fixa 12 000 ms de teto
  contra os `30_000L` de `SessaoPtt.kt:234`; `specs/gatilho-por-voz.spec.md:255` proíbe
  endereçar talk group, que agora é requisito. Documentação que mente é pior que ausente: o
  próximo agente confia nela e constrói em cima.
- **`docs/INDICE.md` não existe**, e é citado por `CLAUDE.md` e por `AGENTS.md` como o
  índice de onde buscar o trecho antes de tocar em áudio, posição ou fala. O gatilho de
  leitura aponta para o vazio.
- **Ramos mortos nos dois lados.** `transmissao.nova` difundido pelo servidor e não
  interpretado pelo cliente; `SupabaseSyncGateway` mirando `tactical_messages`, tabela que
  nenhuma das dez migrações cria; `CanalDePosicoes` completo, testado e sem instanciação;
  `mapaVisivel` fixo em `false`; arredondamento de distância implementado só no arquivo que
  vai ser apagado. Cada um é uma promessa que a banca pode cobrar no pitch.

---

## Riscos, com mitigação

- **Eliminação no Segundo Filtro (23 a 29/08).** Resultado só em 31/08; todo código
  posterior é aposta até lá. Mitigação: a Fase 0 é absoluta e o documento descreve apenas
  capacidade com caminho alcançável — a forma mais barata de perder viabilidade técnica
  (30 pts) é afirmar o que não roda.
- **Um aparelho só até data indefinida (D7).** Sem o segundo celular, o Pilar 1 não é
  testável como rede em áudio. Mitigação: aceite da Fase 3 desmembrado — protocolo, piso
  remoto e roteamento de transcrição provados por sessão headless com JWT distinto; áudio
  entre dois aparelhos físicos fica marcado como pendente explícito, não como item verde.
- **A equipe nunca tocará nos óculos antes de 18/09.** Todo o trabalho de HFP/SCO, câmera e
  ciclo de vida de stream é feito às cegas. Mitigação: fone Bluetooth HFP como bancada
  honesta; `MockDeviceKit` para os caminhos de erro; e permissão de câmera e `errorStream`
  resolvidos na Fase 5, que são exatamente os dois que só falham em hardware real.
- **Banda estreita, não banda larga.** A doc do DAT descreve o áudio dos óculos como 8 kHz
  mono. O passo "16 kHz ponta a ponta" conserta o pitch — que é defeito real — mas não vira
  banda larga: é contêiner de 16 kHz sobre conteúdo estreito. Todo número público de recall
  de KWS e de WER assume banda larga. Mitigação: **nenhuma medição da Fase 2 vale se for
  feita pelo microfone do celular** — as 30 pronúncias do aceite passam por fone HFP.
- **O KWS disponível é inglês, e chunk-16.** Verificado por mim no artefato: os dois presets
  do AAR são wenetspeech e gigaspeech, ambos `chunk-16-left-64`. Recall de "Claryon" em
  pt-BR por grafia fonética é desconhecido. Mitigação estrutural, não empírica: **o KWS
  nunca abre canal**, só antecipa o earcon; recall ruim degrada a sensação, não a correção.
  E a licença individual do pacote gigaspeech **não está confirmada** — confirmar antes de
  embarcar (o sherpa-onnx ser Apache-2.0 não decide a licença de cada modelo pré-treinado).
- **Meta de latência escrita antes de medida.** Os 500 ms da Fase 2 dependem de um número de
  320 ms atribuído ao chunk-16 pela doc do sherpa, ainda não reconferido nesta sessão.
  Mitigação: meia sessão de medição no Samsung **antes** de a meta entrar na spec — se o
  piso algorítmico for maior, o alvo sobe e a razão fica escrita, em vez de a spec conter um
  número impossível por construção.
- **Colisão de `libggml*.so`.** Verificado: este projeto já produz `libggml.so`,
  `libggml-base.so` e `libggml-cpu.so` como bibliotecas compartilhadas, e `lib/arm64-v8a/`
  no APK é diretório plano. llama.cpp pelo mesmo CMake produz os mesmos nomes. Mitigação:
  decidir o motor **antes** de escrever qualquer linha (ver decisões em aberto); se for
  llama.cpp, renomear os alvos é pré-requisito, não detalhe; e o aceite da Fase 4 exige
  `unzip -l` mostrando um conjunto só.
- **Corpus do RAG sem origem definida.** Não há corporação parceira no repositório, e POP
  oficial de PM tem prazo administrativo e restrição de distribuição. A Etapa A inteira
  depende disso. Mitigação: corpus inicial de material aberto e de manual de fabricante,
  com o desenho deixando a fonte trocável — e o documento dizendo que o corpus é do
  contratante, não do produto.
- **Licença e política de uso do modelo.** O Llama tem licença própria (não é open source) e
  política de uso aceitável que veda armas, contra um caso de uso de manejo de pistola. O
  Qwen3 é Apache-2.0. Mitigação: a licença entra como critério de decisão do motor, não como
  descoberta posterior.
- **Afirmação falseável no documento.** Dizer "nunca coordenadas" é demonstravelmente falso
  a partir deste repositório: `MapaDeRuas.kt:265` e `:370` reconstroem a coordenada absoluta
  do par com `Geo.destino(minhaLat, minhaLon, distanciaM, rumo)`. Se a banca fizer a conta,
  toda outra afirmação de privacidade passa a ser lida com desconfiança. Mitigação: declarar
  o limite em texto, e declarar o que as migrações 0006/0009/0010 de fato impedem — dump em
  massa, par arbitrário e trilateração de terceiros.
- **Auditoria virando o segundo banco de vigilância.** Logar cada sondagem do mapa registra
  quando o agente estava olhando a tela — monitoramento comportamental do próprio titular,
  criado em nome de protegê-lo. Mitigação obrigatória: sessionizar a porta de alto volume e
  **nunca gravar a resposta devolvida**.
- **Janela de 30 minutos dimensionada pelo caso raro.** É a decisão do usuário e entra como
  decidida, mas o mecanismo é o clássico de escalada: todo mundo passa a ler meia hora de
  todo mundo, o turno inteiro. Mitigação: a janela é o **teto** e cada abertura passa pelo
  log de acesso; emergência prolongada, se precisar de mais, ganha porta própria com
  registro de quem abriu.
- **Regra Zero pode dobrar o tempo das Fases 3 e 4.** A API de canal privado/`setAuth` do
  Supabase Realtime, a assinatura de `cron.schedule` e as coordenadas Maven do motor de LLM
  **não estão confirmadas**. Mitigação: a primeira meia sessão de cada fase é só confirmação,
  antes de qualquer diff; plano B do canal é validação de token na Edge Function com rotação
  curta, não improviso no cliente.
- **E2EE mal desenhado derruba o rádio em vez de protegê-lo.** Rotação de época iniciada
  pelo cliente, sem árbitro, produz split-brain justamente quando a composição do grupo
  muda. E chave de grupo sem assinatura por emissor não impede a forja de um P1. Mitigação:
  canal privado por JWT primeiro (resolve a exposição real por uma fração do esforço); E2EE
  só entra com assinatura de origem antes da cifra; se não couber, vira roadmap declarado —
  declarar honestamente pontua mais que entregar cifra decorativa.
- **A Fase 4 rouba a folga que o caminho crítico não tem.** O LLM é a feature mais vistosa e
  a menos crítica. Mitigação: ela é a última, a Etapa B é o primeiro corte, e nenhum aceite
  de outra fase depende dela.

---

## Decisões ainda em aberto

- **1. Motor e modelo da Etapa B (Fase 4).** Duas opções, e a diferença é grande: **(a)**
  llama.cpp com um GGUF de licença permissiva (Qwen3 1.7B é Apache-2.0), pagando o custo
  confirmado de renomear alvos para não colidir com os `libggml*.so` do whisper; **(b)**
  LiteRT-LM com um modelo multimodal, que atende a segunda metade do pedido (foto e vídeo)
  sem trocar de família depois. A pesquisa indica que o artefato Maven do LiteRT-LM existe,
  mas isso **não foi confirmado por doc oficial nesta sessão** — e a Regra Zero manda parar
  e perguntar antes de qualquer linha em `build.gradle.kts`. Decisão pede meia sessão de
  confirmação, não opinião.
- **2. Origem do corpus do RAG.** (material aberto e de fabricante / POP de corporação
  parceira / outro). É o item que trava a Fase 4 inteira e não é resolvível por engenharia.
- **3. Verificação de locutor por embedding — permitido ou proibido?**
  `SpeakerEmbeddingExtractor` e `SpeakerEmbeddingManager` existem no AAR (verificado: as
  classes estão em `com/k2fsa/sherpa/onnx/`) e resolveriam de verdade o "silêncio **da voz
  do agente**". Mas produzem um embedding biométrico de voz, e `AGENTS.md` proíbe "base
  biométrica. Nenhuma versão, nenhuma flag." A proibição está escrita pensando em face.
  Decidir explicitamente é melhor que deixar ambíguo — e enquanto não decidir, o fecho por
  silêncio continua detectando ausência de fala, não ausência da fala do agente.
- **4. Criptografia ponta a ponta do áudio — escopo até 18/09.** **(a) recomendado** — canal
  privado por JWT + assinatura Ed25519 por emissor em cada anúncio, E2EE completo declarado
  como roadmap. **(b)** E2EE completo com chave de grupo por época: 3 sessões, risco de
  split-brain, e sem assinatura por emissor a cifra não impede a forja. **(c)** só canal
  privado, E2EE inteiro como roadmap.
- **5. Transcrição no servidor — cifrada ou em claro?** **(a)** em claro, com `transmissions`
  como registro operacional auditável, retenção executada e log de acesso — preserva
  corregedoria, perícia e o resumo por voz. **(b)** cifrada com chave do servidor. **(c)**
  E2EE, que mata busca, auditoria e ordem judicial. Furar essa decisão depois é pior que
  decidir errado agora.
- **6. Prazo da camada de corregedoria.** O prazo pertence ao prazo de apuração disciplinar
  da corporação contratante, não ao produto. Enquanto ele não existir, fica como constante
  única numa migração, alterável em uma linha — e o documento diz isso em vez de inventar um
  número.
- **7. Store-and-forward de fala com rede caída — manter na narrativa?** (sim / não). Hoje
  `ArquivoDeFalasDiferidas` só tem chamador em teste e grava **Opus em claro** num `File`
  puro, com o `transmissaoId` no nome do arquivo, sem `EncryptedFile` nem Keystore — colide
  com regra dura. Se **sim**, entra na Fase 3 com cifra em repouso (1,5 sessão). Se **não**,
  sai da UI e do documento, e a decisão fica registrada.
- **8. Quando chega o segundo aparelho?** Não é decisão de engenharia, mas determina se o
  aceite de áudio da Fase 3 fecha com hardware ou fica pendente até 18/09. Se a resposta for
  "não sei", o plano segue como está: sessão headless prova protocolo, e o áudio entre dois
  aparelhos vira o primeiro item do onboarding no dia.

---

**Encerradas por decisão do usuário e removidas desta lista:** template da Etapa 5 (entrega
em 22/08, escrito depois do MVP) · trilha do edital (Produtividade) · IA local ou em nuvem
(local, on-device) · gatilho por voz para transmitir (aprovado, com o fluxo da Fase 2) ·
trilha histórica de posição (guardar, em duas camadas) · revogação institucional via
`agents.ativo` (entra, Fase 3) · credencial exposta (sem pendência).

---

## Decisões tomadas em 2026-08-16 (encerradas)

| # | Decisão | O que muda |
|---|---|---|
| **E2EE** | **Opção (a)**: canal privado por JWT + **assinatura Ed25519 por emissor**. E2EE completo fica declarado como roadmap | O ataque real hoje é **personificação**, não escuta: o indicativo é string livre e ninguém verifica. Chave de grupo prova pertencimento, **não autoria** |
| **Transcrição no servidor** | **Em claro**, com acesso por RLS e log de auditoria | Cifrada ponta a ponta, corregedoria e perícia deixariam de conseguir ler — e o valor probatório é requisito, não acessório |
| **"Voz do agente"** | **Não é verificação de locutor.** Não usar `SpeakerEmbeddingExtractor` | O requisito é detectar que **quem veste os óculos** está falando — proximidade, não identidade. Resolve-se por nível e beamforming (campo próximo a ~5 cm contra fontes a metros: 20–30 dB). Nenhum embedding biométrico é produzido, e a regra dura não é tocada |
| **Rótulo "na fila"** | **Removido.** Vira "não saiu" | `ArquivoDeFalasDiferidas` tem 187 linhas, 11 testes e **zero chamadores**: não há fila. "Na fila" fazia o agente seguir a ocorrência contando com um apoio que nunca foi pedido |
| **Segundo aparelho** | Chega em breve; o plano **não se limita** a um | O aceite de áudio da Fase 3 continua desmembrado — protocolo por sessão headless, áudio entre aparelhos quando houver |
| **Retenção da corregedoria** | **Pendente** por decisão | Fica como constante única numa migração até a corporação definir o prazo de apuração |

### A regra que estas decisões consolidam

> **O app e o código não podem mentir nem inventar.** Rótulo sem lastro, motivo fixo que
> já não é verdade, e documentação que afirma capacidade inexistente são a mesma falha —
> e num produto sem display, onde o agente não tem como conferir, ela é operacional e não
> cosmética.

Duas correções aplicadas hoje sob esta regra: o rótulo de entrega (acima) e o relatório de
prontidão, que dizia *"recepção de posições ainda não disponível no transporte"* como string
fixa — falso desde que o mapa passou a funcionar, dando falso negativo em 100% das aberturas
sobre a capacidade que funciona.
