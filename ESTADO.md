# Onde estamos — 2026-08-22 · Onze defeitos consertados; o LLM troca de modelo e segue desligado

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build :app:compileDebugAndroidTestKotlin` verde: **1004 testes únicos, 125 classes, 0 falhas,
  0 pulados**. Contagem por classe (debug+release inflavam 63%); **`build` sozinho NÃO compila
  `androidTest`** — empurrei um `HEAD` quebrado por esse buraco.
- **RÁDIO, os 8 defeitos do caos consertados**, cada um com contra-teste em duas rodadas. O bloqueador:
  **sobreposição de vozes de 4 640 ms → 60 ms** (232 quadros → 3). O conserto não acrescentou tráfego —
  o anúncio de P1 **já era difundido e ninguém lia**. Também: `liberar` devolve resultado tipado e a
  guarnição não fica muda 30 s em silêncio · sem rede deixou de se disfarçar de canal ocupado · fala
  truncada tem motivo · quem entra no meio ouve o autor · piso local fala "Sem servidor".
- **GPS**: eram **0 linhas persistidas em 20 min** com o Android confirmando entrega. Seis consertos, e o
  mapa parou de escrever *"ninguém publicando"* quando a culpa é do portador — agora fica **em branco com
  a posição própria**, e distingue *não publiquei* de *ninguém publicou* de *não estou recebendo*.
- **PLACA: 0 erradas aceitas em 31 cenas sintéticas** (chuva, contraluz, oclusão, barro, noite); sem foto real.
  Dita: 40/40. Roteador a 93 µs · OCR a p50 8 ms · os **7 fps são ESCOLHA** (`CameraProfile.OCR`), não teto.
- **LLM roda no celular, provado**: `adb push`, `mmap` sobre FUSE, carga 2 435 ms, PSS +1,91× o GGUF.
  **MODELO TROCADO** (22/08, humano, sem bancada): sai Llama 3.2 1B, entra **Qwen2.5-1.5B Apache-2.0** —
  pt-BR nos pesos, e a AUP do Llama alcançava o caso da Glock. **Zero linha** de Kotlin/C++: template vem
  do GGUF, arquivo é `redator.gguf`, portão multiplica bytes. Fica o llama.cpp; saem os pesos da Meta.
- **CONSULTA EXTERNA LIGADA** (§2 revogado em parte, `specs/consulta-externa.spec.md`): Overpass **depois**
  do local, prazo 2 s; atravessam categoria fechada + a **minha** coordenada a 4 casas, medido no corpo
  HTTP de um socket real. Dois registros: auditoria precisa × uso por **dia**.
- **Quatro documentos novos** para os quatro critérios do §11.2 do edital: LGPD art. 38, aderência ao
  toolkit, prontidão de hardware, e impacto com **entrevista de PM da PMERJ** (autorizada, sem iniciais).

## O que está quebrado, e nós sabemos

1. **LIGAR O LLM PIORARIA O PRODUTO — medido, e a decisão é humana.** Fica **mudo em 9 de 20**, rende
   **1 a 2 utilizáveis em 20**, e nas outras 5 a 6 aprovações diz a lei ao contrário. O achado que
   governa: **`aprova` e `utilizáveis` andam em sentidos opostos** — a régua premia casamento lexical, e
   o jeito barato de casar é **copiar**. **Tudo isso é do modelo que SAIU**: nada foi remedido no Qwen, e
   o prefill piora (986 MB, 28 camadas). `redigir()` tem zero chamadores, e um teste falha se ligarem.
2. **O guarda é cego a NEGAÇÃO.** *"NÃO DEIXOU DE observar as cautelas"* passa com lastro 0,78 sobre um
   artigo que pune quem **deixa** de observar. É régua de sentido, e não existe.
3. **Prefill de 500 tokens custa 1 620–2 550 ms contra prazo de 2 500** — `llama_decode` aborta antes de o
   prompt entrar. Mesma formulação rendeu 14/20 e depois 4/20 só por carga de máquina.
4. **Falso positivo da ativação: 2,08/h contra meta de 0,5** — e o `0,99` que este arquivo publicava era
   da cabeça **v3**; a **v5** é que está embarcada. Recall 3/4 locutores, nunca medido por HFP.
5. **Barro é 0/3 no OCR**, e é onde afrouxar o validador mata: com 6 caracteres, `DEF4567` vira `DEF456`
   consultado. Farol **piora** a leitura noturna. Três falsos negativos na placa ditada, do parser de extenso.
6. **Nada medido em óculos reais** (o emulador não tem SCO): bateria, térmica, boca-a-ouvido, RPC novo.
7. **O mapa fica preto e mudo** se só os tiles forem bloqueados (wifi de evento) — Supabase acessível,
   `OnStyleLoaded` nunca dispara, sem marcador e sem mensagem. Única violação viva de "falha nunca é silêncio".
8. **`ROADMAP` define fases 0 a 5**, sem Fase 6 escrita — placa lida e ligada, sem aceite contra o que
   medir. Deck descreve face e display; **nuvem deixou de ser divergência** (§2 revogado em parte).
9. **O COFRE NÃO GUARDA O RÁDIO.** Só `Intent.IniciarGravacao` o alimenta; nenhum caminho do PTT escreve
   nele. Conferir virou caminho do produto (Perfil → Periciar); **EXPORTAR não** — tirar segmento e
   manifesto ainda exige `adb`, e `Confere` não é inforjável.
10. **Renovação de token dormindo:** `manterFresco` usa `delay`, que não roda em *doze* — o 1º comando
    depois de horas no bolso acha token vencido e recusa; o 2º funciona. Teto medido 6 122 ms (era 29 210).

## O que vem a seguir

Segundo Filtro venceu em **22/08** e é o portão. Depois: **remedir a Etapa B no Qwen** (5 braços de prompt
+ PSS, nada é do modelo novo) · régua de sentido do guarda · truncamento no balão · aceite 4 da troca de
grupo · `0024` no banco · persistir o registro de uso da consulta externa, que hoje morre com o processo.
