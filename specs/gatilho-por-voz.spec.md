---
feature: gatilho-por-voz
capacidade: C1 (rádio tático)
estado: proposta
autor: revisão humana pendente
criada: 2026-08-15
sobrepoe:
  - "docs/PADROES_DE_ENGENHARIA.md § Rádio tático — 'nunca por palavra de ativação'"
depende_de:
  - dono-unico-da-saida-de-audio
  - taxa-16khz-ponta-a-ponta
---

# Gatilho por voz para transmissão do rádio

## Objetivo

Permitir que o agente abra e feche uma transmissão de rádio **sem tocar na tela**,
porque o edital do hackathon exige operação mãos-livres e porque em campo as mãos
estão ocupadas — algemando, dirigindo, com a arma em punho.

## O que este documento sobrepõe, e por quê

A regra dura vigente diz: *"transmissão é sempre push-to-talk explícito; nunca por
palavra de ativação, porque um falso positivo difundiria para a guarnição inteira"*.

A regra acerta o risco e erra o alvo. Ela trata "palavra de ativação" como sinônimo
de "sem intenção explícita", e não é: **dois estágios com confirmação sonora são
intenção explícita**, provada por uma frase deliberada em vez de por um dedo.

O risco real não é ativação falsa — é **continuação falsa**: o agente diz a frase,
o earcon toca, e ele não percebe que o microfone continua vivo, porque não há
display. Isso se mitiga com sinal recorrente, não recusando voz.

**Redação nova proposta para a regra:**

> Transmissão exige intenção explícita e confirmada. Push-to-talk satisfaz. Palavra
> de ativação satisfaz **apenas em dois estágios** (ativação + destino nomeado), com
> earcon antes de abrir o microfone, earcon recorrente enquanto o canal estiver
> aberto, e teto duro de duração. Ativação em um estágio continua proibida.

## Comportamento

### Vocabulário

| Estágio | Frase | Papel |
|---|---|---|
| 1 — ativação | **"Hey Claryon"** | Acorda o copiloto. Não abre canal nenhum |
| 2 — destino | **"na escuta"** | Arma a transmissão para o talk group corrente |

Os dois são casados por **keyword spotting** contra léxico fechado, nunca por STT
livre. Casamento exato: STT devolveria texto livre e obrigaria a inventar uma
comparação difusa — que é exatamente o que a segurança deste fluxo não tolera.

### Critérios de aceite (EARS)

**Ativação**

1. `Quando` o detector reconhecer "Hey Claryon" `e` nenhum áudio estiver saindo pelos
   alto-falantes, `o sistema deverá` emitir o earcon de escuta e abrir uma janela de
   4 s aguardando o estágio 2.
2. `Se` a janela de 4 s expirar sem estágio 2, `então o sistema deverá` fechar a
   janela em silêncio e voltar a dormir, sem abrir canal.
3. `Enquanto` qualquer áudio estiver saindo pelos alto-falantes, `o sistema deverá`
   manter o detector de palavra de ativação **desligado**.
4. `Enquanto` uma transmissão estiver em curso, `o sistema deverá` manter o detector
   desligado.

**Abertura da transmissão**

5. `Quando` "na escuta" for reconhecido dentro da janela, `o sistema deverá` emitir o
   earcon de canal aberto **antes** de o primeiro quadro sair, e só então começar a
   transmitir.
6. `Quando` a transmissão começar, `o sistema deverá` enviar quadros Opus de 20 ms ao
   vivo, enquanto o agente fala. Gravar o arquivo inteiro e enviar depois é proibido.
7. `Quando` a transmissão começar, `o sistema deverá` incluir o pré-roll de 600 ms
   anterior ao início da fala, localizado por VAD retroativo.

**Encerramento**

8. `Quando` o VAD detectar silêncio contínuo por 1,2 s, `o sistema deverá` encerrar a
   transmissão e emitir o earcon de canal fechado.
9. `Se` a transmissão atingir 15 s sem silêncio detectado, `então o sistema deverá`
   encerrá-la e emitir o earcon de **falha**, nunca o de sucesso.
10. `Enquanto` a transmissão estiver aberta, `o sistema deverá` emitir o earcon de
    "no ar" a cada 4 s.

**Recusa honesta**

11. `Se` a rota de áudio não estiver em SCO, `então o sistema deverá` recusar a
    abertura e dizer a causa.
12. `Se` o canal estiver ocupado por outro agente, `então o sistema deverá` recusar
    a abertura e emitir o earcon de canal ocupado.
13. `Se` não houver rede, `então o sistema deverá` recusar a abertura e dizer
    *"Sem rede."* — a transmissão ao vivo **não** vai para fila offline.

### Não-funcionais

| Métrica | Alvo | Como medir |
|---|---|---|
| "Hey Claryon" → earcon de escuta | ≤ 400 ms | `Telemetry.mark` nos dois pontos |
| "na escuta" → primeiro quadro no ar | ≤ 900 ms | idem |
| Falsos positivos do estágio 1 | ≤ 1/hora | 30 min de fala natural como material |
| Falsos positivos dos dois estágios em sequência | 0 em 8 h | turno simulado |

### Fora de escopo

- Endereçar talk group por nome arbitrário. O léxico é fechado e fixo no aparelho.
- Cancelar uma transmissão já iniciada. **Não existe desfazer**: os quadros já
  saíram e já tocaram no ouvido dos pares. É a consequência de transmitir ao vivo,
  e é por isso que os itens 9 e 10 existem.
- Transcrever ou arquivar a fala transmitida por este caminho.

## Riscos aceitos

- **O earcon recorrente do item 10 custa fala.** Cada emissão abre ~200 ms de
  supressão do microfone para o produto não conversar consigo mesmo, ou seja ~5% da
  fala do agente é descartada da captura. É o preço de não ter display.
- **Estágio 2 usa uma expressão de rádio real.** "Na escuta" é natural sob estresse,
  o que é a virtude e o risco: pode ocorrer numa conversa sobre o rádio. Mitigado
  por exigir o estágio 1 dentro de 4 s antes.

## Testes

Cada critério acima mapeia para pelo menos um teste. Os de 1 a 4 e 8 a 13 são
determinísticos e ficam na JVM (a máquina de estados é pura e recebe o relógio por
parâmetro). Os de 5 a 7 exigem **fone Bluetooth com HFP real** — o MockDeviceKit não
simula áudio (verificado por `javap`; ver `DECISIONS.md`, 2026-08-15).
