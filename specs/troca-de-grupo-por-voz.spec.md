---
feature: troca-de-grupo-por-voz
capacidade: C1 (rádio tático) — seleção de talk group sem tocar na tela
estado: proposta
autor: revisão humana pendente
criada: 2026-08-17
sobrepoe: nada — capacidade nova dentro do desenho de léxico fechado já aprovado
depende_de:
  - gatilho-por-voz
  - migracao-0011-rotulo-falado
  - dono-unico-da-saida-de-audio
---

# Troca de talk group por voz

## Objetivo

O agente muda de guarnição falando, porque trocar de canal é o que se faz **no
momento em que as mãos estão ocupadas** — chegando na ocorrência, saindo da
viatura, com o rádio na outra mão. Hoje o canal é a constante `CanalDoPiloto.ID`,
escrita em código: o app fala com uma guarnição e só com ela.

A fundação já existe e está verificada no servidor: a migração `0011` deu à
`talk_groups` a coluna `rotulo_falado`, `public.meus_rotulos_falados()` devolve
**só** os grupos de que este agente é membro com o solicitante saindo do JWT, e
`RadioTatico.trocarDeGrupo` troca sem tocar em `AudioDoAgente`. O que falta é o
caminho da fala até lá — e é só isso que esta spec define.

## O que esta capacidade NÃO é

**Não é autorização.** A lista de rótulos é conveniência de UX e economia de rede.
A fronteira de segurança continua sendo `pedir_canal`, que recusa não-membro em
`servidor/migracoes/0005_controle_de_piso.sql:78-82`. Um cliente adulterado que
peça um grupo fora da sua lista continua sendo recusado onde importa. Esta spec
não pode ser lida como "o cliente decide de que grupo o agente é membro".

**Não é busca.** Não há correspondência aproximada, fonética ou por similaridade
(ver § *Por que não há casamento aproximado*).

## Comportamento

### O comando

| Papel | Frase | Justificativa |
|---|---|---|
| Troca | **"Claryon, mudar para `<rótulo>`"** | "mudar para" é verbo explícito e não é locução de protocolo — operador diz "muda pro canal X" no ar, mas não "Claryon, mudar para" |
| Sinônimo aceito | **"Claryon, trocar para `<rótulo>`"** | mesma estrutura; duas formas cobrem a variação natural sem abrir o léxico |

O `<rótulo>` **não é vocabulário do código**. Ele vem de `rotulo_falado` no
servidor, por agente, e o cliente só compara. Um município que chame suas unidades
de "viatura", "equipe" ou "setor" funciona sem recompilar nada — e é por isso que a
coluna existe no banco em vez de um `enum` em Kotlin.

### A resolução, e por que ela é exata

O rótulo falado é normalizado pela **mesma** função nos dois lados
(`RotulosFalados.normalizar`: minúsculas, sem acento, espaços colapsados) e
comparado por **igualdade**, contra a lista carregada no login.

#### Por que não há casamento aproximado

A tentação é óbvia: o STT devolveu "agora nisso são três" e "guarnição três" está
na lista, então uma distância de edição resolveria. **Está proibido**, e a razão é
operacional, não estética.

Trocar de grupo redireciona a voz do agente para outra guarnição. Um casamento
aproximado que erra faz o agente pedir apoio para a unidade errada acreditando ter
pedido para a certa — e ele não tem como saber, porque a confirmação falada diria o
nome do grupo que o sistema *escolheu*, não o que ele *quis*. Num produto de
segurança pública, **recusar é sempre melhor que adivinhar**: recusa custa uma
repetição, adivinhação errada custa apoio que não vem.

Se a transcrição é ruim demais para casar exato, o defeito é do STT e é lá que se
conserta. Distância de edição aqui seria um remendo que **esconde** o defeito e
converte erro de transcrição em erro de despacho.

### A recusa não revela o que não é do agente

`Se` o rótulo falado não estiver na lista deste agente, a resposta `deverá` ser
*"Não conheço essa guarnição."* — e **nunca** distinguir "não existe" de "existe e
você não é membro". A distinção é informação sobre a estrutura da corporação, e o
princípio já vale no servidor: ele devolve grandezas, nunca coordenada de terceiro.
Vazar existência de grupo pelo texto da recusa seria a mesma classe de erro, por
outra porta.

Consequência aceita: o agente que erra o nome recebe a mesma resposta de quem pede
um grupo alheio. É o preço, e é barato — a lista dele está na tela.

