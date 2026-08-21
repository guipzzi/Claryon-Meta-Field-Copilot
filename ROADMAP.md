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

## Reconciliação de 20/08 — por que há marcadores nos itens

O texto de cada item é do dia em que foi escrito e está no **presente**: "hoje X está
quebrado". Isso envelhece mal. Em 20/08 o item da `0012` ainda dizia que "qualquer
portador do APK entra em `realtime:tg-<uuid>`", o que deixou de ser verdade em 18/08 —
e este é o documento que o `CLAUDE.md` aponta como *"o que vem, em que ordem"*. Um
roadmap que descreve um projeto que não existe mais rotea a próxima sessão para o lugar
errado, e é o tipo de mentira que este projeto mais persegue.

Cada item ganhou o veredito de uma auditoria feita **contra o código**, não contra a
memória: `grep` do símbolo em `src/main`, migração em `servidor/migracoes/`, régua do §6
do `CLAUDE.md` (chamador alcançável em runtime; classe testada sem chamador é *escrita*).

| | significado |
|---|---|
| ✅ **FEITO** | existe e tem chamador ou migração aplicada. O texto abaixo dele é **história**, não estado |
| 🟡 **PARCIAL** | metade existe. Quase sempre: servidor pronto, cliente sem porta |
| ⬜ **ABERTO** | não começou, e o texto continua literalmente verdadeiro |
| 🚫 **OBSOLETO** | a decisão mudou. **Executar o item seria regressão** — leia o motivo antes |

**Os dois OBSOLETO merecem leitura, porque parecem pendências.** O KWS por preset foi
cortado em `specs/fase-2-gatilho-por-voz.spec.md:420` ("não há preset streaming em pt") e
substituído pelo detector acústico treinado. E a "recusa honesta" que diria *"você não é
da guarnição 3"* foi rejeitada no desenho: `Utterance.kt:120-129` documenta que dois
textos distintos — "não existe" e "existe e você não é membro" — transformariam o produto
num oráculo sobre a estrutura da corporação. A recusa audível existe; o texto nominal não
volta.

**O padrão que a auditoria revelou** aparece nos 🟡 da Fase 3: `rastro_do_par` e
`quem_me_consultou` têm migração aplicada e **zero chamador Kotlin**. É "construir, testar
e não ligar" pela sétima vez, agora do lado do servidor — capacidade que existe no banco e
não tem porta no produto.

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

- [P1] ✅ **FEITO** — Passar `sampleRateHz = 16_000` na construção de `RadioTatico` (`RadioTatico.kt:88`
  é o default de 8 kHz que ninguém sobrescreve) e alinhar o codec — esforço: 0,5 sessão —
  depende: nada.
- [P1] ✅ **FEITO** — `AudioTrack` único e serial na recepção, substituindo a criação por quadro de 20 ms
  em `GlassesAudioManagerImpl` — esforço: 1 sessão — depende: 16 kHz.
- [P3] ✅ **FEITO** — Porta de entrada do copiloto por **botão** em `TelaDeGuarnicao` chamando
  `cicloDeVoz()`. Não é o desenho final (o final é a Fase 2), é o caminho alcançável que
  prova que C2/C3/C4 existem — esforço: 0,5 sessão — depende: nada.
- [P1] ✅ **FEITO** — Chamar a Edge Function `transmit` a partir do Kotlin. Hoje `grep "functions/v1"
  --include=*.kt` devolve zero, logo `transmissions` nunca recebe INSERT e
  `HistoricoDoCanal.falas()` devolve lista vazia **sempre**: o fio do canal mostra só
  inserções otimistas que somem na recarga — esforço: 1 sessão — depende: nada.
- [SEG] ✅ **FEITO** — Deletar `supabase/functions/locate (apagada)` e derivar identidade do JWT em `transmit.ts`
  e `ack.ts`. `locate.ts:21-23` aceita `solicitante_id` do corpo e chama
  `private.posicao_relativa` com `service_role`, reabrindo na borda a trilateração que a
  migração 0006 fechou no banco — violação direta de regra dura do `AGENTS.md` — esforço:
  0,5 sessão — depende: nada. Melhor relação risco/esforço do projeto.
- [UX] ✅ **FEITO** — Devolver o âmbar ao uso único, que é a regra escrita no próprio
  `ui/tema/Cores.kt` ("o âmbar tem um significado só: você está no ar"). Hoje `Cores.NoAr`
  aparece em `MapaDeRuas.kt:420,432,455`, `TelaDoMapa.kt:245,246,255`, `TelaDeLogin.kt:203,240`
  e `TelaDeGuarnicao.kt:172` — esforço: 0,5 sessão — depende: nada. Entra aqui porque custa
  quase nada e conserta toda captura de tela que for para o documento.
- [REFAT] ✅ **FEITO** — `CopilotService`: `stopSelf()` em `:80` e `:89` acontecem antes de qualquer
  `startForeground()` (que só existe em `:133`/`:135`), e `parar()` usa `startService` em
  `:215` em vez de `startForegroundService` — esforço: 0,3 sessão — depende: nada. Duas
  linhas, e o sintoma é crash de ciclo de vida em aparelho que a equipe não escolheu.
