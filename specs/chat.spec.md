---
feature: chat
capacidade: C1 (rádio tático) — frente de superfície
estado: proposta
autor: revisão humana pendente
criada: 2026-08-15
sobrepoe:
  - "app/src/main/kotlin/com/claryon/field/ui/telas/TelaDeGuarnicao.kt:207-214 — KDoc de `LinhaDeFala`, 'bolha alinhada à direita para o eu é gramática de aplicativo social'"
  - "app/src/main/kotlin/com/claryon/field/ui/telas/TelaDeGuarnicao.kt:250 — rótulo de entrega pintado com `Cores.P2`"
depende_de:
  - estado-de-entrega-da-fala-propria
  - presenca-de-quem-fala
---

# Thread da guarnição

## Objetivo

Fazer o histórico do canal ser lido **de relance**, no suporte da viatura, com o
agente dirigindo ou com as mãos ocupadas. Hoje o histórico é uma pilha de linhas
uniformes (`TelaDeGuarnicao.kt:224-259`): para saber se uma fala é sua ou de um par,
é preciso ler o indicativo. Isso é decodificação, não leitura.

O ganho está numa coisa só: **saber de que lado veio antes de ler qualquer texto.**

## O que este documento sobrepõe, e por quê

O KDoc de `LinhaDeFala` (`TelaDeGuarnicao.kt:207-214`) recusa balões porque "bolha
alinhada à direita para o eu é gramática de aplicativo social". A recusa acerta o
risco e erra o alvo: o que faz um balão parecer social são três coisas separáveis —
**canto arredondado**, **rabinho**, e o **"eu" pintado de cor viva**. Nenhuma das
três é necessária para lateralidade.

**Redação nova proposta para a regra:**

> O histórico do canal usa lateralidade — bloco alinhado a um lado, com margem
> reservada do lado oposto. Não usa canto arredondado, rabinho, sombra nem cor viva.
> A fala própria é a **rebaixada**, não a realçada: o agente já sabe o que disse; o
> que ele pode ter perdido é o que entrou.

A inversão em relação ao WhatsApp não é estilo, é o modelo de uso. Quem rola este
histórico está procurando o que perdeu.

## Comportamento

### Dois tipos de registro, um só thread

| Tipo | Condição | Largura | Alinhamento |
|---|---|---|---|
| Conversa recebida | `prioridade == null && !propria` | encolhe, teto de 84% | esquerda |
| Conversa própria | `prioridade == null && propria` | encolhe, teto de 84% | direita |
| Registro de canal | `prioridade != null` | largura inteira | sem lado |

Alerta classificado não é mensagem de alguém: é registro do canal, e ocupa a linha
inteira com banda de classificação no topo, como num terminal de despacho. É essa
segunda forma que impede a tela de virar bate-papo, e ela sai de graça do
`prioridade: Int?` que `RadioViewModel.kt:201` já preenche.

### Fronteira de teste — requisito estrutural, não sugestão

Não existe `androidx.compose.ui:ui-test-junit4` no projeto (`app/build.gradle.kts:135-140`;
`gradle/libs.versions.toml` não declara o módulo). Sem ele, nenhum critério é
verificável se a decisão morar dentro do `@Composable`.

Portanto: **toda decisão desta tela é função pura, em arquivo sem import de
Compose**, e o composable só resolve token em pixel. Assinaturas:

```kotlin
// app/src/main/kotlin/com/claryon/field/ui/telas/TrafegoDoCanal.kt — zero import de Compose

enum class FormaDoRegistro { RECEBIDO, PROPRIO, REGISTRO_DE_CANAL }
enum class TokenDeCalha  { TRACO, TRACO_FORTE, P1, P2, P3 }
enum class TokenDeTinta  { TINTA, TINTA_MEDIA, TINTA_FRACA }
enum class RotuloDeEntrega { ENVIADA, NA_FILA }

data class ItemDeTrafego(
    val fala: FalaNoGrupo,
    val forma: FormaDoRegistro,
    val calha: TokenDeCalha,
    val tintaDoTexto: TokenDeTinta,
    val faixaHoraria: String?,
    val mostraIndicativo: Boolean,
    val rotuloDeEntrega: RotuloDeEntrega?,
)

fun montarTrafego(falas: List<FalaNoGrupo>): List<ItemDeTrafego>
fun deveRolarParaOFim(ultimoVisivel: Int, ultimoIndice: Int, folga: Int): Boolean
fun leituraEmVoz(item: ItemDeTrafego): String
fun rotuloDePrioridade(prioridade: Int): String
```

