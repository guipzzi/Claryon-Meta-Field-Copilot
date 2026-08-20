-- ─────────────────────────────────────────────────────────────────────────────
-- 0021 — A distância sai arredondada do SERVIDOR, não só da fala
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O QUE ESTAVA ERRADO
--
-- `PosicaoRelativa.distanciaFalada` já arredonda em faixas — 10 m abaixo de
-- 100, 50 m abaixo de 1 km, 100 m acima — e o KDoc explica o porquê: *"mil e
-- duzentos é o que um agente processa correndo; 1.237 não é"*.
--
-- Só que isso é **formatação de saída**. A `consultar_posicao` devolve
-- `distancia_m double precision` cru, e essa é a porta que qualquer portador de
-- JWT chama direto. O arredondamento na fala não protege nada: ele acontece
-- depois de o número exato já ter atravessado a rede.
--
-- DOIS PROBLEMAS, E O SEGUNDO É O QUE MOTIVA A MIGRAÇÃO
--
-- 1. **Precisão falsa.** As correções que alimentam o cálculo têm de 5 m
--    (GPS bom) a 1 000 m (torre). Devolver `1237.4829` sobre isso é um número
--    com sete dígitos de certeza que o dado não tem — a mesma classe de mentira
--    que a `0020` acabou de tirar da idade.
--
-- 2. **Resolução métrica ajuda a trilateração.** O §2 do `CLAUDE.md` proíbe
--    função que receba a identidade do solicitante como parâmetro justamente
--    porque *"com ela, distâncias trilateram a posição absoluta de qualquer
--    par"*. O solicitante vir do JWT fecha o caso geral. Mas um agente legítimo,
--    andando e consultando, coleta uma série de distâncias exatas de dentro do
--    grupo — e três delas bastam. Arredondar não impede: **degrada**, que é o
--    que se pode fazer sem quebrar o produto, porque o par precisa mesmo ser
--    encontrado.
--
-- POR QUE AS MESMAS FAIXAS DO CLIENTE, E NÃO UM PASSO ÚNICO
--
-- Um passo fixo de 50 m diria "a 50 metros" para quem está a 12 — inútil numa
-- abordagem, onde a diferença entre 10 e 50 metros é a diferença entre ver o
-- companheiro e não ver. As faixas do `distanciaFalada` já resolvem isso, e
-- repetir o mesmo critério aqui mantém **uma regra**, não duas.
--
-- A escolha tem uma propriedade que importa: arredondar duas vezes com o mesmo
-- passo é idempotente. O cliente continua chamando `distanciaFalada` sobre um
-- valor já arredondado e o resultado não muda. Se as faixas divergirem um dia,
-- o cliente arredonda por cima do servidor e o número encolhe — por isso as duas
-- tabelas estão citadas uma na outra.
--
-- O QUE ESTA MIGRAÇÃO **NÃO** RESOLVE
--
-- O arredondamento é por faixa de distância, não pela **incerteza da correção**.
-- Um par cuja última posição tem 800 m de erro continua sendo reportado com
-- granularidade de 50 m, o que ainda é precisão que aquele dado não tem. A
-- resposta certa seria arredondar pelo maior entre a faixa e a incerteza
-- combinada — mas isso faz a distância oscilar conforme o GPS do OUTRO melhora
-- ou piora, e um número que muda sem ninguém se mover é pior que um número
-- grosseiro. Fica registrado; `accuracy_m` já viaja na linha para quem quiser
-- atacar isso com uma decisão de produto por trás.

begin;

create or replace function private.distancia_arredondada(m double precision)
returns double precision
language sql
immutable
as $$
  -- **`::numeric` não é decoração.** `round(double precision)` no Postgres
  -- delega para `rint()` da libc, que faz arredondamento BANCÁRIO — metade para
  -- o par. `round(numeric)` faz metade para cima. O cliente calcula
  -- `((v + passo/2) / passo) * passo` em inteiro, que é metade para cima.
  --
  -- Com `double precision` aqui, servidor e cliente discordariam exatamente nos
  -- limites: 95 m vira 100 no cliente e 90 no servidor. Quatro valores em 715
  -- divergiam, e foi a verificação `0010` que apontou — eu tinha escrito o aviso
  -- sobre essa divergência no cabeçalho desta migração e cometido o erro no
  -- corpo dela.
  select case
    when m is null then null
    -- Abaixo de 100 m a diferença entre 10 e 50 metros decide se o agente vê o
    -- companheiro. Passo de 10.
    when m < 100  then (round(m::numeric / 10)  * 10)::double precision
    when m < 1000 then (round(m::numeric / 50)  * 50)::double precision
    else               (round(m::numeric / 100) * 100)::double precision
  end
$$;

comment on function private.distancia_arredondada(double precision) is
  'Faixas de 10/50/100 m, as MESMAS de PosicaoRelativa.distanciaFalada no '
  'cliente. Mudar aqui sem mudar lá faz o cliente arredondar por cima e o '
  'número encolher. Existe porque distância métrica crua é precisão que o GPS '
  'não tem e resolução que a trilateração agradece.';

