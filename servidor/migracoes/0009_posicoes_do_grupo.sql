-- 0009 — Posições relativas de todos os pares do talk group, para o mapa.
--
-- **Por que não Postgres Changes.**
--
-- O caminho óbvio seria publicar `agent_positions` no Realtime e deixar o banco
-- empurrar as mudanças. Ele foi descartado por privacidade, não por desempenho:
-- Postgres Changes envia **a linha inteira**, incluindo `geom`. Cada aparelho da
-- guarnição passaria a receber a coordenada bruta de todos os outros — que é
-- exatamente a garantia que `consultar_posicao` existe para manter.
--
-- A regra do produto é "o aparelho de um agente jamais recebe a posição de
-- outro". Uma assinatura que entrega `geom` a transformaria em promessa, e o
-- cliente teria de descartar o que já recebeu — o que não é descartar, é confiar.
--
-- Então o mapa pergunta, e o servidor responde em grandezas. Enquanto a tela está
-- aberta — que é 5% do turno — sondar a cada poucos segundos custa menos que
-- manter uma assinatura viva o turno inteiro, e mantém a garantia intacta.

begin;

create or replace function public.posicoes_do_grupo(talk_group uuid)
returns table (
  indicativo text,
  distancia_m double precision,
  azimute double precision,
  speed_mps real,
  idade_s integer,
  idade_solicitante_s integer
)
language sql
stable
security definer
set search_path = ''
as $$
  with eu as (
    select private.current_agent_id() as id
  ),
  minha as (
    select p.geom, p.updated_at
      from public.agent_positions p, eu
     where p.agent_id = eu.id
  )
  select
    a.indicativo,
    public.ST_Distance(pos.geom, minha.geom),
    -- `ST_Azimuth` devolve NULL para pontos coincidentes — dupla na mesma
    -- viatura. O cliente trata; forçar zero aqui inventaria "norte".
    degrees(public.ST_Azimuth(minha.geom::public.geometry, pos.geom::public.geometry)),
    pos.speed_mps,
    extract(epoch from (now() - pos.updated_at))::integer,
    extract(epoch from (now() - minha.updated_at))::integer
  from public.agents a
  join public.agent_positions pos on pos.agent_id = a.id
  cross join minha
  cross join eu
  where a.id <> eu.id
    -- Só quem divide talk group com quem perguntou, e só o grupo pedido.
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

comment on function public.posicoes_do_grupo(uuid) is
  'C5: distância, rumo e idade de cada par do talk group. Nunca coordenadas. '
  'Substitui uma assinatura de Postgres Changes, que entregaria geom bruto a '
  'todos os aparelhos da guarnição.';

revoke all on function public.posicoes_do_grupo(uuid) from public, anon;
grant execute on function public.posicoes_do_grupo(uuid) to authenticated;

commit;
