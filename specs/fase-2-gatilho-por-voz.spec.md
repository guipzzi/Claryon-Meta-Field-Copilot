---
feature: gatilho-por-voz-fase-2
capacidade: C1 (rádio tático) — abertura, fecho e comando sem tocar na tela
estado: **APROVADA em 2026-08-17** — as seis propostas foram decididas (ver §7)
autor: revisão humana pendente
criada: 2026-08-17
substitui: as partes B/C de `specs/gatilho-por-voz.spec.md` (itens 5 a 26)
sobrepoe:
  - "docs/PADROES_DE_ENGENHARIA.md § Rádio tático — 'nunca por palavra de ativação'"
  - "CLAUDE.md §2 — 'transcrever a fala de terceiros' (ver PROPOSTA-1: não há desenho que evite)"
  - "specs/gatilho-por-voz.spec.md:199-204 — 'na escuta sai do vocabulário' (ver PROPOSTA-2)"
  - "specs/gatilho-por-voz.spec.md:282-284 — teto de 12 000 ms (o código e o aceite dizem 30 000)"
depende_de:
  - fonte-unica-de-microfone-com-fanout
  - dono-unico-da-saida-de-audio
  - silero-vad-embarcado
  - migracao-0011-rotulo-falado
---

# Fase 2 — gatilho, abertura, fecho e teto por voz

## 0. O achado que reordena a fase inteira

**Nada impede hoje que o próprio som do aparelho abra canal.** Verificado, arquivo e linha:

- `app/src/main/kotlin/com/claryon/field/ui/CopilotoViewModel.kt:487` monta o ciclo com `pcmInput = { audio.microfonePcm(prova) }` — microfone **cru**. Os dois únicos leitores de `suprimido()` em `src/main` são `app/src/main/kotlin/com/claryon/field/radio/RadioTatico.kt:238` e `:397`.
- `core-net/src/main/kotlin/com/claryon/net/SupressorDeSaidaPropria.kt:18` enumera, por escrito, o caso **4**: *"O detector de palavra de ativação acordaria com a própria saída"*. O mecanismo existe, foi escrito para este caso, e o caminho do copiloto nunca o consultou.

Hoje isso é inofensivo **por acidente**: `VoiceCycle.runOnce()` faz `vad.segment(pcmInput()).first()` — um segmento e retorna — e o único chamador é um botão (`MainActivity` → `CopilotoViewModel.cicloDeVoz`, guardado por `_copilotoOcupado`). A Fase 2 remove exatamente essa proteção ao tornar a escuta contínua.

Consequência com todos os elos verificados: `RadioTatico.tratarRecepcao` reproduz a transmissão recebida no alto-falante **open-ear** de todo o grupo (`:462` abre a janela de supressão, que protege só a captura **do rádio**). Um agente que diga a frase de abertura com o canal aberto é reproduzido em N−1 aparelhos, a centímetros dos arrays. N−1 portões disparam. **Um enunciado tira a guarnição inteira do canal.** Não é uma taxa; é probabilidade 1.

Por isso esta spec tem um **Bloco 0** que não entrega nenhuma cláusula do aceite e sem o qual **nenhuma** das outras entra.

---

## 1. Fatos verificados nesta sessão (base do desenho)

Tudo abaixo foi lido no arquivo, não lembrado.

| Fato | Onde |
|---|---|
| Ciclo de voz consome microfone cru | `CopilotoViewModel.kt:487` |
| Supressor é `ArrayList` **sem sincronização**; `registrar`/`abrir`/`fechar`/`suprimido`/`podarAntesDe`/`limpar` sem lock | `SupressorDeSaidaPropria.kt:40,47,56,62,72,83,90` |
| A janela é registrada pela duração **presumida**, **antes** de tocar | `app/.../audio/SaidaUnica.kt` (`reproduzirComRotaESupressao`: `supressor.registrar(...)` e só então `rota?.emUso { audio.reproduzir(...) }`) |
| `RadioTatico.emitirComSupressao` registra `earcon + DURACAO_FALA_ESTIMADA_MS = 2_000L` no instante do **enfileiramento** | `RadioTatico.kt:524-542` |
| A fila **descarta** INFORMATIVO em Modo Tático (`offer` → `false`) e **cancela** o job em curso na emergência (`emCurso?.cancel()` — desde 22/08 o job cobre **síntese + reprodução**, e não só a reprodução) | `core-sound/.../SoundScheduler.kt:28`; `core-sound/.../PrioritySoundQueue.kt` (`enqueue`) |
| Os filtros do rádio **removem** quadros (`.filter`), o que a spec item 21 proíbe | `RadioTatico.kt:238,397` |
| O quadro do microfone **não carrega tempo de captura** — só `sequencia`; há `.buffer(50, DROP_OLDEST)` por consumidor (≈1 s) | `core-audio/.../FonteUnicaDeMicrofone.kt:84,100,222` |
| O teto ancora na **invocação** de `transmitir`, não no BIP | `core-net/.../SessaoPtt.kt:118` e `:211` (`withTimeout((duracaoMaximaMs - (agoraMs() - inicio))...)`) |
| `RadioTatico` **não passa** `duracaoMaximaMs` ao construir `SessaoPtt` → aceite intestável em CI | `SessaoPtt.kt:87`, `RadioTatico.kt:373` |
| `ClienteDePisoRemoto` usa `OkHttpClient()` padrão (callTimeout = 0) e `piso.pedir` é chamado **sem** `withTimeout` | `core-net/.../ClientesDePiso.kt:58`; `SessaoPtt.kt:123` |
| `enviarTexto` retorna **antes** do `ws.send` quando o socket caiu → contador "quadros no ar" daria falso PASSA | `core-net/.../TransporteRealtime.kt` (guard `if (ws == null \|\| tg == null \|\| !aberto) return`) |
| `Earcon` tem 8 valores; **`CANAL_ABERTO`, `CANAL_FECHADO`, `NO_AR`, `CANAL_NEGADO` não existem** | `core-common/.../AudioSignals.kt:21-32` |
| `Telemetry.Stage` tem 8 valores; **nenhum** de canal (`CANAL_ABERTO`, `PRIMEIRO_QUADRO`, `FECHO_POR_SILENCIO`, `FECHO_POR_TETO`) | `core-common/.../Telemetry.kt:21-30` |
| `FIM_DA_FALA_ATE_EARCON` (meta 500) é **uma fila só**, sem saber por qual caminho o ciclo veio | `app/.../voice/TelemetriaDoCicloDeVoz.kt:63,142` |
| `jaRegistradas` (`HashSet`) **nunca é podado**; `marcos`/`duracoes` são | `TelemetriaDoCicloDeVoz.kt:103,109,161,173` |
| ~~`WakeWordDetector` é interface **sem implementação**~~ **apagada em 20/08** | Quem faz o trabalho é `app/.../voice/EscutaDeAtivacao.kt`; a costura de teste é `OuvidoDeAtivacao`, no módulo do app |
| `EventoPtt.Transmitindo -> Unit` (**não há BIP**) e `Encerrada` só faz `Log.i` (**fecho é mudo**) | `RadioTatico.kt:423,444` |
| `QuadrosNaoEntregues -> Log.w(...)` e nada mais — rede caída no meio é **silêncio** | `RadioTatico.kt:443` |
| `Earcon.FALHA` já carrega 3 fatos distintos (`CanalOcupado`, `CanalPerdido`, `LimiteDeDuracao`) | `RadioTatico.kt:427,433,438` |
| `trocarDeGrupo` faz `supressor.limpar()` no meio do próprio comando do aceite | `RadioTatico.kt:313` |
| Primeira guarda de troca é `if (transmitindo())` → canal aberto por voz vira **estado absorvente** | `app/.../radio/PoliticaDeTrocaDeGrupo.kt:57-59` |
| `grupoCorrenteId` é `@Volatile var` semeado por `CanalDoPiloto.ID`, e `carregar()` **nunca o escreve** | `app/.../radio/CanaisDoAgente.kt:66-72,150` |
| `DeterministicIntentRouter.matches` é `texto.contains(padrao)` — **sem ancoragem**, em cadeia ordenada | `core-agent/.../DeterministicIntentRouter.kt:159-160` |
| `EscutaDoAgente.deveLiberar` devolve `true` para `TRIM_MEMORY_UI_HIDDEN` — e o aceite começa com "aparelho no bolso" | `app/.../voice/EscutaDoAgente.kt:93-95` |
| `sttFn` colapsa `whisper == null`, `Result.Failure` e transcrição vazia em `""` | `CopilotoViewModel.kt:497` |
| O léxico do servidor grava **dígito**: `set rotulo_falado = 'guarnicao 3'` | `servidor/migracoes/0011_rotulo_falado.sql:93` |
| `RotulosFalados.normalizar` **não** mapeia dígito↔palavra — e o KDoc registra medição de 17/08 em que o whisper devolveu `"...guarnição 4."` (dígito) | `core-net/.../RotulosFalados.kt` (companion) |

