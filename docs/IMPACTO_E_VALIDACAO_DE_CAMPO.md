# Impacto e validação de campo

**Critério §11.2 do edital — 30 pontos:** *"Potencial de valor e de transformação para
o público-alvo."*

O workshop de pitch da organização cobra, com estas palavras: *"Validaram o problema de
maneira local"*, *"Validaram a solução"*, *"Diversidade em fontes de dados (leis,
relatos, pesquisas…)"*. Este documento é a resposta — e ela **não começa pela solução**.

---

## 1. O problema, dito por quem vive nele

**Fonte primária:** entrevista com **um policial militar da PMERJ**, agosto de 2026.
Citações usadas **com autorização expressa** do entrevistado. Por decisão de projeto, a
identificação para aqui: sem nome, sem iniciais, sem unidade, sem matrícula.

> *"Pelo menos aqui na polícia do Rio de Janeiro a gente sempre teve historicamente uma
> dificuldade muito grande na parte da comunicação. […] Tem um rádio-base aqui no meu
> batalhão, porém ele não funciona por conta do sinal. **Rádio digital aqui não
> funciona.**"*

O que ele descreve não é um sistema ruim. É a **ausência** de um sistema, preenchida por
improviso:

> *"Basicamente esse contato integrado é feito pelos grupos de WhatsApp."*

> *"Hoje em dia é todo mundo de fone. Quando é patrulha, é todo mundo no WhatsApp, no
> grupo em ligação e com fone de ouvido com microfone. Aí eles fecham a operação ali e
> vai todo mundo falando por ligação. E tem o rádio também, todo mundo leva um rádio na
> mesma frequência, **mas é muito deficiente**."*

### O que essas duas frases significam para o produto

O estado da arte operacional **já é** uma chamada de grupo com fone de ouvido. O policial
já resolveu o problema com as ferramentas que tinha — e a solução dele é
funcionalmente um walkie-talkie sobre IP, feito com WhatsApp.

**O Claryon não propõe um comportamento novo. Ele substitui um improviso por uma
ferramenta desenhada para o uso.** Essa distinção importa: produto que exige mudança de
hábito falha; produto que formaliza um hábito existente é adotado.

---

## 2. A tese do produto, formulada pelo entrevistado sem ser perguntado

Este é o trecho decisivo. A pergunta central do Claryon — *por que mãos-livres importa
em segurança pública?* — foi respondida por ele, espontaneamente:

> *"Às vezes você se depara com a situação em que é necessário passar essa informação em
> tempo real, mas e aí? Tu vai ter que às vezes meter a mão no rádio pra falar. **Quem
> vai falar? É o policial que tá ali no carona com a arma de fogo já em pronto emprego?
> É o que está dirigindo, que vai ter que tirar a mão do volante ali pra acionar o
> rádio?** E aí, se o rádio não tiver pegando, vai ter que um dos dois pegar o celular e
> digitar no grupo do WhatsApp se algum veículo se evadir."*

Desmonta-se assim:

| O que ele descreve | O custo real |
|---|---|
| Carona com arma em pronto emprego | Largar o armamento para operar rádio |
| Motorista em perseguição | Tirar a mão do volante |
| Rádio sem sinal | O improviso falha justamente quando é mais necessário |
| **Digitar no WhatsApp** durante evasão de veículo | Olhos fora da cena, mãos fora do volante e da arma, num veículo em movimento |

O último item é o caso de uso do Claryon inteiro, em uma frase de um policial.

---

## 3. Ele desenhou a arquitetura do copiloto — e chamou de "comando inteligente"

> *"Ter um dispositivo que reconheça comandos inteligentes… Um exemplo: quando fala
> **'Maré zero'**, aí de repente ele abre um comando inteligente que **aciona o
> Batalhão**, aciona a **central das viaturas**, **informa a localização**, qualquer
> coisa do tipo."*

Isto é, literalmente: **palavra falada → intenção reconhecida → ação disparada com
posição anexada**. É a arquitetura já implementada — palavra de ativação, roteador
determinístico de intenção, `IntentExecutor`, posição publicada pelo próprio aparelho.

**A validação mais forte não é ele gostar da solução. É ele descrever a solução antes de
ver a solução.**

Nota de projeto: o exemplo dele usa um código operacional local (*"Maré zero"*) como
gatilho. O Claryon já suporta vocabulário fechado por corporação — foi assim que o
alfabeto militar e o ordinal de placa da Portaria 071-CG/15 da PMBA entraram. Códigos de
PMERJ entram pelo mesmo caminho, sem alterar arquitetura.

---

## 4. Onde ele diz que isto entra primeiro

Ele é cético quanto à adoção ampla, e é preciso registrar isso com as palavras dele:

> *"Pra Polícia Militar seria interessante, mas **eu acho difícil fazerem esse tipo de
> investimento, a não ser nas tropas especiais**, né: patrulha urbana, Choque, o BOPE."*

Isso não é objeção — é **definição de mercado inicial**, vinda de dentro:

