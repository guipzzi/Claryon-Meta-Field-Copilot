-- Verificação: a consulta veicular não pode inventar "sem restrição", e o
-- solicitante não pode ser forjado.
--
-- O que esta verificação persegue não é a existência da função — é a
-- **impossibilidade** da resposta errada. Um teste que só consultasse `DEM0A01` e
-- visse `roubo_furto` passaria com todos os defeitos desta classe de volta: o
-- conjunto vazio para placa desconhecida, o `restricao` nulo virando "limpo", a
-- semente se passando por base oficial. Por isso quase toda afirmação aqui é
-- contra-teste — as duas configurações rodam e o resultado tem de DIFERIR.
--
-- Os três cenários que o bloco pediu explicitamente estão nas seções 2, 3 e 4:
-- não-membro consultando, solicitante forjado por parâmetro, e placa inexistente
-- devolvendo qualquer coisa que não seja "não encontrada".
--
-- Tudo dentro de uma transação que termina em `rollback`. A revogação do agente, a
-- base esvaziada e as linhas de log envelhecidas voltam ao que eram.
--
--     python3 servidor/executar_sql.py servidor/verificacoes/0012_base_veicular.sql

begin;

create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

-- Captura o SQLSTATE de um comando que deve falhar. Sem isto, "a função recusa" só
-- poderia ser afirmada por introspecção — e introspecção não prova comportamento.
create or replace function pg_temp.sqlstate_de(sql text) returns text
language plpgsql as $$
begin
  execute sql;
  return 'SEM_ERRO';
exception when others then
  return sqlstate;
end $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. A porta responde para quem deve, e a resposta declara a origem
-- ─────────────────────────────────────────────────────────────────────────────

set local request.jwt.claims = '{"sub":"44444444-0000-0000-0000-000000000003"}';  -- Bravo Um

insert into r
select 'placa com restricao devolve a restricao', 'roubo_furto', restricao, restricao = 'roubo_furto'
  from public.consultar_placa('DEM0A01');

insert into r
select 'a resposta declara a procedencia da base', 'demonstracao', procedencia,
       procedencia = 'demonstracao'
  from public.consultar_placa('DEM0A01');

-- Normalização: o agente fala "D-E-M zero A zero um" e o STT pode devolver com
-- hífen ou espaço. Se as duas pontas não normalizarem igual, a consulta erra por
-- formatação e o veículo roubado vira "não encontrada".
insert into r
select 'placa com hifen e minuscula acha a mesma linha', 'encontrado', situacao,
       situacao = 'encontrado'
  from public.consultar_placa('dem-0a01');

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. NÃO-MEMBRO: quem não é agente ativo não consulta
-- ─────────────────────────────────────────────────────────────────────────────
--
-- `current_agent_id()` devolve nulo em dois casos — usuário sem linha em `agents` e
-- agente com `ativo = false` (`0014`). Os dois têm de bater na mesma porta fechada.

set local request.jwt.claims = '{"sub":"99999999-9999-9999-9999-999999999999"}';

insert into r
select 'usuario autenticado SEM agente e recusado', '42501',
       pg_temp.sqlstate_de($q$ select * from public.consultar_placa('DEM0A01') $q$),
       pg_temp.sqlstate_de($q$ select * from public.consultar_placa('DEM0A01') $q$) = '42501';

-- Agente revogado. Contra-teste: a MESMA identidade consulta com sucesso acima na
-- seção 1; o que muda aqui é só o `ativo`.
set local request.jwt.claims = '{"sub":"44444444-0000-0000-0000-000000000003"}';
update public.agents set ativo = false where id = '33333333-0000-0000-0000-000000000003';

insert into r
select 'agente REVOGADO e recusado (mesma identidade que passou acima)', '42501',
       pg_temp.sqlstate_de($q$ select * from public.consultar_placa('DEM0A01') $q$),
       pg_temp.sqlstate_de($q$ select * from public.consultar_placa('DEM0A01') $q$) = '42501';

update public.agents set ativo = true where id = '33333333-0000-0000-0000-000000000003';

-- E a recusa não pode ser silenciosa nem parcial: reativado, volta a responder. Sem
-- esta linha, uma função quebrada que recusa TODO MUNDO passaria nos dois testes
-- acima.
insert into r
select 'reativado, volta a responder (senao o teste acima nao mede nada)', 'encontrado',
       situacao, situacao = 'encontrado'
  from public.consultar_placa('DEM0A01');

