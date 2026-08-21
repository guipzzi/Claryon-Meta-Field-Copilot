---
feature: guarnicao-como-grupo
capacidade: C1 (rádio tático) — frente de superfície · reentrada no canal
estado: proposta
autor: revisão humana pendente
criada: 2026-08-21
sobrepoe:
  - "app/src/main/kotlin/com/claryon/field/ui/telas/TelaDeGuarnicao.kt:601-629 — KDoc de `EntradaNoCanal`, 'O botão «entrar no canal» NÃO está aqui, e a recusa é deliberada'"
  - "app/src/main/kotlin/com/claryon/field/ui/telas/TelaDeGuarnicao.kt:262-266 — 'A mono ficou, e é decisão' sobre o nome do canal no cabeçalho"
  - "specs/chat.spec.md §Fora de escopo — 'Lista de conversas. O app tem um talk group só … Uma lista de um item é cerimônia'"
depende_de:
  - troca-de-grupo-por-voz
---

# A guarnição com a estrutura de um grupo de mensageiro

## Objetivo

Dar à tela da guarnição a forma que o agente já sabe ler — cabeçalho com marca,
nome e subtítulo; a página do grupo atrás do nome; a volta para a lista de
grupos — **sem que um único elemento dessa forma afirme dado que este produto não
tem.**

O pedido literal foi: nome em negrito e foto do grupo no topo; quantos estão
online abaixo do nome; ao lado, a opção de ficar online ou offline naquela
guarnição; tocar no nome abre a página do grupo com membros, online e offline, e
há quanto tempo; botão à esquerda para voltar e ver as guarnições disponíveis
para entrar.

Quatro desses sete itens têm fonte, dois têm fonte **diferente da que o nome
sugere**, e um não tem fonte nenhuma. A seção seguinte é o inventário, e é ela
que decide o desenho.

## Inventário de fontes — o que existe, conferido migração por migração

| Pedido | Fonte | Veredito |
|---|---|---|
| Nome do grupo | `talk_groups.nome`, via `meus_rotulos_falados()` (`0011`) → `GrupoFalado.nome` | **Existe.** Hoje a tela recebe `CanalDoPiloto.NOME`, constante — ver critério 1 |
| **Foto do grupo** | — | **NÃO EXISTE.** `talk_groups` é `id, unit_id, nome, tipo` (`0001:36-41`) + `rotulo_falado` (`0011`). `grep -i 'foto\|avatar\|imagem\|photo\|descricao'` nas 23 migrações devolve **zero**. Não há bucket de Storage para grupo. **Recusado** — ver critério 3 |
| "Quantos estão online" | `ParPresente.online` ← `idadeDaPosicaoS <= 120` (`RadioViewModel:471`) | **Existe com outro nome.** É "publicou posição há ≤ 2 min", nunca "está com o app aberto". A política de presença do servidor está **deliberadamente negada** em `0012` — só `broadcast` tem política. O rótulo diz **posição** |
| Membros do grupo | `cadastro_do_grupo(talk_group)` (`0013`) → `{agent_id, indicativo}` de **toda** a `memberships` | **Existe, e é o cadastro completo.** Já é chamado a cada 10 s em `RadioViewModel.carregarCanal` e colapsado num `Map` de autoria — a tela nunca o recebeu |
| "Há quanto tempo" | `posicoes_do_grupo.idade_s` (`0009`, corrigido em `0020`) e `idade_solicitante_s` para o próprio | **Existe.** É a idade da **posição**, não "visto por último" — este produto não guarda visto |
| Ficar online/offline | `RadioViewModel.abrir` / `fechar`; recusa por JWT em `0012` chega como `EstadoDoPtt.Indisponivel(motivo)` | **Existe como entrar/sair do CANAL.** Ver §"O que 'online/offline' virou" |
| Guarnições para entrar | `CanaisDoAgente.grupos` ← `meus_rotulos_falados()` (`0011`) | **Existe, com um recorte.** A função filtra `rotulo_falado is not null`: guarnição sem rótulo falado **não aparece**. `CanaisDoAgente.grupos` tem **zero chamadores** hoje — `grep` devolve só a própria definição |

