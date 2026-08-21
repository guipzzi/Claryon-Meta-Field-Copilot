-- ─────────────────────────────────────────────────────────────────────────────
-- Semente de demonstração da base veicular — `procedencia = 'demonstracao'`
-- ─────────────────────────────────────────────────────────────────────────────
--
-- POR QUE ESTE ARQUIVO É SEPARADO DA MIGRAÇÃO
--
-- Estrutura e dado têm ciclos de vida diferentes, e é o precedente de
-- `seed_piloto.sql`. Mas há um motivo mais forte aqui: o dia em que a corporação
-- entregar a base real, ela entra **substituindo** este conteúdo, e a operação tem
-- de ser um arquivo que se deixa de rodar — não um `delete` cirúrgico dentro de uma
-- migração já aplicada.
--
-- Esquecer de aplicar esta semente não produz resposta errada: com a tabela vazia,
-- `consultar_placa` devolve `base_indisponivel`, nunca `nao_encontrada`. A falha de
-- implantação soa como falha, e não como "veículo sem restrição".
--
-- COMO A DEMONSTRAÇÃO SE DISTINGUE DA BASE REAL
--
-- Por `procedencia = 'demonstracao'` em toda linha, `not null` e sob CHECK — e,
-- porque toda resposta de `consultar_placa` carrega o campo, **inclusive a que não
-- acha nada**, o cliente nunca recebe um resultado sem saber de onde ele veio.
-- Enquanto qualquer linha destas estiver na tabela, `private.procedencia_da_base()`
-- responde `demonstracao` para o conjunto inteiro: uma base misturada não pode
-- afirmar "não encontrada" com autoridade oficial.
--
-- **O prefixo `DEM` das placas é mnemônico, não garantia.** Ele ajuda quem lê o log
-- ou assiste à demonstração a perceber na hora que aquilo é encenação. Mas não
-- existe faixa de placa brasileira reservada para teste que eu possa afirmar sem
-- confirmar na fonte oficial — e o `CLAUDE.md` §2 proíbe escrever esse tipo de
-- afirmação de memória. Então o prefixo é conveniência humana; **a garantia é o
-- campo `procedencia`**, que é o que o código lê.
--
--     python3 servidor/executar_sql.py servidor/semente_veicular.sql
--
-- As seis linhas cobrem os seis valores de `restricao`, e a cobertura é proposital:
-- o roteiro de demonstração precisa poder mostrar tanto o veículo que trava a
-- abordagem quanto o que a libera. Um roteiro só com casos limpos não prova nada, e
-- um só com casos sujos esconde que "sem restrição" também é uma afirmação da base.

begin;

insert into private.veiculos (placa, procedencia, fonte, restricao, marca_modelo, cor, ano)
values
  -- O caso que justifica o produto: a resposta que não pode falhar nem atrasar.
  ('DEM0A01', 'demonstracao', 'Semente de demonstração — Claryon Field',
   'roubo_furto', 'Fiat Uno', 'Branco', 2014),

  -- O caso que justifica o rigor: "sem restrição" é um valor ESCRITO na base, com
  -- fonte e data, e não o silêncio do sistema interpretado como boa notícia.
  ('DEM0A02', 'demonstracao', 'Semente de demonstração — Claryon Field',
   'sem_restricao', 'Honda Civic', 'Prata', 2019),

  ('DEM0A03', 'demonstracao', 'Semente de demonstração — Claryon Field',
   'bloqueio_judicial', 'Toyota Corolla', 'Preto', 2021),

  -- Formato antigo (ABC1234): a base tem de aceitar os dois, porque a frota real
  -- tem os dois e o agente fala o que está no veículo.
  ('DEM1234', 'demonstracao', 'Semente de demonstração — Claryon Field',
   'apreensao', 'Volkswagen Gol', 'Vermelho', 2011),

  ('DEM5678', 'demonstracao', 'Semente de demonstração — Claryon Field',
   'licenciamento_vencido', 'Chevrolet Onix', 'Azul', 2018),

  ('DEM0A04', 'demonstracao', 'Semente de demonstração — Claryon Field',
   'clonagem_suspeita', 'Hyundai HB20', 'Cinza', 2020)

-- Idempotente: reaplicar a semente na véspera da demonstração não pode falhar por
-- chave duplicada nem sobrescrever um ajuste feito no roteiro.
on conflict (placa) do nothing;

commit;