-- `anon` não executa. É a camada de GRANT, independente do corpo da função.
insert into r
select 'anon NAO executa consultar_placa', 'false',
       has_function_privilege('anon', p.oid, 'execute')::text,
       not has_function_privilege('anon', p.oid, 'execute')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'consultar_placa';

insert into r
select 'authenticated EXECUTA consultar_placa', 'true',
       has_function_privilege('authenticated', p.oid, 'execute')::text,
       has_function_privilege('authenticated', p.oid, 'execute')
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'consultar_placa';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. SOLICITANTE FORJADO: não existe assinatura que aceite a identidade
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Regra dura do `CLAUDE.md` §2. Aqui ela é verificada por comportamento **e** por
-- introspecção: a chamada com identidade extra tem de não existir, e nenhuma função
-- de placa pode ter esse parâmetro na assinatura.

insert into r
select 'consultar_placa tem UM parametro, e e a placa', 'placa text',
       pg_get_function_arguments(p.oid), pg_get_function_arguments(p.oid) = 'placa text'
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'consultar_placa';

-- Contra-teste de comportamento: passar o solicitante junto não é ignorado — é
-- função inexistente (42883). Uma sobrecarga acrescentada por engano no futuro
-- derruba esta linha.
insert into r
select 'chamada COM solicitante nao existe (42883)', '42883',
       pg_temp.sqlstate_de(
         $q$ select * from public.consultar_placa('DEM0A01', '33333333-0000-0000-0000-000000000001') $q$),
       pg_temp.sqlstate_de(
         $q$ select * from public.consultar_placa('DEM0A01', '33333333-0000-0000-0000-000000000001') $q$) = '42883';

insert into r
select 'existe UMA consultar_placa, nao duas', '1', count(*)::text, count(*) = 1
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'consultar_placa';

-- Régua ampla: qualquer função pública de veículo/placa que receba identidade.
insert into r
select 'nenhuma funcao de placa recebe solicitante por parametro', 'nenhuma',
       coalesce(string_agg(p.proname, ','), 'nenhuma'), count(*) = 0
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and (p.proname ~* 'placa|veicul')
   and pg_get_function_arguments(p.oid) ~* '(agent_id|autor|solicitante|quem|consultante)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. PLACA INEXISTENTE: "não encontrada", e nunca conjunto vazio
-- ─────────────────────────────────────────────────────────────────────────────
--
-- **A seção mais importante do arquivo.** O "sem restrição" inventado quase nunca
-- nasce de alguém escrever a frase: nasce de o servidor devolver zero linhas e o
-- cliente concluir que não há restrição. `ConsultaDePosicao` já tem esse padrão
-- (`if (arr.length() == 0) return null`), então a função de placa é obrigada a
-- responder com uma AFIRMAÇÃO.

insert into r
select 'placa inexistente devolve EXATAMENTE uma linha', '1', count(*)::text, count(*) = 1
  from public.consultar_placa('ZZZ9Z99');

-- O contra-teste do vazio: a TABELA é silenciosa para esta placa; a FUNÇÃO não é.
-- Se as duas contagens fossem iguais, a linha acima não estaria medindo nada.
insert into r
select 'e a tabela crua devolveria zero (por isso a funcao afirma)', '0', count(*)::text, count(*) = 0
  from private.veiculos where placa = 'ZZZ9Z99';

insert into r
select 'placa inexistente diz nao_encontrada', 'nao_encontrada', situacao,
       situacao = 'nao_encontrada'
  from public.consultar_placa('ZZZ9Z99');

-- **A afirmação que protege a abordagem.** Placa desconhecida não pode voltar com
-- restricao preenchida — e muito menos com `sem_restricao`.
insert into r
select 'placa inexistente NAO devolve sem_restricao', 'restricao nula',
       coalesce(restricao, 'nula'), restricao is null
  from public.consultar_placa('ZZZ9Z99');

insert into r
select 'placa inexistente ainda declara a procedencia', 'demonstracao', procedencia,
       procedencia = 'demonstracao'
  from public.consultar_placa('ZZZ9Z99');

