-- ─────────────────────────────────────────────────────────────────────────────
-- 0019 — Retenção em duas camadas: turno, trilha, prazo e janela de 30 min
-- ─────────────────────────────────────────────────────────────────────────────
--
-- A REGRA DE SEQUENCIAMENTO, QUE É DO ROADMAP E NÃO MINHA
--
-- "A tabela de trilha entra **na mesma sessão** em que entram a porta de turno, o
-- job de retenção e o log de acesso. Nunca antes."
--
-- O motivo é que a ordem inversa deixa o sistema **estritamente pior que hoje**:
-- passa a existir rastro contínuo do deslocamento de agentes sem recorte de turno,
-- sem prazo executado e sem registro de quem leu. Uma tabela de trilha sozinha não
-- é meio caminho andado — é o dano sem nenhum dos controles.
--
-- O log de acesso entrou na `0017`. Turno, trilha e job entram aqui, juntos.
--
-- ISTO NÃO É ADITIVO, E É A PRIMEIRA VEZ NESTA FASE
--
-- `publicar_posicao` passa a **recusar fora de turno aberto**. O aplicativo que não
-- abrir turno para de publicar posição. A fiação do cliente entra no mesmo bloco de
-- trabalho; aplicar isto sozinho quebra o produto.
--
-- AS DUAS CAMADAS, E POR QUE SÃO DUAS
--
-- **Camada 1 — corregedoria.** A trilha completa, em `private`, sem GRANT para
-- `authenticated`. Ninguém no aplicativo lê. Serve à apuração formal, com prazo.
--
-- **Camada 2 — pares.** `rastro_do_par` devolve **30 minutos** de distância e
-- azimute, nunca coordenada, sujeita à mesma reciprocidade da consulta pontual.
-- É o que um colega precisa para saber se o apoio está chegando — e é tudo.
--
-- Uma camada só não serviria: dar a trilha inteira ao par é vigilância; não dar
-- nada deixa o agente sem saber se o reforço vem.

begin;

-- ─────────────────────────────────────────────────────────────────────────────
-- Prazos, num lugar só
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Prazo espalhado por `delete from ... where em < now() - interval '90 days'` em
-- cinco lugares é prazo que diverge no primeiro ajuste. Aqui muda em uma linha, e
-- a política fica legível para quem for defendê-la.
create or replace function private.prazo_da_trilha()
returns interval language sql immutable as $$ select interval '90 days' $$;

create or replace function private.prazo_do_log_de_acesso()
returns interval language sql immutable as $$ select interval '180 days' $$;

comment on function private.prazo_da_trilha() is
  'Retenção da trilha de posição. Mais curto que o do log de acesso de propósito: '
  'a trilha diz ONDE o agente esteve, o log diz QUEM perguntou. O primeiro é o '
  'dado sensível; o segundo é o controle sobre ele, e controle precisa sobreviver '
  'ao dado que controla.';

create or replace function private.inatividade_que_encerra_turno()
returns interval language sql immutable as $$ select interval '30 minutes' $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Camada 1a — o turno: o recorte que torna a trilha defensável
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists private.turnos (
  id         bigserial primary key,
  agent_id   uuid not null references public.agents(id),
  aberto_em  timestamptz not null default now(),
  fechado_em timestamptz
);

-- **Um turno aberto por agente.** Sem isto, dois turnos simultâneos tornariam a
-- trilha ambígua: o mesmo ponto pertenceria a dois recortes, e a pergunta "o que
-- ele fez no turno da noite" deixaria de ter resposta única — que é justamente a
-- pergunta que a corregedoria faz.
create unique index if not exists turno_aberto_unico_por_agente
  on private.turnos (agent_id) where fechado_em is null;

create index if not exists turnos_por_agente_idx on private.turnos (agent_id, aberto_em desc);

create or replace function public.iniciar_turno()
returns bigint
language plpgsql volatile security definer set search_path = ''
as $$
declare
  v_eu uuid := private.current_agent_id();
  v_id bigint;
begin
  if v_eu is null then
    raise exception 'sem agente' using errcode = '42501';
  end if;

  -- Idempotente: reabrir a tela não pode fatiar o turno em pedaços. Turno picado
  -- vira defesa jurídica picada.
  select id into v_id from private.turnos
   where agent_id = v_eu and fechado_em is null limit 1;
  if v_id is not null then
    return v_id;
  end if;

  insert into private.turnos (agent_id) values (v_eu) returning id into v_id;
  return v_id;
