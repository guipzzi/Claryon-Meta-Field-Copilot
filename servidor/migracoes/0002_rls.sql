-- Segurança em nível de linha.
--
-- A regra que governa tudo: **toda resolução de destinatário acontece no
-- servidor**. Um cliente jamais consulta a posição de terceiros para descobrir
-- para quem enviar. Isso é requisito de privacidade antes de ser de arquitetura:
-- o aplicativo de um agente nunca recebe a lista de onde os outros estão.

-- ─────────────────────────────────────────────────────────────────────────────
-- Schema privado.
--
-- O PostgREST expõe apenas `public`. Tudo que mora aqui é **inalcançável por
-- RPC do cliente**, com ou sem chave — é o controle primário, e os GRANTs abaixo
-- são a segunda camada.
--
-- Isto não é zelo abstrato: `agentes_no_raio` é `SECURITY DEFINER` e devolve
-- quem está perto de uma coordenada. Em `public`, qualquer portador da chave
-- `anon` poderia chamá-la e varrer a cidade atrás de agentes — exatamente a
-- violação que o desenho inteiro existe para impedir.
-- ─────────────────────────────────────────────────────────────────────────────
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

-- ─────────────────────────────────────────────────────────────────────────────
-- `current_agent_id()` — a base de TODA política abaixo.
--
-- `SECURITY DEFINER` é obrigatório e não é conveniência: a função lê `agents`, e
-- com os privilégios do chamador uma política sobre `agents` que a invocasse
-- entraria em recursão. `search_path = ''` com nomes qualificados impede que um
-- schema plantado no caminho sequestre a resolução — função DEFINER com caminho
-- aberto é escalada de privilégio.
--
-- Diferente das outras, esta PRECISA ser executável por `authenticated`: as
-- políticas a invocam no contexto de quem consulta. Expor não custa nada — ela
-- devolve apenas o id do próprio chamador, que ele já conhece.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function private.current_agent_id()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select id from public.agents where auth_user_id = (select auth.uid())
$$;

grant usage on schema private to authenticated;
grant execute on function private.current_agent_id() to authenticated;

alter table transmissions   enable row level security;
alter table deliveries      enable row level security;
alter table agent_positions enable row level security;

-- ─────────────────────────────────────────────────────────────────────────────
-- Políticas.
--
-- Duas convenções aplicadas em todas: `(select ...)` em volta da função, para o
-- Postgres avaliá-la UMA vez em vez de por linha (a diferença chega a duas
-- ordens de grandeza numa tabela grande); e `to authenticated`, para não avaliar
-- nada para o papel `anon`.
-- ─────────────────────────────────────────────────────────────────────────────

-- Lê quem escreveu, ou quem foi destinatário.
create policy transmissions_read on transmissions
for select to authenticated
using (
  author_agent_id = (select private.current_agent_id())
  or exists (
    select 1 from deliveries d
     where d.transmission_id = transmissions.id
       and d.agent_id = (select private.current_agent_id())
  )
);

-- Ninguém insere direto: toda transmissão passa pela Edge Function (service
-- role), que resolve destinatários e grava a entrega na mesma transação.
create policy transmissions_no_direct_insert on transmissions
for insert to authenticated with check (false);

-- Append-only de verdade. Expiração é lógica (`expira_em`), não física — é
-- registro operacional auditável.
create policy transmissions_no_update on transmissions
for update to authenticated using (false);
create policy transmissions_no_delete on transmissions
for delete to authenticated using (false);

-- Cada agente vê apenas as próprias entregas.
create policy deliveries_read on deliveries
for select to authenticated
using (agent_id = (select private.current_agent_id()));

-- ─────────────────────────────────────────────────────────────────────────────
-- Reciprocidade: dentro do talk group, quem vê é visto.
--
-- Não existe modo de observar sem ser observado. Assimetria de visibilidade
-- entre pares é vigilância; simetria é coordenação. A política é simétrica por
-- construção — o mesmo predicado que deixa A ver B deixa B ver A.
-- ─────────────────────────────────────────────────────────────────────────────
create policy positions_read on agent_positions
for select to authenticated
using (
  exists (
    select 1
      from memberships meu
      join memberships dele on dele.talk_group_id = meu.talk_group_id
     where meu.agent_id = (select private.current_agent_id())
       and dele.agent_id = agent_positions.agent_id
  )
);

-- Cada um só escreve a própria posição.
create policy positions_insert on agent_positions
for insert to authenticated
with check (agent_id = (select private.current_agent_id()));

create policy positions_update on agent_positions
for update to authenticated
using (agent_id = (select private.current_agent_id()));
