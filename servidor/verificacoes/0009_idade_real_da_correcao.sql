-- Verificação: `medida_em` é a idade da CORREÇÃO, e não a hora do upload.
--
-- O ponto desta verificação não é ver `medida_em` existir — é provar que ela
-- **difere** de `updated_at` quando tem de diferir. Um teste que só publicasse
-- com idade zero passaria com o defeito de volta, porque com idade zero as duas
-- colunas coincidem. Por isso cada afirmação aqui é um contra-teste: as duas
-- configurações rodam e o resultado tem de ser diferente.
--
-- O cenário 4 é o defeito escrito como comportamento: um agente que ficou 6 min
-- sem sinal e reconectou. Com `updated_at`, `agentes_no_raio` o contava como
-- "está perto AGORA" e o alerta ia para onde ele não estava mais.
--
-- Tudo dentro de uma transação que termina em `rollback`: turno, posição e
-- trilha voltam ao que eram. Nada aqui toca dado de produção de forma durável.

begin;

create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. A invariante: nenhuma entrada produz instante no futuro
-- ─────────────────────────────────────────────────────────────────────────────
--
-- É a propriedade que a 0016 defendeu fechando a escrita direta — lá o exploit
-- gravou `2099` e ficou permanentemente fresco. Aqui ela tem de valer por
-- construção, inclusive para idade negativa, que é o que um cliente com bug
-- mandaria.

insert into r
select 'idade negativa NAO vira futuro', 'true',
       (private.instante_da_medicao(-3600000) <= now())::text,
       private.instante_da_medicao(-3600000) <= now();

insert into r
select 'idade negativa satura em agora', '0',
       round(extract(epoch from (now() - private.instante_da_medicao(-3600000))))::text,
       abs(extract(epoch from (now() - private.instante_da_medicao(-3600000)))) < 1;

insert into r
select 'idade nula vira agora', '0',
       round(extract(epoch from (now() - private.instante_da_medicao(null))))::text,
       abs(extract(epoch from (now() - private.instante_da_medicao(null)))) < 1;

insert into r
select 'idade absurda encosta no teto de 1h', '3600',
       round(extract(epoch from (now() - private.instante_da_medicao(999999999))))::text,
       abs(extract(epoch from (now() - private.instante_da_medicao(999999999))) - 3600) < 1;

insert into r
select 'idade de 4 min vira 240 s', '240',
       round(extract(epoch from (now() - private.instante_da_medicao(240000))))::text,
       abs(extract(epoch from (now() - private.instante_da_medicao(240000))) - 240) < 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Contra-teste na porta de escrita: duas idades, dois resultados
-- ─────────────────────────────────────────────────────────────────────────────

set local role authenticated;
set local request.jwt.claims = '{"sub":"44444444-0000-0000-0000-000000000003"}';
select public.iniciar_turno();

-- Estado limpo: sem isto o guarda de fora-de-ordem do cenário 3 interferiria já
-- aqui, e o teste mediria o guarda em vez de medir a idade.
set local role postgres;
delete from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';

set local role authenticated;
select public.publicar_posicao(-16.6800, -49.2500, 8.0, 1.2, null, 0);
set local role postgres;

insert into r
select 'idade_ms = 0 -> medida_em = agora', 'true',
       round(extract(epoch from (updated_at - medida_em)))::text,
       abs(extract(epoch from (updated_at - medida_em))) < 2
  from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';

-- Agora a MESMA publicação com idade de 4 min. Linha limpa de novo: o que se
-- mede aqui é a idade, não a ordem.
delete from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';
set local role authenticated;
select public.publicar_posicao(-16.6800, -49.2500, 8.0, 1.2, null, 240000);
set local role postgres;

insert into r
select 'idade_ms = 240000 -> medida_em 4 min atras', '240',
       round(extract(epoch from (updated_at - medida_em)))::text,
       abs(extract(epoch from (updated_at - medida_em)) - 240) < 3
  from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';

-- **O contra-teste propriamente dito.** `updated_at` é agora nas DUAS
-- publicações; se ele ainda fosse a fonte da idade, os dois casos acima teriam
-- dado o mesmo número. A afirmação é que diferem.
insert into r
select 'updated_at continua sendo agora (logo, mentiria)', 'true',
       round(extract(epoch from (now() - updated_at)))::text,
       abs(extract(epoch from (now() - updated_at))) < 2
  from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';

