-- Consultas do servidor. Existem para que **nenhuma resolução de destinatário ou
-- de posição aconteça no cliente** — o aparelho de um agente jamais recebe a
-- lista de onde os outros estão.
--
-- ⚠️ Tipos e funções do PostGIS vêm **qualificados com `public.`** porque estas
-- funções usam `search_path = ''`. Caminho vazio é a recomendação de segurança
-- (impede sequestro de resolução de nomes numa função SECURITY DEFINER), mas
-- obriga a qualificar TUDO — inclusive o tipo `geography`, não só as funções
-- `ST_*`. Neste projeto o PostGIS está no schema `public`; se um dia migrar para
-- `extensions`, estes prefixos mudam junto.
--
-- `pg_catalog` continua implícito mesmo com search_path vazio, então `now()`,
-- `lower()`, `degrees()` e `extract()` não precisam de prefixo.

-- Fan-out geográfico do alerta (C3). Chamada pela Edge Function `transmit`.
create or replace function private.agentes_no_raio(
  lon double precision,
  lat double precision,
  raio_m integer,
  excluir uuid
)
returns table (agent_id uuid)
language sql
stable
security definer
set search_path = ''
as $$
  select p.agent_id
    from public.agent_positions p
   where p.agent_id <> excluir
     and raio_m > 0
     and public.ST_DWithin(p.geom, public.ST_MakePoint(lon, lat)::public.geography, raio_m)
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
create or replace function private.posicao_relativa(
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
set search_path = ''
as $$
  select
    alvo.indicativo,
    public.ST_Distance(pos_alvo.geom, pos_sol.geom),
    degrees(public.ST_Azimuth(pos_sol.geom::public.geometry, pos_alvo.geom::public.geometry)),
    pos_alvo.speed_mps,
    extract(epoch from (now() - pos_alvo.updated_at))::integer
  from public.agents alvo
  join public.agent_positions pos_alvo on pos_alvo.agent_id = alvo.id
  join public.agent_positions pos_sol  on pos_sol.agent_id  = solicitante_id
  -- `posicao_relativa.indicativo` desambigua o PARÂMETRO da coluna homônima
  -- `alvo.indicativo`. Sem o prefixo, o Postgres resolveria para a coluna e a
  -- condição viraria uma tautologia — casaria com todo agente do talk group.
  -- O prefixo é o nome da função SEM o schema: `private.` aqui é erro de sintaxe.
  where lower(alvo.indicativo) = lower(posicao_relativa.indicativo)
    and alvo.id <> solicitante_id
    -- Divide talk group com quem perguntou.
    and exists (
      select 1
        from public.memberships meu
        join public.memberships dele on dele.talk_group_id = meu.talk_group_id
       where meu.agent_id = solicitante_id
         and dele.agent_id = alvo.id
    )
  limit 1
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Execução restrita ao `service_role`.
--
-- Estas duas devolvem informação sobre TERCEIROS. Só as Edge Functions podem
-- chamá-las; o cliente nunca. Morarem em `private` já as esconde do PostgREST,
-- e o GRANT explícito é a segunda camada — defesa que não depende de o schema
-- exposto continuar sendo só `public` amanhã.
-- ─────────────────────────────────────────────────────────────────────────────
revoke execute on function private.agentes_no_raio(double precision, double precision, integer, uuid) from public, anon, authenticated;
revoke execute on function private.posicao_relativa(uuid, text) from public, anon, authenticated;

grant execute on function private.agentes_no_raio(double precision, double precision, integer, uuid) to service_role;
grant execute on function private.posicao_relativa(uuid, text) to service_role;
