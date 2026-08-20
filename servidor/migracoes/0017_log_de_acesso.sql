-- ─────────────────────────────────────────────────────────────────────────────
-- 0017 — Log de acesso nas duas portas: quem consultou a posição de quem
-- ─────────────────────────────────────────────────────────────────────────────
--
-- POR QUE UM LOG, E POR QUE ELE É DE PRODUTO E NÃO SÓ DE CONFORMIDADE
--
-- Saber onde um colega está é poder. Um sistema que concede esse poder sem deixar
-- rastro não tem como distinguir a consulta legítima — apoio a caminho — da
-- vigilância de um agente sobre outro. `public.quem_me_consultou()` no fim deste
-- arquivo devolve isso ao titular: ele vê quem perguntou por ele. Conformidade
-- vira característica, e o controle deixa de ser promessa em documento.
--
-- AS DUAS REGRAS QUE O ROADMAP FIXA, E O QUE ACONTECE SE FOREM QUEBRADAS
--
-- **Nunca gravar a resposta.** Nada de distância, azimute, velocidade ou
-- coordenada. Um log que guarda a resposta **é** um histórico de posição por outro
-- nome — e este produto acabou de gastar uma migração inteira (`0016`) garantindo
-- que posição só existe como linha corrente. Registrar o ATO e não o RESULTADO é o
-- que separa auditoria de rastreamento.
--
-- **O autor sai de `private.current_agent_id()`, jamais do protocolo.** Log com
-- autor forjável produz prova falsa, e prova falsa é pior que log nenhum: ela
-- incrimina quem não fez e absolve quem fez, com a autoridade de um registro.
--
-- POR QUE DUAS PORTAS COM FORMATOS DIFERENTES
--
-- `consultar_posicao` é pergunta pontual: uma linha por consulta é proporcional.
--
-- O mapa é outra coisa. O laço redesenha a cada 5 s (`MapaViewModel`), o que daria
-- ~720 linhas por hora **por agente** — a mesma amplificação que a `0016` acabou de
-- eliminar na escrita, ressuscitada no log. Pior: afogaria a consulta pontual, que
-- é justamente a que interessa auditar. Por isso o mapa registra **sessão**:
-- abriu, fechou, e o intervalo diz por quanto tempo a guarnição esteve visível.

begin;

-- ─────────────────────────────────────────────────────────────────────────────
-- Onde o rastro vive
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Em `private`: sem GRANT para `authenticated`, ninguém lê nem escreve direto.
-- As únicas portas são as funções `security definer` abaixo — a mesma lição da
-- `0016`, onde uma política de escrita esquecida deixou a tabela aberta por fora
-- da função que a validava.
create table if not exists private.acessos_a_posicao (
  id              bigserial primary key,
  -- Quem perguntou. **Nunca** vem de parâmetro.
  autor_agent_id  uuid not null references public.agents(id),
  -- Sobre quem. Nulo quando o indicativo não resolveu — a tentativa também é
  -- auditável, e apagá-la esconderia justamente a varredura por tentativa e erro.
  alvo_agent_id   uuid references public.agents(id),
  -- O que foi pedido, como foi pedido. É parte da PERGUNTA, não da resposta.
  alvo_indicativo text,
  tipo            text not null check (tipo in ('consulta', 'mapa')),
  em              timestamptz not null default now(),
  -- Só para sessão de mapa: quando fechou. Nulo = ainda aberta.
  fechado_em      timestamptz,
  talk_group_id   uuid references public.talk_groups(id)
);

comment on table private.acessos_a_posicao is
  'Registro do ATO de consultar posição — nunca da resposta. Guardar distância ou '
  'azimute aqui transformaria o log num histórico de posição, que é exatamente o '
  'que a 0016 impede. Autor sempre de private.current_agent_id().';

-- O titular pergunta "quem me consultou": o índice serve essa pergunta.
create index if not exists acessos_por_alvo_idx
  on private.acessos_a_posicao (alvo_agent_id, em desc);

-- O job de retenção (item seguinte do roadmap) varre por idade.
create index if not exists acessos_por_data_idx
  on private.acessos_a_posicao (em);

