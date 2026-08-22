# Energia — o que o edital exige, onde a energia é gasta, e o que fazer

> Pesquisa e proposta de 2026-08-21. **Nenhum código de produto foi escrito.** O que
> aqui vira mudança de comportamento entra como spec e espera revisão humana
> (`CLAUDE.md` §7). Onde este documento contradiz o `CLAUDE.md`, o `CLAUDE.md` vence.

Três partes: **o que a fonte exige** (§1), **onde a energia é gasta hoje, medido ×
suposto** (§2), e **as propostas, com a conta de cada uma** (§3). §4 lista o que a
fonte contradisse.

---

## 1. O que o edital exige — citado

### 1.1 Bateria é **portão obrigatório**, não critério pontuado

`~/Desktop/Edital AI Glasses Brasil 2026.pdf` §8.1, tabela "Checkpoints técnicos
obrigatórios", literal:

> **Eficiência de bateria** — "Estratégia clara de economia de energia no celular
> e/ou nos óculos."

Três palavras carregam tudo: **estratégia**, **clara**, **e/ou**. O edital não pede
autonomia, não pede %/h, não pede um número. Pede uma decisão articulada e
defensável, e aceita que ela seja só de um dos dois lados do link.

**Onde é cobrado:** §12.3, cronograma de 18/09 — "16h00 · Checkpoint técnico 2
(OBRIGATÓRIO) · Validação parcial: Privacidade e dados e Eficiência de bateria".
Privacidade e bateria são validadas **na mesma sessão**. Um argumento que feche as
duas de uma vez vale o dobro (ver P3 em §3).

**O que o edital NÃO faz:** as duas tabelas de pontuação — §11.1 (Primeiro Filtro) e
§11.2 (Segundo Filtro: Viabilidade técnica 30 · Aderência ao toolkit 20 · Impacto 30
· Considerações éticas 20) — **não têm linha de bateria**. Bateria é portão: não
soma ponto, mas reprova o checkpoint se não houver estratégia. Ver §4, item 1.

### 1.2 A régua que os avaliadores vão usar — a Meta escreveu

`~/Desktop/Curso CEIA Meta/Un12_Material_de_apoio_Meta.pdf` §12.10.3.7, box "Na
prática", literal:

> "este tópico é o coração do checkpoint de 'Eficiência de bateria' do camp. **A
> avaliação vai olhar exatamente para as decisões ensinadas aqui: seu pipeline usa
> foreground service só para o que é contínuo? O trabalho adiável tem constraints? A
> inferência dispara por gatilho ou roda em loop cego?** Um pipeline contínuo de
> visão pode derrubar a bateria do celular em poucas horas — e lembre-se de que a
> bateria dos óculos também é finita e o streaming Bluetooth constante a consome;
> capturar sob demanda poupa os dois lados do link."

São **três perguntas literais**. Hoje o projeto responde bem à primeira e à terceira,
e **falha na segunda** — as duas faixas de `WorkManager` com constraints existem e
nunca são agendadas (§2.6).

Un12 §12.10.3.6 e §12.10.3.8 dão o resto do vocabulário esperado: *race to sleep*,
inferência **bursty** contra contínua, `getThermalHeadroom()` antes de rajadas,
"Gatilho, não loop", "Libere recursos", "Meça, não adivinhe".

### 1.3 O que as palestras acrescentaram

`~/Desktop/Prints Ceia/Captura de Tela 2026-08-15 às 12.23.06.png` — palestra "O
Agente Mínimo Viável" (Frederico Barbosa Relvas, CEIA/UFG), slide "ORÇAMENTO 3 —
MILIAMPÈRES", título **"Bateria é decisão, não otimização"**:

> "O maior ganho de bateria de um agente não vem de quantizar o modelo. Vem de
> **decidir não olhar**."
> *O que drena:* "Câmera em stream contínuo." · "Inferência a cada frame que chega."
> · "LLM chamado a cada turno de conversa." · "**Wake word mal calibrada disparando
> sozinha**."
> *O que preserva:* "Gatilho por **evento**, não por varredura." · "**Um** frame por
> interação, não trinta por segundo." · "Cache de resultado." · "Descarte de frames
> durante a inferência."

`… às 11.37.34.png` — "DEPENDÊNCIA · LIMITES DE SESSÃO / Segundo plano, bateria e
armazenamento":

> "Até 8 h de uso, mas uso intenso de câmera + IA drena mais rápido. O estojo dá
> +48 h, mas fora dele o orçamento é real. **Projete para consumo, não para uso
> contínuo.**"

`… às 12.05.38.png` — "O gatilho errado esvazia a bateria antes do almoço."

`… às 11.25.25.png` — "O hardware é fixo. Você não escolhe câmera, microfone ou
bateria. Projete DENTRO do envelope real do dispositivo."

**Nota metodológica:** a "gambiarra de bateria externa dos pesquisadores do CEIA"
**não foi encontrada**. As 53 capturas de `~/Desktop/Prints Ceia` foram lidas uma a
uma e as três únicas imagens de hardware são fotos/renders oficiais da Meta, sem
modificação. Os 15 PDFs do curso foram extraídos e varridos: zero ocorrências de
`gambiarra`, `power bank`, `bateria externa`, `bateria auxiliar`. Ou a cena está numa
aula sem captura, ou está na fala e não no material. **Está tratada em §3, P6, pelo
que se sabe do problema que ela resolvia — não pelo aparato.**

### 1.4 O hardware — números oficiais, e o que NÃO é oficial

**Confirmado por fonte primária da Meta, buscado e lido:**