**Correção de um ataque:** a alegação de que "guarnição três" nunca casaria `guarnicao 3` (recall 0%) **não se sustenta como fato** — o KDoc de `RotulosFalados` registra uma medição no aparelho em que o decodificador produziu **dígito**. Continua sendo o risco de maior valor por minuto de bancada (item M-0 abaixo), mas entra como **medição**, não como defeito confirmado.

---

## 2. A política de falso positivo — em destaque, porque é o risco que domina

### 2.1 O que impede HOJE

**Nada.** Escrito sem eufemismo:

| Fonte de falso positivo | O que impede hoje |
|---|---|
| TTS do próprio copiloto | **Nada** no caminho do ciclo de voz (`CopilotoViewModel.kt:487` é cru) |
| Transmissão recebida, tocando no alto-falante open-ear | **Nada** — `supressor.abrir` em `RadioTatico.kt:462` protege a captura do rádio, não o ciclo |
| Earcon do próprio produto | **Nada** no ciclo; e a janela registrada é **presumida**, não real |
| Rádio VHF da viatura no ambiente | **Nada**, e não haverá: é som ambiente, o supressor só conhece o que **nós** emitimos |
| Colega falando a 60 cm (co-localização) | **Nada** — o beamforming é premissa não medida neste repositório |
| Alucinação do whisper sobre não-fala | O **Silero** (medido: ruído branco e tom puro → 0 segmentos). `no_speech_prob` **não discrimina** — voltou ~0 nos três casos, então `Transcript.confidence` não pode ser portão |

### 2.2 O que passa a impedir, depois desta spec, e em que camada

Cinco camadas, da mais barata para a mais cara. **Nenhuma delas depende de escolher bem a palavra.**

1. **Supressão real da própria saída** (Bloco 0). Janela aberta/fechada pelos instantes **reais** de reprodução, e o fluxo do portão consome a vista suprimida. Cobre TTS, earcon e transmissão recebida — as três fontes que hoje produzem FP com probabilidade 1.
2. **Silêncio digital, nunca `.filter`.** Remover quadros comprime a linha do tempo e o contador de silêncio do Silero não avança — emenda enunciados e cria posições espúrias para o casamento. `spec:311-313` já exige isso e o código de hoje o viola.
3. **Filtro aritmético de duração do segmento**, antes de invocar o verificador. É o único estágio que barra sem custar STT, e é ele que sustenta o teto de invocações/hora.
4. **Casamento integral contra léxico fechado**, fora do `DeterministicIntentRouter` (que casa por `contains` sem ancoragem). Sem distância de edição, sem casamento fonético, sem `confidence`.
5. **Membresia + piso do servidor.** `pedir_canal` (`0005:78-82`) recusa não-membro. É a única fronteira de segurança; tudo acima é UX e economia de rede.

### 2.3 O que continua descoberto, e é risco aceito escrito

- **Rádio ambiente da viatura.** Não há mitigação acústica. Medido pelo aceite das 8 h, e só por ele.
- **Co-localização.** Dois agentes na mesma viatura: o de A dispara o portão de B. O risco aceito 4 da spec foi precificado para uma frase que ninguém diz; esta spec **reprecifica** e exige medição de **disparo cruzado** (M-4). Se a taxa não for ~0, o caminho mãos-livres não entra em produção sem confirmação por nível/proximidade — que exige hardware.
- **A taxa que governa a decisão não existe.** Tarefa #23. `Aurora` 3/3 é a melhor candidata **medida**, não uma decisão.

### 2.4 Regras negativas que entram na spec para não serem "melhoradas" depois

- **Proibido** usar `Transcript.confidence` / `no_speech_prob` como portão (medido inerte).
- **Proibido** pôr a palavra de ativação no `initial_prompt` (enviesar o decodificador a favor dela é fabricar FP; e `docs/LEXICO_DO_INITIAL_PROMPT.md` mede A0 sem prompt em 12,5% de WER contra 14,3% dos braços com prompt).
- **Proibido** admitir a variante `a hora`. Se o decodificador partir "aurora" em dois tokens, é o vizinho mais provável — e é locução altíssima em pt-BR falado. Admiti-la mata o portão.
- **Proibido** casamento aproximado, fonético ou por distância de edição, em qualquer dos dois portões.

---

## 3. Comportamento

### 3.1 O vocabulário — PROPOSTA-2 (espera decisão humana)

Três vocabulários mutuamente incompatíveis convivem no repositório:

| Fonte | Frase | Problema |
|---|---|---|
| `ROADMAP.md` (aceite da Fase 2) | "Hey Claryon, guarnição 3 na escuta" | `Claryon` mediu **0/3**; "na escuta" é o protocolo do rádio |
| `specs/gatilho-por-voz.spec.md:199-204` | "Claryon, abrir canal" | mesma palavra reprovada; e não carrega o número do grupo |
| Medição de 17/08 | `Aurora` 3/3, `Oriente` 3/3, as três com consoante inicial 0/3 | não define a frase |

**Proposta:** `"Aurora, guarnição três, abrir canal"` — começa por vogal (o traço que a medição isolou), carrega o número do grupo (invariante 1 do ROADMAP), e não é locução de protocolo. **O aceite do ROADMAP precisa ser emendado junto**, ou a frase volta a ser a dele. Decisão humana: o instrumento de medição é idêntico nas duas variantes, então esta escolha não bloqueia o Bloco 0 nem o Bloco 1.

**A lista de variantes começa com um item: `{"aurora"}`.** Normalização por `RotulosFalados.normalizar` (importada, **nunca** reimplementada — `ResolvedorDeGrupo` já registra por escrito o custo de normalizar diferente dos dois lados). Uma variante só entra se **(a)** for observada como saída do decodificador para uma pronúncia real na bancada e **(b)** não aparecer no corpus de falso positivo. O teto é a própria meta: a lista para de crescer no ponto em que o FP medido encostaria no alvo.

### 3.2 Dois portões, com rigor diferente, e a assimetria é de TIPO

Um `Boolean rigoroso` é uma negação de distância do piso da guarnição, e um `if` invertido não quebra nenhum teste que não tenha sido escrito para ele.

| | `PortaoDeComando` | `PortaoDeAbertura` |
|---|---|---|
| Casamento | variante nas **N=2** primeiras palavras + ≥1 token depois | transcrição normalizada **inteira** == frase de abertura |
| Duração do segmento | só piso (descarta clique/estalo) | faixa fechada (ver 3.6) |
| Sobra da frase | vai ao roteador | **não pode existir** — sobra é recusa audível |
| Falha | `Ignorar` (silêncio) | `Ignorar` (silêncio) ou `Recusar` (audível) |
| Consequência do FP | uma consulta local indevida | **o piso da guarnição** |

`N` é **parâmetro de construtor com default 2**, e o aceite exige o **contra-teste**: a bancada roda o mesmo áudio com N=1 e N=2 e reporta os dois. N=2 cobre **inserção** espúria de token no ataque; ele **não** recupera **omissão** — janela nenhuma encontra o que o decodificador não produziu. Se a omissão dominar, N volta para 1 por medição.