end;
$$;

create or replace function public.encerrar_turno()
returns void
language sql volatile security definer set search_path = ''
as $$
  update private.turnos set fechado_em = now()
   where agent_id = private.current_agent_id() and fechado_em is null
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Camada 1b — a trilha
-- ─────────────────────────────────────────────────────────────────────────────
--
-- **Particionada por dia**, e isso é decisão de retenção, não de desempenho:
-- apagar prazo vencido vira `drop partition`, que é instantâneo e não deixa resto.
-- `delete` em tabela grande é lento, incha o arquivo e pode ser interrompido no
-- meio — deixando exatamente o dado que o prazo mandava sumir.
--
-- **Sem GRANT para `authenticated`.** Nenhum caminho do aplicativo lê isto.
--
-- **Sem índice geográfico**, e a ausência é deliberada. Um índice GiST tornaria
-- barata a pergunta "quem esteve perto deste ponto naquele horário" — que é
-- vigilância retroativa de terceiros, exatamente o que este desenho recusa. A
-- corregedoria consulta por agente e por período, e para isso o índice abaixo basta.
create table if not exists private.trilha_de_posicao (
  agent_id   uuid not null references public.agents(id),
  turno_id   bigint not null,
  geom       geography(Point, 4326) not null,
  accuracy_m real,
  speed_mps  real,
  em         timestamptz not null
) partition by range (em);

create index if not exists trilha_por_agente_idx
  on private.trilha_de_posicao (agent_id, em desc);

comment on table private.trilha_de_posicao is
  'Camada 1 (corregedoria). Particionada por dia para que retenção seja DROP e não '
  'DELETE. Sem índice geográfico de propósito: "quem esteve perto daqui" é a '
  'pergunta que este desenho recusa a tornar barata.';

-- Garante a partição do dia. Chamada pelo job (para o dia seguinte) e, por
-- segurança, pela própria escrita — porque partição faltando derrubaria a
-- publicação de posição, e a trilha é acessória: o rádio não pode cair por causa
-- do arquivo.
create or replace function private.garantir_particao_da_trilha(dia date)
returns void
language plpgsql volatile security definer set search_path = ''
as $$
declare
  nome text := 'trilha_' || to_char(dia, 'YYYYMMDD');
begin
  if not exists (
    select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'private' and c.relname = nome
  ) then
    execute format(
      'create table private.%I partition of private.trilha_de_posicao
         for values from (%L) to (%L)',
      nome, dia, dia + 1
    );
  end if;
end;
$$;

select private.garantir_particao_da_trilha((now() - interval '1 day')::date);
select private.garantir_particao_da_trilha(now()::date);
select private.garantir_particao_da_trilha((now() + interval '1 day')::date);

-- **Encerramento automático, e ele é parte do controle e não refinamento.**
--
-- "Esqueci de encerrar" vira 24 h de rastreamento, e com isso cai a defesa inteira:
-- não dá para sustentar que a coleta é recortada por turno se o recorte depende de
-- alguém lembrar. Fecha no instante da última posição publicada, não em `now()` —
-- carimbar agora atribuiria ao turno todo o tempo em que o agente já tinha ido
-- embora, que é o mesmo exagero contra o agente que a `0018` corrigiu na sessão de
-- mapa.
create or replace function private.encerrar_turnos_inativos()
returns integer
language sql volatile security definer set search_path = ''
as $$
  with alvo as (
    select t.id,
           coalesce(
             (select max(tr.em) from private.trilha_de_posicao tr
               where tr.agent_id = t.agent_id and tr.em >= t.aberto_em),
             t.aberto_em
           ) as ultima
      from private.turnos t
     where t.fechado_em is null
  ),
  vencidos as (
    select id, ultima from alvo
     where ultima < now() - private.inatividade_que_encerra_turno()
  ),
  fechados as (
    update private.turnos t set fechado_em = v.ultima
      from vencidos v where t.id = v.id
    returning t.id
  )
  select count(*)::integer from fechados
$$;