- [TRANSVERSAL] ✅ **FEITO** — Reler a proposta da Etapa 1 e conferir se ela menciona WhatsApp ou IA em
  nuvem. §14.1 veda mudança de domínio; melhoria dentro do domínio está confirmada com os
  avaliadores (D6). O documento é continuidade com detalhamento, nunca pivô — esforço: 0,5
  sessão — depende: nada.
- [TRANSVERSAL] ⬜ **ABERTO** — Escrever o documento e o deck no template da organização (20 a 22/08): os
  três pilares, a arquitetura em camadas, IA 100% local com os modelos que estão de fato no
  APK, a política de dados em duas camadas, e a tabela "o que o servidor vê / o que não vê"
  **com os itens ruins na coluna HOJE** — esforço: 2,5 sessões — depende: todos os itens
  acima, porque o documento descreve o que roda.
- [TRANSVERSAL] ⬜ **ABERTO** — Meia página de análise de risco voluntária (art. 38 da LGPD): risco
  identificado, medida adotada, risco residual assumido — esforço: 0,3 sessão — depende:
  nada. Artefato curto que separa nota mediana de nota alta em Considerações éticas (20 pts).

**Aceite.** Instalação limpa num aparelho zerado. (a) Uma fala transmitida por PTT é
reproduzida em tom e duração corretos — verificável por espectrograma comparando entrada
e saída, com o pico de F0 dentro de 5% do original. (b) Um toque no botão "Copiloto"
produz resposta falada sem outro toque. (c) O fio do canal exibe a fala vinda de
`transmissions` **depois de recarregar a tela** — prova de que a Edge Function foi
chamada. (d) **⚠️ PROPOSTA de correção do critério (17/08) — espera decisão humana.**
~~`grep -r "locate" supabase/functions/` não devolve arquivo.~~ Passa a ser: `find
supabase/functions -name "locate*"` não devolve nada, **e** `grep -rn "solicitante_id"
supabase/functions/` não devolve linha de **código**. *Motivo:* a função **está
apagada** — só existem `transmit/` e `ack/`. O critério antigo reprova por causa de
dois **comentários** que explicam por que ela foi apagada (`transmit/index.ts:18,20`,
`ack/index.ts:11,13`), e um `grep` por substring não distingue função de comentário.
O conserto óbvio — reescrever o comentário para não conter a palavra — seria **fazer o
teste passar** em vez de consertar algo: aquele comentário é o que impede alguém de
reintroduzir `solicitante_id` no corpo, que é a trilateração que a migração `0006`
fechou. O critério novo testa o que o antigo queria dizer. (e) `grep -rn
"Cores.NoAr" app/src/main` só aparece em código de transmissão. (f) Documento e deck
enviados até 22/08, cada afirmação de capacidade correspondendo a um caminho alcançável.

**Destrava.** A continuidade do projeto. Sem passar no Segundo Filtro (23 a 29/08,
resultado em 31/08), nenhuma fase seguinte é executada. É a única fase cujo custo de
falha é total.

---

### FASE 1 — Barramento de áudio único e o que mede as metas ✅ CONCLUÍDA (aceite (a)-(f) medido em 17/08)

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
`encoder/decoder/joiner-epoch-12-avg-2-chunk-16-left-64.onnx`. O número de 320 ms de latência algorítmica para o
chunk-16 **foi procurado no artefato em 17/08 e NÃO EXISTE lá**: ele não deriva do nome do
arquivo e o AAR não o contém. Segue NÃO VERIFICADO, e não pode virar linha de spec.
E mesmo confirmado ele não responderia a pergunta certa — 320 ms seria só o enchimento de
chunk; o relógio da meta ainda paga buffer do SCO/HFP, fbank, e o compute de encoder+joiner
no aparelho. O que a meta precisa é de **medição**, não de documentação. Se ele se confirmar, um alvo de 300 ms é impossível por construção com estes
artefatos, e descer dele exige um modelo chunk-8 que os presets do AAR não trazem.

**Correção de fato na spec, antes de qualquer diff.** `specs/gatilho-por-voz.spec.md`
afirma que o único preset do AAR é chinês. São dois, e o segundo é inglês. Isso derruba um
risco aceito da spec como está escrita e transforma o KWS de otimização hipotética em
caminho real — ainda que com recall desconhecido em pt-BR.

**Itens**

- [TRANSVERSAL] ✅ **FEITO** — Revisão da spec com a aprovação D1 escrita e datada dentro dela, mais as
  três correções: dois presets de KWS (não um), teto de **30 s** no item 13 — que hoje diz
  12 000 ms enquanto `SessaoPtt.kt:234` já declara `DURACAO_MAXIMA_MS = 30_000L` — e faixa
  de duração do enunciado subindo de 0,6–2,5 s (`spec:149`) para 1,2–4,0 s, porque a frase
  ficou mais longa — esforço: 0,5 sessão — depende: nada. **A aprovação precisa estar na
  spec, não só na conversa**: `CLAUDE.md` diz que sobrepor regra dura é decisão humana, e
  `docs/PADROES_DE_ENGENHARIA.md:190` continua dizendo o contrário até ser editado.
