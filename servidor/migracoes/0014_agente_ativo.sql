-- ─────────────────────────────────────────────────────────────────────────────
-- 0014 — `agents.ativo`: revogação institucional numa transação
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Um agente afastado, exonerado ou com o aparelho perdido precisa sair do sistema
-- **inteiro** — canal, piso, posição e consulta — sem depender de alguém lembrar de
-- revogar cada um. Hoje não há como: apagar a linha de `agents` quebraria o
-- histórico por chave estrangeira, e revogar a sessão do GoTrue não impede o JWT
-- que já está no bolso de continuar valendo até expirar.
--
-- POR QUE UMA COLUNA RESOLVE TUDO DE UMA VEZ
--
-- `private.current_agent_id()` é o gargalo por onde passa **toda** política de
-- linha, **todo** RPC e o controle de piso (`0002_rls.sql`). Conferir `ativo` lá
-- dentro faz um `UPDATE` de uma linha derrubar o agente de tudo na mesma transação,
-- em vez de espalhar a checagem por dezenas de lugares que envelhecem separados.
--
-- O agente inativo passa a ter `current_agent_id()` = NULL. Consequência desenhada,
-- não efeito colateral: política que compara com NULL não casa, e o padrão do
-- Postgres é negar. Ele perde leitura, escrita, piso e canal privado juntos.
--
-- O QUE ISSO **NÃO** FAZ
--
-- Não invalida o JWT — ele continua criptograficamente válido até expirar (60 min,
-- medido). O que morre é o que o JWT **alcança**: sem `current_agent_id()`, o token
-- abre a porta e não há sala do outro lado. Para o canal Realtime a expulsão é
-- imediata na próxima renovação de token ou reconexão, porque a política de
-- `realtime.messages` também passa por aqui.

begin;

alter table public.agents
  add column if not exists ativo boolean not null default true;

comment on column public.agents.ativo is
  'Revogação institucional. `false` derruba canal, piso, posição e consulta na '
  'mesma transação, porque private.current_agent_id() passa a devolver NULL.';

-- Índice parcial: as consultas de interesse são sempre "os ativos", e a tabela
-- tende a acumular inativos que nunca mais são lidos.
create index if not exists agents_ativos_idx on public.agents (id) where ativo;

-- ─────────────────────────────────────────────────────────────────────────────
-- O gargalo, agora conferindo `ativo`
-- ─────────────────────────────────────────────────────────────────────────────
--
-- `security definer` e `search_path = ''` mantidos pelos mesmos motivos de
-- `0002_rls.sql`: sem eles, uma política sobre `agents` que invoque esta função
-- entra em recursão, e caminho aberto em função DEFINER é escalada de privilégio.
create or replace function private.current_agent_id()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select id
    from public.agents
   where auth_user_id = (select auth.uid())
     and ativo
$$;

commit;
