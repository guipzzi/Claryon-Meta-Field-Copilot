# Onde estamos — 2026-08-20 · Ativação ligada, cérebro fora da tela, 8 dos 12 fechados

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **707 testes JVM, 0 falhas, 0 pulados** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (120) · P1 corta em **11 ms** (≤200) · **WER 3,4%** ·
  earcon **305 ms** (500). **REVOGAÇÃO** (`0014`) · **AUTORIA** (`0013`+`0015`) conferida contra
  `floor_grants`.
- **CANAL PRIVADO POR JWT** (`0012`): não-membro recebe `Unauthorized`; token renovado pelo `exp`.
  E o indicador parou de mentir — mandava **168 quadros para um canal em que não entrou**.
- **PALAVRA DE ATIVAÇÃO LIGADA.** Faltava mais que chamador: os pesos viviam em
  `androidTest/assets`, então `preparar()` em produção daria `false` — e o teste passava lendo os
  assets do próprio APK de teste. Roda no serviço e só onde `PowerPolicy.hfpAberto`, a MESMA
  regra do tipo `MICROPHONE` do FGS. No aparelho: **1500 quadros em 30,0 s** = 50/s exatos; com
  PTT de 6 s, **300 calados** = 6,0 s. Cala na saída própria, no PTT e por um ciclo, e **reinicia
  o anel nas duas bordas da mudez** — senão avaliaria uma janela que nunca existiu.
- **O CÉREBRO SAIU DO VIEWMODEL.** O ciclo morria com a Activity, e bipe que não leva a nada
  afirma ter ouvido. `CerebroDoCopiloto` é dono de processo; a captura de evidência foi junto
  (custódia em `viewModelScope` era defeito) e o ViewModel caiu de 596 para **65 linhas**.
  `CicloSemTelaTest` roda em 8,578 s — o teto do ciclo — sem construir ViewModel nenhum.
- **TRANSCRIÇÃO NA ORIGEM (P1)**, com fala humana e servidor real: **80 000 amostras** = os 5,0 s
  exatos → whisper → o par headless recebeu texto **idêntico**.
- **DONO ÚNICO DA POSIÇÃO** (`0016`): políticas abertas foram **exploradas** (POST gravou
  `updated_at = 2099`). **LOG DE ACESSO** (`0017`+`0018`) e **RETENÇÃO** (`0019`) no servidor, com
  `pg_cron`; o que falta delas no cliente está nos quebrados.
- **BATIMENTO ALCANÇÁVEL** — ele não existia. O `minDistance` suprime a entrega (*"will not
  occur"*, AOSP): agente parado não recebia callback e a linha nunca rodava — pior em Ocorrência,
  onde ele chega e fica. Emulador, parado, 3,5 min: **5 publicações com o conserto, 1 sem**.
- **IDADE REAL DA CORREÇÃO** (`0020`): `updated_at` é hora do UPLOAD e cinco funções a liam como
  idade — quem reconectava após 4 min entrava como `idade_s = 0` e `agentes_no_raio` o contava
  como "está perto". O cliente manda **duração**, nunca instante: `now() - greatest(0, idade)`
  não dá futuro **por construção**. `0009` 17/17: a mesma linha dá 0 no novo filtro, 1 no antigo.
- **PORTA DE CORREÇÃO**: degradação relativa, salto por **incerteza combinada**, **válvula de 3
  recusas** — sem ela um salto verdadeiro congela o marcador. E `ultimaPosicao()` pega a melhor.

## O que está quebrado, e nós sabemos

1. **Falso positivo sem intervalo útil:** `0` em 1,8 min dá teto de **~99/h** contra a meta de
   0,5/h, e fechar exige ~6 h. Os 60 s de silêncio do emulador não contam — silêncio é o negativo
   mais fácil que existe. O modelo também é **de um locutor só**, 27 elocuções.
2. **Os 4 que faltam dos 12** são uma feature só: abrir transmissão por voz (*"guarnição N na
   escuta"*), fecho por silêncio, segunda instância do Silero e `CanalDoPiloto` com UUID fixo.
3. **`CaosDoDatTest` falha um teste por rodada**, variando qual; falha em `HEAD` limpo. E a
   preempção de P1 **não alcança a fase de `render`** — o buraco do aceite (b).
4. **Nada difícil é medido em ambiente próprio:** sem pilha de servidor separada (exige Docker)
   e sem GPS ruim de verdade — a porta de correção só viu teste sintético e emulador a 5 m.
5. **`medida_em` é otimista pelo tempo de ida**, sempre nessa direção: 0,4% de 120 s (ver `0020`).
6. **O gazetteer versionado tem 2 logradouros** — é semente, não gazetteer; a lista operacional
   é dado da corporação. · `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT
   nunca pedida · três cláusulas da Fase 2 presas a HFP · `security-crypto` em alpha.

## O que vem a seguir

**O prazo duro é 22/08**, e o que falta da Fase 0 é o **documento no template da organização** —
não código. Depois: dar porta às duas capacidades do item 1 (barato, fecha a Fase 3 de verdade),
e então Fase 4 (RAG extrativo, Etapa A) e Fase 5 (UX e ensaio).