- [P3] ~~Ligar o detector de ativação — sem chamador em `src/main`~~ **FEITO em 20/08.**
  `EscutaDeAtivacao` no `CopilotService`, sob a mesma regra (`PowerPolicy.hfpAberto`) que decide
  o tipo `MICROPHONE` do FGS. O buraco era maior: os pesos viviam em `androidTest/assets`, e
  `preparar()` em produção teria devolvido `false` — o teste instrumentado lia os assets do
  próprio APK de teste, verde sobre um caminho que o produto não percorre. Medido no aparelho:
  1500 quadros em 30,0 s (50/s exatos); com PTT de 6 s, 300 calados = 6,0 s exatos. Falta o
  **ciclo de voz no serviço**: hoje a escuta e o earcon sobrevivem à tela, o comando não.
- [P3] ✅ **FEITO** — Silero VAD substituindo o detector por energia RMS. `SileroVadModelConfig` está no
  AAR (verificado: a classe existe em `com/k2fsa/sherpa/onnx/`). **Duas instâncias, não
  uma**: a do gatilho quer segmentos curtos, a da transmissão precisa tolerar 30 s via
  `maxSpeechDuration`. Confirmar a assinatura por `javap` antes de escrever — esforço: 1
  sessão — depende: barramento.
- [P3] ✅ **FEITO** — Contexto do Whisper quente entre invocações: hoje `cicloDeVoz` faz `Modelos.whisper()`
  e `release()` por ciclo, recarregando 77,7 MB (`ggml-tiny.bin` tem 77 691 713 B em
  `app/src/main/assets/models/`). Vira `object` de processo com liberação por política
  térmica — esforço: 1 sessão — depende: quebra do `DiagnosticsViewModel` — esforço: 1
  sessão.
- [P3] ✅ **FEITO** — Verificador do gatilho: VAD abre janela → whisper pt transcreve → casamento integral
  contra léxico fechado → grupo resolvido → earcon → piso → BIP → quadros — esforço: 2
  sessões — depende: VAD + contexto quente + telemetria.
- [P3] 🚫 **OBSOLETO** — KWS como adiantamento do earcon, atrás de flag, com o preset inglês e a grafia
  fonética de "Claryon". Último item da fase porque é o único que sai sem quebrar nada —
  esforço: 1 sessão — depende: verificador funcionando.
- [P1] ✅ **FEITO** — Seleção de talk group por voz, em três diffs: migração `0011` com coluna
  `rotulo_falado text` única por `unit_id` em `talk_groups` (nunca derivar o número por
  substring de `nome` — `'GTA-3 Alfa'` casaria "3" por acidente); carga do mapa
  `{rotulo_falado → id}` no login, que a RLS já limita ao que o agente pode ver; e
  `RadioTatico.trocarDeGrupo(id)` reconectando o transporte **sem tocar em `AudioDoAgente`**
  — esforço: 2 sessões — depende: verificador.
- [P1] ✅ **FEITO** — Matar o canal fixo: `CANAL_DEMO` e `NOME_DO_CANAL` em `MainActivity` e o
  fallback `TALK_GROUP_PADRAO` em `RadioViewModel` — **mais** `TALK_GROUP_DEMO` em
  `MapaViewModel`, um terceiro literal gêmeo que a auditoria achou: hoje o mapa e o rádio
  apontam para o mesmo UUID por digitação, não por referência — esforço: 0,3
  sessão — depende: seleção por voz.
- [P3] 🚫 **OBSOLETO** — Recusa honesta e audível: falar um grupo a que o agente não pertence responde "você
  não é da guarnição 3", não silêncio. Descarte silencioso é para gatilho não reconhecido;
  autorização negada merece resposta — esforço: 0,3 sessão — depende: seleção por voz.
- [P3] ✅ **FEITO** — Fecho por silêncio, escrito com honestidade na spec: *o sistema detecta ausência de
  fala, não ausência da fala do agente* — o isolamento depende do beamforming dos óculos.
  Parada por toque continua existindo e é a única que não depende do microfone — esforço:
  0,5 sessão — depende: VAD.
- [P1] ~~Teto de 30 s avaliado **fora** do `collect`~~ **FEITO em 17/08.** `withTimeout`
  descontando o já decorrido, para o teto continuar sendo "30 s desde o toque" e não "30 s
  de áudio ao vivo". O dano real era pior que o descrito: sem o evento `LimiteDeDuracao`,
  `GatilhoPtt.pressionadoEm` ficava setado e **todo toque seguinte era recusado** — o PTT do
  agente morria até a tela fechar. Travado por `fonteQueParaDeEmitir_naoSeguraOCanalParaSempre`.
- [P3] ✅ **FEITO (20/08), com desfecho diferente do pedido** — `WakeWordDetector` foi **apagada**, não implementada: zero implementações, e a abstração afirmava um ponto de troca inexistente. Quem faz o trabalho é `EscutaDeAtivacao`. `PowerPolicy` religada, tornando o modo
  **Standby** alcançável (item 5 do `ESTADO.md`) — esforço: 1 sessão — depende: KWS ou VAD.