-- ─────────────────────────────────────────────────────────────────────────────
-- A porta de escrita passa a exigir turno, e a alimentar a trilha
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.publicar_posicao(
  lat double precision,
  lon double precision,
  precisao_m real default null,
  velocidade_ms real default null,
  rumo_graus real default null
)
returns void
language plpgsql volatile security definer set search_path = ''
as $$
declare
  eu uuid := private.current_agent_id();
  v_turno bigint;
  p public.geography(Point, 4326);
begin
  if eu is null then
    raise exception 'sem agente vinculado ao usuário autenticado';
  end if;

  if lat is null or lon is null
     or lat <> lat or lon <> lon
     or lat < -90 or lat > 90 or lon < -180 or lon > 180 then
    raise exception 'coordenada fora do dominio: %, %', lat, lon;
  end if;

  -- **A recusa fora de turno.** Não é validação de entrada — é a base da defesa
  -- jurídica: sem turno aberto não há autorização para saber onde o agente está,
  -- e coleta sem autorização é o que este bloco inteiro existe para impedir.
  select id into v_turno from private.turnos
   where agent_id = eu and fechado_em is null limit 1;
  if v_turno is null then
    raise exception 'fora de turno aberto' using errcode = '42501';
  end if;

  p := public.ST_SetSRID(public.ST_MakePoint(lon, lat), 4326)::public.geography;

  insert into public.agent_positions (agent_id, geom, heading, speed_mps, accuracy_m, updated_at)
  values (eu, p, rumo_graus, velocidade_ms, precisao_m, now())
  on conflict (agent_id) do update set
    geom       = excluded.geom,
    heading    = excluded.heading,
    speed_mps  = excluded.speed_mps,
    accuracy_m = excluded.accuracy_m,
    updated_at = now();

  -- A trilha é ACESSÓRIA: falha aqui não pode derrubar a publicação. O rádio e o
  -- mapa dependem da linha corrente; o arquivo, não.
  begin
    perform private.garantir_particao_da_trilha(now()::date);
    insert into private.trilha_de_posicao (agent_id, turno_id, geom, accuracy_m, speed_mps, em)
    values (eu, v_turno, p, precisao_m, velocidade_ms, now());
  exception when others then
    raise warning 'trilha nao gravada: %', sqlerrm;
  end;
end;
$$;

revoke all on function public.publicar_posicao(double precision, double precision, real, real, real) from public, anon;
grant execute on function public.publicar_posicao(double precision, double precision, real, real, real) to authenticated;
revoke all on function public.iniciar_turno() from public, anon;
revoke all on function public.encerrar_turno() from public, anon;
grant execute on function public.iniciar_turno() to authenticated;
grant execute on function public.encerrar_turno() to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Camada 2 — a janela de 30 minutos para pares
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Distância e azimute, **nunca coordenada** — a mesma regra da consulta pontual.
-- Com a idade de cada ponto declarada, porque uma série sem idade parece contínua
-- mesmo quando tem buracos de dez minutos, e o colega decidiria deslocamento com
-- base numa linha que o sistema desenhou e não mediu.
create or replace function public.rastro_do_par(indicativo text)
returns table (
  distancia_m double precision,
  azimute double precision,
  idade_s integer
)
language sql stable security definer set search_path = ''
as $$
  select
    public.ST_Distance(tr.geom, pos_sol.geom),
    degrees(public.ST_Azimuth(pos_sol.geom::public.geometry, tr.geom::public.geometry)),
    extract(epoch from (now() - tr.em))::integer
  from public.agents alvo
  join private.trilha_de_posicao tr on tr.agent_id = alvo.id
  -- Reciprocidade: quem vê é visto. O `join` na própria posição é o mesmo
  -- mecanismo de `posicao_relativa` — sem publicar, não há de onde medir e não há
  -- resposta.
  join public.agent_positions pos_sol on pos_sol.agent_id = private.current_agent_id()
  where lower(alvo.indicativo) = lower(rastro_do_par.indicativo)
    and alvo.id <> private.current_agent_id()
    and alvo.ativo
    and tr.em > now() - interval '30 minutes'
    -- Mesmo grupo, sempre.
    and exists (
      select 1 from public.memberships m1
      join public.memberships m2 on m2.talk_group_id = m1.talk_group_id
     where m1.agent_id = private.current_agent_id() and m2.agent_id = alvo.id
    )
  order by tr.em
$$;