### 3.3 O tipo que torna o silêncio representável — e a fronteira dele

```kotlin
// core-agent/src/main/kotlin/com/claryon/agent/PortaoDeAtivacao.kt   (NOVO, puro)
sealed interface Ativacao {
    /** Passou. [restante] é a transcrição SEM a palavra de ativação. */
    data class Comando(val restante: String) : Ativacao
    /** Não era para nós. Descarte em silêncio. NÃO é uma Intent. */
    data object Ignorar : Ativacao
    /** O verificador não existe ou falhou. AUDÍVEL — ver 3.4. */
    data object VerificadorIndisponivel : Ativacao
}
```

`Ignorar` **não é uma `Intent`**, e essa é a peça central. Enquanto o descarte silencioso vive num tipo que o executor não consegue receber, o invariante *"toda `Intent` que chega ao executor produz `ActionOutcome` e portanto som"* continua verdadeiro.

> **Invariante testável:** *silêncio é permitido apenas **a montante** do portão. Depois que o portão devolve `Comando`, todo caminho até o fim produz som.*

O portão mora em `core-agent` porque `core-agent/build.gradle.kts` depende **só** de `core-common`: portão puro, testável em JVM, sem Android. **Não** no `DeterministicIntentRouter` (que só devolve `Intent`, e a única variante disponível, `NaoReconhecida`, vira `SinalizarEFalar(FALHA, ...)` — audível, o oposto do exigido) e **não** como decorador de `IntentRouter` (o FP do caminho de comando é contado em `mark(INTENT_ROUTED)`; um enunciado descartado que já passou pelo roteador contamina o próprio número que decide se a palavra serve).

### 3.4 Falha do verificador nunca vira silêncio

`CopilotoViewModel.kt:497` colapsa `whisper == null`, `Result.Failure` do JNI e transcrição legitimamente vazia em `""`. Hoje `""` é **audível** (`NaoEntendi`). Com o portão, `""` casaria `Ignorar` e o produto ficaria **mudo indefinidamente** com o modelo não baixado — o caso mais comum. `sttFn` passa a devolver um tri-estado, e `VerificadorIndisponivel` é audível com causa própria.

### 3.5 O earcon, a segunda meta, e por que a primeira não se reescreve

`VoiceCycle.kt:87` emite `OUVI_VOCE` **antes** do STT (`sttFn` só em `:97`). O aceite A2 exige descarte **sem earcon**. Logo **qualquer portão a jusante do earcon é inalcançável** — áudio não tem desfazer.

A meta de **500 ms** foi escrita para o caminho de **botão**, onde a intenção já foi dada pelo toque. No caminho mãos-livres ela é impossível por construção: não há como confirmar escuta antes de saber que falaram com a gente. Esta spec **declara uma segunda meta** (`fim do enunciado → OUVI_VOCE ≤ 800 ms`, mãos-livres) e **não** reescreve a primeira. As duas vivem em **`Transicao` distintas** — hoje `FIM_DA_FALA_ATE_EARCON` é uma fila só e misturar as populações destrói o número de 305 ms já medido.

### 3.6 Abertura de transmissão — reúso, ordem e prioridade

O ponto de reúso é **um**: `RadioTatico.aoPressionar`/`aoSoltar`. O caminho por voz **não** chama `SessaoPtt` direto (duplicaria antirrepique, `talkGroupCorrente` e `semDerrubarOProcesso`).

```
fun abrirPorVoz(rota: GlassesAudioRoute): ResultadoDeAbertura   // SEM parâmetro de prioridade
fun fecharPorSilencio()                                          // delega em aoSoltar()
```

**`abrirPorVoz` não recebe prioridade; fixa `P2_APOIO` no corpo.** "P1 por voz" deixa de compilar — invariante que o compilador sustenta, como `GlassesAudioRoute` fez com o microfone do celular. Contra-teste: reprovar a existência de sobrecarga com `PrioridadeTransmissao`.

**Ordem das pré-condições:** resolver rótulo → membresia (léxico) → rota provada → transporte conectado → `piso.pedir` (com `withTimeout`) → BIP → primeiro quadro. Resolver **antes** do `gatilho.aoPressionar`, senão `transmitindo()` já é `true` e `PoliticaDeTrocaDeGrupo` recusa a si mesma.

**"Abrir canal" NÃO troca de grupo.** Se o rótulo difere do corrente, é **recusa falada**. Trocar implicaria `transporte.conectar` (fecha o WebSocket, abre outro, espera `onOpen`) dentro do orçamento de 1 500 ms — não cabe, e o `PortaoDeAbertura` veria `conectado() == false` logo depois da troca. O rótulo serve de **confirmação**, e satisfaz o aceite da "guarnição 9" igualmente bem.

**O BIP em UM lugar só.** O earcon de canal aberto é sinal de **máquina de estados do rádio** (categoria de `CanalOcupado`/`CanalPerdido`), emitido em `EventoPtt.Transmitindo` (`RadioTatico.kt:423`, hoje `Unit`) — que já sai **depois** do piso e **antes** de `transporte.anunciar`. `utteranceFor(TransmissaoAberta)` **não** emite um segundo. Emitir nos dois abriria ~400 ms de supressão com a transmissão já no ar.

**O executor NÃO espera o earcon terminar.** `ClaryonIntentExecutor.execute` é `mutex.withLock { runCatching { ... } }` (`:160-163`) e `runCatching` não captura uma suspensão que nunca retorna; `PrioritySoundQueue` cancela o job de reprodução quando chega uma emergência. Esperar ali significa **mutex preso para sempre** — `Intent.Emergencia` deadlocka em silêncio no instante em que mais importa. O buraco de captura durante o BIP é coberto pelo **pré-roll**, que é para isso que existe.

### 3.7 Fecho por silêncio — detector PRÓPRIO, com estado ARMANDO

`VoiceCycle.runOnce()` faz `.first()`: um segmento e retorna. Não existe laço, nem componente que detecte "parou de falar". E o hangover do detector do gatilho é **0,3 s** — abaixo dele, uma pausa entre orações fecharia o canal no meio da mensagem, tornando "40 s corridos fecha aos 30 s por teto" **inatingível**.

`javap` no AAR já provou que `Vad.setConfig` é `putfield` puro: reconfigurar em runtime é inerte. **São duas instâncias**, e o KDoc do projeto já diz isso.

```
ARMANDO  — até a primeira fala detectada. NÃO acumula silêncio: o BIP e o tempo de
           reação custam zero. Guarda própria semFalaMs → fecha com earcon de fecho
           (abrir e não falar não é falha do sistema).
FALANDO  — acumula silêncio contíguo; silencioParaFecharMs → fecha.
           Qualquer fala zera o acumulador. Lacuna de sequência TAMBÉM zera:
           conteúdo desconhecido não é silêncio.
```

Sem o estado `ARMANDO`, os ~240 ms do próprio BIP entram como silêncio digital (exigência do item 21) e sobram ~460 ms de tempo de reação: o canal fecha na cara do agente. É o modo de falha mais provável do desenho ingênuo, e não aparece em nenhum teste que não toque um earcon durante a transmissão.

`silencioParaFecharMs` é **parâmetro**, não constante: os 700 ms ficam 2,8× acima da juntura intra-frase em pt-BR (250 ms), mas cortam o terço superior da pausa de hesitação (200–1000 ms). O número se fecha medindo as pausas intra-transmissão nas 30 pronúncias que o aceite já exige gravar — custo adicional zero.

### 3.8 Teto de 30 s — ancorar no BIP, mas só DEPOIS do timeout do piso

O ROADMAP diz "a partir do BIP"; o código conta da invocação de `transmitir`; a spec diz 12 000 ms do primeiro quadro. Três zeros, nenhum concorda.

Ancorar no BIP é o certo — **e é perigoso na ordem errada**. `ClienteDePisoRemoto` usa `OkHttpClient()` padrão (**callTimeout = 0**) e `SessaoPtt.kt:123` chama `pedir` cru: o desconto de hoje **é** o único limite da fase pré-BIP. Mover a âncora antes de criar o limite transforma 30 s prometidos em `pedir + 30 s`, e o aceite passa a falhar de forma intermitente por causa da rede — o pior tipo de vermelho, porque parece flake.