O tipo de retorno é enum e não `Color`/`Dp` de propósito: `Color` é value class sobre
`ULong` e passaria no unit test, mas amarrar o núcleo puro ao artefato de UI é como
ele volta a exigir aparelho no próximo refactor.

### Critérios de aceite (EARS)

**Lateralidade e forma**

1. `Quando` `montarTrafego` receber uma fala com `propria == true` e `prioridade == null`,
   `o sistema deverá` classificá-la como `FormaDoRegistro.PROPRIO`.
2. `Quando` `montarTrafego` receber uma fala com `propria == false` e `prioridade == null`,
   `o sistema deverá` classificá-la como `FormaDoRegistro.RECEBIDO`.
3. `Quando` `prioridade != null`, `o sistema deverá` classificar como
   `REGISTRO_DE_CANAL` **independentemente** de `propria`.
4. `Enquanto` um registro for `PROPRIO` ou `RECEBIDO`, `o sistema deverá` reservar
   16% da largura da lista como margem vazia do lado oposto ao bloco.
5. `Enquanto` qualquer registro estiver na tela, `o sistema deverá` desenhá-lo como
   retângulo: raio de canto 0 dp, elevação 0 dp, sem rabinho e sem ripple.

**Superfície e tinta**

6. `Quando` a forma for `PROPRIO`, `o sistema deverá` usar fundo `Cores.Painel` e
   `TokenDeTinta.TINTA_MEDIA` no corpo.
7. `Quando` a forma for `RECEBIDO` ou `REGISTRO_DE_CANAL`, `o sistema deverá` usar
   fundo `Cores.Elevado` e `TokenDeTinta.TINTA` no corpo.
8. `Enquanto` houver registro na tela, `o sistema deverá` alinhar o **corpo do texto
   à esquerda**, inclusive no bloco da direita.

**Agrupamento e tempo**

9. `Quando` uma fala recebida tiver o mesmo `indicativo` da fala imediatamente
   anterior, `e` a anterior também for recebida, `o sistema deverá` omitir o
   indicativo no cabeçalho (`mostraIndicativo == false`).
10. `Enquanto` um registro estiver na tela, `o sistema deverá` exibir o carimbo de
    hora, **inclusive** em continuação de sequência. O horário é o que faz o
    histórico servir de log.
11. `Quando` a hora de uma fala cair numa faixa horária diferente da fala anterior,
    `o sistema deverá` emitir um separador com o rótulo `HH:00` acima dela.
12. `Se` a hora chegar como `"--:--:--"` (fallback de `RadioViewModel.kt:225`),
    `então o sistema deverá` devolver `faixaHoraria == null` e **não** desenhar
    separador. Inventar faixa para horário desconhecido é a interface afirmando o
    que o dado não sustenta.
13. `Se` a recarga de 10 s (`RadioViewModel.kt:361`) devolver lista estruturalmente
    igual à anterior, `então o sistema deverá` reaproveitar o resultado de
    `montarTrafego` sem remontar.

**Rolagem**

14. `Quando` chegar registro novo `e` o último item visível estiver a até 3 posições
    do fim, `o sistema deverá` rolar até o último item.
15. `Se` o último item visível estiver a mais de 3 posições do fim, `então o sistema
    deverá` **não rolar**, e manter a posição de leitura.
16. `Se` só o estado de entrega de um registro já visível mudar, `então o sistema
    deverá` não rolar.

