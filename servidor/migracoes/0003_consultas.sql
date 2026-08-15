-- Consultas do servidor. Existem para que **nenhuma resolução de destinatário ou
-- de posição aconteça no cliente** — o aparelho de um agente jamais recebe a
-- lista de onde os outros estão.

-- Fan-out geográfico do alerta (C3). Chamada pela Edge Function `transmit`.
create or replace function agentes_no_raio(
  lon double precision,
  lat double precision,
  raio_m integer,
  excluir uuid
)
returns table (agent_id uuid)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select p.agent_id
    from agent_positions p
   where p.agent_id <> excluir
     and raio_m > 0
     and ST_DWithin(p.geom, ST_MakePoint(lon, lat)::geography, raio_m)
     -- Posição obsoleta não conta como "está perto": mandar apoio para onde o
     -- agente estava há meia hora é pior que não mandar.
     and p.updated_at > now() - interval '5 minutes'
$$;

-- Consulta de posição por voz (C2). Chamada pela Edge Function `locate`.
--
-- **Devolve distância, rumo e idade — NUNCA coordenadas.** É aqui que mora a
-- diferença entre coordenação e rastreamento: mesmo que o cliente seja
-- comprometido, ele não tem como obter a posição bruta de outro agente.
--
-- Só responde sobre quem divide talk group com o solicitante. Reciprocidade:
-- quem pode ser consultado também pode consultar.
create or replace function posicao_relativa(
  solicitante_id uuid,
  indicativo text
)
returns table (
  indicativo_alvo text,
  distancia_m double precision,
  azimute double precision,
  speed_mps real,
  idade_s integer
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select
    alvo.indicativo,
    ST_Distance(pos_alvo.geom, pos_sol.geom),
    degrees(ST_Azimuth(pos_sol.geom::geometry, pos_alvo.geom::geometry)),
    pos_alvo.speed_mps,
    extract(epoch from (now() - pos_alvo.updated_at))::integer
  from agents alvo
  join agent_positions pos_alvo on pos_alvo.agent_id = alvo.id
  join agent_positions pos_sol  on pos_sol.agent_id  = solicitante_id
  where lower(alvo.indicativo) = lower(posicao_relativa.indicativo)
    and alvo.id <> solicitante_id
    -- Divide talk group com quem perguntou.
    and exists (
      select 1
        from memberships meu
        join memberships dele on dele.talk_group_id = meu.talk_group_id
       where meu.agent_id = solicitante_id
         and dele.agent_id = alvo.id
    )
  limit 1
$$;