**Ordem obrigatória:** `withTimeout` sobre `piso.pedir` (estouro → `Ocupado`, a política que `ClientesDePiso` já adota para falha de rede) **primeiro**; mover a âncora **depois**.

### 3.9 Intertravamentos (o que hoje não existe e o aceite A4 exige)

1. **Transmissão em curso desliga o caminho de comando.** `cicloDeVoz()` tem uma guarda só (gravação de evidência). `RadioTatico` precisa expor o estado e o portão precisa consultá-lo. Sem isso: o agente diz a frase **durante** uma transmissão por toque, ela vai ao ar, e todo receptor cai no laço acústico.
2. **Janela de recepção aberta desarma o portão.** Enquanto `supressor` tem janela sem fim previsto (`abrir` sem `fechar`), o microfone contém a mistura; blanquear não basta.
3. **Voz e toque não compartilham `GatilhoPtt`.** Hoje um encosto acidental na barra do PTT com canal aberto por voz encontra `pressionadoEm` e **mata a transmissão**, sem earcon. `RadioTatico` passa a ter noção explícita de dono da transmissão corrente (`voz` | `toque`), e `aoSoltar` recusa encerrar o que não abriu. `ResultadoDeAbertura` ganha `JaNoAr` com fala própria (3 palavras).
4. **Canal aberto por voz não é estado absorvente.** A primeira guarda de `PoliticaDeTrocaDeGrupo` recusa trocar enquanto `transmitindo()`. Com o canal aberto por VOX o agente não tem como sair, e a **recusa falada vai ao ar**. Enquanto a decisão de 3.6 (abrir não troca) valer, a recusa por `TRANSMISSAO_EM_CURSO` passa a ser **earcon**, não fala.
5. **Rede caída no meio não pode ser `Log.w`.** `QuadrosNaoEntregues` acima de um limiar (~10 quadros = 200 ms) vira aviso audível, **um só**, como já se faz na reprodução. Com o aparelho no bolso, o agente fala 30 s para um socket morto e ouve só o `FALHA` genérico do teto — que ele lê como "acabou o tempo", não como "ninguém te ouviu".

---

## 4. Critérios de aceite (EARS)

**A — Fundações de áudio (Bloco 0; sem elas, nada do resto entra)**

1. `Enquanto` o produto estiver emitindo som pelos alto-falantes, `o sistema deverá` marcar a janela de supressão pelos instantes **reais** de início e fim da reprodução, `e` fechá-la também sob **cancelamento** da reprodução.
2. `Se` a fila de som **recusar** um som (INFORMATIVO em Modo Tático), `então o sistema deverá` **não** registrar janela de supressão para ele.
3. `Quando` um quadro de microfone for produzido, `o sistema deverá` carimbá-lo com o instante de **captura**, `e` toda decisão de supressão `deverá` usar esse carimbo, nunca o instante de consumo.
4. `Quando` um quadro cair numa janela de supressão, `o sistema deverá` substituí-lo por **silêncio digital**; remover o quadro é **proibido** — deforma a contagem de silêncio do detector.
5. `O sistema deverá` sustentar leitura e escrita concorrentes do supressor a partir de dispatchers distintos sem lançar exceção.
6. `Quando` o caminho de voz adquirir a rota de áudio, `o sistema deverá` liberá-la em todo caminho de saída, inclusive exceção e cancelamento, `e` **não** liberar quando a aquisição falhou.
7. `Quando` `piso.pedir` exceder o limite declarado, `o sistema deverá` tratar como **Ocupado**, nunca presumir concedido.
8. `O sistema deverá` contar **toda tentativa** de escrita no transporte, inclusive as que morrem no guard de conexão, num decorador de `TransporteAoVivo`.

**B — Portão de comando**

9. `Quando` o detector fechar um segmento `e` a transcrição normalizada contiver uma variante da palavra de ativação nas **N** primeiras palavras `e` houver ao menos um token depois, `o sistema deverá` rotear **o restante** (sem a palavra) por `DeterministicIntentRouter`, executar, e responder por `utteranceFor(ActionOutcome)`.
10. `Se` a transcrição não satisfizer o item 9, `então o sistema deverá` descartar o segmento **em silêncio**: sem earcon, sem fala, sem exibir a transcrição em tela e sem log de conteúdo.
11. `Se` o verificador estiver indisponível ou falhar, `então o sistema deverá` responder de forma **audível**, com causa própria, nunca silêncio.
12. `Quando` um comando for aceito, `o sistema deverá` emitir `OUVI_VOCE` **depois** do portão e **antes** de executar a ação.
13. `Enquanto` houver transmissão de rádio em curso **ou** janela de recepção aberta, `o sistema deverá` manter o caminho de comando **desligado**.

**C — Abertura de transmissão**

14. `Quando` o segmento tiver duração fora da faixa declarada, `o sistema deverá` descartá-lo **sem invocar o verificador** e sem emitir som.
15. `Quando` a transcrição normalizada for **exatamente** a frase de abertura, `o sistema deverá` armar a transmissão. `Se` contiver a frase **mais qualquer palavra**, `então deverá` recusar de forma audível.
16. `Enquanto` a transmissão estiver armada, `o sistema deverá` verificar, nesta ordem: rótulo resolvido contra o léxico do agente, rota em SCO, transporte conectado, piso concedido. `Se` qualquer uma falhar, `então deverá` recusar com causa.
17. `Se` o rótulo falado não estiver no léxico do agente, `então o sistema deverá` recusar de forma falada **sem revelar se o grupo existe** `e` **nenhuma** tentativa de escrita `deverá` chegar ao transporte.
18. `Quando` todas as pré-condições forem satisfeitas, `o sistema deverá` emitir o BIP de canal aberto **antes** do primeiro quadro, `e` emiti-lo em **um único** ponto do código.
19. `Enquanto` a transmissão aberta por voz estiver em curso, `o sistema deverá` usar **P2_APOIO**, `e` abrir em P1 por voz `deverá` ser **inexprimível no tipo**.
20. `Quando` a transmissão começar, `o sistema deverá` incluir apenas o pré-roll capturado **depois** do fim do segmento do gatilho.
21. `Enquanto` a transmissão aberta por voz estiver em curso, um toque na barra do PTT `não deverá` encerrá-la; `e` uma segunda frase de abertura `deverá` produzir resposta própria ("já no ar"), nunca silêncio nem "rádio fechado".

**D — Fecho e teto**

22. `Quando` o detector de fecho não detectar fala pelo intervalo declarado **após a primeira fala**, `o sistema deverá` encerrar a transmissão e emitir o earcon de fecho.
23. `Se` o agente abrir e não falar dentro de `semFalaMs`, `então o sistema deverá` encerrar e sinalizar — canal aberto e mudo é pior que fechado.
24. `Se` a transmissão atingir **30 000 ms** contados do BIP, `então o sistema deverá` encerrá-la e sinalizar por **teto**, com significado distinto de perda de canal.
25. `O sistema deverá` avaliar o teto **fora** do consumidor de quadros, por relógio independente.
26. `Se` a rede cair no meio da transmissão, `então o sistema deverá` avisar de forma audível **uma vez**, não por evento.
27. `O sistema deverá` manter a parada por **toque** como caminho que não depende do microfone. Esta parada **não sai do produto**.

**E — Medição**

28. `O sistema deverá` exportar p95 das três marcas do ROADMAP, com **zero** no fim do enunciado (descontando o silêncio final), `e` com o caminho de botão e o caminho mãos-livres em **filas separadas**.
29. `O sistema deverá` marcar o BIP no instante **real** de reprodução, nunca no de enfileiramento.
30. `Quando` a transmissão for aberta por voz, `o sistema deverá` atribuir o marco do primeiro quadro ao **mesmo `cycleId`** do enunciado, com um único relógio.

---

## 5. Ordem de implementação