-- Placa inválida é estado próprio: ruído do STT não pode virar "não encontrada",
-- que afirmaria que a base foi consultada.
insert into r
select 'ruido do STT vira placa_invalida, nao nao_encontrada', 'placa_invalida', situacao,
       situacao = 'placa_invalida'
  from public.consultar_placa('nao entendi o que ele falou');

insert into r
select 'placa invalida tambem nao devolve restricao', 'restricao nula',
       coalesce(restricao, 'nula'), restricao is null
  from public.consultar_placa('XX1');

-- `sem_restricao` existe, e é um valor ESCRITO na base — não o silêncio do sistema.
-- Contra-teste do par: a placa limpa e a placa desconhecida têm de responder
-- DIFERENTE. Se um dia colapsarem, o "sem restrição" inventado voltou.
insert into r
select 'placa LIMPA e placa DESCONHECIDA nao respondem igual', 'diferentes',
       (select situacao from public.consultar_placa('DEM0A02')) || ' vs ' ||
       (select situacao from public.consultar_placa('ZZZ9Z99')),
       (select situacao from public.consultar_placa('DEM0A02'))
         is distinct from (select situacao from public.consultar_placa('ZZZ9Z99'));

insert into r
select 'a placa limpa declara sem_restricao explicitamente', 'sem_restricao', restricao,
       restricao = 'sem_restricao'
  from public.consultar_placa('DEM0A02');

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. Demonstração × oficial: o flag discrimina, não é decoração
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Uma função que devolvesse `demonstracao` sempre passaria em todos os testes de
-- procedência acima. Aqui ela é obrigada a mudar de resposta.

insert into r
select 'base semeada: procedencia_da_base = demonstracao', 'demonstracao',
       private.procedencia_da_base(), private.procedencia_da_base() = 'demonstracao';

update private.veiculos set procedencia = 'oficial';

insert into r
select 'base toda oficial: procedencia_da_base MUDA para oficial', 'oficial',
       private.procedencia_da_base(), private.procedencia_da_base() = 'oficial';

-- Base mista: uma única linha de demonstração derruba a autoridade do conjunto,
-- porque é o conjunto que responde pelas placas que não estão nele.
update private.veiculos set procedencia = 'demonstracao' where placa = 'DEM0A01';

insert into r
select 'UMA linha de demonstracao contamina o conjunto', 'demonstracao',
       private.procedencia_da_base(), private.procedencia_da_base() = 'demonstracao';

-- Mas a linha oficial que existe continua se declarando oficial quando é ela que
-- responde. Procedência da LINHA e procedência do CONJUNTO são coisas diferentes.
insert into r
select 'linha oficial em base mista se declara oficial', 'oficial', procedencia,
       procedencia = 'oficial'
  from public.consultar_placa('DEM0A02');

update private.veiculos set procedencia = 'demonstracao';

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. Base vazia é "indisponível", nunca "não encontrada"
-- ─────────────────────────────────────────────────────────────────────────────
--
-- A semente que não foi aplicada é falha de implantação e tem de soar como falha.
-- Sem este estado, a base vazia responderia "não encontrada" para tudo — o pior
-- resultado possível com a melhor aparência.

create temp table veiculos_guardados on commit drop as select * from private.veiculos;
delete from private.veiculos;

insert into r
select 'base VAZIA diz base_indisponivel', 'base_indisponivel', situacao,
       situacao = 'base_indisponivel'
  from public.consultar_placa('DEM0A01');

insert into r
select 'base VAZIA nao devolve restricao', 'restricao nula', coalesce(restricao, 'nula'),
       restricao is null
  from public.consultar_placa('DEM0A01');

insert into private.veiculos select * from veiculos_guardados;

-- Contra-teste: com a base de volta, a MESMA placa volta a ser encontrada.
insert into r
select 'base restaurada: a mesma placa volta a ser encontrada', 'encontrado', situacao,
       situacao = 'encontrado'
  from public.consultar_placa('DEM0A01');

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. A base não é extraível em massa
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O risco característico desta tabela não é a linha indevida — é o dump. Um GET no
-- PostgREST devolveria a lista de todos os veículos com alerta de roubo, sem passar
-- pelo log.

insert into r
select 'a base vive em private (fora do alcance do PostgREST)', 'private',
       coalesce(max(table_schema), 'AUSENTE'), count(*) = 1
  from information_schema.tables
 where table_name = 'veiculos' and table_schema = 'private';