- [P3] ✅ **FEITO** — Gazetteer de logradouros em produção: `configurarGazetteer` só é chamado em teste —
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

- [P1/SEG] ✅ **FEITO** — Canal Realtime privado amarrado ao JWT do agente. Hoje o transporte autoriza só
  pela chave anon do APK e o protocolo não envia `access_token`: qualquer portador do APK
  entra em `realtime:tg-<uuid>` e recebe todos os quadros e indicativos. **Confirmar a API
  de canal privado/`setAuth` na doc oficial do Supabase antes de qualquer diff** — esforço:
  1,5 sessão — depende: nada. Maior risco do sistema pelo menor esforço.
- [P1] ✅ **FEITO** — `ClienteDePisoRemoto` no lugar de `ClienteDePisoLocal`: o `floor_grants` atômico de
  `0005_controle_de_piso.sql` existe, está concedido e nunca foi usado. Sem isso não há
  rede, há aparelhos falando por cima — esforço: 1 sessão — depende: JWT no canal.
- [SEG] ✅ **FEITO** — Indicativo derivado do JWT no protocolo, nunca do payload. Hoje ele é string livre
  não verificada, então personificação é possível — e um P1 forjado em nome de outra
  guarnição toma o canal por desenho — esforço: 1 sessão — depende: JWT no canal.
- [SEG] ✅ **FEITO** — Coluna `ativo` em `agents`, conferida dentro de `private.current_agent_id()`
  (`0002_rls.sql:37-45`). Toda política de linha, todo RPC e o controle de piso passam por
  essa função: um UPDATE derruba o agente de canal, piso, posição e consulta na mesma
  transação. Revogação institucional é item que a banca procura por nome — esforço: 0,5
  sessão — depende: nada.
- [P1] ✅ **FEITO** — Acumulador do PCM transmitido em `SessaoPtt`, derivado dos dois pontos únicos por
  onde o áudio passa (pré-roll e `collect` ao vivo). A invariante é transcrever **os bytes
  que foram ao ar**, não os que foram capturados — esforço: 1 sessão — depende: barramento.
- [P1] ✅ **FEITO** — Whisper disparado no `finally` de `SessaoPtt`, **fora** do `withTimeoutOrNull` e em
  escopo de aplicação, para não competir com a codificação ao vivo nem morrer ao sair da
  tela — esforço: 1 sessão — depende: acumulador + contexto quente.
- [P1] ✅ **FEITO** — Quarto evento `fala.transcricao` no protocolo (hoje há três) e roteamento no
  receptor chaveado por `transmissaoId`, **fora** do laço de reprodução — o texto não pode
  viajar no anúncio, que sai antes da fala — esforço: 1,5 sessão — depende: acumulador.
- [P2/REFAT] Dono único da escrita de posição: `ColetorDePosicao` como único escritor.
  Resolve num diff o custo de 720 escritas/h, a escrita redundante, o apagamento de
  `speed_mps` e a falsificação de frescor — esforço: 1 sessão — depende: quebra do
  `DiagnosticsViewModel`.
- [P2] ~~Batimento alcançável com o agente parado, idade real da correção carimbada no
  servidor (`medida_em`, não hora do upload), porta de precisão com teste de salto, e
  `ultimaPosicao()` escolhendo a **melhor** correção e não a mais nova~~ **FEITO em 20/08,
  as quatro cláusulas.** (a) O batimento era **inalcançável**: o `minDistance` do
  `requestLocationUpdates` suprime a entrega — *"the potential location update will not
  occur"*, AOSP — então agente parado não recebia callback e a linha do batimento nunca
  rodava. Medido no emulador, parado, 3,5 min: **5 publicações com o conserto, 1 sem**.
  (b) `0020` + `private.instante_da_medicao`; o cliente manda **duração** de
  `elapsedRealtimeNanos`, nunca instante — `now() - greatest(0, idade)` não produz futuro
  por construção. Quatro leitores migrados juntos. (c) `PortaDeCorrecao`, relativa e com
  incerteza combinada, mais a **válvula de 3 recusas** sem a qual um salto verdadeiro
  congela o marcador. (d) `EscolhaDeCorrecao`, idade antes de precisão. 24 testes JVM
  novos, cada um com contra-teste; verificador `0009` 17/17.
- [P2] ✅ **FEITO** — Arredondamento de distância dentro de `consultar_posicao` e `posicoes_do_grupo`. O
  arredondamento para 50/100 m existe em `locate.ts` e está morto; a função viva devolve
  precisão métrica crua — esforço: 0,3 sessão — depende: nada.
- [SEG] ✅ **FEITO** — **Camada 1 — corregedoria.** `private.turnos` com índice único parcial de turno
  aberto por agente, `public.iniciar_turno()`/`encerrar_turno()`, `publicar_posicao`
  **recusando escrita fora de turno aberto**, encerramento automático por inatividade, e
  `private.trilha_de_posicao` particionada por dia, sem GRANT para `authenticated` e sem
  índice geográfico — esforço: 2 sessões — depende: dono único. Sem o encerramento
  automático, "esqueci de encerrar" vira 24 h de rastreamento e a defesa jurídica inteira
  cai; ele é parte do controle, não refinamento.