Numerada do que destrava mais para o que destrava menos. **Passo sem medição não entra.**

### Bloco 0 — Fundações. Sem ele, o produto abre canal remotamente acionável.

| # | O que | Arquivos | Como se mede |
|---|---|---|---|
| **0.1** | Supressor thread-safe (`@Synchronized` nos 6 métodos, ou snapshot imutável) | `core-net/.../SupressorDeSaidaPropria.kt:40-92` | Stress: 2 dispatchers reais escrevendo e lendo por 10 s, zero exceção. **Tem de FALHAR em `HEAD`** — senão não exercita a corrida |
| **0.2** | Janela pelos instantes **reais**: `abrir` antes de reproduzir, `fechar` em `finally` do `play` (dispara sob cancelamento) | `app/.../audio/SaidaUnica.kt` (`reproduzirComRotaESupressao`), `core-sound/.../PrioritySoundQueue.kt` | Contra-teste: iniciar fala de ~1,8 s, cortar com P1 aos 11 ms, exigir `suprimido(t+300) == false`. Hoje é `true` por ~1,9 s |
| **0.3** | `emitirComSupressao` só registra se a fila **aceitou** o som | `RadioTatico.kt:524-532`, `SoundScheduler.kt:28` | Modo Tático + INFORMATIVO → `janelasVivas` inalterado. Hoje sobe 2 080 ms para um som que nunca tocou |
| **0.4** | Carimbo de captura em `Sinal.Amostras`; `suprimido(quadro.capturadoEmMs)` | `core-audio/.../FonteUnicaDeMicrofone.kt:84,170` + os 3 consumidores | Contra-teste: atraso artificial de 500 ms entre produtor e coletor; exigir que os quadros da janela sejam descartados **mesmo assim**. Hoje falha |
| **0.5** | `Flow<ShortArray>.semNossaSaida(supressor)` com **silêncio digital**; `RadioTatico:238,397` migram de `.filter` para `.map` | novo em `core-audio`; `RadioTatico.kt:238,397` | Teste: 240 ms de janela no meio de fala contínua → a contagem de silêncio do detector **avança** e o segmento **não** se emenda. Nenhum teste existente faz isso |
| **0.6** | `withTimeout` em `piso.pedir`; estouro → `Ocupado` | `core-net/.../SessaoPtt.kt:123`, `ClientesDePiso.kt:58` | Piso fake com atraso de 10 s → `Ocupado` no limite declarado, não em 10 s |
| **0.7** | `comRota { }` (par `iniciar`/`liberar` garantido); `iniciar`/`liberar` saem do alcance dos ViewModels | `core-audio/.../GlassesAudioManagerImpl.kt`, `CopilotoViewModel.kt:450-522` | Forçar `EscutaDoAgente.de` a lançar e exigir contagem de usuários de volta a zero. Hoje vaza |
| **0.8** | `TransporteContado(delegado)` — decorador contando **antes** do guard de conexão | novo em `core-net`; `TransporteRealtime.kt` fica intacto | Socket derrubado + tentativa de envio → contador **sobe**. Contar dentro de `enviarTexto` reproduziria o falso PASSA que ele existe para pegar |
| **0.9** | `duracaoMaximaMs` promovido a parâmetro de `RadioTatico` (default `SessaoPtt.DURACAO_MAXIMA_MS`) | `RadioTatico.kt:373` | Sem isto o aceite dos 30 s é **intestável** em CI |
| **0.10** | `Telemetry.Stage` ganha os marcos de canal; `Transicao` separa botão × mãos-livres; `jaRegistradas` passa a ser podado | `core-common/.../Telemetry.kt:21-30`, `TelemetriaDoCicloDeVoz.kt:63,142,161` | 10 000 ciclos sintéticos → teto no tamanho do conjunto; e `FIM_DA_FALA_ATE_EARCON` do botão **não** recebe amostra do caminho mãos-livres |
| **0.11** | Correção de documentação que o Bloco 0 faz mentir | `SupressorDeSaidaPropria` KDoc caso 4, `TelemetriaDoCicloDeVoz.kt:28,195`, `docs/LEXICO_DO_INITIAL_PROMPT.md` | `grep` do conceito em KDoc e `docs/`. CLAUDE.md §6 pergunta 4 |

**Medição de fecho do Bloco 0 (a que decide):** reproduzir um WAV com a frase de ativação pelo **caminho de saída real**, com o portão ligado, e exigir **zero** disparos; depois rodar com a supressão removida e exigir que **dispare**. Se as duas colunas empatarem, a supressão não está no caminho e o número bom não prova nada.

### Bloco 1 — Portão de comando

| # | O que | Arquivos | Como se mede |
|---|---|---|---|
| **1.1** | `Ativacao` + `PortaoDeComando` (puro) | **novo** `core-agent/.../PortaoDeAtivacao.kt` | JVM: `"aurora mudar para guarnicao tres"` → `Comando("mudar para guarnicao tres")`; `"aurora"` sozinho → `Ignorar`; `"a hora de mudar…"` → `Ignorar` (veto escrito no teste); normalização (`"Aurora,"`, `"aurora."`, `"Áurora"`) passa **sem entrada extra na lista** |
| **1.2** | Janela N parametrizada | idem | **Contra-teste:** `"na aurora mudar…"` passa com N=2 e **falha** com N=1; `"central chamando aurora mudar…"` → `Ignorar` com N=2. Se passar nos dois, o teste não testa a janela |
| **1.3** | `sttFn` tri-estado (para de colapsar `null`/`Failure`/vazio em `""`) | `CopilotoViewModel.kt:497` | Whisper ausente → resposta **audível** com causa própria. Hoje viraria mudez permanente |
| **1.4** | `Resultado` do ciclo vira `sealed` (`Executado`/`Descartado`); earcon migra para depois do portão | `app/.../voice/VoiceCycle.kt:61-66,87,100` | **Contra-teste de ordem:** com `portao = null` (botão) o mesmo áudio produz `OUVI_VOCE` **antes** do `sttFn`; com portão ligado **não** produz até `avaliar` devolver `Comando`. Sem esse par, o parâmetro é a flag que esta spec condena |
| **1.5** | Ramo descartado não vaza conteúdo | `CopilotoViewModel.kt:550` (`descrever(r.transcricao, …)`) | Zero `Utterance` emitida **e** status sem a transcrição. Ligar o portão sem isto faz o app **exibir** a fala de terceiros que ele descartou |

### Bloco 2 — Escuta contínua e intertravamentos

| # | O que | Arquivos | Como se mede |
|---|---|---|---|
| **2.1** | Laço de escuta consumindo a vista **suprimida** (0.5) | `CopilotoViewModel.kt:487`, novo `EscutaContinua` | O contra-teste de fecho do Bloco 0, agora ponta a ponta |
| **2.2** | Portão desarmado durante transmissão em curso e durante janela de recepção aberta | `RadioTatico` expõe o estado; `EscutaContinua` consulta | Transmitir por toque dizendo a frase → **zero** aberturas locais; reproduzir recepção com a frase → **zero** aberturas |
| **2.3** | Dono explícito da transmissão (`voz` \| `toque`); `aoSoltar` recusa encerrar o que não abriu | `RadioTatico.kt:356,404`, `GatilhoPtt` | Canal aberto por voz + toque na barra → transmissão **segue**; frase de abertura com canal aberto → `JaNoAr` falado |
| **2.4** | Recusa de troca por `TRANSMISSAO_EM_CURSO` vira **earcon**, não fala | `PoliticaDeTrocaDeGrupo.kt:57-59`, `Utterance.kt` | Com canal aberto, comando de troca → nenhuma fala vai ao ar |

### Bloco 3 — Abertura por voz