**Estado de entrega**

17. `Quando` o agente soltar o PTT `e` o transporte estiver conectado
    (`RadioViewModel.kt:324`), `o sistema deverá` inserir a fala local com
    `Entrega.ENVIADA` e rótulo `"enviada"`.
18. `Se` o transporte não estiver conectado ao soltar, `então o sistema deverá`
    inserir a fala local com `Entrega.ENFILEIRADA` e rótulo `"na fila"`.
19. `Enquanto` um registro for `RECEBIDO`, `o sistema deverá` não exibir rótulo de
    entrega nenhum.

**Gramática cromática**

20. `Enquanto` existir registro na tela, `o sistema deverá` garantir que nenhum
    `TokenDeCalha` de prioridade (`P1`/`P2`/`P3`) seja produzido a partir de estado
    de entrega, e que nenhum rótulo de entrega use token de prioridade.
21. `Enquanto` existir registro na tela, `o sistema deverá` não usar `Cores.NoAr` nem
    `Cores.NoArFraco` em elemento nenhum do thread. Âmbar significa "você está no
    ar", e só isso (`Cores.kt:16-21`).
22. `Quando` a forma for `REGISTRO_DE_CANAL`, `o sistema deverá` escrever o rótulo da
    banda em `Cores.Tinta`, **não** na cor da prioridade: `P3` sobre `Elevado` rende
    4,28:1, abaixo do mínimo de 4,5:1 para texto pequeno. A cor da prioridade fica na
    banda e na calha, que são elemento não-textual.
23. `Quando` a prioridade for conhecida, `o sistema deverá` expor os três canais em
    paralelo: cor, largura da calha (4/3/2 dp) **e** rótulo escrito
    (`"P1 emergência"` / `"P2 apoio"` / `"P3 informativo"`). Hoje `P2` e `P3` diferem
    por 1 px (`Comuns.kt:128-135`), ou seja diferem só por cor.

**Acessibilidade**

24. `Quando` o leitor de tela percorrer o histórico, `o sistema deverá` anunciar cada
    registro como **um** nó, na ordem: classificação, autor, hora, texto, e "ainda na
    fila" quando couber.
25. `Quando` o registro for próprio, `o sistema deverá` anunciar o autor como "Você",
    e não como o indicativo — a lateralidade é visual e não sobrevive ao áudio.
26. `Se` o registro for `REGISTRO_DE_CANAL` `e` `propria == true`, `então o sistema
    deverá` manter o indicativo visível no cabeçalho. A largura inteira apaga a
    lateralidade justamente no registro mais importante; sem o indicativo, um P1
    próprio fica indistinguível de um P1 recebido.

### Não-funcionais

| Métrica | Alvo | Como medir |
|---|---|---|
| p95 do tempo de frame rolando 300 registros | ≤ 16 ms | `adb shell dumpsys gfxinfo com.claryon.field framestats`, 3 varreduras ponta a ponta |
| Passes de medida por item | 1 | teste de fonte que falha se `IntrinsicSize` aparecer no arquivo; confirmação no Layout Inspector |
| Contraste do corpo sobre o fundo do bloco | ≥ 4,5:1 | `ContrasteDosTokensTest` (WCAG 2.1 sobre os hex de `Cores.kt`). Valores computados: `TintaMedia`/`Painel` = 5,89:1; `Tinta`/`Elevado` = 14,5:1 |
| Contraste da calha de prioridade contra `Vazio` | ≥ 3:1 | idem. `P3`/`Vazio` = 5,01:1, o pior caso das três |
| Remontagens de `montarTrafego` por ciclo de recarga sem mudança | 0 | teste JVM de igualdade estrutural de `List<FalaNoGrupo>` |
| Registros até o thread ficar inutilizável em memória | ≥ 2 000 | teste JVM de `montarTrafego` com lista sintética, medindo alocação |

### Fora de escopo