revoke all on function public.rastro_do_par(text) from public, anon;
grant execute on function public.rastro_do_par(text) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- O job: prazo que ninguém executa é prazo que não existe
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function private.executar_retencao()
returns text
language plpgsql volatile security definer set search_path = ''
as $$
declare
  v_particoes int := 0;
  v_turnos int;
  v_acessos int;
  v_transmissoes int;
  r record;
begin
  -- Amanhã precisa existir antes de amanhã.
  perform private.garantir_particao_da_trilha((now() + interval '1 day')::date);

  -- DROP, não DELETE: instantâneo e sem resto.
  -- **Partições de verdade, via `pg_inherits`** — não `relname like 'trilha_2%'`.
  -- `pg_class` também guarda ÍNDICES, e os das partições se chamam
  -- `trilha_20260819_agent_id_em_idx`: o filtro por nome os pegaria e o `drop
  -- table` falharia neles. O defeito só apareceria no primeiro vencimento, 90 dias
  -- depois de ninguém mais estar olhando — e o prazo simplesmente não seria
  -- executado. Foi um erro meu na consulta de conferência que o revelou aqui.
  for r in
    select c.relname
      from pg_inherits h
      join pg_class c on c.oid = h.inhrelid
     where h.inhparent = 'private.trilha_de_posicao'::regclass
       and c.relkind = 'r'
       and to_date(right(c.relname, 8), 'YYYYMMDD') < (now() - private.prazo_da_trilha())::date
  loop
    execute format('drop table private.%I', r.relname);
    v_particoes := v_particoes + 1;
  end loop;

  -- **Cada passo isolado.** A primeira versão era um bloco só, e a exclusão de
  -- `transmissions` estourou por chave estrangeira de `deliveries` — o que abortava
  -- a função inteira e deixava as partições vencidas de pé. Job de retenção que não
  -- executa NADA porque o último passo falhou é pior que job nenhum: ele figura no
  -- relatório como existente e o prazo continua não sendo cumprido.
  begin
    delete from private.acessos_a_posicao
     where em < now() - private.prazo_do_log_de_acesso();
    get diagnostics v_acessos = row_count;
  exception when others then
    v_acessos := -1;
    raise warning 'retencao do log de acesso falhou: %', sqlerrm;
  end;

  begin
    v_turnos := private.encerrar_turnos_inativos();
  exception when others then
    v_turnos := -1;
    raise warning 'encerramento de turnos falhou: %', sqlerrm;
  end;

  -- `expira_em` de `transmissions` era campo lógico sem executor: a linha dizia
  -- que expirava e continuava lá.
  --
  -- As entregas saem ANTES, e não por elegância: `deliveries` referencia
  -- `transmissions`, e apagar o pai primeiro levanta 23503. Sem esta ordem, o
  -- prazo de transmissão nunca seria executado.
  begin
    delete from public.deliveries d
     using public.transmissions t
     where d.transmission_id = t.id
       and t.expira_em is not null and t.expira_em < now();
    delete from public.transmissions where expira_em is not null and expira_em < now();
    get diagnostics v_transmissoes = row_count;
  exception when others then
    v_transmissoes := -1;
    raise warning 'retencao de transmissions falhou: %', sqlerrm;
  end;

  return format(
    'particoes_removidas=%s acessos_removidos=%s turnos_encerrados=%s transmissoes_expiradas=%s',
    v_particoes, v_acessos, v_turnos, v_transmissoes
  );
end;
$$;

commit;

-- ─────────────────────────────────────────────────────────────────────────────
-- O agendamento — "prazo que ninguém executa é prazo que não existe"
-- ─────────────────────────────────────────────────────────────────────────────
--
-- `pg_cron` não estava instalado neste projeto: a função de retenção existiria e
-- nunca rodaria, que é o cenário exato que a frase acima descreve. Habilitado aqui.
--
-- Assinatura confirmada no repositório do `pg_cron` antes do diff (a doc do
-- Supabase não a traz) e anotada em `DECISIONS.md`:
--   cron.schedule(job_name text, schedule text, command text) returns bigint
--
-- 03:17 e não 03:00: horário redondo é quando todo mundo agenda, e o `drop` de
-- partição pega lock exclusivo.
create extension if not exists pg_cron;

select cron.unschedule('retencao_claryon')
 where exists (select 1 from cron.job where jobname = 'retencao_claryon');

select cron.schedule('retencao_claryon', '17 3 * * *', 'select private.executar_retencao()');
