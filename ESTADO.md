# Onde estamos — 2026-08-21 · Os dois bloqueios da Etapa B caíram; corpus de lei em pé

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **813 testes JVM, 0 falhas, 0 pulados** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (120) · P1 corta em **11 ms** (≤200) · **WER 3,4%** ·
  earcon **305 ms** (500) · REVOGAÇÃO (`0014`) · AUTORIA (`0013`+`0015`) · CANAL PRIVADO POR
  JWT (`0012`: não-membro recebe `Unauthorized`).
- **PALAVRA DE ATIVAÇÃO LIGADA**, dona de processo (`CerebroDoCopiloto`; ViewModel 596→65
  linhas): 1500 quadros em 30,0 s = 50/s exatos, calando na saída própria, no PTT e por um
  ciclo, e reiniciando o anel nas duas bordas da mudez. **TRANSCRIÇÃO NA ORIGEM (P1)** com
  fala humana e servidor real: o par headless recebeu texto **idêntico**. **POSIÇÃO:** dono
  único (`0016`), log de acesso (`0017`+`0018`), retenção (`0019`), idade como duração
  (`0020`), batimento alcançável (`minDistance` suprimia a entrega: 5 publicações contra 1).
- **COLISÃO DO ggml RESOLVIDA — e o APK ENCOLHEU 884 KB** (−14%). Os três `libggml*.so`
  existiam porque `ggml/CMakeLists.txt:74` põe `BUILD_SHARED_LIBS_DEFAULT` em ON fora do
  Windows: **ninguém escolheu publicá-los.** Com `-DBUILD_SHARED_LIBS=OFF` o ggml linka
  estático — 456 símbolos `ggml_*` dentro do `libwhisper.so`, zero `DT_NEEDED` em libggml,
  zero libggml no `unzip -l` de debug E release. A armadilha era CMP0077: sob a política OLD
  dos nossos escopos `set()` seria descartado calado — por isso vai pelo Gradle, como cache.
- **CORPUS DE LEI**: 1817 trechos do Planalto (CTB, CPP, CP, Drogas, Desarmamento), com
  procedência escrita e conferidor que reprova por 7 critérios, testado por mutação.

## O que está quebrado, e nós sabemos

1. **A Etapa A da Fase 4 está em 2 de 5**, e falta o coração: **embedder e índice vetorial
   não existem** (2 sessões). Há a porta (`PortaDoConhecimento`) e o contrato
   (`BaseDeConhecimento`, sem implementação). **`core-knowledge` tem zero chamadores** e `app`
   nem declara a dependência — escrito, não construído. E o teste da fronteira está em
   `core-knowledge`, quando o ROADMAP exige que viva em `app`.
2. **Eu dei ao extrator quatro contagens vindas do regex quebrado.** CTB 389, CP 430, drogas
   114, desarmamento 52 são o que `Art\.\s*\d+` devolve sobre HTML cru sem resolver entidade
   — a régua que o `PROCEDENCIA.md` já tinha desmascarado no CPP horas antes. Consertei um
   caso e generalizei. Reais: 391/851/434/100/41. Viraram **regressão de sinal invertido**.
3. **No aparelho, a `v5` PIORA neste áudio**: 4 disparos (2,08/h) contra 3 da `v3` (1,56/h),
   mesmo material e arranjo. Não desmente o "cai pela metade", medido em Python sobre metades
   **retidas** — desmente que a v5 melhore em toda parte. E **o negativo desta bancada virou
   negativo de treino** (`podcast5.py` treina com a primeira metade de cinco podcasts, dois
   são estes): sobre metade do material o número é otimista por construção.
4. **Falso positivo do earcon: 0,99/h contra 0,5/h** (3,04 h retidas) e **recall do gatilho
   3/4 locutores** — Guido, a voz que TREINOU o detector, virou "Blerium". O gargalo dos dois
   é **POSITIVO**: 18 elocuções de UM locutor, e no limiar que zera o falso positivo o recall
   cai a 85%, abaixo dos 90% do aceite. **Mais vozes dizendo "Claryon" resolvem os dois; mais
   podcast, nenhum** — 15–20 elocuções por locutor, por fone HFP.
5. **`WhisperCppSttTest` procura `ggml-tiny.bin`; o projeto embarca `ggml-small-q5_1.bin`** —
   os dois testes da classe estão permanentemente pulados e reportam verde. · `CaosDoDatTest`
   falha um teste por rodada em `HEAD` limpo · preempção de P1 não alcança `render` (aceite
   (b)) · nada difícil medido em ambiente próprio (sem Docker, sem GPS ruim) · `medida_em`
   otimista por 0,4% de 120 s · gazetteer com 2 logradouros · `errorStream` não coletado ·
   `STOPPED` não terminal · `security-crypto` em alpha.

## O que vem a seguir

**O prazo duro é 22/08 — amanhã — e o que falta da Fase 0 é o documento no template da
organização mais a análise de risco LGPD, não código.** Depois, o item 1: embedder e índice
fecham a Etapa A, sem depender de decisão em aberto. A Etapa B está **destravada** (motor
decidido, colisão resolvida, Regra Zero cumprida — o llama.cpp entra como submódulo, e não
existe coordenada Maven a conferir), e segue sendo o primeiro item cortado se apertar.
