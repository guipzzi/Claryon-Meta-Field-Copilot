# Onde estamos — 2026-08-22 · Onze defeitos medidos e consertados; o LLM roda e não deve ligar

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build :app:compileDebugAndroidTestKotlin` verde, **0 falhas, 0 pulados**. Contagem por
  classe (debug+release inflavam 63%); **`build` sozinho NÃO compila `androidTest`** — empurrei um `HEAD`
  quebrado por esse buraco. **Reconferir o total: quatro agentes fecharam no mesmo dia.**
- **RÁDIO, os 8 defeitos do caos consertados**, cada um com contra-teste em duas rodadas. O bloqueador:
  **sobreposição de vozes de 4 640 ms → 60 ms** (232 quadros → 3). O conserto não acrescentou tráfego —
  o anúncio de P1 **já era difundido e ninguém lia**. Também: `liberar` devolve resultado tipado e a
  guarnição não fica muda 30 s em silêncio · sem rede deixou de se disfarçar de canal ocupado · fala
  truncada tem motivo · supressor perdeu `abrir`/`fechar` · quem entra no meio ouve o autor ·
  `0024` apaga a concessão do ex-membro · piso local fala "Sem servidor".
- **GPS**: eram **0 linhas persistidas em 20 min** com o Android confirmando entrega. Seis consertos, e o
  mapa parou de escrever *"ninguém publicando"* quando a culpa é do portador — agora fica **em branco com
  a posição própria**, e distingue *não publiquei* de *ninguém publicou* de *não estou recebendo*.
- **PLACA: 0 erradas aceitas em 31 imagens** de campo (ângulo, chuva, contraluz, reflexo, oclusão, noite).
  Dita: 40/40. Roteador a 93 µs no p50. OCR a p50 8 ms — **quem limita é a câmera de 7 fps**, não ele.
- **LLM roda no celular, provado**: `adb push`, `mmap` sobre FUSE, carga 2 435 ms, PSS +1,91× o GGUF.
- **CONSULTA EXTERNA LIGADA** (§2 revogado em parte, `specs/consulta-externa.spec.md`): Overpass **depois**
  do local, prazo 2 s; atravessam categoria fechada + a **minha** coordenada a 4 casas, medido no corpo HTTP
  num socket real. Dois registros: auditoria precisa × uso por **dia**. Ao ligar, "posto de saúde" lançava.
- **Quatro documentos novos** para os quatro critérios do §11.2 do edital: LGPD art. 38, aderência ao
  toolkit, prontidão de hardware, e impacto com **entrevista de PM da PMERJ** (autorizada, sem iniciais).

## O que está quebrado, e nós sabemos

1. **LIGAR O LLM PIORARIA O PRODUTO — medido, e a decisão é humana.** Fica **mudo em 9 de 20** (16 com o
   aparelho ocupado), rende **1 a 2 utilizáveis em 20**, e nas outras 5 a 6 aprovações diz a lei ao
   contrário. O achado que governa: **`aprova` e `utilizáveis` andam em sentidos opostos** — a régua de
   lastro premia casamento lexical, e o jeito mais barato de casar é **copiar**. Aprovação do guarda é
   indicador de cópia, não de utilidade. Cinco formulações de prompt medidas: os defeitos de forma somem,
   a utilidade não muda. `redigir()` segue com zero chamadores, e um teste falha se alguém ligar.
2. **O guarda é cego a NEGAÇÃO.** *"NÃO DEIXOU DE observar as cautelas"* passa com lastro 0,78 sobre um
   artigo que pune quem **deixa** de observar. É régua de sentido, e não existe.
3. **Prefill de 500 tokens custa 1 620–2 550 ms contra prazo de 2 500** — `llama_decode` aborta antes de o
   prompt entrar. Mesma formulação rendeu 14/20 e depois 4/20 só por carga de máquina.
4. **Falso positivo da ativação: 2,08/h contra meta de 0,5** — e o `0,99` que este arquivo publicava era
   da cabeça **v3**; a **v5** é que está embarcada. Recall 3/4 locutores, nunca medido por HFP.
5. **Barro é 0/3 no OCR**, e é onde afrouxar o validador mata: com 6 caracteres, `DEF4567` vira `DEF456`
   consultado. Farol **piora** a leitura noturna. Três falsos negativos na placa ditada, do parser de extenso.
6. **Nada foi medido em óculos reais.** O emulador não tem SCO. Bateria dos óculos, térmica, latência
   boca-a-ouvido e o custo do RPC novo na preempção seguem sem número.
7. **O mapa fica preto e mudo** se só os tiles forem bloqueados (wifi de evento) — Supabase acessível,
   `OnStyleLoaded` nunca dispara, sem marcador e sem mensagem. Única violação viva de "falha nunca é silêncio".
8. **`ROADMAP` define fases 0 a 5**, sem Fase 6 escrita — placa lida e ligada, sem aceite contra o que
   medir. Deck descreve face e display; **nuvem deixou de ser divergência** (§2 revogado em parte).
9. **O COFRE NÃO GUARDA O RÁDIO.** Só `Intent.IniciarGravacao` o alimenta; nenhum caminho do PTT
   escreve nele. Conferir virou caminho do produto (Perfil → Periciar, veredito tipado por gravação);
   **EXPORTAR não** — tirar segmento e manifesto do aparelho ainda exige `adb`, e `Confere` não é inforjável.
10. **Renovação de token dormindo:** `manterFresco` usa `delay`, que não roda em *doze* — o 1º comando
    depois de horas no bolso acha token vencido e recusa; o 2º funciona. Teto medido 6 122 ms (era 29 210).

## O que vem a seguir

A entrega do Segundo Filtro venceu em **22/08** e é o portão. Depois: régua de sentido do guarda ·
truncamento no balão da tela · aceite 4 da troca de grupo · `0024` no banco · persistir o registro de uso
da consulta externa, que hoje vive em RAM e morre com o processo (o andaime só cresce se sobreviver).