- **Lista de conversas.** O app tem um talk group só (`MainActivity.kt:236-237`), e
  `Destino.GUARNICAO` **é** o thread. Uma lista de um item é cerimônia. A régua de
  presença (`TelaDeGuarnicao.kt:124-185`) é o que faz o papel de "com quem eu estou
  falando". Quando houver segundo canal, a lista entra como novo `Destino`, não como
  camada dentro desta tela.
- Digitar texto. Não há caminho de teclado no fluxo, e não deve haver: a entrada é
  voz e o PTT.
- Apagar, editar, responder, reagir, anexar. Tráfego de rádio é registro, não
  conversa revisável.
- Busca e paginação do histórico. `carregarCanal` recarrega a lista inteira
  (`RadioViewModel.kt:189-206`); paginar é outra spec, e mexe no `HistoricoDoCanal`.
- Animação de entrada por item. O `AnimatedVisibility` atual (`TelaDeGuarnicao.kt:220-223`)
  redispara toda vez que o item volta à viewport, então rolar para cima faz histórico
  antigo aparecer com fade — movimento que denuncia enfeite. Sai, e não volta.

## Riscos aceitos

- **A lateralidade não vem da superfície; vem da geometria.** Medido: `Painel` sobre
  `Vazio` = **1,07:1**; `Elevado` sobre `Vazio` = **1,17:1**; `TracoForte` sobre
  `Vazio` = **1,71:1**. Nenhum token neutro da paleta alcança 3:1 contra o fundo. O
  que carrega o lado é o alinhamento e a margem de 16%, e a calha cinza é reforço,
  não sinal. Se em LCD ao sol o bloco sumir, a correção **não** é cor nova: é subir
  próprio para `Elevado` e recebido para `Pressionado` (`Cores.kt:35-44`), o que
  ainda deixa o contraste de superfície abaixo de 3:1 e apenas melhora a borda.
- **`weight` reparte entre irmãos com peso, não fração do pai.** O teto de 84%
  depende de existir um `Box` vazio de peso 0,16 como irmão. Quem "limpar" esse Box
  achando que é resíduo mata o critério 4 **sem erro de compilação**.
- **P2 passa a ter duas leituras no projeto.** Trocar `Cores.P2` por `Cores.Tinta` no
  rótulo de entrega vale só dentro da guarnição. Fora dela, P2 segue como "atenção
  genérica" onde não há faixa de prioridade por perto: `TelaDoMapa.kt:136,328,463`,
  `TelaDeLogin.kt:99`, `BarraDePtt.kt:296`. É dívida declarada, e alguém vai tropeçar.
- **A faixa horária faz cirurgia de string sobre `hora`**, porque `FalaNoGrupo` guarda
  o horário já formatado. Se `RadioViewModel.HORA` (`:365`) deixar de ser `HH:mm:ss`,
  o separador some em silêncio. A correção certa é `FalaNoGrupo` carregar o instante
  além do texto — fora deste escopo porque mexe em `RadioViewModel`.
- **`FaixaDePrioridade` (`Comuns.kt:128-135`) fica sem chamador.** Ou é apagada nesta
  entrega, ou volta a ser usada. Componente vivo sem uso é como o mapa de cores volta
  a divergir no próximo refactor.

## Precondições que esta spec não negocia

Os critérios 17 e 18 **não são trabalho de acompanhamento**. Hoje o único produtor de
`FalaNoGrupo` é `RadioViewModel.kt:191-203`, e ele grava `Entrega.RECEBIDA` fixo —
inclusive quando `propria == true` (`:197`, `:202`). `Entrega.ENVIADA` não tem
ocorrência em lugar nenhum do `app/`; `ENFILEIRADA` só aparece no consumidor
(`TelaDeGuarnicao.kt:246`). Entregar o redesenho sem o produtor em `aoSoltar`
(`RadioViewModel.kt:317`) acrescenta a sexta capacidade morta que o `AGENTS.md`
descreve.

