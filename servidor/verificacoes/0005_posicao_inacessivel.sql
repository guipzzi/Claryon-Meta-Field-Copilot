-- Verificação: a coordenada de um par é inalcançável pela API, e as funções de
-- grandeza continuam funcionando.
--
-- Existe porque a garantia central do produto estava sendo afirmada sem teste, e
-- estava errada: `positions_read` permitia `SELECT geom` de toda a guarnição.
-- Verificação que não existe é promessa.

begin;

create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

-- Dois agentes no mesmo talk group.
insert into public.units (id, nome) values ('aaaa0000-0000-0000-0000-000000000001','V') on conflict do nothing;
insert into public.talk_groups (id, unit_id, nome, tipo)
values ('bbbb0000-0000-0000-0000-000000000001','aaaa0000-0000-0000-0000-000000000001','V','guarnicao') on conflict do nothing;
insert into public.agents (id, auth_user_id, matricula, indicativo, unit_id) values
  ('cccc0000-0000-0000-0000-000000000001','dddd0000-0000-0000-0000-000000000001','v1','V Um','aaaa0000-0000-0000-0000-000000000001'),
  ('cccc0000-0000-0000-0000-000000000002','dddd0000-0000-0000-0000-000000000002','v2','V Dois','aaaa0000-0000-0000-0000-000000000001')
on conflict do nothing;
insert into public.memberships (agent_id, talk_group_id) values
  ('cccc0000-0000-0000-0000-000000000001','bbbb0000-0000-0000-0000-000000000001'),
  ('cccc0000-0000-0000-0000-000000000002','bbbb0000-0000-0000-0000-000000000001')
on conflict do nothing;
insert into public.agent_positions (agent_id, geom, speed_mps, accuracy_m, updated_at) values
  ('cccc0000-0000-0000-0000-000000000001', public.ST_SetSRID(public.ST_MakePoint(-49.2550,-16.6799),4326)::public.geography, 0, 8, now()),
  ('cccc0000-0000-0000-0000-000000000002', public.ST_SetSRID(public.ST_MakePoint(-49.2470,-16.6720),4326)::public.geography, 8, 12, now())
on conflict (agent_id) do update set geom = excluded.geom, updated_at = now();

-- Passa a agir como V Um e coleta tudo numa passada. A gravação em `r` acontece
-- depois do `reset role`: a tabela temporária pertence ao papel original, e
-- `set local role` tiraria o acesso a ela.
set local role authenticated;
set local request.jwt.claims = '{"sub":"dddd0000-0000-0000-0000-000000000001","role":"authenticated"}';

create temp table coleta on commit drop as
select
  (select count(*) from public.agent_positions
    where agent_id = 'cccc0000-0000-0000-0000-000000000001') as propria,
  (select count(*) from public.agent_positions
    where agent_id = 'cccc0000-0000-0000-0000-000000000002') as do_par,
  (select count(*) from public.agent_positions
    where agent_id <> 'cccc0000-0000-0000-0000-000000000001') as alheias,
  (select count(*) from public.posicoes_do_grupo('bbbb0000-0000-0000-0000-000000000001')) as grandezas,
  (select round(distancia_m) from public.posicoes_do_grupo('bbbb0000-0000-0000-0000-000000000001') limit 1) as dist,
  (select round(azimute) from public.posicoes_do_grupo('bbbb0000-0000-0000-0000-000000000001') limit 1) as rumo,
  (select idade_s is not null from public.posicoes_do_grupo('bbbb0000-0000-0000-0000-000000000001') limit 1) as tem_idade;

reset role;

-- 1. A PRÓPRIA posição continua legível — o app precisa dela para saber se está
--    publicando, e ela já é dele.
insert into r select 'le a propria posicao', '1 linha', propria::text, propria = 1 from coleta;

-- 2. **O centro desta verificação.** A do par tem de ser invisível.
insert into r select 'NAO le a posicao do par', '0 linhas', do_par::text, do_par = 0 from coleta;

-- 3. Varredura total: nenhuma linha alheia, por nenhum caminho.
insert into r select 'nenhuma linha alheia visivel', '0', alheias::text, alheias = 0 from coleta;

-- 4. E as grandezas continuam chegando — a correção não pode cegar o mapa.
insert into r select 'posicoes_do_grupo devolve o par', '1 linha', grandezas::text, grandezas = 1 from coleta;
insert into r select 'distancia ~1200 m', '1100-1300', dist::text, dist between 1100 and 1300 from coleta;
insert into r select 'rumo nordeste (~45 graus)', '30-60', rumo::text, rumo between 30 and 60 from coleta;
insert into r select 'devolve a idade do par', 'sim', tem_idade::text, tem_idade from coleta;

-- 5. A saída não pode ganhar coluna de coordenada por descuido futuro.
insert into r select 'saida sem coordenada', 'sem geom/lat/lon',
  case when pg_get_function_result(p.oid) ~* '(lat|lon|geom|geography|point)' then 'TEM' else 'sem' end,
  not (pg_get_function_result(p.oid) ~* '(lat|lon|geom|geography|point)')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'posicoes_do_grupo';

insert into r select 'anon NAO executa posicoes_do_grupo', 'false',
  has_function_privilege('anon', p.oid, 'execute')::text,
  not has_function_privilege('anon', p.oid, 'execute')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'posicoes_do_grupo';

select case passou when true then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by verificacao;

rollback;
