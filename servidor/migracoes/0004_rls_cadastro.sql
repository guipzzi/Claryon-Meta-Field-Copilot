-- ═══════════════════════════════════════════════════════════════════════════
-- RLS das tabelas de cadastro — a lacuna que a verificação de reciprocidade
-- encontrou.
--
-- O `0002` protegeu as tabelas de tráfego (`transmissions`, `deliveries`,
-- `agent_positions`) e deixou `units`, `agents`, `talk_groups` e `memberships`
-- de fora. Duas consequências:
--
--  1. A política de posições faz *join* em `memberships`; sem leitura ali, ela
--     nega tudo — inclusive o agente enxergar a si próprio.
--  2. Pior, no sentido oposto: sem RLS, qualquer autenticado leria o **cadastro
--     inteiro** — matrícula e indicativo de toda a corporação. Proteger o tráfego
--     e deixar o cadastro aberto é proteger a porta e esquecer a janela.
--
-- ── O problema de recursão, e por que as funções auxiliares existem ─────────
--
-- Uma política sobre `memberships` que consulte `memberships` recursa
-- infinitamente. A saída padrão é resolver a associação numa função
-- `SECURITY DEFINER`, que **ignora RLS** por definição e corta o laço.
-- É a mesma razão de `current_agent_id()` ser DEFINER, e o mesmo cuidado se
-- aplica: `search_path` travado, nomes qualificados.
-- ═══════════════════════════════════════════════════════════════════════════

-- Talk groups a que o chamador pertence.
create or replace function private.meus_talk_groups()
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  select talk_group_id
    from public.memberships
   where agent_id = private.current_agent_id()
$$;

-- Agentes que dividem algum talk group com o chamador, **incluindo ele mesmo**.
--
-- O `union` com o próprio id não é detalhe: um agente recém-cadastrado, ainda
-- sem guarnição, precisa enxergar a própria posição e o próprio registro. Sem
-- isso o app quebraria exatamente no primeiro uso.
create or replace function private.pares_do_talk_group()
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  select m.agent_id
    from public.memberships m
   where m.talk_group_id in (
     select talk_group_id
       from public.memberships
      where agent_id = private.current_agent_id()
   )
  union
  select private.current_agent_id()
$$;

grant execute on function private.meus_talk_groups()     to authenticated;
grant execute on function private.pares_do_talk_group()  to authenticated;

alter table units       enable row level security;
alter table agents      enable row level security;
alter table talk_groups enable row level security;
alter table memberships enable row level security;

-- ── Políticas de leitura ───────────────────────────────────────────────────
-- Todas com `(select ...)` para a função ser avaliada uma vez, não por linha.

-- Um agente conhece quem divide guarnição com ele — e mais ninguém. É o que
-- permite exibir "Alfa Dois" no mapa sem expor a corporação inteira.
create policy agents_read on agents
for select to authenticated
using (id in (select private.pares_do_talk_group()));

-- Só as associações dos meus talk groups.
create policy memberships_read on memberships
for select to authenticated
using (talk_group_id in (select private.meus_talk_groups()));

create policy talk_groups_read on talk_groups
for select to authenticated
using (id in (select private.meus_talk_groups()));

-- A unidade do próprio agente.
create policy units_read on units
for select to authenticated
using (
  id = (select unit_id from agents where id = (select private.current_agent_id()))
);

-- ── Posições: simplifica usando o mesmo predicado ──────────────────────────
--
-- A política original fazia *join* em `memberships` — que agora tem RLS, o que
-- acrescentaria uma avaliação de política aninhada a cada linha. A função
-- DEFINER resolve a associação uma vez e a política vira uma checagem de
-- pertinência. Mais rápida, e mais fácil de conferir que está correta.
--
-- **A reciprocidade continua garantida por construção:** o conjunto
-- `pares_do_talk_group` é simétrico — se A está no conjunto de B, B está no de A.
drop policy if exists positions_read on agent_positions;

create policy positions_read on agent_positions
for select to authenticated
using (agent_id in (select private.pares_do_talk_group()));