insert into r
select 'authenticated NAO le private.veiculos direto', 'false',
       has_table_privilege('authenticated', 'private.veiculos', 'select')::text,
       not has_table_privilege('authenticated', 'private.veiculos', 'select');

insert into r
select 'authenticated NAO escreve private.veiculos', 'false',
       has_table_privilege('authenticated', 'private.veiculos', 'insert')::text,
       not has_table_privilege('authenticated', 'private.veiculos', 'insert');

insert into r
select 'anon NAO le private.veiculos', 'false',
       has_table_privilege('anon', 'private.veiculos', 'select')::text,
       not has_table_privilege('anon', 'private.veiculos', 'select');

-- A segunda tranca: RLS ligada e zero políticas. Hoje redundante com a ausência de
-- GRANT; deixa de ser no dia em que alguém escrever o GRANT achando que resolve.
insert into r
select 'RLS ligada em private.veiculos (tranca 2)', 'true', c.relrowsecurity::text,
       c.relrowsecurity
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'private' and c.relname = 'veiculos';

insert into r
select 'e ZERO politicas permissivas', '0', count(*)::text, count(*) = 0
  from pg_policies where schemaname = 'private' and tablename = 'veiculos';

-- Sem índice por restrição: "me dê todos os veículos com alerta de roubo" não pode
-- ser a consulta barata. Mesma decisão do índice geográfico recusado em `0019`.
insert into r
select 'sem indice por restricao (extracao em massa nao e barata)', 'nenhum',
       coalesce(string_agg(indexname, ','), 'nenhum'), count(*) = 0
  from pg_indexes
 where schemaname = 'private' and tablename = 'veiculos' and indexdef ~* 'restricao';

-- Nenhuma coluna de pessoa. O que não é coletado não vaza.
insert into r
select 'base sem coluna de proprietario', 'nenhuma',
       coalesce(string_agg(column_name, ','), 'nenhuma'), count(*) = 0
  from information_schema.columns
 where table_schema = 'private' and table_name = 'veiculos'
   and column_name ~* '(proprietario|dono|cpf|nome|condutor|cnh|endereco)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. As invariantes da tabela: a base é obrigada a se comprometer
-- ─────────────────────────────────────────────────────────────────────────────

insert into r
select 'veiculo SEM restricao declarada e recusado (23502)', '23502',
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte)
                               values ('AAA1A11','demonstracao','teste') $q$),
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte)
                               values ('AAA1A11','demonstracao','teste') $q$) = '23502';

insert into r
select 'restricao fora do dominio e recusada (23514)', '23514',
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AAA1A11','demonstracao','teste','provavelmente_limpo') $q$),
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AAA1A11','demonstracao','teste','provavelmente_limpo') $q$) = '23514';

insert into r
select 'procedencia fora do dominio e recusada (23514)', '23514',
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AAA1A11','quase_oficial','teste','sem_restricao') $q$),
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AAA1A11','quase_oficial','teste','sem_restricao') $q$) = '23514';

insert into r
select 'placa fora dos dois formatos e recusada (23514)', '23514',
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AB12','demonstracao','teste','sem_restricao') $q$),
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AB12','demonstracao','teste','sem_restricao') $q$) = '23514';

-- Contra-teste das quatro linhas acima: uma linha VÁLIDA entra. Se não entrasse, os
-- CHECKs estariam recusando tudo e os testes acima não provariam nada.
insert into r
select 'linha valida e aceita (senao os CHECKs acima nao medem nada)', 'SEM_ERRO',
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AAA1A11','demonstracao','teste','sem_restricao') $q$),
       pg_temp.sqlstate_de($q$ insert into private.veiculos (placa, procedencia, fonte, restricao)
                               values ('AAA1A11','demonstracao','teste','sem_restricao') $q$)
         in ('SEM_ERRO','23505');

delete from private.veiculos where placa = 'AAA1A11';

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. O log registra o ATO — e nem mesmo se achou
-- ─────────────────────────────────────────────────────────────────────────────

insert into r
select 'log sem coluna de RESPOSTA', 'nenhuma',
       coalesce(string_agg(column_name, ','), 'nenhuma'), count(*) = 0
  from information_schema.columns
 where table_schema = 'private' and table_name = 'consultas_de_placa'
   and column_name ~* '(restricao|situacao|encontr|marca|modelo|cor|ano|fonte|resultado|procedencia)';