-- ─────────────────────────────────────────────────────────────────────────────
-- As duas portas que devolvem distância
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Só o `ST_Distance` é embrulhado. O azimute continua cru de propósito: ele é o
-- que orienta para onde correr, e arredondar rumo em passos grossos manda o
-- agente para o lado errado de um quarteirão. `speed_mps` idem — o cliente já o
-- reduz a um booleano ("deslocando").

create or replace function private.posicao_relativa(solicitante_id uuid, indicativo text)
returns table(indicativo_alvo text, distancia_m double precision, azimute double precision, speed_mps real, idade_s integer)
language sql stable security definer set search_path = ''
as $$
  select
    alvo.indicativo,
    private.distancia_arredondada(public.ST_Distance(pos_alvo.geom, pos_sol.geom)),
    degrees(public.ST_Azimuth(pos_sol.geom::public.geometry, pos_alvo.geom::public.geometry)),
    pos_alvo.speed_mps,
    extract(epoch from (now() - pos_alvo.medida_em))::integer
  from public.agents alvo
  join public.agent_positions pos_alvo on pos_alvo.agent_id = alvo.id
  join public.agent_positions pos_sol  on pos_sol.agent_id  = solicitante_id
  -- `posicao_relativa.indicativo` desambigua o PARÂMETRO da coluna homônima
  -- `alvo.indicativo`. Sem o prefixo, o Postgres resolveria para a coluna e a
  -- condição viraria uma tautologia — casaria com todo agente do talk group.
  where lower(alvo.indicativo) = lower(posicao_relativa.indicativo)
    and alvo.id <> solicitante_id
    and exists (
      select 1
        from public.memberships meu
        join public.memberships dele on dele.talk_group_id = meu.talk_group_id
       where meu.agent_id = solicitante_id
         and dele.agent_id = alvo.id
    )
  limit 1
$$;

create or replace function public.posicoes_do_grupo(talk_group uuid)
returns table(indicativo text, distancia_m double precision, azimute double precision, speed_mps real, idade_s integer, idade_solicitante_s integer)
language sql stable security definer set search_path = ''
as $$
  with eu as (
    select private.current_agent_id() as id
  ),
  minha as (
    select p.geom, p.medida_em
      from public.agent_positions p, eu
     where p.agent_id = eu.id
  )
  select
    a.indicativo,
    private.distancia_arredondada(public.ST_Distance(pos.geom, minha.geom)),
    -- `ST_Azimuth` devolve NULL para pontos coincidentes — dupla na mesma
    -- viatura. O cliente trata; forçar zero aqui inventaria "norte".
    degrees(public.ST_Azimuth(minha.geom::public.geometry, pos.geom::public.geometry)),
    pos.speed_mps,
    extract(epoch from (now() - pos.medida_em))::integer,
    extract(epoch from (now() - minha.medida_em))::integer
  from public.agents a
  join public.agent_positions pos on pos.agent_id = a.id
  cross join minha
  cross join eu
  where a.id <> eu.id
    and exists (
      select 1
        from public.memberships meu
        join public.memberships dele on dele.talk_group_id = meu.talk_group_id
       where meu.agent_id = eu.id
         and dele.agent_id = a.id
         and meu.talk_group_id = talk_group
    )
  order by 2
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- `rastro_do_par` — o caso PIOR, e o que quase escapou daqui
-- ─────────────────────────────────────────────────────────────────────────────
--
-- A primeira versão desta migração deixou aqui um `raise notice` dizendo
-- "conferir se devolve arredondada". Isso é um TODO fantasiado de trabalho: a
-- migração passava, o aviso ia para um log que ninguém lê, e a função continuava
-- devolvendo métrica crua.
--
-- E ela é o caso pior de todos. As outras duas devolvem UMA distância; esta
-- devolve uma **série temporal** de até 30 minutos. Uma sequência de distâncias
-- exatas, com azimute e instante, não aproxima a trajetória do par: reconstrói.
-- Era a função com mais superfície e a única sem arredondamento.
create or replace function public.rastro_do_par(indicativo text)
returns table(distancia_m double precision, azimute double precision, idade_s integer)
language sql stable security definer set search_path = ''
as $$
  select
    private.distancia_arredondada(public.ST_Distance(tr.geom, pos_sol.geom)),
    degrees(public.ST_Azimuth(pos_sol.geom::public.geometry, tr.geom::public.geometry)),
    extract(epoch from (now() - tr.em))::integer
  from public.agents alvo
  join private.trilha_de_posicao tr on tr.agent_id = alvo.id
  -- Reciprocidade: quem vê é visto. O `join` na própria posição é o mesmo
  -- mecanismo de `posicao_relativa` — sem publicar, não há de onde medir e não há
  -- resposta.
  join public.agent_positions pos_sol on pos_sol.agent_id = private.current_agent_id()
  where lower(alvo.indicativo) = lower(rastro_do_par.indicativo)
    and alvo.id <> private.current_agent_id()
    and alvo.ativo
    and tr.em > now() - interval '30 minutes'
    -- Mesmo grupo, sempre.
    and exists (
      select 1 from public.memberships m1
      join public.memberships m2 on m2.talk_group_id = m1.talk_group_id
     where m1.agent_id = private.current_agent_id() and m2.agent_id = alvo.id
    )
  order by tr.em
$$;

commit;