| # | O que | Arquivos | Como se mede |
|---|---|---|---|
| **3.1** | `Earcon` de canal (**PROPOSTA-3**) + `EarconSynthesizer` + `duracaoDoEarcon` | `core-common/.../AudioSignals.kt:21-32`, `core-sound/.../EarconSynthesizer.kt:18-27` | `when` exaustivo quebra o build até ser tratado. Abertura e fecho **não podem** soar igual num produto sem display |
| **3.2** | `LexicoDeAbertura` — casamento **integral**, importando `RotulosFalados.normalizar` | novo em `app/.../radio/` | Recusa por palavra extra; e um teste que compara a normalização das duas classes sobre a mesma entrada, para que **divergirem quebre o build** |
| **3.3** | `Intent.AbrirTransmissao` / `ActionOutcome.TransmissaoAberta`,`TransmissaoEncerrada` / ramo em `utteranceFor` / `AberturaDeCanal` no executor com lambda injetada (precedente: `TrocarDeGrupo`) | `core-agent/.../Intent.kt`, `ActionOutcome.kt`, `Utterance.kt:44-137`, `ClaryonIntentExecutor.kt:125-142,199-203` | "Nunca lança" já sai de graça de `:160-163`. `utteranceFor` exaustivo obriga o tratamento |
| **3.4** | `RadioTatico.abrirPorVoz` **sem** parâmetro de prioridade; BIP em `EventoPtt.Transmitindo` | `RadioTatico.kt:356,423` | **Teste de tipo:** não existe sobrecarga aceitando `PrioridadeTransmissao`. E BIP emitido em **um** ponto — `grep` do earcon em `src/main` devolve um sítio |
| **3.5** | Pré-roll esvaziado na verificação; `@Synchronized` em `PreRollBuffer` (a premissa "produtor único" já é falsa por `RadioTatico.kt:313`) | `core-net/.../PreRoll.kt` | Relógio falso: amostras marcadas antes do fim do segmento não aparecem em `desdeOInicioDaFala()`. Contra-teste: sem o `limpar()`, falha |
| **3.6** | `supressor.limpar()` **sai** de `trocarDeGrupo` | `RadioTatico.kt:313` | Registrar janela de 800 ms, chamar `trocarDeGrupo`, exigir `suprimido(agora+200) == true`. Hoje é `false` |

### Bloco 4 — Fecho e teto

| # | O que | Arquivos | Como se mede |
|---|---|---|---|
| **4.1** | Timeout do piso já feito em 0.6 → **então** mover a âncora do teto para o BIP | `SessaoPtt.kt:118,143,211` | **Contra-teste da âncora:** `piso.pedir` com 0 ms e com 800 ms de atraso; com a âncora certa os instantes de fecho relativos ao BIP são **iguais**; com a de hoje diferem em 800 ms. **Falha em `HEAD`** |
| **4.2** | `DetectorDeFechoPorSilencio` (ARMANDO/FALANDO), instância própria de `Vad`, relógio injetável | **novo** `core-voice/.../DetectorDeFechoPorSilencio.kt` | JVM com relógio virtual: 650 ms de vão → aberto; 750 ms → fechado. Silêncio de 3 s após o BIP → fecho por `semFalaMs`, **não** por silêncio |
| **4.3** | Ciclo de vida: o detector é **filho do mesmo job** da transmissão | `SessaoPtt.transmitir`, `RadioTatico.kt:406-411` | 100 toques curtos e `CanalOcupado` → `AudioRecord` fechado, zero assinaturas vivas. Microfone aberto sem ouvinte é **gravação ambiente** |
| **4.4** | Contra-teste do teto | `core-net/.../SessaoPttTest.kt:303-327` (padrão que já funciona) | Duas configurações (3 000 e 10 000 ms) assertando o **instante** contra o relógio injetado e exigindo que **difiram**. `quadros.size < 30` passaria com teto de 100 ms |
| **4.5** | `QuadrosNaoEntregues` acima do limiar vira aviso audível, um só | `RadioTatico.kt:443` | Socket derrubado no meio → exatamente **um** aviso |
| **4.6** | Earcons distintos para teto e perda de canal (**PROPOSTA-3**) | `RadioTatico.kt:427,433,438` | Os dois eventos produzem `Earcon` **diferentes**. Hoje falha |

### Bloco 5 — Medição e canal fixo

| # | O que | Arquivos | Como se mede |
|---|---|---|---|
| **5.1** | Bancada de recall/FP: extrair leitor de WAV que **caminha chunks** (`AndroidTts.readWavAsPcm`, não `WhisperCppSttTest.readWavPcm`, que assume 44 bytes fixos) | novo `app/src/androidTest/.../bench/Wav.kt` | O runner falha se `fmt` não for PCM 16-bit mono ou se a taxa divergir do manifesto. Corpus de campo com chunk `LIST` viraria recall baixo **indistinguível** de modelo ruim |
| **5.2** | `FalsoPositivoDoGatilhoTest` — matriz 2×2 | novo | **Aceite do instrumento antes do aceite do produto:** a coluna "VAD sempre-aberto" tem de ser materialmente **pior** que "VAD no laço". Se empatarem, o VAD não está no caminho. Braços **fala** e **não-fala** reportados **separados** |
| **5.3** | `CanaisDoAgente.grupoCorrente` vira `StateFlow`; `MainActivity`, `MapaViewModel`, `RadioViewModel.canalAtual()` passam a lê-lo | `CanaisDoAgente.kt:66-72,156,168`, `MainActivity.kt:172,188,213`, `MapaViewModel.kt:88`, `RadioViewModel.kt:502` | `grep -rn "CanalDoPiloto" app/src/main` devolve **um** arquivo. Um escritor só no `_estado` do mapa: o laço captura o grupo antes da chamada e **descarta** o resultado se o grupo mudou em voo |
| **5.4** | `docs/VERIFICACOES_COM_HARDWARE.md` ganha os três itens ausentes (recall HFP, FP de 8 h, segundo ouvinte) | idem | O documento que o CLAUDE.md declara ser "o que só se mede com hardware" está hoje sem os três maiores aceites da fase |

---

## 6. Código agora × hardware — quais aceites ficam abertos

### Dá para fazer AGORA, sem óculos e sem fone

Blocos 0, 1, 2, 3, 4 inteiros, e os instrumentos do 5. Todos os contra-testes listados acima rodam em JVM ou emulador. Os que **têm de falhar em `HEAD`** — e portanto provam que o defeito era real — são: 0.1 (corrida), 0.2 (janela órfã por cancelamento), 0.4 (carimbo), 3.6 (`supressor.limpar`), 4.1 (âncora do teto), 4.6 (earcons colididos), 5.3 (`nomeDoCanalAtual`).

Também dá para fazer agora, e não é código: **criar os dois usuários no GoTrue**. `servidor/seed_piloto.sql` cria três agentes com `auth_user_id` **nulo**; sem dois JWTs reais vinculados, o "segundo ouvinte" não autentica. É caminho crítico do aceite e é administrativo.

### Exige HARDWARE — e estes aceites da Fase 2 ficam ABERTOS até haver óculos e fone

| Aceite | Por que não fecha sem hardware |
|---|---|
| **Recall ≥ 90% em 30 pronúncias reais por HFP** | Todas as bancadas são Piper → reamostrador → whisper a 16 kHz. O link negocia mSBC (16 kHz) **ou** CVSD (8 kHz), e essa é a variável independente. Foi uma bancada assim que aprovou "Claryon" em 14/08 e errou. **Anotar o codec negociado**, sem ele o corpus não é interpretável |
| **8 h de rádio ambiente sem abrir canal** | Rádio VHF real, reproduzido no ambiente, com os óculos na cabeça. A versão acelerada em bancada elimina candidatas ruins; **não** substitui o aceite |
| **Segundo ouvinte recebe** | Dois aparelhos, dois fones, alguém ouvindo. A bancada prova o fan-out do transporte, não que sai som de um segundo fone |
| **p95 das três marcas** | O aceite começa com "aparelho no bolso, tela apagada", e `EscutaDoAgente.deveLiberar` devolve o whisper em `TRIM_MEMORY_UI_HIDDEN`. Todo p95 medido com `connectedAndroidTest` sai com a **tela acesa** — e portanto não é o número do aceite (ver PROPOSTA-4) |
| **Disparo cruzado co-localizado** | Dois aparelhos a 60 cm. O beamforming atenua e a atenuação é **não medida** neste repositório |
| **Boca-a-ouvido, AGC do uplink, subida da rota SCO** | Governam `MARGEM_MS` e `silencioParaFecharMs`. Hoje são argumento, não número |