insert into r
select 'log em private, invisivel ao aplicativo', 'false',
       has_table_privilege('authenticated', 'private.consultas_de_placa', 'select')::text,
       not has_table_privilege('authenticated', 'private.consultas_de_placa', 'select');

insert into r
select 'authenticated NAO escreve o log direto', 'false',
       has_table_privilege('authenticated', 'private.consultas_de_placa', 'insert')::text,
       not has_table_privilege('authenticated', 'private.consultas_de_placa', 'insert');

-- `volatile`, senão não escreve. Se alguém "otimizar" para `stable`, o log some em
-- silêncio — o mesmo cuidado da verificação `0007`.
insert into r
select 'consultar_placa e volatile (senao nao registra)', 'v', p.provolatile::text,
       p.provolatile = 'v'
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'consultar_placa';

-- Comportamento, não só forma: uma consulta nova gera exatamente uma linha nova.
create temp table antes_do_log on commit drop as
  select count(*) as n from private.consultas_de_placa;

select * from public.consultar_placa('DEM0A03');

insert into r
select 'a consulta gera UMA linha de log', '1',
       ((select count(*) from private.consultas_de_placa) - (select n from antes_do_log))::text,
       (select count(*) from private.consultas_de_placa) - (select n from antes_do_log) = 1;

insert into r
select 'o log grava a PERGUNTA (a placa)', 'DEM0A03', coalesce(max(placa_consultada), 'AUSENTE'),
       count(*) = 1
  from private.consultas_de_placa
 where placa_consultada = 'DEM0A03'
   and autor_agent_id = '33333333-0000-0000-0000-000000000003';

-- A varredura por tentativa e erro também é auditável. Apagá-la esconderia
-- justamente o comportamento que se quer flagrar.
create temp table antes_do_log2 on commit drop as
  select count(*) as n from private.consultas_de_placa;

select * from public.consultar_placa('lixo do reconhecedor');

insert into r
select 'placa INVALIDA tambem e registrada', '1',
       ((select count(*) from private.consultas_de_placa) - (select n from antes_do_log2))::text,
       (select count(*) from private.consultas_de_placa) - (select n from antes_do_log2) = 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. Retenção: prazo que ninguém executa é prazo que não existe
-- ─────────────────────────────────────────────────────────────────────────────

insert into r
select 'executar_retencao inclui o log de placa', 'true',
       (p.prosrc ~ 'consultas_de_placa')::text, p.prosrc ~ 'consultas_de_placa'
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'private' and p.proname = 'executar_retencao';

-- O job que já roda é o que executa o prazo novo. Um segundo agendamento seria
-- outro lugar para desalinhar.
insert into r
select 'o job existe, esta ativo e chama executar_retencao', 'true',
       coalesce(bool_or(active and command ~ 'executar_retencao')::text, 'AUSENTE'),
       coalesce(bool_or(active and command ~ 'executar_retencao'), false)
  from cron.job where jobname = 'retencao_claryon';

-- Contra-teste de comportamento: linha vencida sai, linha nova fica. Um `delete`
-- sem `where` passaria no primeiro e falharia no segundo; um `delete` que nunca
-- roda falharia no primeiro.
insert into private.consultas_de_placa (autor_agent_id, placa_consultada, em)
values ('33333333-0000-0000-0000-000000000003', 'VENCIDA1',
        now() - private.prazo_do_log_de_acesso() - interval '1 day'),
       ('33333333-0000-0000-0000-000000000003', 'NOVA0001', now());

select private.executar_retencao();

insert into r
select 'retencao APAGA o log de placa vencido', '0', count(*)::text, count(*) = 0
  from private.consultas_de_placa where placa_consultada = 'VENCIDA1';

insert into r
select 'e PRESERVA o log de placa recente', '1', count(*)::text, count(*) = 1
  from private.consultas_de_placa where placa_consultada = 'NOVA0001';

insert into r
select 'o relatorio da retencao reporta as placas', 'true',
       (private.executar_retencao() ~ 'placas_removidas')::text,
       private.executar_retencao() ~ 'placas_removidas';

-- ─────────────────────────────────────────────────────────────────────────────

select case passou when true then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by verificacao;

select count(*) filter (where passou) || '/' || count(*) as placar,
       count(*) filter (where not passou) as falhas
  from r;

rollback;