- [SEG] ✅ **FEITO** — **Camada 2 — janela de 30 minutos para pares.** `public.rastro_do_par(indicativo)`
  devolvendo série de distância e azimute dos últimos 30 min, com a idade de cada ponto
  declarada, sujeita à mesma reciprocidade que a consulta de posição já pratica — esforço:
  1 sessão — depende: camada 1.
- [SEG] ✅ **FEITO** — Job de retenção executando os dois prazos e o `expira_em` de `transmissions`, que
  hoje é campo lógico sem executor. Prazos como constante única numa migração, alteráveis
  em uma linha. **Confirmar a assinatura de `cron.schedule` na doc do Supabase antes do
  diff** — esforço: 1 sessão — depende: camada 1.
- [SEG] ✅ **FEITO** — Registro de acesso nas duas portas: linha por consulta em `consultar_posicao`,
  sessão em `abrir_mapa`/`fechar_mapa` para a porta de alto volume. **Nunca gravar a
  resposta**, e o autor sai de `private.current_agent_id()`, jamais do indicativo do
  protocolo — log com autor forjável produz prova falsa e é pior que log nenhum — esforço:
  1 sessão — depende: indicativo do JWT.
- [SEG] ✅ **FEITO** — `public.quem_me_consultou()`: o titular vê quem o consultou — esforço: 0,3 sessão —
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

1. ~~**O ggml deste projeto é compilado como biblioteca compartilhada.**~~ **RESOLVIDO em
   21/08** — e a causa era mais rasa do que este item supunha. Os três `.so`
   (`libggml.so`, `libggml-base.so`, `libggml-cpu.so`) existiam porque
   `whisper/ggml/CMakeLists.txt:74` põe `BUILD_SHARED_LIBS_DEFAULT` em ON fora de
   Emscripten e MinGW, e a `option()` da linha 85 só herda esse default: **ninguém
   escolheu publicá-los.** Passando `-DBUILD_SHARED_LIBS=OFF` pelos argumentos do CMake
   no `core-voice`, o ggml linka estático dentro de `libwhisper.so` e o APK fica com
   **zero libggml** — conferido por `unzip -l` no debug e no release, e por
   `llvm-readelf -d`, que já não lista libggml em `DT_NEEDED`. Assim o llama.cpp fica
   livre para usar a configuração oficial dele em vez de receber remendo em CMake
   vendorizado. O custo estimado aqui ("alguns MB de disco duplicado") também estava
   errado, na direção contrária: o payload nativo **encolheu 884 KB** (−14%), porque
   `--gc-sections` + `-flto` fazem cada `libwhisper` puxar só o ggml que chama. Detalhes
   e a armadilha de política CMP0077 em `DECISIONS.md` (21/08).
2. **Llama 3.2 1B e 3B são texto puro.** O pedido inclui interpretação de foto e vídeo
   depois; visão só existe em 11B/90B, que não cabem no aparelho. Escolher 1B hoje é
   escolher trocar de família, tokenizador e prompt depois — não "trocar um arquivo".
   Somem-se a licença própria do Llama (não é open source) e a política de uso aceitável
   que veda armas, num produto cujo caso de uso-bandeira é manejo de pistola: isso é
   parecer jurídico, e ele vem antes do código, não depois.

~~Por isso o motor **não está decidido aqui**~~ — **DECIDIDO em 20/08 (humano): llama.cpp**,
com a licença adiada por decisão explícita. Integrado em 21/08 em `core-llm`, build de
fonte, estático, **zero libggml no APK** e **+8,25 MiB de release**. O que a integração
mediu, e que este texto acima não previa, está em `DECISIONS.md` (21/08): a Llama 3.2 1B
Q4_K_M **erra em pt-BR sobre norma** (chamou infração administrativa de "crime grave" e
produziu *"não há nada que aconteça com quem dirige embriagado"*), e o filtro de lastro
**não vê negação**. O motor está resolvido; o **modelo** não.

**Itens**

- [P3] `core-knowledge`, módulo novo que depende só de `core-common` e **não** declara
  dependência de `core-agent` — esforço: 0,5 sessão — depende: nada.
- [P3] Corpus curado com o número do documento em cada trecho, de material de licença
  compatível com embarque em APK. **Este é o item de maior risco de cronograma da fase** e
  não é tarefa de engenharia — esforço: 1 sessão de curadoria — depende: decisão sobre a
  fonte.
- [P3] Embedder + índice vetorial local + recuperação por similaridade com **limiar**.
  Abaixo do limiar, o copiloto diz que não sabe — esforço: 2 sessões — depende: corpus.
