-- ═══════════════════════════════════════════════════════════════════════════
-- Verificação do controle de piso.
--
-- Roda em transação com ROLLBACK — nenhum dado sobrevive.
--
-- Como no `0001`, cada checagem assume o papel `authenticated` e forja o claim
-- `sub`: verificar sem trocar de papel rodaria como `postgres`, que ignora RLS e
-- executa as funções DEFINER sem passar pelas checagens de pertinência.
-- ═══════════════════════════════════════════════════════════════════════════

begin;

create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;
grant all on r to authenticated;

insert into units (id, nome) values ('11111111-1111-1111-1111-111111111111','Batalhão de Teste');

insert into agents (id, auth_user_id, matricula, indicativo, unit_id) values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','M1','Alfa Um','11111111-1111-1111-1111-111111111111'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','M2','Bravo Dois','11111111-1111-1111-1111-111111111111'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc','cccccccc-cccc-cccc-cccc-cccccccccccc','M3','Charlie Três','11111111-1111-1111-1111-111111111111');

insert into talk_groups (id, unit_id, nome, tipo) values
  ('44444444-4444-4444-4444-444444444444','11111111-1111-1111-1111-111111111111','GTA-3','guarnicao'),
  ('55555555-5555-5555-5555-555555555555','11111111-1111-1111-1111-111111111111','GTA-7','guarnicao');

insert into memberships (agent_id, talk_group_id) values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','44444444-4444-4444-4444-444444444444'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','44444444-4444-4444-4444-444444444444'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc','55555555-5555-5555-5555-555555555555');

set local role authenticated;

-- ── 1. Canal livre concede ─────────────────────────────────────────────────
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}';
insert into r select 'canal livre concede', 'concedido', resultado, resultado = 'concedido'
  from pedir_canal('44444444-4444-4444-4444-444444444444',
                   '99999999-9999-9999-9999-999999999901', 2::smallint);

-- ── 2. Segundo agente recebe ocupado ───────────────────────────────────────
set local request.jwt.claims = '{"sub":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}';
insert into r select 'segundo agente recebe ocupado', 'ocupado', resultado, resultado = 'ocupado'
  from pedir_canal('44444444-4444-4444-4444-444444444444',
                   '99999999-9999-9999-9999-999999999902', 2::smallint);

insert into r select 'ocupado informa quem detem', 'Alfa Um', detentor_indicativo,
                     detentor_indicativo = 'Alfa Um'
  from pedir_canal('44444444-4444-4444-4444-444444444444',
                   '99999999-9999-9999-9999-999999999902', 2::smallint);

-- ── 3. Emergência toma de prioridade menor ─────────────────────────────────
insert into r select 'emergencia toma o canal', 'tomado', resultado, resultado = 'tomado'
  from pedir_canal('44444444-4444-4444-4444-444444444444',
                   '99999999-9999-9999-9999-999999999903', 1::smallint);

-- ── 4. Emergência NÃO toma de outra emergência ─────────────────────────────
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}';
insert into r select 'emergencia NAO toma de emergencia', 'ocupado', resultado, resultado = 'ocupado'
  from pedir_canal('44444444-4444-4444-4444-444444444444',
                   '99999999-9999-9999-9999-999999999904', 1::smallint);

-- ── 5. O interrompido não consegue renovar ─────────────────────────────────
-- É o sinal para parar de falar: seguir transmitindo seria falar para o vazio.
insert into r select 'interrompido NAO renova', 'false',
                     coalesce(renovar_canal('99999999-9999-9999-9999-999999999901')::text,'false'),
                     coalesce(renovar_canal('99999999-9999-9999-9999-999999999901'), false) = false;

-- ── 6. O detentor renova ───────────────────────────────────────────────────
set local request.jwt.claims = '{"sub":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}';
insert into r select 'detentor renova', 'true',
                     coalesce(renovar_canal('99999999-9999-9999-9999-999999999903')::text,'false'),
                     coalesce(renovar_canal('99999999-9999-9999-9999-999999999903'), false);

-- ── 7. Quem já perdeu não derruba quem tomou ───────────────────────────────
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}';
select liberar_canal('99999999-9999-9999-9999-999999999901');

set local role postgres;
insert into r select 'liberar alheio nao derruba o detentor', 'bbbbbbbb...', left(agent_id::text,8)||'...',
                     agent_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
  from floor_grants where talk_group_id = '44444444-4444-4444-4444-444444444444';
set local role authenticated;

-- ── 8. O detentor libera ───────────────────────────────────────────────────
set local request.jwt.claims = '{"sub":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}';
select liberar_canal('99999999-9999-9999-9999-999999999903');

set local role postgres;
insert into r select 'detentor libera o canal', '0 linhas', count(*)::text || ' linhas', count(*) = 0
  from floor_grants where talk_group_id = '44444444-4444-4444-4444-444444444444';
set local role authenticated;

-- ── 9. Não-membro não pede o canal ─────────────────────────────────────────
-- Sem isto, o controle de piso viraria negação de serviço contra a guarnição:
-- qualquer autenticado calaria um grupo do qual não participa.
set local request.jwt.claims = '{"sub":"cccccccc-cccc-cccc-cccc-cccccccccccc"}';
do $$
declare v_ok boolean := false;
begin
  begin
    perform pedir_canal('44444444-4444-4444-4444-444444444444',
                        '99999999-9999-9999-9999-999999999905', 2::smallint);
  exception when others then
    v_ok := true; -- recusou, que é o correto
  end;
  insert into r values ('nao-membro NAO pede o canal', 'recusa', case when v_ok then 'recusou' else 'PERMITIU' end, v_ok);
end $$;

-- ── 10. Escrita direta na tabela é barrada ─────────────────────────────────
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}';
do $$
declare v_ok boolean := false;
begin
  begin
    insert into floor_grants (talk_group_id, agent_id, transmissao_id, prioridade, expira_em)
    values ('44444444-4444-4444-4444-444444444444','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            '99999999-9999-9999-9999-999999999906', 1::smallint, now() + interval '1 hour');
  exception when others then
    v_ok := true;
  end;
  insert into r values ('escrita direta em floor_grants barrada', 'recusa',
                        case when v_ok then 'recusou' else 'PERMITIU' end, v_ok);
end $$;

-- ── 11. Talk groups são independentes ──────────────────────────────────────
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}';
select pedir_canal('44444444-4444-4444-4444-444444444444',
                   '99999999-9999-9999-9999-999999999907', 2::smallint);
set local request.jwt.claims = '{"sub":"cccccccc-cccc-cccc-cccc-cccccccccccc"}';
insert into r select 'uma guarnicao nao cala a outra', 'concedido', resultado, resultado = 'concedido'
  from pedir_canal('55555555-5555-5555-5555-555555555555',
                   '99999999-9999-9999-9999-999999999908', 2::smallint);

-- ── 12. TTL expirado devolve o canal ───────────────────────────────────────
set local role postgres;
update floor_grants set expira_em = now() - interval '1 second'
 where talk_group_id = '44444444-4444-4444-4444-444444444444';
set local role authenticated;

set local request.jwt.claims = '{"sub":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}';
insert into r select 'TTL expirado devolve o canal', 'concedido', resultado, resultado = 'concedido'
  from pedir_canal('44444444-4444-4444-4444-444444444444',
                   '99999999-9999-9999-9999-999999999909', 3::smallint);

reset role;

select case when passou then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by passou, verificacao;

rollback;