-- A trilha carrega a mesma idade: arquivo com hora de upload não reconstrói
-- deslocamento nenhum.
-- Sem `order by em desc limit 1`, e o motivo é uma armadilha do Postgres que me
-- pegou aqui: **`now()` é a hora de INÍCIO da transação**, igual para toda
-- chamada dentro dela. As duas linhas da trilha saem com o mesmo `em`, e ordenar
-- por ele escolhe uma ao acaso — o teste falhou apontando para a linha errada e
-- eu quase fui procurar defeito na escrita. Em produção cada publicação é uma
-- transação, então `em` discrimina; a verificação é que não pode depender disso.
insert into r
select 'a trilha grava medida_em junto', '1',
       count(*)::text, count(*) = 1
  from private.trilha_de_posicao
 where agent_id = '33333333-0000-0000-0000-000000000003'
   and abs(extract(epoch from (em - medida_em)) - 240) < 3;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Correção atrasada não sobrescreve correção nova
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Sem este guarda, um retry entregue fora de ordem empurraria `medida_em` para
-- trás e a posição boa que chegou no meio sumiria — o dado ficaria mais velho
-- depois de uma publicação bem-sucedida, que é o oposto do que publicar
-- significa.

delete from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';
set local role authenticated;
select public.publicar_posicao(-16.6800, -49.2500, 8.0, 1.2, null, 0);
select public.publicar_posicao(-16.7000, -49.3000, 8.0, 1.2, null, 600000);
set local role postgres;

insert into r
select 'correcao de 10 min NAO derruba a de agora', 'true',
       round(extract(epoch from (now() - medida_em)))::text,
       extract(epoch from (now() - medida_em)) < 5
  from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';

-- E a trilha guarda as duas: o arquivo não pode perder o trecho em que o agente
-- estava sem sinal, que é justamente o que uma corregedoria iria procurar.
insert into r
select 'a trilha ARQUIVA a atrasada que o corrente recusou', '1',
       count(*)::text, count(*) = 1
  from private.trilha_de_posicao
 where agent_id = '33333333-0000-0000-0000-000000000003'
   and abs(extract(epoch from (em - medida_em)) - 600) < 3;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. O defeito escrito como comportamento: quem reconectou não está "perto"
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Agente com correção de 6 min de idade, publicada AGORA. `agentes_no_raio`
-- filtra por 5 min. Com `updated_at` ele passava; com `medida_em` não passa.

delete from public.agent_positions where agent_id = '33333333-0000-0000-0000-000000000003';
set local role authenticated;
select public.publicar_posicao(-16.6800, -49.2500, 8.0, 1.2, null, 360000);
set local role postgres;

insert into r
select 'reconexao com correcao de 6 min NAO conta como perto', '0',
       count(*)::text, count(*) = 0
  from private.agentes_no_raio(-49.2500, -16.6800, 5000,
                               '00000000-0000-0000-0000-000000000000');

-- Contra-prova: com a MESMA linha, o filtro por `updated_at` (que é agora)
-- devolveria o agente. Se as duas contagens fossem iguais, o cenário acima não
-- estaria medindo nada.
insert into r
select 'e o filtro antigo, por updated_at, devolveria 1', '1',
       count(*)::text, count(*) = 1
  from public.agent_positions p
 where p.agent_id <> '00000000-0000-0000-0000-000000000000'
   and public.ST_DWithin(p.geom, public.ST_MakePoint(-49.2500, -16.6800)::public.geography, 5000)
   and p.updated_at > now() - interval '5 minutes';

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. Os leitores olham a coluna certa
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Consertar a escrita e esquecer um leitor daria duas telas com idades
-- diferentes para a mesma posição, que destrói a confiança nas duas.

insert into r
select 'nenhuma funcao viva calcula idade por updated_at', '0',
       coalesce(string_agg(p.proname, ', '), '0'),
       count(*) = 0
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname in ('public', 'private')
   and p.prosrc ~ 'now\(\)\s*-\s*[a-z_]+\.updated_at';

insert into r
select 'publicar_posicao aceita idade_ms', 'true',
       pg_get_function_identity_arguments(p.oid),
       pg_get_function_identity_arguments(p.oid) like '%idade_ms bigint%'
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'publicar_posicao';

-- Uma só: se o `drop` da assinatura de cinco argumentos tivesse falhado, a
-- chamada com cinco viraria `function is not unique` em produção.
insert into r
select 'existe UMA publicar_posicao, nao duas', '1',
       count(*)::text, count(*) = 1
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
