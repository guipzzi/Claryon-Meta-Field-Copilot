-- Verificação da consulta de posição por voz (C2).
--
-- A propriedade sob teste não é "a função devolve o número certo" — é que **o
-- aparelho de um agente jamais recebe a posição de outro**, e que a distância,
-- que ele pode receber, não pode ser encadeada para reconstruir a posição.
--
-- Somente leitura. Roda com:
--   python3 servidor/executar_sql.py --somente-leitura servidor/verificacoes/0003_consulta_de_posicao.sql

with verificacoes as (

-- ── 1. A assinatura não aceita o solicitante ─────────────────────────────────
-- O centro de tudo. Com `solicitante_id` como parâmetro, um agente legítimo
-- perguntaria "onde está Alfa Dois em relação a Bravo Um" e, variando o segundo
-- argumento entre os pares do talk group, trilateraria a posição absoluta de
-- qualquer um — só com distâncias, que é o dado que a API pode devolver. A
-- checagem de talk group continuaria passando: o solicitante forjado é membro.

select 1 as n, 'consultar_posicao recebe só o indicativo' as verificacao,
  (select count(*) = 1
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao'
      and p.pronargs = 1
      and pg_get_function_arguments(p.oid) = 'indicativo text') as passou

union all
select 2, 'a função existe e é SECURITY DEFINER',
  (select bool_and(p.prosecdef)
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

union all
-- search_path vazio: sem isso, um schema no caminho de busca do chamador pode
-- sequestrar `ST_Distance` dentro de uma função SECURITY DEFINER.
select 3, 'search_path fixado em vazio',
  -- O Postgres normaliza `set search_path = ''` para a string `search_path=""`,
  -- com as aspas literais. Comparar com `search_path=` reprovava uma função
  -- correta — a primeira versão desta verificação fez exatamente isso.
  (select bool_and(exists (
       select 1 from unnest(coalesce(p.proconfig, array[]::text[])) c
        where c in ('search_path=', 'search_path=""')))
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

-- ── 2. Nada de coordenadas na saída ──────────────────────────────────────────
union all
select 4, 'a saída não tem latitude, longitude nem geometria',
  (select not (pg_get_function_result(p.oid) ~* '(lat|lon|geom|geography|point)')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

union all
select 5, 'a saída inclui a idade da posição',
  -- Sem idade, o app afirmaria como atual uma posição de dez minutos atrás.
  (select pg_get_function_result(p.oid) ~ 'idade_s'
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

-- ── 3. Quem pode chamar ──────────────────────────────────────────────────────
union all
select 6, 'anon NÃO pode executar',
  (select not has_function_privilege('anon', p.oid, 'execute')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

union all
select 7, 'authenticated pode executar',
  (select has_function_privilege('authenticated', p.oid, 'execute')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

union all
select 8, 'public (todos) NÃO pode executar',
  (select not has_function_privilege('public', p.oid, 'execute')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where ns.nspname = 'public' and p.proname = 'consultar_posicao')

-- ── 4. O motor continua fora do alcance do PostgREST ─────────────────────────
-- `posicao_relativa` ACEITA o solicitante como parâmetro — é o invólucro que
-- tira essa liberdade. Se ela vazasse para o schema público, o invólucro viraria
-- decoração: bastaria chamar a de baixo.
union all
select 9, 'posicao_relativa só existe em private',
  (select bool_and(ns.nspname = 'private')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where p.proname = 'posicao_relativa')

union all
select 10, 'agentes_no_raio só existe em private',
  (select bool_and(ns.nspname = 'private')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where p.proname = 'agentes_no_raio')

union all
select 11, 'current_agent_id só existe em private',
  (select bool_and(ns.nspname = 'private')
     from pg_proc p join pg_namespace ns on ns.oid = p.pronamespace
    where p.proname = 'current_agent_id')

union all
select 12, 'o schema private não é exposto pela API',
  -- PostgREST só serve os schemas de `pgrst.db_schemas`. `private` fora dessa
  -- lista é o que mantém tudo acima inalcançável por HTTP.
  (select not exists (
     select 1 from pg_namespace where nspname = 'private'
       and has_schema_privilege('anon', oid, 'usage')))

-- ── 5. As tabelas por baixo continuam protegidas ─────────────────────────────
union all
select 13, 'agent_positions tem RLS ativo',
  (select bool_and(c.relrowsecurity)
     from pg_class c join pg_namespace ns on ns.oid = c.relnamespace
    where ns.nspname = 'public' and c.relname = 'agent_positions')

union all
select 14, 'agents tem RLS ativo',
  (select bool_and(c.relrowsecurity)
     from pg_class c join pg_namespace ns on ns.oid = c.relnamespace
    where ns.nspname = 'public' and c.relname = 'agents')

union all
select 15, 'memberships tem RLS ativo',
  (select bool_and(c.relrowsecurity)
     from pg_class c join pg_namespace ns on ns.oid = c.relnamespace
    where ns.nspname = 'public' and c.relname = 'memberships')

)
select
  n,
  verificacao,
  case coalesce(passou, false) when true then 'PASSOU' else 'FALHOU' end as resultado
from verificacoes
order by n;
