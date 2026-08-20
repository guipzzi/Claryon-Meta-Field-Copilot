-- ─────────────────────────────────────────────────────────────────────────────
-- 0018 — Sessão de mapa que não fica aberta para sempre
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O DEFEITO QUE EU MESMO NOMEEI E DEIXEI PASSAR
--
-- A `0017` registra a consulta ao mapa como **sessão**: abre, fecha, e o intervalo
-- diz por quanto tempo a guarnição esteve visível. O commit dela dizia, com estas
-- palavras, que "sessão que nunca fecha vira registro de vigilância sem fim".
--
-- E era exatamente o que acontecia. `fechar_mapa` é chamado no `ON_STOP` da tela,
-- o que cobre a saída limpa **e só ela**. O Android mata processo por memória o
-- tempo todo; nesses casos ninguém fecha nada. Demonstrado: três aberturas
-- seguidas sem fechamento deixaram três linhas abertas.
--
-- A consequência é a pior possível para um registro de auditoria: ele **exagera**
-- contra o agente. Um turno em que o mapa foi consultado por dois minutos ficaria
-- gravado como consulta contínua até o fim dos tempos, e é essa a linha que a
-- corregedoria leria.
--
-- DUAS GARANTIAS, PORQUE ELAS COBREM FALHAS DIFERENTES
--
-- 1. **Ao abrir, fecha o que ficou aberto.** Cobre o caso comum — o app morreu e o
--    agente voltou. Barato, imediato, sem depender de agendador.
--
-- 2. **Teto na leitura.** Cobre o caso que a primeira não alcança: o agente que
--    nunca mais abre o mapa. Uma sessão sem fechamento **nunca** pode ser lida como
--    mais longa que o teto, mesmo que nenhum job jamais rode. Garantia que depende
--    de cron não é garantia — é expectativa.
--
-- O teto é de 1 hora. É generoso para consulta de mapa em rua e curto o bastante
-- para que "esqueci aberto" não vire prova de vigilância prolongada. O número está
-- numa função só, para mudar em uma linha.

begin;

create or replace function private.teto_da_sessao_de_mapa()
returns interval
language sql
immutable
as $$ select interval '1 hour' $$;

comment on function private.teto_da_sessao_de_mapa() is
  'Duração máxima atribuível a uma sessão de mapa sem fechamento explícito. '
  'Existe porque processo morto não chama fechar_mapa, e registro que exagera '
  'contra o agente é pior que registro nenhum.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Garantia 1 — abrir fecha o que ficou para trás
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.abrir_mapa(talk_group uuid)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_eu uuid := private.current_agent_id();
  v_id bigint;
begin
  if v_eu is null then
    raise exception 'sem agente' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.memberships m
     where m.talk_group_id = talk_group and m.agent_id = v_eu
  ) then
    raise exception 'nao e membro do grupo' using errcode = '42501';
  end if;

  -- Fecha o que este agente deixou aberto, **com teto**. `now()` puro daria à
  -- sessão órfã toda a duração até agora — que é justamente o exagero que este
  -- arquivo existe para impedir.
  update private.acessos_a_posicao
     set fechado_em = least(now(), em + private.teto_da_sessao_de_mapa())
   where autor_agent_id = v_eu
     and tipo = 'mapa'
     and fechado_em is null;

  insert into private.acessos_a_posicao (autor_agent_id, tipo, talk_group_id)
  values (v_eu, 'mapa', talk_group)
  returning id into v_id;
  return v_id;
end;
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Garantia 2 — teto na leitura, independente de qualquer job
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function private.duracao_da_sessao(
  aberta_em timestamptz,
  fechada_em timestamptz
)
returns interval
language sql
immutable
as $$
  select least(
    coalesce(fechada_em, aberta_em + private.teto_da_sessao_de_mapa()),
    aberta_em + private.teto_da_sessao_de_mapa()
  ) - aberta_em
$$;

comment on function private.duracao_da_sessao(timestamptz, timestamptz) is
  'Duração de uma sessão de mapa, sempre limitada pelo teto — inclusive quando '
  'fechado_em existe mas é absurdo. Todo leitor deve usar esta função em vez de '
  'subtrair as colunas, senão o teto vira convenção e não garantia.';

commit;
