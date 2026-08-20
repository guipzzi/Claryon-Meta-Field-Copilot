-- Verificação: `publicar_posicao` é a ÚNICA porta de escrita de posição.
--
-- Existe porque a garantia estava sendo afirmada sem teste, e estava errada.
-- `positions_insert`/`positions_update` (`0002_rls.sql:112,116`) sobreviveram à
-- 0010 e permitiam POST direto em `/rest/v1/agent_positions`. Explorado em 18/08
-- com o JWT do agente de teste: gravou `updated_at = 2099-01-01` e `speed_mps = 999`.
--
-- A consequência não é escrita indevida, é desinformação: toda política de frescor
-- pergunta `updated_at > now() - interval '5 minutes'`, então uma linha datada no
-- futuro fica **permanentemente fresca** para a guarnição inteira.
--
-- Verificação que não existe é promessa.

begin;

create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

-- 1. Não pode haver política de INSERT nem de UPDATE na tabela: sem elas, a RLS
--    nega por padrão e só a função `security definer` escreve.
insert into r
select 'sem politica de INSERT em agent_positions', '0', count(*)::text, count(*) = 0
  from pg_policies where schemaname = 'public' and tablename = 'agent_positions' and cmd = 'INSERT';

insert into r
select 'sem politica de UPDATE em agent_positions', '0', count(*)::text, count(*) = 0
  from pg_policies where schemaname = 'public' and tablename = 'agent_positions' and cmd = 'UPDATE';

insert into r
select 'sem politica de DELETE em agent_positions', '0', count(*)::text, count(*) = 0
  from pg_policies where schemaname = 'public' and tablename = 'agent_positions' and cmd = 'DELETE';

-- 2. RLS tem de estar LIGADA, senão a ausência de política libera tudo em vez de
--    negar — o oposto exato do que os itens acima assumem.
insert into r
select 'RLS ligada em agent_positions', 'true', c.relrowsecurity::text, c.relrowsecurity
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public' and c.relname = 'agent_positions';

-- 3. A porta única continua de pé e continua sendo `security definer` — sem isso
--    ela também seria barrada pela própria RLS e ninguém publicaria nada.
insert into r
select 'publicar_posicao existe e e security definer', 'true', p.prosecdef::text, p.prosecdef
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'publicar_posicao';

insert into r
select 'authenticated executa publicar_posicao', 'true',
  has_function_privilege('authenticated', p.oid, 'execute')::text,
  has_function_privilege('authenticated', p.oid, 'execute')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'publicar_posicao';

insert into r
select 'anon NAO executa publicar_posicao', 'false',
  has_function_privilege('anon', p.oid, 'execute')::text,
  not has_function_privilege('anon', p.oid, 'execute')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'publicar_posicao';

select case passou when true then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by verificacao;

rollback;
