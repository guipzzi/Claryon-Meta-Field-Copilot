-- Segurança em nível de linha.
--
-- A regra que governa tudo: **toda resolução de destinatário acontece no
-- servidor**. Um cliente jamais consulta a posição de terceiros para descobrir
-- para quem enviar. Isso é requisito de privacidade antes de ser de arquitetura:
-- o aplicativo de um agente nunca recebe a lista de onde os outros estão.

-- ─────────────────────────────────────────────────────────────────────────────
-- `current_agent_id()` — a base de TODA política abaixo.
--
-- `SECURITY DEFINER` é obrigatório e não é conveniência: a função lê `agents`, e
-- se ela rodasse com os privilégios do chamador, uma política sobre `agents` que
-- a invocasse entraria em recursão infinita. `search_path` travado impede que um
-- schema plantado no caminho sequestre a resolução de nomes — uma função
-- DEFINER com search_path aberto é escalada de privilégio.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function current_agent_id()
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select id from agents where auth_user_id = auth.uid()
$$;

alter table transmissions enable row level security;
alter table deliveries    enable row level security;
alter table agent_positions enable row level security;

-- Lê quem escreveu, ou quem foi destinatário.
create policy transmissions_read on transmissions for select
using (
  author_agent_id = current_agent_id()
  or exists (
    select 1 from deliveries d
     where d.transmission_id = transmissions.id
       and d.agent_id = current_agent_id()
  )
);

-- Ninguém insere direto: toda transmissão passa pela Edge Function (service role),
-- que é quem resolve destinatários e grava a entrega na mesma transação.
create policy transmissions_no_direct_insert on transmissions for insert with check (false);

-- Append-only de verdade.
create policy transmissions_no_update on transmissions for update using (false);
create policy transmissions_no_delete on transmissions for delete using (false);

-- Cada agente vê apenas as próprias entregas.
create policy deliveries_read on deliveries for select
using (agent_id = current_agent_id());

-- Reciprocidade: dentro do talk group, quem vê é visto. Não existe modo de
-- observar sem ser observado — assimetria de visibilidade entre pares é
-- vigilância; simetria é coordenação.
create policy positions_read on agent_positions for select
using (
  exists (
    select 1
      from memberships meu
      join memberships dele on dele.talk_group_id = meu.talk_group_id
     where meu.agent_id = current_agent_id()
       and dele.agent_id = agent_positions.agent_id
  )
);

create policy positions_write on agent_positions for insert
with check (agent_id = current_agent_id());
create policy positions_update on agent_positions for update
using (agent_id = current_agent_id());