**Consequência honesta:** ao fim dos Blocos 0–5, a Fase 2 está **construída e instrumentada**, não **aceita**. Escrever "aceite completo" antes das medições de hardware repetiria exatamente o padrão que a auditoria de 17/08 pegou (8 de 8 PARCIAIS).

---

## 7. PROPOSTAS — ✅ DECIDIDAS em 2026-08-17

**As seis foram decididas em revisão humana. O registro abaixo mantém o texto
original de cada proposta e acrescenta a decisão, porque saber o que foi
descartado é metade do valor de uma decisão.**

| # | Decisão | Consequência imediata |
|---|---|---|
| **P-1** | **Escuta contínua COM ESCOPO** | armada pelo agente, desarmada ao sair da tela / ir a segundo plano / por teto. **P-4 sai de escopo.** Exige indicador audível de "estou ouvindo" e a mitigação escrita na meia página do art. 38 |
| **P-2** | **Par de palavras** | palavra única abandonada. `Hey Claryon` entra como candidata medida (`ParDeAtivacaoTest`) — pedida na revisão e é a frase original do ROADMAP |
| **P-3** | **Aprovada** | criar `CANAL_ABERTO`, `CANAL_FECHADO`, `CANAL_NEGADO` |
| **P-4** | **Sai de escopo** | consequência de P-1: o modo mãos-livres não sobrevive ao segundo plano, então `EscutaDoAgente` segue liberando o whisper em `TRIM_MEMORY_UI_HIDDEN`. Sem *foreground service*, sem o risco de LMK |
| **P-5** | **Aprovada** | teto 30 000 ms, âncora no BIP, `duracaoMaximaMs` promovido a parâmetro |
| **P-6** | **Migração `0012`** | `talk_groups.primario` (ou `memberships.primario`); `meus_rotulos_falados()` passa a devolver qual é |

**O que P-1 muda no Bloco 0:** a supressão da própria saída deixa de ser
higiene e passa a ser **pré-condição de segurança** — sem ela, o modo armado
transforma cada transmissão recebida num acionador remoto em N−1 aparelhos.
A ordem do §5 não muda; o Bloco 0 só fica mais obrigatório.

---

### O texto original das propostas, preservado



**PROPOSTA-1 — o portão textual transcreve a fala de terceiros.**
CLAUDE.md §2 proíbe, "sem versão, sem flag, sem exceção": *"Transcrever, classificar ou indexar a fala de terceiros"*. Um portão textual transcreve **todo** segmento de fala fechado e só então decide se era para nós; o descarte posterior não desfaz a transcrição. Não há terceira via: `javap` no AAR 1.13.5 provou que os únicos presets de KWS são chinês e inglês, e não há preset **streaming** em pt para emprestar — logo não existe portão acústico em português. A única defesa é o beamforming, e `specs/gatilho-por-voz.spec.md:401-406` diz por escrito que ele é **premissa não medida** neste repositório. **Decisão humana:** (a) assumir por escrito que se transcreve terceiros, com as mitigações do §2 desta spec (supressão real, nada persistido, nenhum log de conteúdo, descarte imediato) e pagar isso na meia página do art. 38 da LGPD; ou (b) a Fase 2 fica restrita ao caminho por **botão** até haver KWS em pt. Não decido isto sozinho.

**PROPOSTA-2 — a frase de ativação.** §3.1. Emenda o aceite do ROADMAP.

**PROPOSTA-3 — earcons novos.** `CANAL_ABERTO`, `CANAL_FECHADO`, `CANAL_NEGADO`. A biblioteca fixa de earcons é regra dura de design de áudio. Hoje `Earcon.FALHA` carrega três fatos operacionais distintos e o desenho acrescentaria o quarto e o quinto; e abertura e fecho soariam idênticos num produto sem display. Entrego a proposta, não o diff.

**PROPOSTA-4 — `TRIM_MEMORY_UI_HIDDEN` deixa de liberar o whisper** enquanto a escuta contínua estiver ligada. Muda o orçamento de memória do processo (whisper small-q5_1 + Piper + **duas** instâncias nativas de Silero + MapLibre) e provavelmente exige foreground service. O KDoc de `EscutaDoAgente:89-91` justifica a liberação com "o ciclo de voz não roda sem tela" — premissa que a Fase 2 destrói.

**PROPOSTA-5 — teto do item 13 vai de 12 000 para 30 000 ms** e a âncora vira o BIP. O ROADMAP já lista esta correção como pendente; a spec e o código discordam hoje.

**PROPOSTA-6 — semente do grupo corrente.** `CanaisDoAgente.grupoCorrenteId` nasce em `CanalDoPiloto.ID` e `carregar()` nunca o escreve; `meus_rotulos_falados()` (`0011:73-84`) não tem coluna de grupo primário. Trocar o literal por referência **move** o canal fixo de três lugares para um; não o mata. Três saídas: migração `0012` com `talk_groups.primario`; último grupo persistido localmente; ou **rádio abre fechado** e a primeira coisa que o agente faz é abrir por voz. A terceira é a mais coerente com o aceite; a primeira é a que o produto real precisa.

---

## 8. Fora de escopo desta fase, com motivo escrito

- **KWS como adiantamento do earcon.** Sai. Não há preset streaming em pt; o achado que elegeu `Aurora` (token 40663, `yon`→`ion`) é propriedade do **vocabulário BPE do whisper** e **não transfere** para openWakeWord. Implementar `WakeWordDetector` com um portão textual seria dar corpo à abstração errada e deixar o nome mentindo. **Atualização de 20/08:** a conclusão valeu, o desfecho foi outro — a interface e `WakeEvent` foram **apagadas** de `VoicePipeline.kt`, porque uma abstração com zero implementações afirma um ponto de troca inexistente. Quem faz o trabalho é `EscutaDeAtivacao` (detector acústico treinado, não preset). E a linha do `TelemetriaDoCicloDeVoz` que dizia "sem produtor" **deixou de ser verdadeira** no mesmo dia: `ATIVACAO_ATE_EARCON` coleta.
- **Earcon `NO_AR` periódico** (itens 15-16 da spec). Sai: sem `CANAL_ABERTO`/`CANAL_FECHADO` decididos (PROPOSTA-3), acrescentar um terceiro tom de rádio à mesma família é desenhar no escuro.
- **TTL de 15 s do piso por voz** (item 20). Sai: `ClienteDePisoRemoto` fixa o TTL na **construção** (`ClientesDePiso.kt:57`) e `ClienteDePiso.pedir` não tem o parâmetro. Exigiria mudar a interface ou instanciar um segundo cliente, e **não é aceite da Fase 2**. Fica registrado como dívida em vez de entrar de contrabando.
- **Fecho por palavra ("câmbio").** Já fora de escopo e continua: exigiria KWS fora do idioma, e um falso fecho trunca mensagem operacional. "Câmbio" é **hábito** que produz a pausa, não gatilho.
- **Gazetteer de logradouros em produção.** Fica na fase, mas fora deste diff: `configurarGazetteer` só tem chamador em teste.

---

## 9. Riscos aceitos

