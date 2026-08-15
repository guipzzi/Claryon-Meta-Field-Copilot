-- 0008 — Publicação da própria posição.
--
-- Por RPC e não por escrita direta em `agent_positions`, pela mesma razão da
-- 0006: **a identidade vem do JWT, nunca do payload**. Com um INSERT direto pelo
-- PostgREST, a política de linha teria de comparar `agent_id` com
-- `current_agent_id()` — o que funciona, mas deixa a porta aberta para alguém
-- afrouxar a política um dia. Aqui o `agent_id` sequer é parâmetro: não existe
-- forma de escrever a posição de outro agente.
--
-- Contrapartida importante: publicar é **pré-condição da reciprocidade**. Quem
-- consulta a posição dos pares está, por construção, publicando a própria.

begin;

create or replace function public.publicar_posicao(
  lat double precision,
  lon double precision,
  precisao_m real default null,
  velocidade_ms real default null,
  rumo_graus real default null
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  eu uuid := private.current_agent_id();
begin
  if eu is null then
    raise exception 'sem agente vinculado ao usuário autenticado';
  end if;

  -- Coordenada fora do domínio é erro de sensor, não posição. Gravar levaria o
  -- ST_Distance a devolver números plausíveis para um ponto que não existe.
  if lat is null or lon is null
     or lat < -90 or lat > 90 or lon < -180 or lon > 180
     or lat <> lat or lon <> lon then
    raise exception 'coordenada inválida';
  end if;

  insert into public.agent_positions (agent_id, geom, heading, speed_mps, accuracy_m, updated_at)
  values (
    eu,
    public.ST_SetSRID(public.ST_MakePoint(lon, lat), 4326)::public.geography,
    rumo_graus,
    velocidade_ms,
    precisao_m,
    now()
  )
  on conflict (agent_id) do update set
    geom       = excluded.geom,
    heading    = excluded.heading,
    speed_mps  = excluded.speed_mps,
    accuracy_m = excluded.accuracy_m,
    -- `now()` do servidor, não um carimbo do cliente. O relógio do celular pode
    -- estar adiantado, e uma posição "do futuro" seria lida como recentíssima
    -- pela política de obsolescência — o pior erro possível, porque afirma como
    -- atual justamente o que não dá para saber.
    updated_at = now();
end;
$$;

comment on function public.publicar_posicao(double precision, double precision, real, real, real) is
  'C5: publica a posição do agente autenticado. O agent_id vem do JWT — não é '
  'parâmetro, então não existe forma de escrever a posição de outro agente.';

revoke all on function public.publicar_posicao(double precision, double precision, real, real, real) from public, anon;
grant execute on function public.publicar_posicao(double precision, double precision, real, real, real) to authenticated;

commit;
