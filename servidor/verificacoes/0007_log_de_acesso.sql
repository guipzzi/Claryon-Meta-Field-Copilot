-- Verificação: o log de acesso registra o ATO e nunca a RESPOSTA, e o autor não
-- é forjável.
--
-- Existe porque um log com autor de parâmetro produz prova falsa — que é pior que
-- log nenhum, porque incrimina quem não fez com a autoridade de um registro.
-- Verificação que não existe é promessa.

begin;

create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

-- 1. A RESPOSTA não pode estar no log. Se alguém acrescentar distância, azimute
--    ou coordenada aqui, o log vira histórico de posição por outro nome — que é
--    exatamente o que a 0016 impede na tabela corrente.
insert into r
select 'log sem coluna de resposta', 'nenhuma', coalesce(string_agg(column_name, ','), 'nenhuma'),
       count(*) = 0
  from information_schema.columns
 where table_schema = 'private' and table_name = 'acessos_a_posicao'
   and column_name ~* '(distancia|azimute|rumo|geom|lat|lon|speed|velocidade|precisao)';

-- 2. Nenhuma das funções de registro pode receber a identidade do autor como
--    parâmetro. Com ela, qualquer agente escreve consulta em nome de outro.
insert into r
select 'nenhuma porta recebe autor por parametro', 'nenhuma',
       coalesce(string_agg(p.proname, ','), 'nenhuma'), count(*) = 0
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('consultar_posicao', 'abrir_mapa', 'fechar_mapa', 'quem_me_consultou')
   and pg_get_function_arguments(p.oid) ~* '(agent_id|autor|solicitante|quem)';

-- 3. `quem_me_consultou` não pode receber parâmetro NENHUM: com um alvo como
--    argumento ela viraria "quem consultou fulano", e um agente leria o padrão de
--    consultas de toda a corporação.
insert into r
select 'quem_me_consultou sem parametros', '', pg_get_function_arguments(p.oid),
       pg_get_function_arguments(p.oid) = ''
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'quem_me_consultou';

-- 4. A tabela vive em `private` e sem GRANT: as funções são a única porta.
insert into r
select 'tabela do log em private', 'private', coalesce(max(table_schema), 'AUSENTE'),
       count(*) = 1
  from information_schema.tables
 where table_name = 'acessos_a_posicao' and table_schema = 'private';

insert into r
select 'authenticated NAO le o log direto', 'false',
       has_table_privilege('authenticated', 'private.acessos_a_posicao', 'select')::text,
       not has_table_privilege('authenticated', 'private.acessos_a_posicao', 'select');

insert into r
select 'authenticated NAO escreve o log direto', 'false',
       has_table_privilege('authenticated', 'private.acessos_a_posicao', 'insert')::text,
       not has_table_privilege('authenticated', 'private.acessos_a_posicao', 'insert');

-- 5. `consultar_posicao` precisa ser volatile: função estável não escreve, e sem
--    escrever não há registro. Se alguém a "otimizar" de volta para stable, o log
--    para de existir em silêncio.
insert into r
select 'consultar_posicao e volatile (senao nao registra)', 'v', p.provolatile::text,
       p.provolatile = 'v'
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'consultar_posicao';

-- 6. As portas existem e são executáveis por quem deve.
insert into r
select 'anon NAO abre sessao de mapa', 'false',
       has_function_privilege('anon', p.oid, 'execute')::text,
       not has_function_privilege('anon', p.oid, 'execute')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'abrir_mapa';

select case passou when true then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by verificacao;

rollback;