O mesmo vale para `ParPresente.falando`, `false` fixo em `RadioViewModel.kt:217`: o
`PontoDeEstado(pulsando = true)` de `TelaDeGuarnicao.kt:168-175` nunca pulsa, e a
ordenação "quem fala primeiro" (`:157-159`) nunca reordena nada.

## Testes

Cada critério mapeia para pelo menos um teste. Os de 1 a 3 e 6 a 26 são
determinísticos e ficam na JVM, porque a decisão é função pura.

| Critérios | Teste | Onde |
|---|---|---|
| 1-3, 9-13 | `TrafegoDoCanalTest` — classificação, agrupamento, faixa horária, fallback `--:--:--`, reaproveitamento | `app/src/test/kotlin/com/claryon/field/ui/` |
| 6-7 | `TrafegoDoCanalTest#tokensDeSuperficie` | JVM |
| 14-16 | `RolagemDoTrafegoTest` — tabela sobre `deveRolarParaOFim` | JVM |
| 17-19 | `EstadoDeEntregaTest` — `aoSoltar` com transporte conectado e desconectado, transporte falso | JVM |
| 20-21 | `GramaticaDeCorTest` — varre o produto cartesiano de `Entrega` × `prioridade` × `propria` e falha se algum `TokenDeCalha` de prioridade sair de estado de entrega, ou se `NoAr`/`NoArFraco` aparecerem no arquivo | JVM |
| 22-23 | `ContrasteDosTokensTest` — razão WCAG 2.1 computada dos hex de `Cores.kt`; `rotuloDePrioridade` cobre os três valores e o `else` | JVM |
| 24-25 | `LeituraEmVozTest` — ordem dos campos, "Você" para próprio, sufixo de fila | JVM |
| 26 | `TrafegoDoCanalTest#alertaProprioMantemIndicativo` | JVM |
| 4-5, 8 | Sem teste automatizado hoje. Verificação por captura em aparelho, registrada em `docs/VERIFICACOES_COM_HARDWARE.md` | aparelho |

O limite de sete palavras do TTS operacional (`AGENTS.md`) **não** se aplica ao
critério 24: aquele limite vale para a fala que o produto emite com o agente de mãos
ocupadas. Isto é o leitor de tela do aparelho, acionado por quem está lendo de
propósito. `LeituraEmVozTest` afirma essa distinção por escrito.

## Perguntas abertas para a revisão humana

1. **Critérios 4, 5 e 8 ficam sem teste, ou entra `androidx.compose.ui:ui-test-junit4`?**
   O `AGENTS.md` exige justificar tamanho, licença e alternativa nativa de toda
   dependência nova. A dependência é `androidTestImplementation` (não vai no APK de
   release), Apache-2.0, e casa com a BOM `2024.06.00` já em uso; a alternativa
   nativa é captura manual em aparelho, que não roda em CI. Decisão de quem revisa.
2. **A entrega deve ser um commit só** com o produtor de `Entrega` incluído, ou dois
   commits com o segundo obrigatório antes do merge? A spec pede o primeiro.

## Estado da verificação

Assinaturas de Compose usadas pelo desenho foram conferidas por `javap` nos AAR do
cache do Gradle nas versões que a BOM `2024.06.00` fixa — `foundation-layout-android/1.6.8`,
`foundation-android/1.6.8`, `ui-android/1.6.8`: `RowScope.weight` e `ColumnScope.weight`
(com `weight$default`, `fill` tem default), `ColumnScope.align`, `RowScope.align`,
`SemanticsModifierKt.clearAndSetSemantics`, `SemanticsPropertiesKt.setContentDescription`,
`LazyListState.getLayoutInfo`, `LazyListLayoutInfo.getVisibleItemsInfo`,
`getTotalItemsCount`, `animateScrollToItem`, `LazyDslKt.items`.

**Nada foi compilado.** Não há JDK no `PATH` desta máquina — `/usr/libexec/java_home`
falha e o único `javap` disponível está em `/Applications/Android Studio.app/Contents/jbr/`.
`./gradlew :app:compileDebugKotlin` precisa rodar com
`JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home` antes de
qualquer commit.
