---
feature: consulta-externa
capacidade: C2 (consulta) — fonte externa como último degrau da cascata
estado: proposta
autor: revisão humana pendente
criada: 2026-08-22
sobrepoe:
  - "CLAUDE.md §2 — 'Enviar áudio, transcrição ou frame para serviço externo no caminho crítico'. A proibição passa a valer para ÁUDIO e FRAME; a CONSULTA TEXTUAL derivada passa a ser permitida sob as condições desta spec"
  - "CLAUDE.md §1 — 'IA on-device: 100% local. Nada de IA na nuvem em caminho nenhum'"
depende_de:
  - leitura-de-norma
---

# Consulta externa: o último degrau, e por que ele é o último

## 1. O problema, medido

O corpus embarcado são **cinco leis federais**, 1817 trechos:

| CPP | CP | CTB | Lei de Drogas | Estatuto do Desarmamento |
|---|---|---|---|---|
| 851 | 434 | 391 | 100 | 41 |

Isso responde bem o que está nessas cinco. **Não responde nada fora delas** — e a lista do que fica de fora é o trabalho diário do policial:

- *"Como destravar minha Glock"* — manual de fabricante, obra protegida (Lei 9.610/1998), fora do corpus por licença
- *"Como trocar a roda da Blazer"* — nenhuma fonte
- *"Qual o hospital mais próximo"* — dado geoespacial, não normativo
- Lei estadual, portaria de corporação, atualização do CTB — fora das cinco

Medido: a pergunta-bandeira do `ROADMAP` (*"minha Glock 19 emperrou"*) tem confiança **0,070** contra limiar de **0,30**. O índice recusa, corretamente, porque a resposta não está lá.

## 2. A decisão que esta spec registra

**§2 do `CLAUDE.md` é regra nossa, não do edital.** O §8.1 do edital permite explicitamente:

> *"A solução deve incorporar ao menos um componente de IA, seja via API, **modelo em nuvem** ou processamento local"*

A proibição de nuvem foi decisão de projeto, tomada por razão de privacidade, e **o dono do projeto a revogou parcialmente em 22/08**. Esta spec registra a revogação e a delimita.

**O que passa a ser permitido:** consulta **textual derivada**, para responder pergunta que a fonte local não responde.

**O que continua proibido, sem exceção:**
- ❌ Áudio para serviço externo
- ❌ Frame de câmera para serviço externo
- ❌ Transcrição **literal** para serviço externo
- ❌ Qualquer dado de terceiro, posição de par, placa consultada, ou identificador de agente

## 3. Como as fontes se ordenam

```
pergunta
  ├─ 1. LOCAL, sempre primeiro
  │     corpus de normas · base veicular · posição da guarnição
  │     ↓ não achou
  ├─ 2. EXTERNA ESTRUTURADA
  │     geoespacial (OSM/Overpass): hospital, delegacia, posto
  │     ↓ não é geoespacial, ou não achou
  ├─ 3. EXTERNA TEXTUAL
  │     busca, com o trecho recuperado como fonte
  │     ↓ sem rede, ou não achou
  └─ 4. RECUSA FALADA COM MOTIVO   ← comportamento de hoje, preservado inteiro
```

**Por que estruturada antes de textual.** *"Hospital mais próximo"* tem resposta exata: nome, endereço, distância. Passá-la por um modelo de linguagem troca dado por prosa e abre espaço para endereço alucinado. É como os assistentes grandes fazem — o modelo reconhece a intenção e **chama a ferramenta**; ele formata, não sabe.

**Por que local antes de tudo.** Offline é o caso normal em campo, não a exceção. O relato da PMERJ que fundamenta este produto descreve região onde *"rádio digital aqui não funciona"* por sinal. Uma cascata que começasse pela rede falharia exatamente ali.

## 4. Procedência: a INTERNA se credencia; a externa apenas responde

**Decisão do dono do projeto, 22/08, e ela inverte a proposta original desta spec.**

A primeira versão dizia que a resposta externa deveria se rebaixar em voz alta —
*"segundo a internet: …"*. Está errado, por uma razão que o teto de **7 palavras**
torna concreta: **gastar palavra falada com ressalva é caro**, e uma ressalva repetida
vira ruído que o agente aprende a ignorar.

O sinal de credibilidade **já existe, e é a citação**:

| Fonte | Como soa | O sinal |
|---|---|---|
| Corpus local | *"Artigo 33 da Lei 11.343."* | **cita norma e número** — máxima credibilidade |
| Base veicular oficial | como hoje, com `Procedencia` | **cita o sistema** |
| Geoespacial externa | *"Hospital Getúlio Vargas, 800 metros."* | responde, **sem citação** |
| Textual externa | responde direto | responde, **sem citação** |

**A ausência de citação é o sinal**, e ele não custa sílaba nenhuma. O agente aprende a
distinção pelo uso — quando ouve artigo e lei, é norma; quando ouve só a resposta, é
apoio. É o mesmo mecanismo que faz *"nada consta"* valer diferente conforme a
`Procedencia` da base veicular.

### A procedência é REGISTRADA, ainda que não falada

Não falar não é não guardar. Toda resposta de fonte externa registra, fora da fala:

- a **URL ou o serviço** de origem
- o **trecho exato** que fundamentou a resposta
- o **carimbo de tempo** da consulta

Isso vai para o log operacional e fica disponível na tela. Serve a três coisas: o
agente pode conferir depois; a resposta é auditável se for contestada; e o registro do
que foi perguntado alimenta a expansão do corpus com **uso real**, não com palpite.