1. **Rádio ambiente da viatura é invisível ao supressor e continuará sendo.** Não há mitigação acústica; a defesa é casamento integral + membresia + piso. Medido só pelo aceite das 8 h.
2. **Co-localização.** O portão de B pode disparar com a fala de A. O beamforming atenua, não anula, e a atenuação é não medida. Reprecificado: a frequência sobe de "nunca" (frase que ninguém diz) para "toda vez que um agente usa o copiloto perto de outro".
3. **Falso negativo não tem earcon** (item 26 da spec): o agente repete a frase. Recusar quem falou certo é o modo de falhar que faz desligarem o produto — e a medição anterior com `Claryon` recusou 2 em 8 tentativas legítimas.
4. **`silencioParaFecharMs` não cobre** pausa de hesitação longa, fala de baixa energia (cochicho em abordagem) nem perda de beamforming. Fechar cedo é a direção segura: o pior caso é reabrir.
5. **Base empírica da palavra é n=3 por candidata**, por síntese Piper a 16 kHz. Duas de cinco candidatas em 3/3 é compatível com ruído. O princípio fonético derivado dela ("o traço discriminativo não pode estar na consoante inicial") é **hipótese**, não achado.
6. **A janela N=2 trata inserção, não omissão.** Ela foi justificada por uma medição de **omissão** de token, e janela nenhuma encontra o que o decodificador não produziu. Se a omissão dominar, N=2 só dobrou a superfície de FP em troca de nada.

---

## 10. O menor conjunto que já entrega a Fase 2 demonstrável

Se restarem poucos dias, este é o corte — e ele é **honesto sobre o que não entrega**.

**Obrigatório, sem corte possível:** Bloco 0 itens **0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 0.9**. Sem eles a demonstração é a que abre canal sozinha na frente dos avaliadores, e o modo de falha é público e em cascata. Custo: nenhuma cláusula do aceite, e é o que torna todas as outras possíveis.

**O caminho demonstrável (nesta ordem):**

1. **0.x** (fundações acima).
2. **1.1 + 1.3 + 1.4 + 1.5** — portão de comando com contra-teste de ordem do earcon e sem vazamento de conteúdo.
3. **2.1 + 2.2** — escuta contínua sobre fluxo suprimido, desarmada durante transmissão e recepção. É aqui que "nenhum toque na tela" passa a existir.
4. **3.2 + 3.3 + 3.4** — `LexicoDeAbertura`, a `Intent`/`ActionOutcome`, e `abrirPorVoz` com P2 inexprimível. Com **0.8** já pronto, isto fecha a cláusula mais difícil do aceite: *"guarnição 9 → recusa falada e NENHUM QUADRO NO AR"*, com o par teste + **contra-teste** (grupo próprio → contador sobe).
5. **4.2 + 4.3** — fecho por silêncio com estado ARMANDO e ciclo de vida amarrado ao job da transmissão.
6. **4.1 + 4.4** — teto de 30 s ancorado no BIP, com contra-teste de âncora e de duas configurações.
7. **0.10** — as três marcas em filas separadas, p95 exportado.

**O que este conjunto entrega do aceite:** earcon, BIP, transmissão que um segundo ouvinte recebe (bancada), fecho por silêncio, teto de 30 s por relógio independente, recusa do grupo alheio com contagem zero no transporte verificada por par teste/contra-teste, e p95 das três marcas exportado.

**O que este conjunto NÃO entrega, e tem de ser dito em voz alta:** recall ≥ 90% por HFP, as 8 h de rádio ambiente, o segundo ouvinte com fone real, e os três números do `ESTADO.md` **medidos no aparelho com a tela apagada**. Esses quatro dependem de hardware e de PROPOSTA-4.

**O que sai do corte, se precisar cortar mais:** 3.1/4.6 (earcons novos — dependem de decisão humana), 3.5/3.6 (pré-roll e `supressor.limpar`), 2.3/2.4 (dono da transmissão e recusa por earcon), 4.5 (aviso de rede caída), 5.3 (canal fixo). Cortar 2.3 significa que um encosto na tela mata a transmissão aberta por voz — aceitável numa demonstração controlada, **indefensável em campo**, e tem de estar escrito no `ESTADO.md` como quebrado.

---

## 11. Fecho — o que fazer antes de escrever "pronto"

1. Reler o critério de aceite **no `ROADMAP.md`**, o texto e não a lembrança. As oito cláusulas são de **transmissão**; um portão de comando entregue sozinho não é "Fase 2", é "portão de comando".
2. `grep` de cada símbolo novo em `src/main`: `PortaoDeComando`, `LexicoDeAbertura`, `abrirPorVoz`, `AbrirTransmissao`, `DetectorDeFechoPorSilencio`, `semNossaSaida`, `TransporteContado`. Zero chamador alcançável em runtime = **escrito**, não construído.
3. Para cada teste: o corpo prova o que o **nome** afirma? Os sete contra-testes que devem **falhar em `HEAD`** são a prova de que os defeitos eram reais.
4. `grep` do conceito em KDoc e `docs/` — o Bloco 0 faz mentir, no mínimo, o KDoc caso 4 do supressor, `TelemetriaDoCicloDeVoz.kt:28,195`, o KDoc "produtor único" de `PreRoll`, e `docs/LEXICO_DO_INITIAL_PROMPT.md`.
5. Rodar no aparelho e ler `adb logcat -s ClaryonField` **depois** do conserto. Nada nesta spec foi compilado ou executado: é leitura de arquivo, com linha citada e texto conferido.

### M — medições que precedem o código

| # | Medição | Custo | O que decide |
|---|---|---|---|
| **M-0** | Dizer "guarnição três" no aparelho e ler o log: o decodificador produz **dígito** ou **palavra**? | 20 min | Se produzir palavra, o recall do aceite é ~0 e a decisão é de **spec** (cadastro por extenso ou mapeamento canônico dígito↔palavra aplicado **identicamente** nos dois lados) — nunca "consertar o teste" |
| **M-1** | Atraso de `isSpeechDetected()`: WAV com vão de 1 000 ms conhecido, janela a janela | 20 min | Se ele já embutir `minSilenceDuration`, 700 ms viram ~950 ms de fato — e a marca "fim da fala → fecho" falharia por um motivo invisível no Kotlin |
| **M-2** | Rodar `PalavraDeAtivacaoTest` e `PosicaoDaPalavraTest` com **n ≥ 30** por candidata, com band-limiting a 8 kHz, e **versionar a saída** | 1 sessão | Hoje os resultados de `PosicaoDaPalavraTest` que sustentam N=2 **não existem no repositório**, e `Aurora` 3/3 é transcrição humana de um `Log.i` |
| **M-3** | Custo por `acceptWaveform` em arm64 (µs/janela × 31,25 janelas/s) e ΔmA em 10 min | 30 min | Substitui "é uma duplicação de carga já aceita", que é argumento e não medida |
| **M-4** | Disparo cruzado co-localizado: dois aparelhos, uma fala | hardware | Se não for ~0, o caminho mãos-livres não entra sem confirmação por proximidade |
| **M-5** | Tempo de parede do bench de FP com 10 min de corpus, antes de prometer as 8 h | 20 min | "Roda mais rápido que tempo real" é hipótese não medida |

---

### Anexo — o que NÃO foi verificado nesta sessão

- Não compilei, não rodei teste, não li log de aparelho. Todas as afirmações do §1 foram lidas no arquivo; as afirmações de **runtime** (latências, ordem de escalonamento, comportamento sob carga) são aritmética sobre números que outros mediram.
- Não confirmei por `javap` nenhuma API nova, porque o desenho não introduz nenhuma — os portões são Kotlin puro sobre `String`, e o `DetectorDeFechoPorSilencio` usa métodos de `Vad` que o `javap` de sessão anterior já registrou (`acceptWaveform`, `isSpeechDetected`, `compute`, `reset`, `clear`). A semântica de `isSpeechDetected()` é **M-1**, não fato.
- Não abri `servidor/migracoes/0005_controle_de_piso.sql`. A afirmação de que `pedir_canal` recusa não-membro vem de duas citações no Kotlin (`RadioViewModel`, `ResolvedorDeGrupo`) e do texto de `specs/troca-de-grupo-por-voz.spec.md`, não do SQL. Como o aceite "nenhum quadro no ar" depende disso quando o léxico local falha, é **leitura obrigatória antes de codar o Bloco 3**.
- Common Voice pt-BR: licença, tamanho e disponibilidade **NÃO VERIFICADOS**. Não planejar corpus em cima disso sem confirmar.
- A contradição **30** (ROADMAP) × **150** pronúncias (`spec:445`) eu só reporto. Resolver depois de gravar custa uma segunda ida a campo.