| Fato | Valor | Fonte |
|---|---|---|
| Autonomia Gen 2, uso típico | "up to eight hours with typical use" | [about.fb.com, 09/2025](https://about.fb.com/news/2025/09/ray-ban-meta-gen-2-better-battery-life-video-capture/) |
| Autonomia Gen 1, uso moderado | "Óculos totalmente carregados duram até quatro horas para uso moderado." | [meta.com/help](https://www.meta.com/help/ai-glasses/303057485648146/) |
| Estojo Gen 2 | "an additional 48 hours of charging on-the-go" | about.fb.com |
| Estojo Gen 1 | "até 32 horas de carregamento" | meta.com/help |
| Carga rápida Gen 2 | "up to 50% in just 20 minutes" | about.fb.com |
| Ressalva oficial | durações "podem variar de acordo com o uso e outros fatores" | meta.com/help |

**NÃO confirmado em fonte primária, e a distinção importa:** "19 h de standby",
"5,3 h de chamada" e "5 h de música" aparecem (a) no slide do CEIA
`… às 11.29.42.png` — "BATERIA — Até 8 h de uso · 19 h standby · +48 h com o
estojo" — e (b) em resumo de buscador sobre páginas de loja da Meta. **Não consegui
carregar uma página da Meta que os declarasse.** Trate-os como indicativos.

Isso é decisivo e por isso está em destaque: **se "5,3 h de chamada" for verdade, é
esse o número do Claryon, não os 8 h.** O regime do produto — SCO aberto o turno
inteiro com o array de microfones capturando — é uma chamada telefônica permanente,
não "uso típico". A proposta P2 (§3) existe justamente para substituir esse palpite
por medida.

**Capacidade em mAh dos óculos: NÃO ENCONTRADA** em fonte oficial. Não use nenhum
número de mAh para estes óculos; não há de onde tirar.

### 1.5 Aritmética do turno — a conclusão que mais muda o produto

Turno de segurança pública: **6 a 12 h**. Autonomia oficial dos óculos: **8 h** (Gen
2) ou **4 h** (Gen 1), em "uso típico", que é mais leve que o nosso regime.

> **Nenhuma configuração de software faz os óculos cobrirem um turno de 12 h.**
> Não é um problema de otimização. É aritmética sobre um número oficial.

Portanto **o produto tem de ser projetado em torno de pelo menos um acoplamento no
estojo por turno.** Isso não é uma escolha; é a única leitura possível dos números.

E o outro lado da mesma conta, que é a boa notícia:

- Estojo Gen 2 = 48 h ÷ 8 h = **seis recargas completas**. O estojo sozinho cobre um
  turno de 12 h seis vezes.
- 50% em 20 min ⇒ um intervalo de refeição de 20 min compra **~4 h**.
- 8 h de uso + um acoplamento de 20 min ≈ **12 h**. (Apertado, e otimista: os 8 h
  são "uso típico", não o nosso. Pode ser que precise de dois. **É exatamente isso
  que P2 mede.**)

**Energia não é o recurso escasso. Oportunidade de acoplamento é — e ninguém avisa o
agente quando acoplar.** Esta é a reformulação que organiza toda a §3.

### 1.6 A restrição do dia 18/09 que elimina a gambiarra

Edital §9, literal:

> "os demais componentes (óculos e smartphone) deverão ser **exclusivamente
> fornecidos pela organização**." · "**Devolução:** ao final do Hackathon Presencial,
> todos os óculos e smartphones fornecidos pela organização para o desenvolvimento
> das soluções são devolvidos aos organizadores."

Hardware emprestado e devolvido não se modifica. Bateria externa colada, soldada ou
cabeada nos óculos **não está disponível em 18/09**, independentemente de mérito
técnico.

E o próprio cronograma já resolve o problema pelo caminho oficial — §12.3: "15h30
Coffee Break — Pausa curta para **recarregar dispositivos** e equipe."

---

## 2. Onde a energia é gasta hoje — medido × suposto

Tudo abaixo foi conferido no código, não aceito de enunciado. Caminhos absolutos.

### 2.0 O resumo, antes do detalhe

| Consumidor | Lado | Estado | Custo |
|---|---|---|---|
| Recarga do canal por HTTP, 10 s, **sem porta de ciclo de vida** | Celular (modem) | **medido em contagem, não em mAh** | **≥ 360 requisições/h com a tela apagada** |
| SCO/HFP aberto o turno inteiro | **Óculos** + celular | **suposto** | o maior contínuo dos óculos, e nunca medido |
| `DeviceSession` do DAT aberta e ociosa | **Óculos** + celular | **explicitamente não medido** | desconhecido |
| GPS a 60 s + POST por correção | Celular (GNSS + modem) | suposto | 60 fixes/h + 60 POSTs/h, o turno inteiro |
| WebSocket Realtime, batimento 30 s | Celular (modem) | suposto | 120 batimentos/h, sem porta de ciclo de vida |
| Vigia de conectividade, 2 s | Celular (CPU) | suposto | 1800 despertares/h, sem rede |
| Detector de palavra de ativação | Celular (CPU) | **medido** | p50 **3,5 ms**/decisão × 12,5/s = **4,4% de um núcleo** |
| Recarga do whisper por `onTrimMemory` | Celular (flash + CPU) | suposto | 77 691 713 B por retorno de segundo plano |
| Freio térmico | — | **não existe em release** | — |
| `WorkManager` com constraints | — | **nunca agendado** | — |
| Telemetria de bateria | — | **não existe** | — |

### 2.1 O maior consumidor do celular provavelmente não estava na sua lista

`app/src/main/kotlin/com/claryon/field/radio/RadioViewModel.kt:381-386`:

```kotlin
recarga = viewModelScope.launch {
    while (true) {
        carregarCanal(canal, historico)   // → historico.falas(canal) : HTTP
        delay(INTERVALO_DE_RECARGA_MS)    // 10_000L (:780)
    }
}
```

`recarga` só é cancelado em `fechar()` (`:541,550`), que é chamado em
`MainActivity.kt:219` (`onDispose` da composição raiz = **Activity destruída**) e em
`:120` (encerrar turno). **Ir para segundo plano não cancela nada.**

Com o celular no bolso — que é a **postura-alvo do produto** — o app faz **uma
requisição HTTPS a cada 10 s, o turno inteiro, para atualizar uma lista de texto numa
tela apagada**. São 360/h; **4 320 num turno de 12 h**.

Some: batimento do WebSocket a cada 30 s (`core-net/.../TransporteRealtime.kt:313`,
`HEARTBEAT_MS = 30_000L`), também sem porta de ciclo de vida, e um POST de posição
por correção (60/h). Total: **um evento de rede a cada ~7 s, ininterrupto, sem
ninguém olhando.**

**O mecanismo, e por que isto é grande:** o modem celular tem uma máquina de estados
RRC com temporizador de inatividade antes de voltar ao repouso. Tráfego a cada 10 s
plausivelmente impede o modem de repousar **em nenhum momento do turno**. Não vou
afirmar um número de mAh que não posso citar — mas a ordem de grandeza de um modem
retido em estado conectado é de centenas de mW, contra alguns mW do detector. A
medição que resolve está em P4.

**O KDoc que justifica o polling está invertido pelo próprio defeito.**
`RadioViewModel.kt:392-397` argumenta:

> "uma assinatura permanente para texto custaria bateria pelo turno inteiro por um
> ganho que ninguém percebe"

O polling **é** permanente, e é mais caro que a assinatura: a assinatura pegaria
carona num socket que já existe para o áudio; o polling abre conexão nova a cada 10 s
e segura o modem acordado.

**E o padrão do conserto já existe no mesmo repositório.** `MapaViewModel.abrirMapa`
/ `fecharMapa` estão ligados a `ON_START`/`ON_STOP` em `MainActivity.kt:270-271`, e
por isso o laço de 5 s do mapa (`MapaViewModel.kt:109-131`) **só roda com a tela do
mapa aberta**. A tela do rádio simplesmente não recebeu o mesmo tratamento.

### 2.2 Os dois consumidores dos ÓCULOS, e os dois são suposição

Este é o eixo que separa o que o produto controla do que ele só paga.

**(a) SCO/HFP aberto o turno inteiro.** `PowerPolicy` diz `hfpAberto = true` em
`ATIVO` e `OCORRENCIA` (`core-agent/.../PowerPolicy.kt:41,48`), e o modo em produção
é sempre `ATIVO` (§2.3). Então o canal SCO — link Bluetooth **síncrono**, com o array
de 5 microfones e o beamforming ativos nos óculos — fica aberto do login ao fim do
turno.

O próprio `PowerPolicy.kt:26-27` já nomeia o suspeito:

> "**Standby:** HFP fechado — o rádio Bluetooth em SCO é **o maior consumidor
> contínuo**"

Isso está escrito como fato e **nunca foi medido**. É a hipótese central deste
documento, e P2 existe para testá-la.

A única justificativa de custo/benefício registrada é estimativa:
`docs/VERIFICACOES_COM_HARDWARE.md:96` — "Tempo até o SCO estabelecer | **500–1500 ms
esperados**. É o que justifica manter o canal aberto em modo Ativo". Esperados, não
medidos. É esse número que decide se o SCO pode fechar (§3, P1).

**(b) `DeviceSession` do DAT aberta e ociosa.** Desde 21/08 a sessão é dona de
processo (`app/src/main/kotlin/com/claryon/field/oculos/SessaoDosOculos.kt:76`, KDoc
"**Nunca cancelado**"), aberta em `MainActivity.kt:209` e fechada **só** no
`aoEncerrarTurno` (`:118`). O `onDispose` explicitamente não fecha.

O agente que abre o app e nunca toca em "Encerrar turno" fica com uma `DeviceSession`
aberta enquanto o processo viver. `specs/dono-de-processo-para-a-facade-do-dat.spec.md`
§3 registra o custo por escrito:

> "**Isso é intencional** […] mas o custo em bateria de uma sessão ociosa **não foi
> medido e não é mensurável sem óculos reais**."

E `docs/VERIFICACOES_COM_HARDWARE.md:184` é o buraco mais importante desta avaliação:

> "**Quanto custa em bateria uma `DeviceSession` ABERTA e ociosa, por hora?** […] O
> custo de manter a conexão sem stream **não foi medido e não é mensurável sem
> óculos**: o MockDeviceKit não modela rádio. Se for alto, a decisão muda para 'abrir
> sob demanda'"

**Confirmei no artefato que não há como medir por dentro** (`javap`, JDK 17, sobre
`mwdat-core-0.9.0.aar` do cache Gradle):

```
public final class com.meta.wearable.dat.core.types.DeviceState {
  public final ThermalLevel getThermalLevel();     // ← campo ÚNICO
}
public final class com.meta.wearable.dat.core.types.Device {
  getName · getLinkState · getDeviceType · getFirmwareInfo · getCompatibility · isDisplayCapable
}
```

**Não existe API pública para ler nível de bateria dos óculos.** Nem em `DeviceState`,
nem em `Device`, nem em `Wearables`. O que existe é causa de morte, não medidor:

```
DeviceSessionError:  BATTERY_CRITICAL · THERMAL_CRITICAL · THERMAL_EMERGENCY · PEAK_POWER_SHUTDOWN
StreamError:         THERMAL_HOT · BATTERY_LOW · PEAK_POWER_LIMIT   (mwdat-camera-0.9.0)
```

E o MDK não cobre o buraco: no mock, bateria é 100 e térmico é `NONE`, fixos, sem API
pública para mudar.

**Um custo da sessão aberta que não é bateria e vale registrar.** Un13 §13.1.3.5,
tabela do que o DAT não permite: *"Mais de uma sessão simultânea no mesmo dispositivo;
e **alguns recursos nativos dos óculos ficam indisponíveis durante a sessão**."* Uma
sessão permanentemente aberta monopoliza os óculos o turno inteiro.

**Capacidade de graça que o projeto não usa.** `javap` no mesmo artefato:

```
public final StateFlow<DeviceState> getDeviceState(DeviceIdentifier)   // Wearables
enum ThermalLevel { UNKNOWN, NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }
```

A doc oficial descreve `getDeviceState` como **"Always-on — no session required"**. É
o único sinal de saúde dos óculos que existe, custa um coletor, e o projeto o chama
**zero vezes** (grep: as duas únicas ocorrências de `ThermalLevel`/`DeviceState` em
`.kt` são prosa de KDoc em `core-glasses/.../Models.kt`).

### 2.3 `ModoOperacao` é uma constante em produção — e isso anula quase toda a política de energia

O achado que mais muda a leitura do checkpoint.

`ModoOperacao` tem três valores e uma política de energia inteira pendurada neles:

| modo | `hfpAberto` | `wakeWordAtiva` | `cameraPorPadrao` | GPS | intervalo |
|---|---|---|---|---|---|
| STANDBY | false | false | false | NETWORK | 5 min / 250 m |
| ATIVO | **true** | **true** | false | GPS | 60 s / 50 m |
| OCORRENCIA | **true** | **true** | **true** | GPS | **15 s / 10 m** |

Só que:

- **`MainActivity.kt:197` é o único ponto de `src/main` que sobe o serviço com um
  modo, e o literal é `ModoOperacao.ATIVO`.**
- `MainActivity.kt:109` manda `STANDBY` + `ACAO_PARAR`, e `CopilotService.kt:243-248`
  trata isso como **encerrar o serviço** — não como reconfigurar para Standby. Não
  existe caminho para "rodando em Standby".
- O comando de voz *"modo ocorrência"* chega em `Intent.TrocarModo(OCORRENCIA)` e
  termina em `CopilotoDoAgente.kt:188`:
  ```kotlin
  aoTrocarModo = { modo -> saida.modoTatico(modo == ModoOperacao.OCORRENCIA) },
  ```
  Isso só liga a supressão de informativos na fila de som. **Não chama
  `CopilotService.iniciar`.** Não muda `foregroundServiceType`, não muda a cadência de
  GPS, não liga câmera, não muda a escuta.
- Os três botões de modo existem só em `app/src/debug/.../DiagnosticsScreen.kt:191-193`.

**Consequência:** `OCORRENCIA` é inalcançável em release; `STANDBY` significa
"serviço parado". Em produção o pipeline roda em `ATIVO` do login ao fim do turno:
SCO aberto, detector ligado, GPS a 60 s / alta precisão, FGS com
`connectedDevice|microphone|location`.

`wakeWordAtiva`, `cameraPorPadrao` e `suprimeInformativos` de `PerfilDeEnergia` **não
têm um único leitor** — quem decide a escuta é `hfpAberto`
(`EscutaDeAtivacao.kt:134`).

Pela régua do `CLAUDE.md` §6, a política de energia por modo é **escrita, não
construída**: testada em JUnit (`PowerPolicyTest.kt`), sem caminho alcançável em
runtime. E é justamente ela que `docs/COMPLIANCE.md:61` apresenta ao avaliador.

### 2.4 O detector — o número real, e por que ele é o alvo errado

**Medido, e é o que o repositório sustenta:** p50 de **3,5 ms por decisão**
(`app/src/main/kotlin/com/claryon/field/voice/EscutaDeAtivacao.kt:54`; repetido em
`docs/COMPLIANCE.md:72,115`). Com hop de 80 ms (`PASSO_AMOSTRAS = 1_280` sobre
`TAXA = 16_000`), são **12,5 avaliações/s**, e 3,5 / 80 = **4,4% de um núcleo** —
~157 s de CPU por hora de escuta. O intake é de 50 quadros/s de 20 ms
(`GlassesAudioManagerImpl.kt:201`), mas a rede neural roda 12,5×/s, não 50×/s.

**A alocação removida está registrada com número**, em
`core-voice/.../DetectorDeAtivacao.kt:165-178`: `janelaDesenrolada` virou campo e
matou **781 KB/s de lixo** (16 000 floats × 4 B × 12,5/s), o turno inteiro.

**Correção ao enunciado da tarefa:** o par "24,9% → 4,5% de um núcleo" **não existe
em lugar nenhum do repositório** — nem em `.kt`, nem em `.md`, nem no histórico do
`git` (`git log -S`). O que existe é (a) os 3,5 ms ≈ 4,4% de um núcleo e (b) a
economia de **alocação**, 781 KB/s → 0. Se os 24,9% foram medidos numa sessão, eles
não foram escritos, e pela régua do projeto isso significa que não existem. Ver §4.

**E há um defeito de disciplina embutido:** os dois testes que imprimem o custo —
`app/src/androidTest/.../bench/DetectorDeAtivacaoTest.kt:158` e
`.../OficinaDeDesperdicioTest.kt:288` — apenas **registram** a porcentagem em log.
`OficinaDeDesperdicioTest` termina em `assertTrue(tempos.isNotEmpty())`. **Se alguém
reintroduzir a alocação por avaliação, os dois testes continuam verdes.** É a
pergunta 3 do `CLAUDE.md` §6: um teste que passaria com o defeito de volta não testa
o defeito. Falta o contra-teste — um teto assertado sobre `cpuPorSegundo`.

**Por que o detector é o alvo errado, ainda assim:** o laço de captura já é acordado
50×/s porque o SCO está aberto e o `AudioRecord` está lendo. O detector **acrescenta
computação a uma thread que já seria acordada de qualquer jeito**. O custo marginal
dele é aritmética; o custo estrutural é do SCO. Otimizar o detector poupa
miliwatts numa conta cujo termo dominante é o rádio.

> O detector não é o maior consumidor contínuo. **O que o torna possível é** — o SCO
> aberto o turno inteiro, pago pelos óculos, que são o dispositivo que não pode ser
> recarregado enquanto está no rosto e não tem como dizer que está morrendo.

### 2.5 `EscutaDoAgente.deveLiberar` — a justificativa é falsa e o predicado está errado

`app/src/main/kotlin/com/claryon/field/voice/EscutaDoAgente.kt:93-95`:

```kotlin
fun deveLiberar(nivel: Int): Boolean =
    nivel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        nivel == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
```

KDoc `:88-91`, literal: *"`TRIM_MEMORY_UI_HIDDEN` também libera: o app foi para
segundo plano, **e o ciclo de voz não roda sem tela**. O rádio continua, e é ele que
precisa da memória."*

**A afirmação é falsa desde o `CerebroDoCopiloto`**, e o próprio repositório prova:
`CopilotoDoAgente.kt:92-94` é dono de processo com escopo próprio;
`CopilotService.kt:103-109` roda `cicloDeVoz` a partir do serviço; e
`app/src/androidTest/.../CicloSemTelaTest.kt` diz literalmente que ali *"nenhum
ViewModel é construído e nenhuma Activity é lançada"*.

**E há um segundo defeito, mais sutil, confirmado por `javap` em
`android-35/android.jar`:**

```
TRIM_MEMORY_RUNNING_MODERATE = 5     TRIM_MEMORY_UI_HIDDEN  = 20
TRIM_MEMORY_RUNNING_LOW      = 10    TRIM_MEMORY_BACKGROUND = 40
TRIM_MEMORY_RUNNING_CRITICAL = 15    TRIM_MEMORY_MODERATE   = 60
                                     TRIM_MEMORY_COMPLETE   = 80
```

1. **`|| nivel == TRIM_MEMORY_UI_HIDDEN` é código morto.** 20 ≥ 10 já é verdade. A
   cláusula nunca decide nada; removê-la não muda comportamento nenhum.
2. **`>=` sobre `TRIM_MEMORY_*` não é comparação de severidade.** `UI_HIDDEN` (20) é
   numericamente *maior* que `RUNNING_CRITICAL` (15) sendo muito menos grave — é um
   aviso de visibilidade, não de pressão. O comportamento "libera ao ir para segundo
   plano" vem da **primeira** cláusula, e é acidental.

**O efeito de campo:** o cenário-alvo do produto é o celular no bolso. Ao ir para
segundo plano o processo devolve os 77 691 713 B do `ggml-tiny.bin`, e o próximo
"Claryon" paga a carga inteira dentro de uma meta de 2,0 s — em latência e em E/S
de flash.

**A liberação continua certa pelo motivo certo?** Não. O processo segura um FGS com
`microphone`, então já está alto na ordem do *Low Memory Killer* — a proteção que a
liberação buscava, o FGS já dá. O predicado deveria **nomear os níveis que
significam pressão** (`RUNNING_LOW`, `RUNNING_CRITICAL`), como conjunto explícito, em
vez de `>=`.

### 2.6 O que o `ROADMAP` promete de energia — e o que foi medido

**Correção ao enunciado:** o `ROADMAP.md` **não tem seção de Energia**. Grep por
`bateria|energia|consumo|autonomia` devolve duas linhas, e nenhuma é meta (`:306` é
energia *acústica* do VAD; `:314` fala de liberação por política térmica). A seção de
Energia mora em `docs/PADROES_DE_ENGENHARIA.md:249-256`, e a única meta numérica está
em `:292`.

| Promessa | Onde | Medido? |
|---|---|---|
| **≤ 12%/h no celular, modo Ativo** | `PADROES:292` | ❌ **CHUTE.** `VERIFICACOES:147` — *"o consumo real nunca foi medido"*. Zero números de %/h em todo o repositório |
| Gatilho, nunca loop; cascata wake→VAD→STT | `PADROES:251` | ✅ estrutural e medido no custo (3,5 ms/decisão) |
| Câmera desligada por padrão | `PADROES:252` | ✅ **e desde 21/08 não é mais vacuamente.** `withCamera` ganhou chamador em `app/src/main` (`oculos/CapturaDePlaca.kt`, pela consulta de placa): abre sob intenção explícita, com `CameraProfile.OCR` (LOW, 7 fps), e a coleta tem teto de **5 s** — para no primeiro frame com placa, e o `finally` baixa a câmera. `startCameraStream` e `capturePhoto` continuam com zero chamadores em `src/main` (só `src/debug`) |
| Três modos com perfis distintos | `PADROES:253` | ⚠️ **testado em JUnit, morto em produção** (§2.3) |
| FGS com tipo derivado do modo | `PADROES:254` | ✅ medido, mas **no emulador** (`DIARIO:381-388`, `0x90`/`0xD0`) |
| `WorkManager` com `requiresCharging` + UNMETERED + `requiresBatteryNotLow` | `PADROES:255` | ❌ **escrito, nunca construído.** `core-sync/.../SyncManager.kt:57-68` existe; `agendarDrenagemTatica`/`agendarDrenagemPesada` têm **zero chamadores** |
| `getThermalHeadroom()` antes de rajadas | `PADROES:256` | ❌ **não existe em release** |
| `sample` no Flow como teto de taxa | `PADROES:256` | ❌ não usado (`COMPLIANCE:128` admite) |
| Custo da `DeviceSession` ociosa | `VERIFICACOES:184` | ❌ explicitamente não medido |

**O freio térmico, em detalhe.** `ThermalGovernor.fpsPermitido` só é chamado por
`CopilotService.fpsPermitidoAgora` (`:447-459`), cujo **único chamador é
`app/src/debug/.../DiagnosticsScreen.kt:185`**. `ThermalGovernor.podeIniciarRajada`
tem **zero chamadores em qualquer source set**. `addThermalStatusListener` não aparece
no repositório. O próprio `EscutaDoAgente.kt:39-45` já registra isso por escrito.

**Telemetria de bateria: não existe.** Greps em `src/main` por `BatteryManager`,
`ACTION_BATTERY_CHANGED`, `BATTERY_PROPERTY`, `nivelDeBateria` → **zero**. As ~40
ocorrências de "bateria" no código são prosa de KDoc justificando decisões, nunca
leitura de sensor.

### 2.7 `docs/COMPLIANCE.md` contradiz a si mesmo, na linha que o jurado lê

`COMPLIANCE.md:61` (tabela-resumo dos checkpoints obrigatórios):

> **Eficiência de bateria** | "Modos Standby/Ativo/Ocorrência como política pura e
> testada; FGS com tipos derivados do modo; **freio térmico** com `NaN` tratado;
> **WorkManager em duas faixas**" | ✅ **montado — verificado em aparelho**

`COMPLIANCE.md:127-131` (detalhe do mesmo documento, 66 linhas abaixo):

> ⚠️ "o teto de FPS é apenas **exibido** — não é aplicado ao stream, e
> `podeIniciarRajada` nunca é chamado. `sample` não é usado."
> ❌ "Medir com Power Profiler + `dumpsys batterystats` — **nenhum número medido**."

As duas linhas não podem estar certas ao mesmo tempo. A linha 61 é a que um avaliador
lê primeiro, e ela sustenta três coisas que não têm caminho em runtime: os modos
(§2.3), o freio térmico (§2.6) e as faixas de `WorkManager` (§2.6). É a repetição
literal do padrão que o `CLAUDE.md` §6 manda caçar, sobre o checkpoint em questão.
**Corrigir essas duas linhas é a ação mais barata deste documento inteiro** — e não
está no meu território; fica como recomendação.

---

## 3. Propostas

### 3.1 Empatizar — o agente, não o aparelho

Turno de 6 a 12 h. Óculos no rosto, celular no bolso. Horas dentro da viatura, e é lá
que ele passa a maior parte do tempo: deslocamento, espera, patrulha lenta,
preenchimento de ocorrência. Sai da viatura sem aviso e é aí que tudo importa. Volta,
senta, digita, dirige.

O que ele já carrega e já usa: colete, cinto, rádio analógico da corporação, o
celular, **o estojo dos óculos**, e uma viatura que é uma bateria de 12 V que anda,
com tomada e USB.

O que ele **não** tem: um display nos óculos. Um ícone de bateria. Qualquer forma de
saber que o copiloto está morrendo antes de ele já ter morrido.

E o que ele faz quando não está usando os óculos: empurra para a testa, tira e
pendura na gola, dobra e joga no painel, guarda no bolso do peito.

### 3.2 Definir — três reformulações

**R1 — Energia não é o recurso escasso; oportunidade de acoplamento é.** O estojo dá
seis recargas (§1.5). A viatura tem tomada. O escasso é *saber a hora*, e hoje nada no
sistema sabe.

**R2 — As duas baterias não são simétricas, e a arquitetura hoje gasta a errada.**

| | Celular | Óculos |
|---|---|---|
| Nível legível? | sim (`BatteryManager`) | **não** — provado por `javap`, §2.2 |
| Recarregável em serviço? | sim, na viatura | **não** enquanto no rosto |
| Substituível? | há outro celular na guarnição | **não** — é o único sensor |
| Avisa que vai morrer? | sim | só o atestado de óbito |

Toda decisão deveria empurrar custo para o celular. **Hoje "escutar sempre" empurra
para os óculos** — SCO síncrono, array de 5 microfones e beamforming ligados 12 h —
para comprar uma palavra de ativação que roda no celular a 4,4% de um núcleo.

**R3 — Energia tem de ser previsão falada e honesta.** Sem display, "bateria" só
existe se for dita. O projeto já tem a doutrina: `PoliticaDeRedacao` degrada e diz;
`marcadorObsoleto` esmaece porque *"publicar de minuto em minuto para manter o
marcador cheio seria mentir com mais bateria"*. Energia é o próximo lugar onde ela se
aplica.

### 3.3 As propostas

Ordem: retorno sobre custo. Cada uma traz **custo · economia (com a conta) · o que
quebra · como medir**. Proposta sem forma de medir é opinião.

---

#### P1 · A tomada da viatura é o sensor de ocorrência

**A ideia.** O que falta para a política de energia por modo funcionar não é
política — ela está escrita, testada e completa (§2.3). Falta **sinal**. E o agente
já produz o sinal perfeito, de graça, várias vezes por turno: **ele desconecta o
celular do carregador e sai da viatura.**

`ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` (ou
`BatteryManager.EXTRA_PLUGGED`) é o discriminador mais confiável de "na viatura" ×
"a pé" que existe no aparelho: não custa sensor, não custa modelo, não erra.

E leva a uma conclusão contra-intuitiva, que é o ponto: **na viatura, a energia do
celular é de graça — ele está carregando. A dos óculos não.** Então a política de
dentro da viatura é o inverso do que a intuição escreve: *gaste o celular à vontade,
descanse os óculos.*

**O que custa.** Um `BroadcastReceiver` e a fiação de `_modo`. Nenhuma dependência
nova. A política, as cadências e os tipos de FGS já existem e já têm teste.

**O que economiza, com a conta.** Hoje as transições de modo em produção são
**provadamente zero** — `MainActivity.kt:197` fixa `ATIVO`. Qualquer número maior que
zero é ganho novo. Se a fração dentro da viatura for *f* e o custo do SCO nos óculos
for *c* %/h, a economia nos óculos é `f · c · duração`. Com *f* = 0,6 (a medir, não
supor) e um turno de 12 h, são **7,2 h de SCO evitadas**. Em fração de autonomia isso
só fecha depois de P2 medir *c* — e é essa a ordem correta: medir antes de prometer.

**O que quebra.** O PTT. Hoje toque→1º quadro é **31–48 ms** (meta 120) *com o SCO já
aberto*; estabelecer SCO é estimado em **500–1500 ms** e nunca foi medido
(`VERIFICACOES:96`). Se o SCO fechar na viatura, o primeiro PTT depois de sair custa
esse tempo. **É o número que decide a proposta inteira, e ele não existe.** Além
disso: agente de carona está em serviço — "ligado na tomada" não pode virar "surdo".
A saída tem de ser audível e o modo, sobreponível por voz.

**Como medir.**
1. *Contra-teste de existência*: instrumentar 4 h de turno simulado e contar
   transições de `CopilotService.modo`. Aceite: **> 0**. Hoje é 0 e o teste falha —
   que é o ponto.
2. `adb shell dumpsys batterystats --reset`, 30 min em cada modo, ler mAh por uid.
   Aceite: os modos **diferem**. Se não diferirem, a política não faz nada e a
   proposta morre — contra-teste no padrão do `CLAUDE.md` §6.
3. **Medir o SCO**: `System.nanoTime()` entre `setCommunicationDevice()` e o primeiro
   quadro. Se passar de ~300 ms, o SCO **não** pode fechar em serviço e a proposta
   recua para "só o que não é HFP muda com o modo".

**Vira spec:** `specs/energia-por-modo.spec.md` (escrita junto com este documento).

---

#### P2 · O medidor que não existe: o túmulo como instrumento

**A ideia.** Os óculos não têm medidor de bateria — está provado por `javap` (§2.2), e
nenhuma engenhosidade contorna isso. Mas eles têm **atestado de óbito**:
`DeviceSessionError.BATTERY_CRITICAL`, `SESSION_ENDED_BY_DEVICE`, e no stream
`StreamError.BATTERY_LOW` / `PEAK_POWER_LIMIT`.

Hoje esse atestado é jogado fora. O coletor de `s.errors` em
`core-glasses/.../DatGlassesFacade.kt` (procure por `"glasses.session_error"` — o
arquivo está sendo editado em paralelo e o número da linha anda) colapsa **todos** os
erros de sessão numa string:

```kotlin
s.errors.collect { erro ->
    _sessionErrors.emit(ClaryonError.Glasses("glasses.session_error", erro.toString()))
}
```

`BATTERY_CRITICAL` é indistinguível de `CAPABILITY_NOT_FOUND`. Duas coisas seguem:

**(a) Tipar.** Um `ErroDeSessao` espelhando `DeviceSessionError` **por nome**, no
mesmo padrão que `ErroDeStream` já usa e que já resiste a acréscimos do SDK. Então
`BATTERY_CRITICAL` vira frase dentro do teto de 7 palavras: *"Óculos sem bateria.
Rádio continua."* — 5 palavras. Hoje os óculos morrem e o agente descobre pelo
silêncio.

**(b) Usar como medidor.** Registrar `(abertura, morte, causa)` por sessão. Ao longo
de N turnos isso é uma **curva de sobrevivência**. É o único instrumento que existe
para a bateria dos óculos, e não custa nada porque o evento já chega.

> Um medidor de bateria construído com lápides. Não porque seja elegante — porque é
> o único material disponível.

**O experimento que fecha a hipótese central**, e ele não precisa de API nenhuma:
duas corridas não assistidas, dos 100% até a morte, cronômetro na mão —

- **A:** sessão DAT aberta + SCO aberto + detector ligado (produção hoje).
- **B:** sessão DAT aberta, SCO fechado.

A diferença entre os dois tempos **é o custo do SCO nos óculos**, o número que este
documento inteiro depende e que ninguém tem. Linha de base oficial para comparar:
8 h de "uso típico" (Gen 2). Se A ficar perto de 5 h, o indicativo não confirmado de
"5,3 h de chamada" (§1.4) estava certo e o produto tem de ser redesenhado em torno de
**dois** acoplamentos por turno.

**O que custa.** Um `when` sobre nomes de enum + uma linha persistida por sessão.
Nenhuma dependência.

**O que economiza.** Zero joules, diretamente. **Torna todas as outras propostas
falsificáveis.** Sem ela, tudo que se disser sobre os óculos é opinião — inclusive o
que está escrito em `PowerPolicy.kt:26`.

**O que quebra.** Nada. O risco é mapear nome de enum errado, e o guarda é o mesmo
teste que já protege `ErroDeStream`.

**Como medir.** A entrega **é** a medição. Aceite: depois de 3 turnos existe
`t_morte − t_abertura` com causa tipada, e as duas corridas A/B **diferem**.

---

#### P3 · Os óculos que ninguém está usando — e o argumento que fecha dois checkpoints

**A ideia.** Parte do turno os óculos não estão no rosto: na testa, na gola, dobrados
no painel, no bolso do peito. Hoje o app não liga: o SCO continua aberto, o array de
microfones e o beamforming continuam rodando, e o detector avalia o forro de um
casaco.

Sinais disponíveis, todos de graça, todos confirmados em fonte:

- **Dobradiça (fold/unfold)** — medido no emulador com MDK 0.9.0 e registrado em
  `VERIFICACOES:150-152`: *"dobrar as hastes e desligar o aparelho mudam o estado
  observado pelo app (STREAMING → STOPPED)"*. Já é visível.
- **Detecção de uso (don/doff)** — Un13 §13.4.3.7 diz que a sessão reage ao *doff*
  "when wear detection is enabled", e **não há API para habilitar**: é ajuste no app
  Meta AI. No MDK, *"tirar do rosto não muda nada"*. Ou seja: é **pré-requisito de
  configuração**, e o projeto já o tem como pergunta aberta de hardware
  (`VERIFICACOES:160-166`).

A regra é uma frase: **sem rosto, sem SCO.**

**E aqui está o melhor argumento deste documento inteiro.** Isto não é só energia. O
próprio `VERIFICACOES:167-170` já registra a consequência de privacidade, sem
mitigação:

> "**A consequência a carregar:** fora do rosto, o beamforming que isola quem os veste
> deixa de valer. Um PTT apertado nessa condição difunde a conversa ao redor."

E o edital valida **privacidade e bateria no mesmo checkpoint, às 16h00** (§1.1).
Uma mudança de ~20 linhas fecha um vazamento de energia e um vazamento de privacidade
com o mesmo diff, e o vazamento de privacidade já está escrito como risco conhecido
sem conserto.

**O que custa.** Assinar algo que já é coletado (`session.state`) + um item de
configuração no roteiro de onboarding (ligar a detecção de uso no app Meta AI).

**O que economiza, com a conta.** `fração_fora_do_rosto × custo_SCO × 12 h`. A
fração é medível hoje mesmo, sem hardware novo: um cronômetro num
acompanhamento de turno. O custo do SCO vem de P2. **Não estimo a fração — ela é
observável e estimá-la seria exatamente o erro que este documento cobra dos outros.**

**O que quebra.** O agente com os óculos na testa espera continuar sendo ouvido. O
fechamento do canal **tem de ser audível** — earcon, não silêncio. E há um caso feio:
óculos na testa durante uma ocorrência. Mitigação: nunca degradar com PTT no ar ou
ciclo de voz aberto — o padrão que `EscutaDeAtivacao.silenciarPor` já usa.

**Como medir.** Registrar `(t_dobra, t_abertura)` por turno → a fração real. Com P2,
vira joules. Aceite: a fração é **medida**, e o fechamento do canal é audível em
100% dos casos.

---

#### P4 · Parar de ligar para o servidor quando ninguém está olhando

**A ideia.** É o conserto mais barato e provavelmente o maior do lado do celular.
`RadioViewModel.recarga` faz uma requisição HTTPS a cada 10 s a partir de
`viewModelScope`, cancelada só quando a Activity morre — **4 320 requisições por
turno de 12 h com a tela apagada** (§2.1). O padrão do conserto já existe no mesmo
repositório: o mapa é fechado por `ON_STOP` via `MainActivity.kt:270-271`, e por isso
o laço de 5 s dele não roda com a tela fora. A tela do rádio não recebeu o mesmo
tratamento.

Conserto: porta de ciclo de vida (`repeatOnLifecycle(Lifecycle.State.STARTED)`), com
uma recarga única no `ON_START` — que é o que o mapa já faz. Mesma decisão para o
batimento de 30 s do WebSocket e para a vigia de 2 s.

**O que custa.** Quase nada. Padrão já presente no projeto, zero dependências.

**O que economiza.** **Não vou afirmar mAh que não posso citar.** O que afirmo: o
mecanismo (o modem preso em estado conectado porque nunca passa 10 s sem tráfego), a
contagem (4 320 + 1 440 + 720 eventos por turno, todos sem tela), e o comando que
resolve a dúvida em 30 minutos. A hipótese é que este termo domine os 4,4% de núcleo
do detector por mais de uma ordem de grandeza — **e ela é falsificável.**

**O que quebra.** O histórico de texto fica velho enquanto a tela está fora, e
aparece atualizado ao abrir. É o comportamento do mapa hoje, e ninguém reclamou. O
que **não** pode parar é o socket do áudio: P1 é tempo real e não passa por este
laço.

**Como medir.**
```bash
adb shell dumpsys batterystats --reset
# 30 min com a tela apagada, app em segundo plano, sem tocar em nada
adb shell dumpsys batterystats > bs.txt   # ler "Mobile radio active time" do uid
```
Rodar com a porta e sem a porta. **Contra-teste obrigatório: se os dois não
diferirem, o polling não era o custo e a hipótese está morta.** Métrica secundária,
mais barata e igualmente conclusiva: contar requisições por hora no log.

---

#### P5 · Degradação honesta de energia, lida da escada de prioridade que já existe

**A ideia.** O projeto já ordena suas capacidades: P1 preempta tudo (medido: corta em
11 ms). `PoliticaDeRedacao` já degrada o LLM por RAM, é **pura** para ter
contra-teste, e diz o que fez. Energia tem exatamente a mesma forma — e a escada não
é política nova: **é a escada de prioridade existente, lida de trás para frente.**

`PoliticaDeEnergia`, pura como a irmã, sobre nível e estado de carga (hoje o app tem
**zero** telemetria de bateria — §2.6):

| Faixa | O que o produto deixa de fazer | O que ele diz (≤ 7 palavras) |
|---|---|---|
| 100–40% | nada | — |
| 40–20% | LLM de redação e câmera saem; volta à Etapa A | "Copiloto em economia. Rádio intacto." (5) |
| 20–10% | palavra de ativação sai; copiloto vira push-to-talk; posição cai para o plano de Standby — **que já existe, já tem teste e nunca rodou** | "Gatilho por voz desligado." (4) |
| < 10% | só P1 e posição | "Só rádio e posição." (4) |

Se estiver carregando (na viatura), a escada não desce. É P1 fechando o laço.

**O que custa.** Um `object` puro + um leitor de `BatteryManager` + uma fala por
degrau. Reaproveita a forma de `PoliticaDeRedacao`, **incluindo a disciplina do
contra-teste**.

**O que economiza.** É seguro, não economia. Converte uma morte não planejada em
degradação planejada. E em termos de edital é **literalmente** o texto do checkpoint:
"estratégia clara de economia de energia no celular".

**O que quebra.** Degradar no meio de uma ocorrência. Mitigação: nunca com PTT no ar
ou ciclo de voz aberto — adiar a transição, o padrão de `silenciarPor`. E o agente
tem de **ouvir** cada degrau, uma vez: descobrir que o copiloto emburreceu
perguntando algo e não recebendo resposta é o pior desfecho possível.

**Como medir.** Contra-teste obrigatório, no molde de `PoliticaDeRedacaoTest`: as
mesmas entradas com bateria alta e baixa **têm** de decidir diferente; um teste que
passa nos dois ramos não testa nada. Mais a verificação de campo: levar o aparelho a
15% e confirmar que o degrau dispara e é audível.

---

#### P6 · O estojo é um power bank homologado — e o copiloto é quem sabe a hora

**A refutação da bateria externa, em três frentes.**

1. **Operacional.** Edital §9: óculos e celular são fornecidos pela organização e
   **devolvidos**. Hardware emprestado não se modifica. Em 18/09 a gambiarra não
   existe (§1.6).
2. **Aritmética.** Ela não é necessária. O estojo dá **seis recargas**; 50% em 20
   minutos; um intervalo de refeição compra ~4 h (§1.5). Os organizadores já
   agendaram a pausa: "15h30 Coffee Break — Pausa curta para recarregar dispositivos".
3. **Produto.** Um cabo saindo do rosto de um policial é o oposto deste produto. Ele
   já carrega o estojo.

**O que vale copiar, e é o "jeito muito específico".** O que os pesquisadores
resolveram não foi bateria — foi **falta de medidor**. Eles tiraram o medidor da
equação porque não tinham um. Nós não podemos tirar (o hardware volta), então
**construímos o medidor**: com lápides (P2) e com navegação estimada.

O medidor por estimativa: o copiloto sabe `t_abertura` da sessão e, da curva de P2, um
orçamento esperado por regime. **Ele nunca afirma ler um nível.** Ele diz, uma vez, na
hora certa, dentro de 7 palavras: *"Óculos no estojo, vinte minutos."* — e quando não
sabe, diz que não sabe, que é a doutrina de `marcadorObsoleto` aplicada a energia.

A hora certa não é "quando a bateria está baixa" — é **quando existe oportunidade de
acoplamento**: o carregador da viatura conectando (P1 já dá esse evento), a chegada à
base, o horário de refeição. O copiloto não pede o impossível; ele reconhece a janela.

**O que custa.** Uma tabela calibrada por P2 + uma fala + um earcon. **Zero
hardware.**

**O que economiza.** A diferença entre "os óculos morreram na hora 8, no meio de uma
ocorrência" e "os óculos foram acoplados na refeição e cobriram 12 h". Isso é o turno
inteiro — e é a única proposta aqui que ataca o limite físico de §1.5 em vez de
espremer percentuais em torno dele.

**O que quebra.** Uma previsão errada que insiste. Mitigação: no máximo um pedido por
turno, dispensável por voz, e nunca durante ocorrência.

**Como medir.** Ao longo de N turnos, contar mortes por `BATTERY_CRITICAL` por turno.
Alvo: **0**. Hoje isso é imensurável, porque o evento é uma string (§2.2) — **P2 é
pré-requisito duro desta proposta.**

---

#### P7 · Os três consertos que são só correção

Sem design thinking; são coisas erradas.

**(a) `deveLiberar` — `>=` sobre `TRIM_MEMORY_*` não é teste de severidade** (§2.5).
A cláusula `|| nivel == TRIM_MEMORY_UI_HIDDEN` é código morto (20 ≥ 10, por `javap`),
e a justificativa no KDoc é falsa desde o `CerebroDoCopiloto`. O predicado deveria
nomear os níveis de pressão como conjunto explícito. Muda comportamento ⇒ §7 manda
spec antes de diff. *Contra-teste:* um ciclo de voz com a tela apagada **não** pode
registrar "whisper carregado" depois do conserto, e **tem** de registrar antes.

**(b) Ler o único sinal de saúde que os óculos dão.**
`Wearables.getDeviceState(id): StateFlow<DeviceState>`, "Always-on — no session
required", com `ThermalLevel` em 8 níveis — confirmado por `javap` em
`mwdat-core-0.9.0` (§2.2). O projeto o chama **zero vezes**. Custa um coletor, e é o
único aviso antecipado que existe: `SEVERE` = "Performance may be throttled",
`CRITICAL` = "Sessions may be denied or terminated". Vale mais que o
`getThermalHeadroom` do celular, que hoje não tem consumidor em release.

**(c) Parar de reivindicar o que não tem chamador.** `COMPLIANCE.md:61` conta os três
modos, o freio térmico e as duas faixas de `WorkManager` como "montado — verificado em
aparelho", e as linhas 127-131 do mesmo arquivo desmentem duas delas (§2.7). Ou se
liga, ou se para de afirmar. A régua da Meta pergunta literalmente *"O trabalho
adiável tem constraints?"* (§1.2) — as faixas de `WorkManager` já estão escritas e é o
ponto mais barato da mesa: falta o `agendar`. **Não está no meu território editar
`COMPLIANCE.md`; fica como recomendação.**

### 3.4 Ordem sugerida

| # | Proposta | Custo | Destrava |
|---|---|---|---|
| 1 | **P4** — porta de ciclo de vida no polling | horas | provável maior economia do celular, e o padrão já existe no repo |
| 2 | **P2** — tipar o erro de sessão + curva de sobrevivência | horas | torna tudo sobre os óculos falsificável; **pré-requisito de P6** |
| 3 | **P7(c)** — reconciliar `COMPLIANCE.md` | minutos | tira do documento do avaliador três afirmações sem caminho |
| 4 | **P1** — modo por evento físico | 1 sessão | faz a política de três modos **existir**; é o que a régua da Meta pergunta |
| 5 | **P3** — sem rosto, sem SCO | 1 sessão | fecha energia **e** privacidade no mesmo checkpoint |
| 6 | **P5** — degradação honesta | 1 sessão | é o texto literal do checkpoint |
| 7 | **P6** — o estojo e a hora certa | depende de P2 | única que ataca o limite físico de §1.5 |

Para 22/08 (documento da Etapa 5), o que muda o texto entregue são **P4, P2 e P7(c)**:
os três cabem em uma sessão somados e transformam "política pura testada" em
"medida, com número e com caminho".

---

## 4. Onde a fonte contradisse o enunciado

Registrado porque foi pedido, e porque cada item mudaria uma decisão.

1. **Bateria não é critério pontuado do edital.** As tabelas §11.1 e §11.2 não têm
   linha de energia. Bateria é **checkpoint obrigatório** (§8.1) — portão, validado às
   16h00 do dia 18/09 junto com privacidade. Isso muda a estratégia: o entregável não
   é um número de %/h, é **uma narrativa defensável** ("estratégia clara"), e ela vale
   mais se fechar privacidade junto (P3).

2. **"24,9% → 4,5% de um núcleo" não existe no repositório.** Nem em `.kt`, nem em
   `.md`, nem em `git log -S`. O que existe: p50 de **3,5 ms**/decisão ≈ **4,4% de um
   núcleo** (`EscutaDeAtivacao.kt:54`), e a remoção de **781 KB/s de alocação**
   (`DetectorDeAtivacao.kt:169`). Pela régua do projeto, medida não escrita não
   existe. **E os testes que imprimem o custo não o assertam** — reintroduzir a
   alocação mantém os dois verdes (§2.4).

3. **O `ROADMAP.md` não tem seção de Energia.** Ela está em
   `docs/PADROES_DE_ENGENHARIA.md:249-256`, e a única meta numérica — ≤ 12%/h — está
   em `:292` e **nunca foi medida**, como o próprio `VERIFICACOES:147` admite.

4. **A gambiarra de bateria externa não está no material.** 53 capturas lidas uma a
   uma, 15 PDFs extraídos e varridos: nada. Não está refutada por falta de mérito —
   está **ausente da fonte**. O que a refuta para 18/09 é o edital §9 (hardware
   devolvido) e a aritmética do estojo (§1.5, §3.3-P6).

5. **O consumo do detector é provavelmente irrelevante perto de duas coisas que não
   estavam na lista.** (a) **A recarga do canal por HTTP a cada 10 s sem porta de
   ciclo de vida** — 4 320 requisições por turno com a tela apagada, segurando o modem
   acordado (§2.1); e (b) **o SCO aberto o turno inteiro**, que é pago pelos óculos.
   O detector custa 4,4% de um núcleo numa thread que o SCO já acorda 50×/s de
   qualquer jeito.

6. **A política de energia por modo não roda em produção.** `ModoOperacao` é
   efetivamente a constante `ATIVO`; `OCORRENCIA` é inalcançável fora de `src/debug`;
   "modo ocorrência" por voz só suprime informativo (§2.3). O enunciado dizia que "o
   produto sabe o modo" — ele **sabe nomear** o modo, e não o **usa**.

7. **A liberação do whisper por `TRIM_MEMORY_UI_HIDDEN` tem dois defeitos, não um.**
   Além da justificativa falsa, a cláusula é **código morto** e o `>=` sobre
   `TRIM_MEMORY_*` não é comparação de severidade — provado por `javap` nas constantes
   (§2.5).

8. **Não existe API para ler a bateria dos óculos.** `DeviceState` tem um único campo
   e é térmico (`javap`, `mwdat-core-0.9.0`). Qualquer proposta que dependa de ler o
   nível é impossível — e é por isso que P2 constrói o medidor com lápides.

9. **Correção a um achado da própria pesquisa, para não propagar erro.** Chegou a ser
   levantado que os nomes de `ErroDeStream` em `core-glasses/.../Models.kt` divergiam
   do SDK. **Não divergem.** Rodei `javap` em `mwdat-camera-0.9.0`:
   `StreamError { STREAM_ERROR, CRITICAL_STREAM_ERROR, HINGE_CLOSED, PERMISSIONS_DENIED,
   THERMAL_HOT, BATTERY_LOW, PEAK_POWER_LIMIT, TIMEOUT }` — bate exatamente. A
   confusão era com `DeviceSessionError` (de `mwdat-core`), que é outro enum, tem
   `BATTERY_CRITICAL`/`THERMAL_CRITICAL`/`PEAK_POWER_SHUTDOWN`, e **esse** sim não tem
   espelho no projeto (§2.2, P2).

---

## 5. O que ainda não sei, e o que resolveria

| Pergunta em aberto | Como se resolve | Bloqueia |
|---|---|---|
| Quanto custa o SCO aberto, nos óculos? | Corrida A/B até a morte, cronômetro + lápide (P2) | R2, P1, P3 — o eixo inteiro |
| Quanto custa a `DeviceSession` ociosa? | Terceira corrida: sessão sem SCO × sem sessão | a decisão "abrir sob demanda" |
| Quanto tempo o SCO leva para subir? | `nanoTime` entre `setCommunicationDevice` e o 1º quadro | se o SCO pode fechar em serviço (P1) |
| Qual a fração do turno fora do rosto? | Cronômetro num acompanhamento de turno | dimensionar P3 |
| O polling de 10 s domina mesmo? | `dumpsys batterystats`, 30 min, com e sem a porta | P4 — e a hipótese pode morrer aí |
| Os óculos são Gen 1 ou Gen 2 em 18/09? | Perguntar à organização | 4 h × 8 h muda o número de acoplamentos por turno |
| "5,3 h de chamada" é real? | Substituído por P2; não depende de confirmar | dimensionar P6 |