- [P3] Etapa A alcançável por voz: pergunta → recupera → Piper cita o documento — esforço:
  0,5 sessão — depende: índice + Fase 2.

  > ⚠️ **O exemplo deste item foi medido em 21/08 e NÃO tem resposta no corpus.** *"minha
  > Glock 19 emperrou"* recebe confiança **0,070** e é **recusada** — abaixo do limiar de
  > 0,30. E a recusa está **certa**: manejo de arma é manual de fabricante, e o que está
  > embarcado é **lei federal e de trânsito** (CTB, CPP, CP, Drogas, Desarmamento). O
  > corpus responde *"posso apreender a moto sem placa"*, não *"minha arma emperrou"*.
  >
  > Isto não é defeito do índice: é o aceite pedindo prova com uma pergunta fora do
  > domínio embarcado. **Decisão pendente:** ou o corpus ganha manual de fabricante (e aí
  > entra a questão de licença de distribuição), ou este exemplo sai do aceite e é
  > substituído por um que o corpus cobre. Deixar como está faz a fase ser reprovável por
  > uma demonstração que nunca poderia funcionar.
  >
  > A palavra "lê" também saiu: a fala é a **citação** (≤7 palavras, o invariante do §4 do
  > `CLAUDE.md`). Leitura verbatim está proposta em `specs/leitura-de-norma.spec.md`.
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
circulares e âncoras assimétricas.

O que a auditoria de 21/08 corrigiu sobre isso está no item do botão de recentrar,
abaixo.

**Itens**

- [UX] Auditoria do sistema atual com a skill `audit-design-system`, produzindo a lista de
  divergências entre `Cores.kt`/`Tema.kt` e o que as sete telas de fato usam — esforço: 0,5
  sessão — depende: nada.
- [UX] **Botão de recentrar no estilo Uber Driver.** O item anterior mandava *remover* o
  alvo circular de `TelaDoMapa.kt:245-255` sob a regra de que alvos circulares foram
  rejeitados três vezes. A auditoria de 21/08 mostrou que aquilo **não é alvo**: é o ícone
  do `BotaoRecentrar` — a mira de "centralizar em mim", com quatro riscos cardeais.
  **Decisão humana de 21/08: fica, como botão de recentrar.** A proibição de alvo circular
  continua valendo para MARCADOR de mapa (que é desenhado em `MapaDeRuas.kt`, não aqui); um
  controle de recentrar é affordance, não ornamento, e o agente já a lê sem aprender —
  esforço: 0,3 sessão — depende: nada.
- [UX] Tela de guarnição como painel: canal ativo nomeado pelo `rotulo_falado` real (não
  mais `"GTA-3 Alfa"` fixo), estado do piso, estado da rota de áudio, e quem está falando —
  esforço: 1 sessão — depende: Fase 2 e Fase 3.
- [UX] Escala tipográfica de dado tabular: indicativo, distância, rumo e idade alinhados por
  coluna, com tabular figures. É o que faz a tela ler como instrumento e não como app de
  mensagem — esforço: 0,5 sessão — depende: auditoria.
- [UX] Movimento com a skill `motion-design`, e só onde carrega informação: o pulso do "no
  ar", a transição de piso concedido/negado, o esmaecimento do marcador por idade. Nada
  decorativo — esforço: 1 sessão — depende: itens acima.
- [UX] Teste de captura por tela para não regredir depois — esforço: 0,5 sessão — depende:
  itens acima.
- [SEG] ~~Permissão de câmera do DAT pedida em produção~~ **FEITA em 21/08.**
  `PermissaoDaCameraDoDat` (core-glasses) pelo contrato oficial, chamador em
  `TelaDePermissoes`, composta pela `MainActivity` em toda instalação nova. Rodou no
  emulador: resolve honestamente para "Óculos não encontrados." e o portão nunca bloqueia.
  **Achado junto:** o DAT NÃO exige a permissão `CAMERA` do Android — só `BLUETOOTH` e
  `BLUETOOTH_CONNECT`. O texto de `PermissoesEssenciais.CAMERA.porQue` engana e é decisão
  de spec (§7).
- [SEG] ~~Coletar `Stream.errorStream` e tratar `STOPPED` como terminal~~ **FEITO em
  21/08, com uma ressalva.** O coletor entra ANTES do `start()` (obrigatório:
  `replay=0`, erro anterior à assinatura some), e `STOPPED` só é terminal depois de
  `STARTED`/`STREAMING` — porque o stream NASCE em `STOPPED` e o SDK retenta. ~~**A
  ressalva:** `DatGlassesFacade` é construída com `viewModelScope` e morre com a
  Activity.~~ **FEITO em 21/08:** `SessaoDosOculos` é dona de processo, provada no
  aparelho (a Activity morreu, `dumpsys` confirmou 0 instâncias, e o vigia instalado
  antes continuou vivo). E o bloco achou algo maior que a tarefa: `startSession()`
  tinha **ZERO chamadores em `src/main`** — a sessão do DAT nunca era aberta em
  produção, só pelo painel `debug` e por quatro testes. Mover só o dono trocaria uma
  capacidade morta por outra, então o abridor entrou junto.
- [SEG] Assinatura do manifesto de custódia com chave no Keystore, assinatura incremental
  sobre o hash corrente — esforço: 1 sessão — depende: cofre instanciado.
