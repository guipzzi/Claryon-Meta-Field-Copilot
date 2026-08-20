# Onde estamos — 2026-08-20 · Fase 3 fechada; a palavra de ativação ligada

**Reescrito a cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O resto vai para `DECISIONS.md`.

## O que funciona hoje

- `./gradlew build` verde. **707 testes JVM, 0 falhas, 0 pulados** · instrumentados OK.
- **PTT:** toque→1º quadro **31–48 ms** (120) · P1 corta em **11 ms** (≤200) · **WER 3,4%** ·
  earcon **305 ms** (500). **REVOGAÇÃO** (`0014`) e **AUTORIA** (`0013`+`0015`) conferida
  contra `floor_grants`; divergência vira *"Origem não confirmada"*.
- **CANAL PRIVADO POR JWT** (`0012`): não-membro recebe `Unauthorized`, e o token é renovado
  pelo `exp` — o JWT vive 60 min. E o indicador parou de mentir: mostrava verde e mandou
  **168 quadros para um canal em que não entrou**; agora `CanalRecusado` é evento.
- **PALAVRA DE ATIVAÇÃO LIGADA.** Faltava mais que chamador: os pesos viviam em
  `androidTest/assets`, então `preparar()` em produção devolveria `false` — e o teste passava
  lendo os assets do próprio APK de teste. `EscutaDeAtivacao` roda no serviço e só onde
  `PowerPolicy.hfpAberto`, a MESMA regra do tipo `MICROPHONE` do FGS. No aparelho: **1500
  quadros em 30,0 s** = 50/s exatos; com PTT de 6 s, **300 calados** = 6,0 s exatos. Cala na
  saída própria, no PTT e por um ciclo, e **reinicia o anel nas duas bordas da mudez** — senão
  avaliaria uma janela que nunca existiu. Perfil mostra a causa, não um booleano.
- **TRANSCRIÇÃO NA ORIGEM (P1)**, ponta a ponta com fala humana e servidor real: acumulador com
  **80 000 amostras** = os 5,0 s exatos → whisper → o par headless recebeu texto **idêntico**.
- **DONO ÚNICO DA POSIÇÃO** (`0016`): as políticas abertas foram **exploradas** — POST direto
  gravou `updated_at = 2099`, permanentemente "fresco". **LOG DE ACESSO** (`0017`+`0018`): mapa
  vira **sessão** de 1 h e **nunca grava a resposta**. **RETENÇÃO** (`0019`) com job no `pg_cron`.
- **O PISO ERA LOCAL O TURNO INTEIRO.** `pisoDoCanal` lia o cache vazio logo após o login e a
  decisão se toma uma vez — dois agentes podiam achar que detinham o canal. Achado **pela tela**:
  teste nenhum acharia, porque o instrumentado constrói a sessão antes e o app não.
- **BATIMENTO ALCANÇÁVEL** — ele não existia. O `minDistance` suprime a entrega (*"will not
  occur"*, AOSP): agente parado não recebia callback e a linha do batimento nunca rodava — pior
  em Ocorrência, onde o agente chega e fica. Emulador, parado, 3,5 min: **5 publicações com o
  conserto, 1 sem**. E 3 min contra os 2 de `OBSOLETO_S` esmaeciam todo parado; agora 60 s.
- **IDADE REAL DA CORREÇÃO** (`0020`): `updated_at` é hora do UPLOAD e cinco funções a liam como
  idade — quem reconectava após 4 min entrava como `idade_s = 0` e `agentes_no_raio` o contava
  como "está perto". O cliente manda **duração**, nunca instante: `now() - greatest(0, idade)`
  não dá futuro **por construção**. `0009` 17/17: a mesma linha dá 0 no filtro novo, 1 no antigo.
- **PORTA DE CORREÇÃO**: degradação relativa (teto fixo mataria o Standby), salto por **incerteza
  combinada** (distância crua mataria a rede) e **válvula de 3 recusas** — sem ela um salto
  verdadeiro congela o marcador, pior que sumir porque mente parado.

## O que está quebrado, e nós sabemos

1. **O ciclo de voz ainda mora no ViewModel.** Escuta e earcon vivem no serviço, mas com a
   Activity destruída o agente ouve o bipe e o comando não roda — falta isso para mãos livres
   de verdade com a tela apagada.
2. **Falso positivo sem intervalo útil:** `0` em 1,8 min dá teto de **~99/h** contra a meta de
   0,5/h, e fechar exige ~6 h. Os 60 s de silêncio do emulador não contam — silêncio é o
   negativo mais fácil que existe. O modelo também é **de um locutor só**, 27 elocuções.
3. **`CaosDoDatTest` falha um teste por rodada**, variando qual; falha em `HEAD` limpo. E a
   preempção de P1 **não alcança a fase de `render`** — o buraco do aceite (b).
4. **Nada difícil é medido em ambiente próprio:** sem pilha de servidor separada (exige Docker)
   e sem GPS ruim de verdade — a porta de correção só viu teste sintético e emulador a 5 m.
5. **`medida_em` é otimista pelo tempo de ida**, sempre nessa direção: 0,4% de 120 s (ver `0020`).
6. `errorStream` não coletado · `STOPPED` não terminal · câmera do DAT nunca pedida · três
   cláusulas da Fase 2 presas a dispositivo HFP · `security-crypto` em alpha.

## O que vem a seguir

**A Fase 3 fechou inteira**, nove itens, e a palavra de ativação saiu da lista de quebrados.
Agora: **o ciclo de voz no serviço** (item 1), e então Fase 4 e Fase 5.