-- ─────────────────────────────────────────────────────────────────────────────
-- Porta 1 — consulta pontual: uma linha por pergunta
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Vira `plpgsql volatile`: era `sql stable`, e função estável não escreve. A
-- mudança de volatilidade é consequência de passar a registrar, não preferência.
create or replace function public.consultar_posicao(indicativo text)
returns table (
  indicativo_alvo text,
  distancia_m double precision,
  azimute double precision,
  speed_mps real,
  idade_s integer,
  idade_solicitante_s integer
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_eu   uuid := private.current_agent_id();
  v_alvo uuid;
begin
  -- Resolve o alvo **dentro dos grupos que o solicitante compartilha**. Fora
  -- disso fica nulo: o log não pode virar um jeito de descobrir quem existe.
  select a.id into v_alvo
    from public.agents a
   where a.indicativo = consultar_posicao.indicativo
     and exists (
       select 1
         from public.memberships m1
         join public.memberships m2 on m2.talk_group_id = m1.talk_group_id
        where m1.agent_id = v_eu and m2.agent_id = a.id
     )
   limit 1;

  -- Registra ANTES de responder. Depois da resposta, um erro no meio deixaria
  -- consulta atendida sem rastro — e o rastro que falta é sempre o da consulta
  -- que alguém quis esconder.
  insert into private.acessos_a_posicao (autor_agent_id, alvo_agent_id, alvo_indicativo, tipo)
  values (v_eu, v_alvo, consultar_posicao.indicativo, 'consulta');

  return query
    select
      r.indicativo_alvo,
      r.distancia_m,
      r.azimute,
      r.speed_mps,
      r.idade_s,
      coalesce(
        (select extract(epoch from (now() - p.updated_at))::integer
           from public.agent_positions p
          where p.agent_id = v_eu),
        2147483647
      )
    from private.posicao_relativa(v_eu, consultar_posicao.indicativo) r;
end;
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Porta 2 — o mapa: sessão, não linha por quadro
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
  -- Membro do grupo, ou não há sessão a abrir. A mesma porta de
  -- `cadastro_do_grupo` (`0013`): grupo é dado da pergunta, identidade é do JWT.
  if not exists (
    select 1 from public.memberships m
     where m.talk_group_id = talk_group and m.agent_id = v_eu
  ) then
    raise exception 'nao e membro do grupo' using errcode = '42501';
  end if;

  insert into private.acessos_a_posicao (autor_agent_id, tipo, talk_group_id)
  values (v_eu, 'mapa', talk_group)
  returning id into v_id;
  return v_id;
end;
$$;

create or replace function public.fechar_mapa(sessao bigint)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  -- Só o autor fecha a própria sessão. Sem esta condição, um agente encerraria a
  -- sessão de outro e encurtaria o registro de vigilância alheia — adulteração de
  -- prova com aparência de operação normal.
  update private.acessos_a_posicao
     set fechado_em = now()
   where id = sessao
     and autor_agent_id = private.current_agent_id()
     and tipo = 'mapa'
     and fechado_em is null;
end;
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- A contrapartida: o titular vê quem perguntou por ele
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O solicitante sai do JWT, e é por isso que esta função **não recebe parâmetro
-- nenhum**: com um `agent_id` como argumento, ela viraria "quem consultou fulano",
-- e um agente leria o padrão de consultas de toda a corporação.
create or replace function public.quem_me_consultou()
returns table (
  indicativo_de_quem_consultou text,
  em timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  select a.indicativo, l.em
    from private.acessos_a_posicao l
    join public.agents a on a.id = l.autor_agent_id
   where l.alvo_agent_id = private.current_agent_id()
     -- A própria consulta não é vigilância de ninguém.
     and l.autor_agent_id <> private.current_agent_id()
   order by l.em desc
   limit 100
$$;

revoke all on function public.abrir_mapa(uuid) from public, anon;
revoke all on function public.fechar_mapa(bigint) from public, anon;
revoke all on function public.quem_me_consultou() from public, anon;
grant execute on function public.abrir_mapa(uuid) to authenticated;
grant execute on function public.fechar_mapa(bigint) to authenticated;
grant execute on function public.quem_me_consultou() to authenticated;

commit;