### Critérios de aceite (EARS)

1. `Quando` a transcrição normalizada começar por "claryon" `e` o restante casar o
   padrão de troca com um rótulo não vazio, `o sistema deverá` rotear para
   `Intent.TrocarDeGrupo(rotuloFalado)`.
2. `Quando` `Intent.TrocarDeGrupo` for executada `e` o rótulo normalizado casar por
   **igualdade** um `rotulo_falado` da lista do agente, `o sistema deverá` chamar
   `RadioTatico.trocarDeGrupo(id)` `e` devolver `ActionOutcome.GrupoTrocado` com o
   **nome de exibição** do grupo, nunca o UUID.
3. `Se` o rótulo não casar nenhum item da lista, `então o sistema deverá` devolver
   `ActionOutcome.GrupoNaoReconhecido` `e` dizer *"Não conheço essa guarnição."*,
   sem revelar se o grupo existe.
4. `Se` o agente já estiver no grupo pedido, `então o sistema deverá` devolver
   `ActionOutcome.GrupoTrocado` do grupo corrente `e` **não** derrubar o transporte
   — trocar para onde já se está não pode custar uma reconexão.
5. `Enquanto` houver transmissão de rádio em curso, `o sistema deverá` recusar a
   troca `e` dizer *"Fale depois de encerrar."* Trocar de grupo no meio de uma
   transmissão mandaria o fim da frase para a guarnição errada.
6. `Se` a lista de rótulos não tiver sido carregada (sem sessão, ou falha de rede
   no login), `então o sistema deverá` recusar `e` dizer *"Sem lista. Entre de
   novo."* — nunca cair para `CanalDoPiloto` em silêncio.
7. `O sistema deverá` manter toda resposta falada desta feature em **≤ 7 palavras**
   (invariante do `CLAUDE.md` §4) `e` derivá-la do `ActionOutcome`, nunca da
   `Intent`.
8. `Quando` a troca for bem-sucedida, `o sistema deverá` emitir `ACAO_EXECUTADA`
   antes da fala, `e` a fala `deverá` nomear o grupo — confirmação cega ("pronto")
   é inaceitável quando o efeito é redirecionar a voz do agente.

### Como se mede que funcionou

| Aceite | Como se prova |
|---|---|
| 1 | teste de unidade do `DeterministicIntentRouter`, com o contra-teste de que "claryon mudar para" **sem** rótulo dá `NaoReconhecida` |
| 2, 3, 4 | teste de unidade do executor com lista falsa; o de (4) exige que `trocarDeGrupo` **não** seja chamado |
| 5 | teste de unidade com transmissão simulada em curso |
| 6 | teste de unidade com lista vazia; asserção sobre a fala, não só sobre o outcome |
| 7 | o teste de laconicidade já existente varre `utteranceFor` inteiro |
| 8 | instrumentado: o earcon precede a fala na fila única |
| corrente inteira | `VerificadorDoGatilhoTest` (instrumentado): fala sintetizada → VAD → whisper → léxico → troca efetiva, com asserção sobre o grupo corrente do `RadioTatico` |

### Fora de escopo

- **Criar ou editar grupo por voz.** Lotação é cadastro, não comando de campo.
- **Listar grupos por voz** ("Claryon, quais guarnições?"). A tela mostra a lista;
  ler oito nomes por HFP violaria o teto de 7 palavras e não cabe no ciclo de 2 s.
- **Recarregar a lista por voz.** Ela vem no login. Se mudou a lotação, entra de
  novo — e isso é raro por construção.

### Riscos aceitos

1. **A lista envelhece dentro da sessão.** Um agente removido de um grupo continua
   com o rótulo na lista até o próximo login, e a troca vai *parecer* funcionar até
   `pedir_canal` recusar o piso. A recusa é audível e a segurança é do servidor, mas
   a mensagem de erro vai apontar para o piso e não para a lotação. Aceito: a
   alternativa é consultar rede no caminho do gatilho, que soma latência a um
   comando que precisa caber em 2 s.
2. **"mudar para" pode ser dito dentro de um ditado.** Mitigado pela ordem do
   roteador: `NARRAR` é avaliado **antes**, então "narrar ocorrência: mudar para a
   rua tal" continua sendo ditado. É o mesmo cuidado que já protege "modo
   abordagem" de virar alerta.