| Camada | Unidades | Por que aqui |
|---|---|---|
| **Praia de desembarque** | BOPE, Choque, patrulha urbana especializada | Orçamento próprio, operação coordenada, já usam fone |
| **Expansão** | Patrulhamento urbano, conduta de patrulha | Volume, mas exige custo por unidade menor |
| **Adjacente** | Polícia Civil, negociação de crise | Ele cita ambas espontaneamente |

Um pitch que diz "toda a PM do Brasil" perde credibilidade. Um que diz "tropas
especiais primeiro, porque foi um PM que nos disse isso" ganha.

---

## 5. O caso adjacente que ele levantou: transmissão de cena

> *"Pra negociação também de crise / resgate de refém, pro policial poder transmitir ali
> em tempo real pra uma central de inteligência, uma central de crise, o cenário de
> maneira mais ampla. Isso hoje também é feito através da câmera corporal."*

Importante para o escopo: o Claryon **não** faz transmissão contínua de vídeo, e não vai
fazer — é incompatível com as proibições duras de privacidade do projeto e com a
bateria. Registramos como demanda adjacente identificada, **não** como capacidade
prometida.

O que o produto faz na direção disso é a **captura pontual sob comando** — a leitura de
placa pela câmera, com o frame vivendo apenas em RAM.

---

## 6. Veredito dele

> *"É uma ideia muito revolucionária, porque com certeza vai dinamizar mais o trabalho da
> polícia."*

> *"É um equipamento que veio pra revolucionar a parte operacional."*

> *"Muito válido, muito bacana. Se der certo é uma ideia muito boa, muito boa,
> excelente."*

**Repare na condicional: "se der certo".** Ele endossa a tese e reserva juízo sobre a
execução. É exatamente a postura que este projeto adota consigo mesmo — e é por isso que
o documento de prontidão de hardware declara o que ainda não foi medido.

---

## 7. Como cada pilar do produto responde ao que ele relatou

| O que o entrevistado relatou | Pilar | Como responde |
|---|---|---|
| Rádio digital não pega; WhatsApp é o substituto | **P1 — rede de comunicação** | PTT sobre IP, com transcrição **na origem**: todos leem o mesmo texto, e o servidor nunca precisa transcrever |
| Digitar no grupo durante ocorrência | **P1 + P3** | Voz em vez de teclado; mãos no volante e no armamento |
| "Informa a localização" no comando | **P2 — geolocalização** | Posição publicada pelo próprio aparelho; o servidor devolve grandezas, nunca coordenada de terceiro |
| "Comando inteligente" por palavra falada | **P3 — copiloto local** | Palavra de ativação → roteador determinístico → ação. **100% no aparelho** |
| Sinal deficiente | Transversal | O caminho crítico não depende de nuvem: STT, TTS, OCR e consulta de norma são locais |

O último merece ênfase. Ele descreve uma região onde **o rádio digital não funciona por
falta de sinal**. Uma solução que dependesse de nuvem para entender comandos falharia no
mesmo lugar e pela mesma razão. **A decisão de IA local deixa de ser preferência
arquitetural e passa a ser requisito de campo.**

---

## 8. Método, e o que esta pesquisa ainda não é

**Diversidade de fontes** (item cobrado pelo workshop de pitch):

- **Relato de campo** — entrevista com policial militar da PMERJ (esta seção)
- **Fonte normativa** — Portaria 071-CG/15 da PMBA, que define a ditagem de algarismo
  por ordinal; 1817 trechos de cinco leis federais indexados
- **Medição própria** — WER 3,4%; 40/40 em placa ditada; 2 frames e 67–180 ms na câmera

**Limites honestos desta validação:**

1. **N = 1 no momento desta versão.** Duas entrevistas adicionais estão agendadas.
2. **Uma corporação e uma região.** PMERJ, Norte/Noroeste Fluminense. A PMBA aparece
   por fonte normativa, não por entrevista.
3. **Nenhum teste de uso.** Ele reagiu à descrição, não ao produto. *"Se der certo"* é
   a ressalva dele, e continua de pé.
4. **Viés de cortesia** não pode ser descartado numa entrevista única e não estruturada.

O que **não** é limitação: os trechos usados aqui são descrições do trabalho **dele**,
não opiniões sobre o nosso produto. *"Quem vai falar? O carona com a arma em pronto
emprego?"* vale independentemente do que ele ache do Claryon.

---

## 9. Consentimento e tratamento do depoimento

Coerente com o [Relatório de Impacto LGPD](RELATORIO_DE_IMPACTO_LGPD.md):

- **Autorização expressa concedida** pelo entrevistado para uso das citações em
  material submetido à organização.
- Identificação reduzida ao mínimo que sustenta a fonte: **"um policial militar da
  PMERJ"**. Sem nome, **sem iniciais**, sem unidade, sem matrícula. Autorização não é
  licença para identificar mais do que o necessário — é o princípio da **necessidade**
  (art. 6º, III), aplicado a nós mesmos.
- O áudio da entrevista **não** entra no repositório.

---

*Documento vivo. Próxima revisão após as duas entrevistas adicionais.*