- [REFAT] **Trava contra teste que fica verde sem rodar** — esforço: 0,5 sessão —
  depende: nada. Substitui a varredura única, porque **varredura única apodrece**: em UM
  dia (21/08) achamos quatro casos — `WhisperCppSttTest` sempre pulado reportando `OK` em
  0,008 s; `caos_mdk.sh` imprimindo "N/N verdes" com `tests="0"`; `CaosDoAparelhoTest`
  com `@Ignore` no nível da CLASSE; e `UtteranceTest.todos` dizendo "todos os resultados"
  com quatro faltando. A superfície é **110 `assumeTrue` e 14 `@Ignore`** — 124 lugares
  onde um teste pode passar sem executar. A trava: o build declara o número de pulados e
  quebra quando ele sobe sem justificativa escrita, e nenhum script pode reportar verde
  sobre `tests="0"`. Trocar faxina por trava.
- [REFAT] Limpar os ramos mortos que a trava acima expuser — esforço: 0,5 sessão —
  depende: a trava.
- [PERF] **Razão de latência ponta a ponta, instrumentado** — esforço: 0,5 sessão —
  depende: nada. **É o primeiro item de desempenho, e ele vem antes de otimizar qualquer
  coisa.** Existe `Telemetry` com estágios em `VoiceCycle.kt`, mas não há número ponta a
  ponta registrado em lugar nenhum. O razão precisa imprimir, por ciclo real:
  `fim da fala → VAD fecha → STT → roteador → recuperação → síntese Piper → 1ª amostra`.
  Mediana e p90 sobre ≥10 ciclos, nunca uma amostra. **Sem isso, escolheríamos entre cinco
  estágios no chute** — e este projeto já otimizou no escuro duas vezes: os "14,9 s do STT"
  eram build debug sem `-O`, e os 965 ms do `SyncManager` continuaram depois do conserto
  porque a E/S era de outro lugar.
- [PERF] **Piper medido isolado** — esforço: 0,3 sessão — depende: nada.
  `PiperTts.synthesize()` devolve `PcmAudio` **completo**: nenhuma amostra sai antes da
  última ser gerada. Preciso do custo de "Art. 306, Lei 9.503" (4 palavras, a resposta real
  da Etapa A) e de uma frase de 7 (o teto). **A pergunta é se o custo é de PARTIDA ou
  PROPORCIONAL**: se for de partida, streaming não ajuda e a conclusão é essa; se for
  proporcional, streaming do primeiro bloco corta o tempo **percebido**, que é o que o
  agente sente.
- [PERF] **Caça a desperdício no laço quente** — esforço: 0,5 sessão — depende: o razão.
  Isto roda no bolso de um agente em serviço, o turno inteiro. Alvos, com número antes e
  depois: alocação por quadro de 20 ms, PCM convertido de ida e volta entre `Short` e
  `Float`, reamostragem feita duas vezes, trabalho pesado na Main (o projeto já pegou o
  Opus e o `SyncManager` ali), e o custo por hora do detector de ativação, que roda **50
  quadros/s o turno inteiro**. Regra: otimização que muda a saída **não é otimização**, é
  outra feature — e para no diff de spec.
- [PERF] **Orçamento da demonstração, decidido por MEDIÇÃO** — esforço: 0,3 sessão —
  depende: o razão. O aceite da Fase 4 é ≤4 s do fim da fala até a resposta falada. Medido
  em 21/08: recuperação **913 µs**, redação pelo LLM **4 680 ms de mediana** com p90 de
  8 532 ms. São cinco mil vezes de diferença, e **nenhum ajuste de prompt ou quantização
  chega perto de simplesmente não chamar o LLM**. Recomendação registrada: **Etapa A
  sozinha no palco**. Ressalva honesta: os 4 680 ms são de EMULADOR, e emulador arm64 no
  Mac não é celular de campo — o número precisa de medição no aparelho alvo antes de
  fundamentar decisão.
- [P3] **Saber dizer que não sabe — e a assimetria já está medida** — esforço: 1 sessão —
  depende: nada. Este é requisito de produto, não de qualidade: um copiloto de segurança
  pública que responde com confiança o que não sabe é pior que um que cala. As 100
  perguntas de abordagem de 21/08 mediram os dois lados:
  **a RECUPERAÇÃO sabe recusar** — as 14 perguntas de gramatura pura foram todas
  recusadas, e *"quantos gramas de maconha configura tráfico"* fica em 0,130 contra limiar
  0,30, porque a Lei de Drogas **não fixa gramatura** (4 ocorrências de palavra de peso em
  1817 trechos, nenhuma nela);
  **a GERAÇÃO não sabe** — 25 de 268 gerações inventam número que a lei não tem, e o
  guarda aprovou 23. O que falta construir é a régua do guarda: ela hoje reprova texto cujo
  `\d+` não esteja na fonte, e **reprovou zero de 268**, porque dígito comum já está na
  fonte ("1,5" vira `1` e `5`, presentes em "§ 1º" e "§ 5º"), número por extenso não tem
  dígito, e reatribuição usa cifras da fonte trocadas de grandeza. Some-se a cegueira a
  negação, já registrada. **Dois buracos independentes, e modelo maior só reduz frequência.**
- [PERF] **A prosa também é latência.** — esforço: 0,3 sessão — depende: o razão. Se a
  Etapa B entrar algum dia, ela deve **escrever enquanto o Piper fala a citação**, não
  depois: a citação existe <1 ms após o STT e o earcon já sai em 305 ms. Pipelinar
  transforma 4,7 s de silêncio em resposta imediata seguida de detalhe — e degrada bem,
  porque um P1 que preempta a prosa não apaga a resposta que o agente já recebeu.
