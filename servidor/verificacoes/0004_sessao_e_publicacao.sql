-- Verificação das RPCs de C2 fechado: `consultar_posicao` (0007) e
-- `publicar_posicao` (0008).
--
-- A propriedade central é a mesma nas duas: **a identidade vem do JWT, nunca do
-- payload.** Um `agent_id` como parâmetro permitiria publicar a posição de outro
-- agente, ou consultar em nome dele — e a checagem de talk group continuaria
-- passando, porque a identidade forjada é de um membro legítimo.
--
-- Somente leitura.

with verificacoes as (

-- ── consultar_posicao: a idade do solicitante ────────────────────────────────
select 1 as n, 'consultar_posicao devolve a idade do solicitante' as verificacao,
  (select pg_get_function_result(p.oid) ~ 'idade_solicitante_s'
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao') as passou

union all
select 2, 'consultar_posicao continua sem parâmetro de solicitante',
  (select bool_and(pg_get_function_arguments(p.oid) = 'indicativo text')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

union all
select 3, 'consultar_posicao continua sem expor coordenada',
  (select not (pg_get_function_result(p.oid) ~* '(lat|lon|geom|geography|point)')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

-- ── publicar_posicao: não aceita agent_id ────────────────────────────────────
union all
select 4, 'publicar_posicao NÃO aceita agent_id',
  -- O centro da migração 0008. Com `agent_id` na assinatura, qualquer agente
  -- autenticado escreveria a posição de outro — falsificando o mapa da guarnição
  -- e o ponto de onde as distâncias de todo mundo são medidas.
  (select bool_and(pg_get_function_arguments(p.oid) !~ 'agent')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'publicar_posicao')

union all
select 5, 'publicar_posicao é SECURITY DEFINER com search_path vazio',
  (select bool_and(p.prosecdef and exists (
       select 1 from unnest(coalesce(p.proconfig, array[]::text[])) c
        where c in ('search_path=', 'search_path=""')))
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'publicar_posicao')

union all
select 6, 'anon NÃO publica posição',
  (select not has_function_privilege('anon', p.oid, 'execute')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'publicar_posicao')

union all
select 7, 'authenticated publica posição',
  (select has_function_privilege('authenticated', p.oid, 'execute')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'publicar_posicao')

union all
select 8, 'publicar_posicao recusa coordenada inválida',
  -- NaN e fora de domínio são erro de sensor, não posição. Gravados, fariam o
  -- ST_Distance devolver números plausíveis para um ponto que não existe.
  (select prosrc ~ 'coordenada inválida' and prosrc ~ 'lat <> lat'
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'publicar_posicao')

union all
select 9, 'o carimbo de tempo é do servidor, não do cliente',
  -- Relógio de celular adiantado produziria posição "do futuro", lida como
  -- recentíssima pela política de obsolescência.
  (select prosrc ~ 'updated_at = now\(\)'
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'publicar_posicao')

union all
select 10, 'a publicação é upsert, não insert que falha na segunda vez',
  (select prosrc ~ 'on conflict'
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'publicar_posicao')

-- ── O motor continua fora do alcance ─────────────────────────────────────────
union all
select 11, 'current_agent_id continua só em private',
  (select bool_and(ns.nspname = 'private')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where p.proname = 'current_agent_id')

union all
select 12, 'agent_positions continua com RLS ativo',
  (select bool_and(c.relrowsecurity)
     from pg_class c join pg_namespace ns on ns.oid = c.relnamespace
    where ns.nspname = 'public' and c.relname = 'agent_positions')

)
select
  n,
  verificacao,
  case coalesce(passou, false) when true then 'PASSOU' else 'FALHOU' end as resultado
from verificacoes
order by n;
