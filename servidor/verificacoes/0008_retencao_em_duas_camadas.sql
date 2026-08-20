-- Verificação: a retenção entrou INTEIRA — turno, trilha, prazo executado e as
-- duas camadas separadas.
--
-- A regra do roadmap é que a trilha nunca entre sem os três controles, porque a
-- ordem inversa deixa o sistema estritamente pior que antes: rastro contínuo do
-- deslocamento de agentes sem recorte de turno, sem prazo e sem registro de leitura.
-- Esta verificação existe para que "entrou inteira" seja conferível e não afirmado.

begin;

create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

-- 1. Turno: um aberto por agente. Dois turnos simultâneos tornariam a trilha
--    ambígua e a pergunta "o que ele fez no turno da noite" ficaria sem resposta.
insert into r
select 'indice unico de turno aberto por agente', 'existe',
       coalesce(max(indexname), 'AUSENTE'), count(*) = 1
  from pg_indexes
 where schemaname = 'private' and tablename = 'turnos'
   and indexdef ~* 'unique' and indexdef ~* 'fechado_em is null';

-- 2. A porta de escrita recusa fora de turno. Sem isto não há recorte, e sem
--    recorte a defesa jurídica da coleta cai inteira.
insert into r
select 'publicar_posicao recusa fora de turno', 'sim',
       case when pg_get_functiondef(p.oid) ~* 'fora de turno' then 'sim' else 'NAO' end,
       pg_get_functiondef(p.oid) ~* 'fora de turno'
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'publicar_posicao';

-- 3. Trilha particionada: retenção tem de ser DROP e não DELETE, senão o prazo
--    depende de uma varredura lenta que pode ser interrompida no meio.
insert into r
select 'trilha particionada', 'p', c.relkind::text, c.relkind = 'p'
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'private' and c.relname = 'trilha_de_posicao';

-- 4. **Sem índice geográfico na trilha.** A ausência é o controle: um GiST
--    tornaria barata a pergunta "quem esteve perto deste ponto naquele horário",
--    que é vigilância retroativa de terceiros.
insert into r
select 'trilha SEM indice geografico', 'nenhum',
       coalesce(string_agg(indexname, ','), 'nenhum'), count(*) = 0
  from pg_indexes
 where schemaname = 'private' and tablename like 'trilha_%'
   and (indexdef ~* 'gist' or indexdef ~* 'geom');

-- 5. Trilha inacessível pelo aplicativo. Camada 1 é corregedoria, não produto.
insert into r
select 'authenticated NAO le a trilha', 'false',
       has_table_privilege('authenticated', 'private.trilha_de_posicao', 'select')::text,
       not has_table_privilege('authenticated', 'private.trilha_de_posicao', 'select');

-- 6. Camada 2 devolve grandeza, nunca coordenada — a mesma regra da consulta
--    pontual, e a que separa "o apoio está chegando" de "onde ele está".
insert into r
select 'rastro_do_par sem coordenada', 'sem',
       case when pg_get_function_result(p.oid) ~* '(lat|lon|geom|geography|point)'
            then 'TEM' else 'sem' end,
       not (pg_get_function_result(p.oid) ~* '(lat|lon|geom|geography|point)')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'rastro_do_par';

-- 7. Encerramento automático existe. Sem ele, "esqueci de encerrar" vira 24 h de
--    rastreamento e o recorte por turno deixa de ser sustentável.
insert into r
select 'encerramento de turno por inatividade', 'existe',
       count(*)::text, count(*) = 1
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'private' and p.proname = 'encerrar_turnos_inativos';

-- 8. **O prazo é executado.** Função de retenção sem agendamento é política em
--    documento, não controle.
insert into r
select 'job de retencao agendado e ativo', 'true',
       coalesce(bool_or(active)::text, 'AUSENTE'), coalesce(bool_or(active), false)
  from cron.job where jobname = 'retencao_claryon';

select case passou when true then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by verificacao;

rollback;