- [UX] **A câmera: ligar ou tirar da narrativa** — esforço: 0,3 sessão (decisão) —
  depende: nada. Os dois [SEG] acima foram fechados e no caminho se descobriu que
  `PlacaOcr.lerPlaca` e `startCameraStream` têm **zero chamadores**: a leitura de placa não
  quebra em campo porque **nunca acontece**. A fase escolhe **uma**: ligar o caminho
  (é a Fase 6) ou tirar leitura de placa do roteiro. Prometer no palco o que o `grep` diz
  que não existe é a pior forma de descobrir isso.
- [SEG] **Medir o vazamento do alto-falante open-ear** — esforço: 0,3 sessão — depende:
  óculos reais. Decisão humana de 21/08: o resultado de consulta de placa **pode** ser
  falado, com a premissa de que o vazamento exige silêncio e volume alto. A premissa é
  razoável e **barata de confirmar**: volume operacional, 1 m e 2 m, ambiente silencioso →
  um número em `docs/VERIFICACOES_COM_HARDWARE.md`. Não é para reabrir a decisão; é para
  ela parar de depender de premissa.
- [UX] **A tela consegue exibir alerta que nunca foi despachado?** — esforço: 0,3 sessão —
  depende: nada. `AlertarOcorrencia` respondeu por **188 dos 252** artigos de lei que viram
  ação pelo roteador, e por **49 das 61** gerações do LLM. É o gatilho mais sensível do
  produto. A regra do "app não pode mentir" aplicada ao caminho que mais dispara.
- [TRANSVERSAL] **Ensaio SECO no primeiro dia da fase, com o que existir.** — esforço:
  0,5 sessão — depende: nada. **Este item foi movido para a frente em 21/08, e o motivo é
  aritmético:** a fase vai de 15/09 a 17/09 e o hackathon é 18/09. Um ensaio que roda no
  dia 17 não deixa tempo para consertar o que ele achar. A saída dele **é a lista de
  conserto da fase**, não um selo. E ensaio acha coisa grande: a mediana de 4 680 ms da
  Etapa B, medida em 21/08, mata uma demonstração ao vivo sozinha — ninguém fica nove
  segundos em silêncio no palco.
- [TRANSVERSAL] Ensaio cronometrado dos dois checkpoints obrigatórios, cada um em ≤ 10 min,
  com roteiro escrito e aparelho já pareado — esforço: 1 sessão — depende: o ensaio seco e
  o que ele achar. Agora é **confirmação**, não descoberta.
- [TRANSVERSAL] Ensaio do pitch, reescrita final de `ESTADO.md`, `git push origin master` —
  esforço: 1 sessão — depende: tudo.

**Aceite.** App instalado do zero num aparelho limpo executa os dois roteiros de checkpoint
em ≤ 10 min cada, cronometrado, com a permissão de câmera do DAT pedida e concedida no
primeiro uso. `grep -rn "Cores.NoAr" app/src/main` só devolve código de transmissão.
Nenhum `drawCircle` decorativo permanece. Nenhum estado exibido na UI corresponde a
capacidade inexistente — verificável por grep dos termos removidos.

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
- ~~**Regra Zero pode dobrar o tempo das Fases 3 e 4.**~~ **As três confirmações foram
  feitas** — canal privado/`setAuth` e `cron.schedule` em 18/08, motor de LLM em 21/08. E o
  risco se pagou: em 21/08 a confirmação do llama.cpp corrigiu **duas** coisas que eu tinha
  de memória e que teriam virado código errado (o módulo chama-se `lib`, não `llama`; e o
  caminho que escrevi de cabeça deu 404). Meia sessão de confirmação continua sendo a
  primeira coisa de cada fase.
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

- ~~**1. Motor e modelo da Etapa B (Fase 4).**~~ **DECIDIDO: llama.cpp** (decisão humana em
  20/08, licença adiada por decisão explícita). A Regra Zero foi cumprida em 21/08 e mudou
  a pergunta: **não há artefato Maven a confirmar.** O caminho Android oficial do llama.cpp
  é build de FONTE — `examples/llama.android/lib/src/main/cpp/CMakeLists.txt:34` faz
  `add_subdirectory(${LLAMA_SRC} build-llama)`, e o módulo (que se chama `lib`, não
  `llama`) declara `externalNativeBuild { cmake { path(...) } }`. É a mesma forma que o
  whisper.cpp já tem aqui, então não há coordenada, versão nem `javap` a conferir: há um
  submódulo a acrescentar. O custo de "renomear alvos" que este item cobrava **não existe
  mais** — ver o fato 1 acima. **Integrado em 21/08** (`core-llm`, `-DBUILD_SHARED_LIBS=OFF`,
  zero libggml no APK). O que resta em aberto deixou de ser a licença e passou a ser **qual
  modelo**: a Llama 3.2 1B medida no emulador erra sobre norma em pt-BR, e o `.gguf` ainda
  não é submódulo (203 MB contra 43 MB do whisper — decisão de custo, humana).
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
