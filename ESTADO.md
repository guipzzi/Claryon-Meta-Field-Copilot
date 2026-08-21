# Onde estamos — 2026-08-21 · Placa por voz e por câmera; Fase 6 quase inteira

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **1147 testes JVM, 0 falhas, 0 pulados** — conferido com `git stash -u`,
  ou seja, `HEAD` de pé **sozinho**, sem árvore suja.
- **PTT:** toque→1º quadro **31–48 ms p50** (o p95 é 82 e **126**, e 126 estoura a meta de 120) ·
  P1 corta em **11 ms** · **WER 3,4%** · REVOGAÇÃO/AUTORIA/CANAL PRIVADO POR JWT. **CICLO DE VOZ:
  873–945 ms** (aceite ≤4000), **STT sendo 69%** — medido sobre uma RECUSA; o acerto custa mais,
  porque o Piper expande número por extenso.
- **CONSULTA DE PLACA, DUAS FORMAS.** **Ditando:** alfabeto militar brasileiro, e a PM dita
  algarismo por **ORDINAL** (Portaria 071-CG/15 PMBA: *"Quinto; Quarto Dobrado; Oitavo"*). 84
  elocuções, **40/40**, zero extração errada, zero falso positivo em 44 negativos, **zero placa
  fabricada** em 1817 trechos — com 93 corridas de sete caracteres reprovadas na validação.
  **Pela câmera:** **2 frames, 67–180 ms**; quem limita é a câmera (7 fps), não o OCR, e frames
  vivem em RAM. As duas passam pelo **portão único** de `PlacaValidator`.
- **BASE VEICULAR** (`0023`): verificação **53/53 testada por mutação**, `procedencia`
  oficial/demonstração em **toda** resposta — inclusive na que não acha nada, que é onde não há
  linha para carregar o flag.
- **FALHA DE CÂMERA FALADA**: as oito causas do SDK viravam "Consulta indisponível."; agora falam
  por **recuperação** — abrir as hastes, mexer no Meta AI, esperar esfriar, carregar.
- **SESSÃO DOS ÓCULOS COM DONO DE PROCESSO**, provada no aparelho — e `startSession()` tinha
  **zero chamadores em `src/main`**: nunca era aberta em produção. **MARCA, ABERTURA E LOGIN**: a
  abertura do sistema era **BRANCA**, no app que recusa tema claro por visão noturna. **DESIGN**:
  base croma zero, e `TintaFraca` foi de **2,51:1** (abaixo do AA) para **4,70** — ela é a cor
  padrão dos rótulos que este projeto brigou para tornar honestos.
- **ETAPA A DA FASE 4**: 1817 trechos, índice com 1744, e as **14 perguntas de gramatura foram
  todas recusadas** porque a Lei de Drogas não fixa número. **MEDIDO E OTIMIZADO**: 1ª consulta ao
  índice **4855→618 ms** · detector **24,9%→4,5%** de um núcleo · earcons **14 476→1,3 µs** ·
  APK **−884 KB** ao resolver a colisão do ggml.

## O que está quebrado, e nós sabemos

1. **A ETAPA B REPROVA COM 1B.** 300 gerações: **25 de 268 inventam número que a lei não tem**, e o
   guarda aprovou **23**. A régua de cifras reprovou **zero de 268** — dígito comum já está na
   fonte, número por extenso não tem dígito, e reatribuição usa cifras da fonte trocadas de
   grandeza. Some-se a cegueira a negação. Mediana **4680 ms**, que estoura o aceite sozinha.
2. **Dois aceites são inconsistentes por construção**: câmera gasta até 5,9 s contra os ≤4 s da
   Fase 4. **Decidido**: a régua se divide — os 4 s valem para o caminho em que o SISTEMA trabalha;
   a janela de captura é espera pelo HUMANO, e estourá-la é recusa, não latência.
3. **Falso positivo do earcon 0,99/h** (meta 0,5) e **recall 3/4 locutores** — o gargalo é
   POSITIVO: 18 elocuções de UM locutor. Mais vozes resolvem; mais podcast, não.
4. **O deck submetido descreve reconhecimento facial, display e nuvem** — hoje proibições duras, e
   o §14.1 veda alteração de escopo. Pergunta à organização; as três vão na direção mais restritiva.
5. **O separador de conversa (15 min) é hipótese declarada**, e a medição que resolve está
   escrita. **`posicoes_do_grupo` faz `join` com `agent_positions`**: quem nunca publicou posição
   SOME da lista, então a contagem é "com posição" e não sabe dizer quanto menor.
6. **Bateria da `DeviceSession` aberta não medida** (preço direto do dono de processo) ·
   `RadioViewModel` faz uma requisição a cada **10 s**, 4320 por turno com a tela apagada ·
   `ModoOperacao` é a constante `ATIVO` em produção.
7. **110 `assumeTrue` e 14 `@Ignore`** — 124 lugares onde um teste passa sem executar; quatro
   casos hoje, incluindo `caos_mdk.sh` com "N/N verdes" sobre `tests="0"` · `CaosDoDatTest` falha
   um por rodada em `HEAD` limpo · P1 não alcança `render` · `security-crypto` em alpha.

## O que vem a seguir

O prazo da Fase 0 (documento no template) **venceu em 22/08**. Depois: decidir o modelo da Etapa B
— o motor está resolvido, o modelo não —, e a régua do guarda, que tem dois buracos independentes.
O PDF de contexto está em `~/Downloads/`, fora do repositório.