## 5. Higiene da consulta

**A pergunta que sai NÃO é a transcrição.** Ela é reconstruída a partir da intenção reconhecida, e passa por um filtro que remove, antes de sair:

- placa de veículo, matrícula, nome próprio, indicativo de guarnição
- endereço específico da ocorrência corrente (a categoria pode sair, o número não)
- qualquer coisa que o roteador tenha classificado como identificador

O que sai de *"Claryon, estou na Rui Barbosa em Niterói, qual o hospital mais próximo"* é uma consulta geoespacial por categoria `hospital` com um raio — **não** a frase.

## 6. Aceite (EARS)

- **WHEN** a fonte local responde, **THE SYSTEM SHALL** usá-la e **não** consultar fonte externa.
- **WHEN** a fonte local não responde e há rede, **THE SYSTEM SHALL** tentar a externa na ordem estruturada → textual.
- **WHEN** não há rede, **THE SYSTEM SHALL** recusar com motivo falado, exatamente como hoje — **sem degradação do comportamento atual**.
- **THE SYSTEM SHALL** registrar a procedência — serviço, trecho e carimbo de tempo — em **toda** resposta de fonte externa, verificável por teste que inspecione o registro.
- **THE SYSTEM SHALL NOT** gastar palavra falada com ressalva de origem. A citação de norma é o sinal de credibilidade da resposta interna; a **ausência** dela é o sinal da externa. Verificável por teste que confira que nenhuma fala de fonte externa contém termo de rebaixamento.
- **THE SYSTEM SHALL NOT** enviar áudio, frame, transcrição literal, placa, matrícula, nome ou posição de par a qualquer serviço externo — verificável por teste que inspecione a consulta emitida.
- **THE SYSTEM SHALL** aplicar o teto de latência: a consulta externa tem prazo, e estourá-lo é **recusa**, não espera.
- **THE SYSTEM SHALL** aplicar `GuardaDaRedacao` ao texto externo antes de qualquer redação — a régua de grandezas vale para fonte de fora tanto quanto para a lei.

## 7. O que decidir antes do diff

**Decididas em 22/08 pelo dono do projeto:**

1. **Prazo da consulta externa: 2 s**, o mesmo da consulta de posição. Estourou, é
   recusa falada — não espera.
2. **Geoespacial primeiro, e sem chave.** OSM/Overpass é aberto. A busca textual, que
   exige chave, fica para depois: chave em APK é chave vazada, e intermediar pelo
   Supabase acrescenta um salto e uma dependência na hora da demonstração.
3. **A fala não se rebaixa; o registro guarda a fonte.** Ver §4.
4. **Sem geoespacial offline na v1.** Os dados da região no APK fariam a camada
   funcionar sem rede, mas custam tamanho num APK que já tem 384 MB, e a v1 precisa
   provar o caminho antes de otimizá-lo. Fica registrado como o primeiro candidato a
   v2, porque é o que faria a camada sobreviver à região sem sinal que o relato da
   PMERJ descreve.

5. **A pergunta não respondida é registrada, e isso muda o que esta feature É.**

   Decidido em 22/08, com o argumento do dono do projeto: *"vamos alimentando. Um dia
   ele não precisará consultar na internet e diminuirá nossa latência."*

   Isso reposiciona a consulta externa: **ela não é uma dependência, é um andaime.**
   Cada pergunta que a web responde é uma pergunta que o corpus **deveria** responder,
   e o registro é o mapa de para onde o corpus precisa crescer — construído com uso
   real, não com palpite sobre o que o policial perguntaria.

   A curva pretendida:

   ```
   v1   maioria das perguntas fora do corpus → rede → ~2 s, e falha sem sinal
   vN   maioria já no corpus                 → local → 618 ms, e funciona sem sinal
        a rede vira exceção, não regra
   ```

   Isto importa para um produto que opera onde **nem rádio digital pega**. Uma feature
   que aumentasse a dependência de rede andaria contra o campo; uma que a reduz com o
   tempo anda a favor.

   ### O desenho, e por que ele é barato

   **O que sai para a fonte externa e o que entra no registro são a MESMA string.** O
   filtro de higiene do §5 já removeu placa, matrícula, nome e indicativo antes de a
   consulta sair — então o texto que vai para o registro já nasce limpo. Um filtro, dois
   consumidores; não há um segundo lugar onde a higiene possa divergir.

   **O que é guardado:** a consulta higienizada, a categoria de intenção, se houve
   resposta, e a fonte que respondeu.

   **O que NÃO é guardado, e é o que torna o registro publicável:**
   - ❌ a transcrição literal — só a consulta reconstruída
   - ❌ identificador de agente, guarnição ou aparelho
   - ❌ posição
   - ❌ carimbo de tempo com precisão que permita correlacionar com uma ocorrência —
     a granularidade é o dia, não o segundo

   Sem agente, sem posição e sem hora exata, duas perguntas do mesmo turno não são
   ligáveis entre si — que é a propriedade que separa "estatística de uso" de
   "histórico de um policial".

## 8. O que esta spec NÃO propõe

- Não propõe embarcar o LLM. A cascata funciona **sem** ele: o trecho recuperado é lido com citação, que é o comportamento verbatim que já existe.
- Não propõe transmissão de vídeo nem orientação visual.
- Não revoga nenhuma outra proibição do §2.
