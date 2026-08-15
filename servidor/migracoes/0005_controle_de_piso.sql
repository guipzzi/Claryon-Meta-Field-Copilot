-- ═══════════════════════════════════════════════════════════════════════════
-- Controle de piso: um emissor por vez, por talk group.
--
-- Implementado como função do Postgres, e não como Edge Function, por um motivo
-- que decide correção: a concessão precisa ser **atômica**. Dois agentes que
-- apertam o PTT no mesmo instante não podem ambos receber o canal — e é
-- exatamente isso que aconteceria com "ler, decidir, escrever" numa função
-- serverless. Aqui, `insert ... on conflict do update ... where` resolve num
-- único comando, com o lock de linha do próprio banco.
--
-- Diferente das consultas de `private`, estas funções **são** do cliente: pedir
-- o canal é operação legítima do aparelho. Por isso ficam em `public` e são
-- executáveis por `authenticated`.
--
-- A identidade **não é parâmetro**: sai de `current_agent_id()`. Se o agente
-- viesse por argumento, qualquer cliente poderia pedir o canal em nome de outro,
-- e o controle de piso viraria vetor de negação de serviço contra a guarnição.
-- ═══════════════════════════════════════════════════════════════════════════

create table if not exists floor_grants (
  talk_group_id uuid primary key references talk_groups(id),
  agent_id      uuid not null references agents(id),
  transmissao_id uuid not null,
  prioridade    smallint not null check (prioridade between 1 and 3),
  expira_em     timestamptz not null
);

create index if not exists floor_grants_agent_idx on floor_grants (agent_id);

alter table floor_grants enable row level security;

-- Leitura: só quem divide o talk group precisa saber quem está com a palavra.
create policy floor_grants_read on floor_grants
for select to authenticated
using (talk_group_id in (select private.meus_talk_groups()));

-- Escrita só pelas funções abaixo (que são SECURITY DEFINER).
create policy floor_grants_no_direct_write on floor_grants
for all to authenticated using (false) with check (false);

-- ── Pedir o canal ──────────────────────────────────────────────────────────
--
-- Devolve uma linha com o desfecho:
--   resultado: 'concedido' | 'ocupado' | 'tomado'
--   detentor_*: quem está (ou estava) com a palavra
--
-- Regra de emergência: P1 toma o canal de prioridade menor, mas **não** de outra
-- P1 — cortar quem já está numa ocorrência em curso é pior que esperar, e duas
-- emergências se revezando calariam as duas.
create or replace function pedir_canal(
  p_talk_group_id uuid,
  p_transmissao_id uuid,
  p_prioridade smallint,
  p_ttl_segundos integer default 30
)
returns table (
  resultado text,
  detentor_agent_id uuid,
  detentor_indicativo text,
  expira_em timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_eu uuid := private.current_agent_id();
  v_agora timestamptz := now();
  v_expira timestamptz := now() + make_interval(secs => p_ttl_segundos);
  v_atual public.floor_grants%rowtype;
begin
  if v_eu is null then
    raise exception 'sem agente autenticado';
  end if;

  -- Só membro do talk group pede o canal daquele grupo.
  if not exists (
    select 1 from public.memberships
     where agent_id = v_eu and talk_group_id = p_talk_group_id
  ) then
    raise exception 'nao pertence ao talk group';
  end if;

  -- Trava a linha do grupo. É o que serializa dois toques simultâneos.
  select * into v_atual
    from public.floor_grants
   where talk_group_id = p_talk_group_id
   for update;

  -- Livre, expirado, ou já é meu → concede.
  if v_atual.talk_group_id is null
     or v_atual.expira_em <= v_agora
     or v_atual.agent_id = v_eu then

    insert into public.floor_grants as f
      (talk_group_id, agent_id, transmissao_id, prioridade, expira_em)
    values (p_talk_group_id, v_eu, p_transmissao_id, p_prioridade, v_expira)
    on conflict (talk_group_id) do update
      set agent_id = excluded.agent_id,
          transmissao_id = excluded.transmissao_id,
          prioridade = excluded.prioridade,
          expira_em = excluded.expira_em;

    return query select 'concedido'::text, v_eu, null::text, v_expira;
    return;
  end if;

  -- Emergência toma de prioridade menor.
  if p_prioridade = 1 and v_atual.prioridade <> 1 then
    update public.floor_grants
       set agent_id = v_eu, transmissao_id = p_transmissao_id,
           prioridade = p_prioridade, expira_em = v_expira
     where talk_group_id = p_talk_group_id;

    return query
      select 'tomado'::text, v_atual.agent_id,
             (select indicativo from public.agents where id = v_atual.agent_id),
             v_expira;
    return;
  end if;

  -- Ocupado.
  return query
    select 'ocupado'::text, v_atual.agent_id,
           (select indicativo from public.agents where id = v_atual.agent_id),
           v_atual.expira_em;
end;
$$;

-- ── Renovar ────────────────────────────────────────────────────────────────
--
-- `false` significa "o canal já não é seu" — expirou ou foi tomado. É o sinal
-- para o emissor parar de falar, em vez de seguir falando para o vazio.
create or replace function renovar_canal(
  p_transmissao_id uuid,
  p_ttl_segundos integer default 30
)
returns boolean
language sql
volatile
security definer
set search_path = ''
as $$
  update public.floor_grants
     set expira_em = now() + make_interval(secs => p_ttl_segundos)
   where transmissao_id = p_transmissao_id
     and agent_id = private.current_agent_id()
     and expira_em > now()
  returning true;
$$;

-- ── Liberar ────────────────────────────────────────────────────────────────
--
-- Só o detentor libera: quem já perdeu o canal soltando o PTT não pode derrubar
-- quem o tomou.
create or replace function liberar_canal(p_transmissao_id uuid)
returns boolean
language sql
volatile
security definer
set search_path = ''
as $$
  delete from public.floor_grants
   where transmissao_id = p_transmissao_id
     and agent_id = private.current_agent_id()
  returning true;
$$;

revoke execute on function pedir_canal(uuid, uuid, smallint, integer) from public, anon;
revoke execute on function renovar_canal(uuid, integer) from public, anon;
revoke execute on function liberar_canal(uuid) from public, anon;

grant execute on function pedir_canal(uuid, uuid, smallint, integer) to authenticated, service_role;
grant execute on function renovar_canal(uuid, integer) to authenticated, service_role;
grant execute on function liberar_canal(uuid) to authenticated, service_role;