### O achado que muda o denominador

`HistoricoDoCanal.membros()` deriva de `posicoes_do_grupo`, que faz `join` com
`agent_positions`. Quem **nunca** publicou posição não aparece — nem como
ausente. A contagem do cabeçalho, hoje, não é a guarnição: é quem tem posição, e
o `KDoc` de `DetalhesDaGuarnicao` já escrevia isso ("Ela é menor que a guarnição,
e não sabe dizer quanto menor").

**Não precisa continuar assim.** `cadastro_do_grupo` (`0013`) devolve a
`memberships` inteira, o cliente já a busca a cada recarga, e o próprio KDoc dela
diz por que ela existe: *"um colega legítimo que ainda não publicou ficaria de
fora"*. O que faltava era caminho até a tela.

Esta spec cruza as duas fontes: **o cadastro dá o denominador, a posição dá a
idade.** O que sobra de incerteza é menor e nomeável — ver critério 12.

## O que "online/offline" virou, e por quê

O pedido diz "online ou offline naquela guarnição". Duas leituras cabem na
frase, e elas têm fontes diferentes:

1. **"Estou publicando posição"** — não é controlável aqui. Quem publica é o
   `CopilotService`, que roda com o app fechado e é derrubado só pelo "Encerrar
   turno". Um botão nesta tela que prometesse desligar isso mentiria.
2. **"Estou na escuta do canal"** — é controlável, e é o que o agente quer dizer
   quando diz que "saiu". `RadioViewModel.abrir` levanta rota de áudio,
   transporte, piso e recarga; `fechar` desfaz.

A spec adota (2) e **escreve (2) na tela**: *"Na escuta"* e *"Fora"*, nunca
"online"/"offline". Um agente fora da escuta continua publicando posição, e
continua aparecendo "com posição" para os colegas — se o rótulo dissesse
"offline", ele acreditaria ter sumido do mapa da guarnição, e não sumiu.

### As duas cicatrizes que este controle não pode reabrir

- **Nada de "entrou" antes do servidor confirmar.** Este projeto já publicou
  **168 quadros para um canal em que não tinha entrado**, com o indicador aceso.
  É por isso que `Movimento` não tem `PisoPendente`, e é por isso que o rótulo do
  controle **não** é o que o agente pediu: é o que o rádio reporta.
- **A recusa é visível, e não vira "conectando" perpétuo.**
  `EstadoDoPtt.Indisponivel` já carrega o `Unauthorized` literal do canal privado
  (`0012`), traduzido por `ProtocoloRealtime` em `CanalRecusado` e reescrito pelo
  `RadioViewModel` como *"Canal negado. …"*. Ele sai em `Cores.FalhaTexto`
  (6,76:1), com o motivo palavra por palavra.

## Comportamento

### O cabeçalho — duas linhas, quatro elementos

```
┌──────────────────────────────────────────────────────────┐
│  ‹    (GA)   GTA-3 Alfa                     [ Na escuta ] │
│              4 na guarnição · 2 com posição               │
└──────────────────────────────────────────────────────────┘
   ①      ②        ③                              ④
```

① volta para a lista de guarnições · ② marca do grupo (**não** foto) · ③ nome +
subtítulo, e é a porta da página do grupo · ④ entrar/sair do canal.

A segunda linha tem **altura fixa** e três conteúdos possíveis, nesta ordem de
precedência:

1. **Recusa** — quando o agente pediu escuta e o rádio reporta `Indisponivel`:
   o motivo, em `Cores.FalhaTexto`, uma linha, com reticências. Vem primeiro
   porque, sem canal, "quem está no ar" é afirmação sobre um canal que não
   estamos ouvindo.
2. **Quem está no ar**, quando há piso concedido.
3. **A contagem**, que é o caso comum.

### A página do grupo — o que ela mostra

Ordem, de cima para baixo: marca e nome · rótulo falado (é assim que o canal se
abre por voz, e é dado) · **sua entrada** com o botão de entrar/sair e o motivo
literal da recusa · **quem é da guarnição**, com idade de posição por linha ·
e o parágrafo do que a lista é.

### A lista de guarnições — o que ela mostra

Uma linha por grupo de `CanaisDoAgente.grupos`, com nome, rótulo falado e a
marca do corrente. Entrar é o **mesmo caminho da voz**:
`CanaisDoAgente.trocar(rotuloFalado)`, que passa por `PoliticaDeTrocaDeGrupo`.
Não há segunda porta para o rádio, e é deliberado: duas portas para o mesmo
socket são duas verdades sobre em que grupo estamos.

### Critérios de aceite (EARS)

**O nome do canal**

1. `Enquanto` a tela da guarnição estiver composta, `o sistema deverá` exibir o
   nome que o **servidor** devolveu para o grupo corrente, e não
   `CanalDoPiloto.NOME`. Hoje `MainActivity:262` passa a constante, e o
   `RadioViewModel` reconcilia o canal com o cadastro trinta linhas depois sem
   que a tela saiba — um agente de outra lotação leria "GTA-3 Alfa" no topo
   estando noutra guarnição.
2. `Enquanto` o léxico não tiver carregado
   (`CanaisDoAgente.canalConfirmadoPeloServidor == false`), `o sistema deverá`
   marcar o nome como **não confirmado** na página do grupo. O campo existe
   desde a `0011` com esta finalidade escrita e **zero chamadores**.

**A marca do grupo — a recusa da foto**

3. `Enquanto` a tela estiver composta, `o sistema deverá` desenhar a marca do
   grupo a partir do **nome**, e `deverá não` exibir, buscar ou reservar espaço
   para imagem de grupo. Não há coluna, não há bucket, e um retângulo vazio
   pedindo foto é como a invenção entra em produção.
4. `Quando` a marca for desenhada, `o sistema deverá` derivá-la de forma
   **determinística** do nome, para que o mesmo grupo tenha sempre a mesma marca
   e dois grupos diferentes tendam a marcas diferentes.
5. `Enquanto` a marca estiver na tela, `o sistema deverá` usar apenas tokens de
   base (croma zero). Cor por grupo seria uma quinta gramática cromática num
   painel em que cor significa prioridade e transmissão.

**A contagem, e o que ela conta**

6. `Quando` o cadastro do grupo tiver sido carregado, `o sistema deverá` usar
   `cadastro_do_grupo` como **denominador** — o número de membros da
   `memberships` — e `posicoes_do_grupo` apenas para a **idade** de cada um.
7. `Enquanto` a contagem estiver na tela, `o sistema deverá` escrever a palavra
   junto do número (`"4 na guarnição · 2 com posição"`), nunca um `2/4` cru.
8. `Se` o cadastro ainda não tiver chegado, `então o sistema deverá` contar
   apenas o que tem e **dizer que só tem isso**, jamais apresentar a lista de
   posições como se fosse a guarnição.
9. `Enquanto` qualquer rótulo de presença estiver na tela, `o sistema deverá`
   escrever **posição**, e `deverá não` escrever "online", "offline", "ativo",
   "visto por último" ou "no canal" a respeito de um par.

**Há quanto tempo**

10. `Quando` um membro tiver posição publicada, `o sistema deverá` exibir a
    **idade** dela em linguagem de duração (`"há 12 s"`, `"há 4 min"`,
    `"há 1 h 5 min"`), truncada para baixo.
11. `Se` um membro não tiver posição, `então o sistema deverá` escrever
    `"sem posição"` — e `deverá não` inferir dela ausência do agente.
12. `Enquanto` a página do grupo estiver na tela, `o sistema deverá` declarar por
    escrito que a idade vem do **upload** da posição e é otimista pelo tempo de
    ida (`0020`), e que membro sem idade é membro que nunca publicou — não
    membro que saiu.

**Entrar e sair do canal**

13. `Quando` o agente acionar o controle de escuta `e` o rádio estiver fechado,
    `o sistema deverá` chamar `RadioViewModel.abrir` com os mesmos argumentos da
    primeira abertura.
14. `Quando` o agente acionar o controle de escuta `e` o rádio estiver aberto,
    `o sistema deverá` chamar `RadioViewModel.fechar`.
15. `Enquanto` houver um pedido de escuta em curso, `o sistema deverá` derivar o
    rótulo do controle **exclusivamente de `EstadoDoPtt`**, e `deverá não`
    exibir, animar ou colorir estado de "entrando", "conectando" ou "entrou"
    antes de o rádio reportar. Não há dado de "conectando"; inventar um é a
    versão animada dos 168 quadros.
16. `Quando` o rádio reportar `Indisponivel` `e` o agente tiver pedido escuta,
    `o sistema deverá` exibir o **motivo literal** — incluindo o `Unauthorized`
    do canal privado — em `Cores.FalhaTexto`, no cabeçalho e na página do grupo.
17. `Quando` o agente sair do canal por vontade própria, `o sistema deverá`
    distinguir isso da recusa: `Cores.TintaFraca` e a frase *"Você saiu do
    canal"*, nunca a cor de falha. Sair não é erro.
18. `Enquanto` o agente estiver fora do canal, `o sistema deverá` declarar as
    consequências que ele não vê: o histórico para de recarregar e o PTT fica
    indisponível.

**A lista de guarnições**

19. `Quando` o agente tocar o controle de voltar, `o sistema deverá` abrir a
    lista de guarnições do agente, vinda de `CanaisDoAgente.grupos`.
20. `Enquanto` a lista estiver na tela, `o sistema deverá` declarar que ela é
    **as guarnições com rótulo falado** — `meus_rotulos_falados()` filtra
    `rotulo_falado is not null` —, e que uma guarnição sem rótulo não aparece
    ali. Uma lista que se apresenta como completa sem ser é o mesmo defeito da
    contagem de presença, noutro lugar.
21. `Se` o léxico não tiver carregado, `então o sistema deverá` dizer isso e
    `deverá não` mostrar lista vazia como se o agente não tivesse guarnição.
    Vazio e não-carregado pedem recuperações diferentes.
22. `Quando` o agente tocar uma guarnição da lista, `o sistema deverá` entrar por
    `CanaisDoAgente.trocar(rotuloFalado)` — o mesmo caminho do comando falado —
    e `deverá não` abrir uma segunda porta para `RadioTatico.trocarDeGrupo`.
23. `Quando` a troca for recusada, `o sistema deverá` exibir a recusa **na linha
    tocada**, com a causa distinguida: transmissão em curso, rádio fechado, sem
    léxico. As três pedem gestos diferentes do agente.
24. `Enquanto` uma troca estiver em curso, `o sistema deverá` recusar novos
    toques e `deverá não` exibir a guarnição alvo como corrente antes do
    resultado.
25. `Quando` a troca for aceita, `o sistema deverá` recarregar histórico e
    cadastro **do grupo novo**. Hoje o laço de recarga captura o `talkGroupId` do
    parâmetro de `abrir`, então qualquer troca — inclusive a reconciliação
    automática do cadastro, que roda em toda abertura — deixa a tela lendo o
    histórico do grupo anterior.

**Acabamento**

26. `Enquanto` a tela estiver composta, `o sistema deverá` usar canto de 12 dp
    nos blocos e margem simétrica dos dois lados.
27. `Enquanto` o cabeçalho estiver na tela, `o sistema deverá` exibir o nome do
    grupo em **sans, peso semibold, caixa normal**. A mono do nome do canal era
    decisão escrita (`TelaDeGuarnicao.kt:262-266`); esta spec a sobrepõe porque a
    reclamação de "muita mono e muita caixa alta" veio três vezes, e o nome de um
    grupo é o rótulo de uma tela, não um dado que se compara caractere a
    caractere. **Indicativo continua em mono** — esse sim se compara.
28. `Enquanto` a barra de composição estiver na tela, `o sistema deverá` conter
    **apenas** o push-to-talk. "Perguntar ao copiloto" sai — decisão humana desta
    sessão. O ciclo de voz continua alcançável em produção pela palavra de
    ativação (`CopilotService:107`), que é o único chamador que `cicloDeVoz`
    precisa ter.
29. `Enquanto` qualquer texto estiver na tela, `o sistema deverá` manter razão de
    contraste ≥ 4,5:1 sobre a superfície em que assenta, e `deverá não`
    introduzir cor fora de `Cores`.

**Acessibilidade**

30. `Quando` o leitor de tela percorrer o cabeçalho, `o sistema deverá` anunciar
    três nós separados — voltar, abrir a guarnição, e o controle de escuta —
    porque são três ações diferentes.
31. `Quando` o leitor de tela ler o controle de escuta, `o sistema deverá`
    anunciar o **estado do rádio**, não a intenção do agente.

### Fronteira de teste

Vale a regra de `chat.spec.md`: não há `androidx.compose.ui:ui-test-junit4`, então
toda decisão mora em função pura, em arquivo **sem import de Compose**, e o
composable só traduz token em pixel.

```kotlin
// app/src/main/kotlin/com/claryon/field/ui/telas/CadastroDaGuarnicao.kt
// zero import de Compose

data class MembroDaGuarnicao(
    val indicativo: String,
    val idadeDaPosicaoS: Int?,
    val proprio: Boolean,
    val falando: Boolean,
)

data class GuarnicaoNaLista(
    val id: String,
    val nome: String,
    val rotuloFalado: String,
    val corrente: Boolean,
)

enum class RecusaDaTroca { TRANSMITINDO, RADIO_FECHADO, SEM_LEXICO, NAO_RECONHECIDO }
sealed interface ResultadoDaTroca {
    data class Entrou(val nome: String) : ResultadoDaTroca
    data class Recusada(val causa: RecusaDaTroca) : ResultadoDaTroca
}

fun idadeLegivel(idadeS: Int?): String
fun ordenarMembros(membros: List<MembroDaGuarnicao>): List<MembroDaGuarnicao>
fun iniciaisDe(nome: String): String
fun resumoDaGuarnicao(membros: List<MembroDaGuarnicao>, cadastroCarregado: Boolean): String
fun rotuloDaRecusa(causa: RecusaDaTroca): String
```

### Não-funcionais

| Métrica | Alvo | Como medir |
|---|---|---|
| Chamadas de rede acrescentadas por ciclo de recarga | **0** | `cadastro_do_grupo` já é chamada em `carregarCanal`; a mudança é só levá-la à tela |
| Contraste do subtítulo sobre `Cores.Vazio` | ≥ 4,5:1 | `TintaFraca`/`Vazio` computado dos hex de `Cores.kt` |
| Contraste da recusa sobre `Cores.Vazio` | ≥ 4,5:1 | `FalhaTexto`/`Elevado` = 6,76:1, e `Vazio` é mais escuro que `Elevado` |
| Altura do cabeçalho a 1,3× de escala de fonte | sem corte | captura na vitrine com `fontScale` alterado |
| Movimento na troca de conteúdo da 2ª linha | 240 ms, `Morfose` | é morfose de conteúdo já na tela — `motion-design` §B |

### Fora de escopo

- **Presença de verdade** (Realtime Presence). A política está negada em `0012`,
  e ligá-la é mudança de servidor com consequência de privacidade — outra spec.
- **Foto de grupo, mesmo com upload.** Exigiria coluna, bucket, política de RLS e
  uma decisão sobre retenção. Nada disso é o gargalo do produto.
- **Sair da guarnição** (deixar a `memberships`). O controle desta spec é escuta,
  não lotação. Lotação é cadastro da corporação.
- **Desligar a publicação de posição.** Ver §"O que 'online/offline' virou".

## Riscos aceitos

- **`fechar()` derruba a recarga do histórico.** Fora da escuta, as falas
  congelam na última carga. É honesto — não estamos no canal — e está escrito na
  tela (critério 18), mas é uma diferença de comportamento que ninguém pediu e
  que o agente vai notar.
- **`fechar()` chama `audio.liberar()`.** A contagem de usuários é de processo
  (`GlassesAudioManagerImpl:439-441`), então a rota só cai se ninguém mais a
  segurar. **Não medido no aparelho**: se a escuta de "Hey Claryon" for o único
  outro usuário e ela também soltar, sair do canal pode calar a palavra de
  ativação. Precisa de medida, e está em
  `docs/VERIFICACOES_COM_HARDWARE.md`.
- **A lista de guarnições é a dos rótulos falados.** Grupo sem `rotulo_falado`
  existe, funciona e não aparece. O conserto certo é uma RPC de "meus grupos"
  sem o filtro — servidor, outra spec. Até lá, a tela declara o recorte.
- **O denominador melhora, não fica perfeito.** `cadastro_do_grupo` responde
  conjunto vazio para quem não é membro e para chamada sem sessão. Sem sessão, a
  tela cai para a contagem antiga e diz que caiu (critério 8).
- **A régua de indicativos sai do cabeçalho.** Os nomes dos pares em rolagem
  horizontal viravam três elementos disputando a mesma linha com o controle de
  escuta. Eles descem para a página do grupo, a um toque. É perda de relance, e
  é o preço da segunda linha caber.

## Precondições que esta spec não negocia

Os critérios 13-18 **não são trabalho de acompanhamento**. Sem `abrir`/`fechar`
ligados a um controle alcançável, o "online/offline" é rótulo sem tomada — a
oitava capacidade construída e não ligada. E o critério 25 não é polimento:
sem ele, entrar noutra guarnição mostra o histórico da anterior, e a tela
mente sobre a coisa que a feature inteira existe para mudar.

## Testes

| Critérios | Teste | Onde |
|---|---|---|
| 4 | `CadastroDaGuarnicaoTest#iniciaisSaoDeterministicas` — mesmo nome, mesma marca; nomes diferentes, marcas diferentes | JVM |
| 6-8 | `#resumoUsaOCadastroComoDenominador` e o contra-teste `#semCadastroOResumoNaoFingeSaberOTamanho` | JVM |
| 9 | `#nenhumRotuloDizOnline` — varre as saídas das funções puras e falha se aparecer "online"/"offline"/"visto" | JVM |
| 10-11 | `#idadeLegivelTruncaParaBaixo`, `#semPosicaoNaoViraIdadeZero` — o contra-teste é `null → "sem posição"`, nunca `"há 0 s"` | JVM |
| 23 | `#cadaRecusaTemFraseDiferente` — as três causas produzem três frases; se duas colidirem, o agente recebe o gesto errado | JVM |
| 15, 17 | `EscutaDoCanalTest` — a partir de `EstadoDoPtt` × intenção, o rótulo e o token de cor; falha se intenção sozinha produzir rótulo de sucesso | JVM |
| 25 | `RecargaSegueOCanalTest` — o laço lê o canal corrente a cada volta; contra-teste com troca no meio | JVM |
| 1-3, 26-29 | Sem teste automatizado. Captura na vitrine, registrada em `docs/VERIFICACOES_COM_HARDWARE.md` | aparelho |

## Perguntas abertas para a revisão humana

1. **O nome do grupo em sans sobrepõe uma decisão escrita.** O critério 27 diz
   que sim, pela reclamação repetida. Se a leitura for outra — que `GTA-3 Alfa`
   tem dígito e hífen e é dado —, o critério cai e o cabeçalho volta à mono.
2. **Sair do canal deve parar a recarga do histórico, ou só o áudio?** A spec
   aceita o comportamento atual de `fechar()`. Separar as duas coisas é mexer no
   ciclo de vida do rádio.
3. **A lista de guarnições merece uma RPC própria** (`meus_grupos()`, sem o
   filtro de rótulo falado), ou o recorte declarado basta até o piloto?
