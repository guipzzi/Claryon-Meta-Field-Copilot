---
feature: falha-de-camera-falada
capacidade: C2 (consulta) — Fase 6, bloco 2
estado: proposta
autor: revisão humana pendente
criada: 2026-08-21
sobrepoe:
  - "core-agent/src/main/kotlin/com/claryon/agent/ActionOutcome.kt — `FalhaOperacional` é enum fechado sem nenhum valor para câmera; toda falha de captura vira `CONSULTA_INDISPONIVEL`"
  - "app/src/main/kotlin/com/claryon/field/voice/CopilotoDoAgente.kt:270 — a causa tipada morre num `Log.w`"
depende_de:
  - consulta-de-placa-por-camera
---

# A falha de câmera precisa chegar ao ouvido

## O defeito, em uma frase

`ErroDeStream` distingue **oito** causas com frases já prontas e dentro do teto de
sete palavras. O agente ouve **uma** — *"Consulta indisponível."* — nas oito.

```
HINGE_CLOSED       "Óculos dobrados. Abra as hastes."
PERMISSIONS_DENIED "Libere a câmera no Meta AI."
THERMAL_HOT        "Óculos quentes. Câmera pausada."
BATTERY_LOW        "Bateria dos óculos acabando."
        ↓  Log.w  ↓
                        "Consulta indisponível."
```

## Por que isto é defeito e não falta de polimento

Este produto tem uma regra dura escrita: **falha nunca é silêncio**. Ela existe
porque o agente não tem display — ele não pode conferir. Uma falha que soa igual a
outra é a versão sonora do silêncio: informa que algo deu errado e esconde **o quê**,
que é a única parte acionável.

E as quatro recuperações são **fisicamente diferentes**:

| causa | o que o agente faz |
|---|---|
| óculos dobrados | abre as hastes — dois segundos, resolve na hora |
| permissão negada | mexe no app da Meta — precisa parar e olhar o celular |
| óculos quentes | espera esfriar — não adianta insistir |
| bateria acabando | põe no estojo — o turno tem um problema maior |

Ouvindo *"Consulta indisponível."*, o agente que dobrou os óculos sem perceber vai
**insistir** — e a insistência é exatamente o que não resolve. Pior: com a câmera
quente, insistir piora.

O próprio `ErroDeStream` foi escrito com essa intenção; o KDoc dele diz que
`HINGE_CLOSED` vira *"óculos dobrados"* **porque foi isso que aconteceu no mundo**. A
tradução existe e está sendo jogada fora um passo antes do alto-falante.

## O critério de agrupamento: RECUPERAÇÃO, não causa

Oito causas não precisam de oito falas. Precisam de tantas falas quantas forem as
**ações distintas** que o agente pode tomar — e duas causas que levam à mesma ação
podem, e devem, compartilhar a fala.

O inverso é o que não pode acontecer: **duas causas com recuperações diferentes
colapsando na mesma fala** é o defeito que esta spec conserta, e reintroduzi-lo por
economia de valores de enum seria trocar seis por meia dúzia.

## A decisão de desenho, e a alternativa descartada

**Proposta: valores novos em `FalhaOperacional`**, não um campo de texto livre em
`ActionOutcome.Falhou`.

A alternativa — `Falhou(falha, causaCurta: String?)` — é tentadora porque evita
reescrever frases que já existem. Foi descartada por uma razão que este projeto já
pagou caro: **`utteranceFor` aceitar apenas `ActionOutcome` é o que torna impossível
o app falar o que não aconteceu.** Um `String?` que vira fala é uma porta por onde
texto arbitrário alcança o alto-falante — e em 21/08 foi medido que **2 de 3 saídas
do LLM**, entregues ao roteador, viram ação. A disciplina que segura isso é o
vocabulário fechado; furá-la aqui por conveniência a enfraquece em todo o resto.

O custo aceito é duplicação de frase entre `ErroDeStream.frase` (que é o texto do
log e do painel) e `FalhaOperacional.causaCurta` (que é o que o agente ouve). O
antídoto está no aceite abaixo: **a tradução tem de ser total e testada**, para que
um valor novo do SDK não caia silenciosamente num balde genérico.

## Aceite (EARS)

- **WHEN** a captura de placa falha por causa tipada, **THE SYSTEM SHALL** falar a
  causa correspondente à **recuperação** daquela falha.
- **THE SYSTEM SHALL** traduzir **todo** valor de `ErroDeStream` para um valor de
  `FalhaOperacional` — tradução **total**, verificada por teste que enumera o enum de
  origem. Valor novo no SDK **quebra o teste**, não vira balde genérico em silêncio.
- **THE SYSTEM SHALL NOT** colapsar na mesma fala duas causas cujas recuperações
  sejam diferentes — verificável por teste que compara as falas duas a duas.
- **THE SYSTEM SHALL** manter toda fala nova dentro do teto de **7 palavras**, pelo
  mesmo teste que já varre todos os ramos de `utteranceFor`.
- **THE SYSTEM SHALL NOT** aceitar texto livre como fala de falha — `Falhou`
  continua carregando apenas o enum.

## O que decidir

1. **Quantas falas?** A proposta usa quatro novas — dobradas, permissão, quentes,
   bateria — mais o genérico que já existe para falha de stream sem recuperação
   específica. Se a demonstração exigir menos vocabulário, o corte tem de ser por
   recuperação idêntica, nunca por economia.
2. **`PEAK_POWER_LIMIT`**: a frase dele é *"Óculos sem energia para transmitir"*, e a
   recuperação é a mesma de `BATTERY_LOW` (carregar). Colapsar os dois é legítimo pelo
   critério desta spec — mas é decisão, e fica registrada.
