-- ─────────────────────────────────────────────────────────────────────────────
-- 0020 — `medida_em`: a idade da CORREÇÃO, não a hora do upload
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O QUE ESTÁ ERRADO HOJE
--
-- Cinco funções vivas — `posicao_relativa`, `posicoes_do_grupo`,
-- `consultar_posicao`, `agentes_no_raio` — calculam idade assim:
--
--     extract(epoch from (now() - p.updated_at))
--
-- e `updated_at` é `now()` do servidor no instante do INSERT. Isso não é a idade
-- da medição: é a idade do **upload**. Entre o GPS fixar o ponto e a linha entrar
-- no banco existe a fila do coletor, a reconexão de rede e o retry — e o produto
-- inteiro lê esse número como "há quanto tempo o agente estava ali".
--
-- A consequência é a que este projeto mais persegue: um instrumento que mente com
-- cara de precisão. Um agente que perdeu sinal por 4 minutos e reconectou publica
-- uma correção de 4 minutos atrás, e ela chega ao mapa como `idade_s = 0`. O
-- marcador aparece cheio, não esmaecido, e `agentes_no_raio` — que decide para
-- quem o alerta vai — a conta como "está perto AGORA". Mandar apoio para onde o
-- agente estava é exatamente o que aquele filtro de 5 minutos existe para impedir,
-- e ele estava sendo enganado pelo próprio dado que consultava.
--
-- A ARMADILHA, QUE É POR QUE ISTO NÃO É "SÓ ACEITAR UM TIMESTAMP DO CLIENTE"
--
-- A `0008` usa `now()` do servidor **de propósito**, e a `0016` gastou uma
-- migração inteira fechando a escrita direta porque a política aberta permitia
-- forjar `updated_at` — o exploit gravou `2099` e ficou permanentemente "fresco"
-- para a guarnição toda. Aceitar `medida_em` como parâmetro ressuscitaria esse
-- buraco pela porta da frente, e nem seria preciso má-fé: um celular com o relógio
-- adiantado publicaria posição do futuro sozinho.
--
-- Então o cliente **não manda instante nenhum**. Manda a **idade** — uma duração,
-- tirada de `elapsedRealtimeNanos`, que é monotônico, conta desde o boot e é imune
-- a fuso, NTP e ao usuário mexendo no relógio. O servidor faz `now() - idade`.
--
-- A propriedade que a `0016` defendia sobrevive inteira, e agora por construção:
--
--     medida_em = now() - greatest(0, idade)  ≤  now()
--
-- Nenhum valor de `idade_ms`, vindo de onde vier, produz um instante no futuro.
-- Idade negativa vira zero; idade absurda encosta no teto e a posição é lida como
-- muito velha — que é o comportamento seguro, porque some do mapa é pior que
-- aparecer envelhecido.
--
-- O QUE ESTA MIGRAÇÃO **NÃO** CONSERTA, E EU MEDI
--
-- A idade é calculada no cliente e o `now()` do servidor acontece depois da
-- viagem. `medida_em` fica mais novo que a verdade pelo tempo de ida da
-- requisição — sempre nessa direção, nunca na outra. É a direção perigosa
-- (posição parece mais fresca), mas o erro é de centenas de milissegundos contra
-- limiares de 120 s e 600 s: 0,4% do primeiro. Registrado aqui para não ser
-- redescoberto como defeito.

begin;

-- ─────────────────────────────────────────────────────────────────────────────
-- A coluna, e o que cada uma das duas passa a significar
-- ─────────────────────────────────────────────────────────────────────────────

alter table public.agent_positions add column if not exists medida_em timestamptz;

-- Retroativo honesto: para as linhas que já existem não há idade nenhuma
-- registrada, então `updated_at` é a **melhor estimativa disponível** e não a
-- verdade. Vale mais que nulo, que quebraria todo leitor no mesmo instante.
update public.agent_positions set medida_em = updated_at where medida_em is null;

alter table public.agent_positions alter column medida_em set default now();
alter table public.agent_positions alter column medida_em set not null;

comment on column public.agent_positions.medida_em is
  'Quando o GPS FIXOU o ponto. É esta a coluna que todo cálculo de idade deve '
  'usar. Derivada de now() menos a idade que o cliente informou, nunca de um '
  'instante enviado pelo cliente: veja 0016 para o que acontece quando dá.';

comment on column public.agent_positions.updated_at is
  'Quando a linha foi ESCRITA. Não é idade da posição. Ler isto como idade foi '
  'o defeito que a 0020 consertou em cinco funções de uma vez.';

-- A trilha ganha a mesma coluna, e aqui **sem** default nem NOT NULL: a tabela é
-- particionada e o arquivo pode ter linhas de antes desta migração. Nulo ali é a
-- afirmação correta — "não sabemos a idade desta linha" — e mentir um valor num
-- arquivo que a corregedoria pode ler é pior do que admitir a lacuna.
alter table private.trilha_de_posicao add column if not exists medida_em timestamptz;

-- ─────────────────────────────────────────────────────────────────────────────
-- O carimbo, num lugar só
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Uma função, não uma expressão repetida: a invariante "nunca no futuro" precisa
-- ser afirmada uma vez e verificável. Repetida em três `insert`, viraria três
-- oportunidades de esquecer o `greatest(0, ...)`.

create or replace function private.teto_de_idade_da_correcao()
returns interval language sql immutable
as $$ select interval '1 hour' $$;

comment on function private.teto_de_idade_da_correcao() is
  'Idade máxima atribuível a uma correção. Acima disso a posição não tem uso '
  'operacional, e o teto impede que um cliente defeituoso empurre uma linha para '
  'o Pleistoceno e quebre aritmética de intervalo nos leitores.';

create or replace function private.instante_da_medicao(idade_ms bigint)
returns timestamptz language sql volatile
as $$
  select now() - least(
    -- Cast explícito: não existe operador `bigint * interval`. Postgres
    -- resolveria por conversão implícita para `double precision`, e depender de
    -- resolução implícita numa função de segurança é como escrever assinatura de
    -- memória — funciona até o dia em que não funciona.
    greatest(coalesce(idade_ms, 0), 0)::double precision * interval '1 millisecond',
    private.teto_de_idade_da_correcao()
  )
$$;

comment on function private.instante_da_medicao(bigint) is
  'now() menos a idade informada, saturada em [0, teto]. O greatest(...,0) é a '
  'garantia de que nenhuma entrada produz instante futuro — a mesma propriedade '
  'que a 0016 defendeu fechando a escrita direta, agora por construção.';

-- ─────────────────────────────────────────────────────────────────────────────
-- A porta de escrita passa a receber a idade
-- ─────────────────────────────────────────────────────────────────────────────
--
-- `idade_ms` entra com default 0 — "medido agora". Cliente antigo, que chama com
-- cinco argumentos, continua funcionando e continua com o comportamento de hoje:
-- a mudança é aditiva, e um APK desatualizado no bolso de alguém não vira erro
-- de publicação no meio de um turno.
--
-- `drop` antes do `create` porque acrescentar parâmetro **com default** cria uma
-- função nova em vez de substituir: as duas coexistiriam e a chamada com cinco
-- argumentos viraria `function is not unique`. Testado: sem o drop, quebra.

drop function if exists public.publicar_posicao(double precision, double precision, real, real, real);

create or replace function public.publicar_posicao(
  lat double precision,
  lon double precision,
  precisao_m real default null,
  velocidade_ms real default null,
  rumo_graus real default null,
  idade_ms bigint default 0
)
returns void
language plpgsql volatile security definer set search_path = ''
as $$
declare
  eu uuid := private.current_agent_id();
  v_turno bigint;
  v_medida timestamptz;
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

  v_medida := private.instante_da_medicao(idade_ms);
  p := public.ST_SetSRID(public.ST_MakePoint(lon, lat), 4326)::public.geography;

  -- **Correção velha não sobrescreve correção nova.** O coletor descarta
  -- publicação que falhou, mas a rede pode entregar fora de ordem, e sem esta
  -- condição um retry atrasado apagaria a posição boa que chegou no meio. O
  -- `updated_at` continua sendo carimbado agora: a linha FOI escrita agora.
  insert into public.agent_positions
    (agent_id, geom, heading, speed_mps, accuracy_m, medida_em, updated_at)
  values (eu, p, rumo_graus, velocidade_ms, precisao_m, v_medida, now())
  on conflict (agent_id) do update set
    geom       = excluded.geom,
    heading    = excluded.heading,
    speed_mps  = excluded.speed_mps,
    accuracy_m = excluded.accuracy_m,
    medida_em  = excluded.medida_em,
    updated_at = now()
  where public.agent_positions.medida_em <= excluded.medida_em;

  -- A trilha é ACESSÓRIA: falha aqui não pode derrubar a publicação. O rádio e o
  -- mapa dependem da linha corrente; o arquivo, não. E a trilha grava **tudo**,
  -- inclusive o que chegou fora de ordem: arquivo que descarta o atrasado perde
  -- justamente o trecho em que o agente estava sem sinal.
  begin
    perform private.garantir_particao_da_trilha(now()::date);
    insert into private.trilha_de_posicao
      (agent_id, turno_id, geom, accuracy_m, speed_mps, medida_em, em)
    values (eu, v_turno, p, precisao_m, velocidade_ms, v_medida, now());
  exception when others then
    raise warning 'trilha nao gravada: %', sqlerrm;
  end;
end;
$$;

revoke all on function public.publicar_posicao(double precision, double precision, real, real, real, bigint) from public, anon;
grant execute on function public.publicar_posicao(double precision, double precision, real, real, real, bigint) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Os quatro leitores, todos de uma vez
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Consertar um e deixar os outros seria pior que não consertar nenhum: duas
-- telas mostrando idades diferentes para a mesma posição destroem a confiança em
-- ambas. Por isso vão juntos, na mesma transação.

create or replace function private.posicao_relativa(solicitante_id uuid, indicativo text)
returns table(indicativo_alvo text, distancia_m double precision, azimute double precision, speed_mps real, idade_s integer)
language sql stable security definer set search_path = ''
as $$
  select
    alvo.indicativo,
    public.ST_Distance(pos_alvo.geom, pos_sol.geom),
    degrees(public.ST_Azimuth(pos_sol.geom::public.geometry, pos_alvo.geom::public.geometry)),
    pos_alvo.speed_mps,
    extract(epoch from (now() - pos_alvo.medida_em))::integer
  from public.agents alvo
  join public.agent_positions pos_alvo on pos_alvo.agent_id = alvo.id
  join public.agent_positions pos_sol  on pos_sol.agent_id  = solicitante_id
  -- `posicao_relativa.indicativo` desambigua o PARÂMETRO da coluna homônima
  -- `alvo.indicativo`. Sem o prefixo, o Postgres resolveria para a coluna e a
  -- condição viraria uma tautologia — casaria com todo agente do talk group.
  -- O prefixo é o nome da função SEM o schema: `private.` aqui é erro de sintaxe.
  where lower(alvo.indicativo) = lower(posicao_relativa.indicativo)
    and alvo.id <> solicitante_id
    and exists (
      select 1
        from public.memberships meu
        join public.memberships dele on dele.talk_group_id = meu.talk_group_id
       where meu.agent_id = solicitante_id
         and dele.agent_id = alvo.id
    )
  limit 1
$$;

create or replace function public.posicoes_do_grupo(talk_group uuid)
returns table(indicativo text, distancia_m double precision, azimute double precision, speed_mps real, idade_s integer, idade_solicitante_s integer)
language sql stable security definer set search_path = ''
as $$
  with eu as (
    select private.current_agent_id() as id
  ),
  minha as (
    select p.geom, p.medida_em
      from public.agent_positions p, eu
     where p.agent_id = eu.id
  )
  select
    a.indicativo,
    public.ST_Distance(pos.geom, minha.geom),
    -- `ST_Azimuth` devolve NULL para pontos coincidentes — dupla na mesma
    -- viatura. O cliente trata; forçar zero aqui inventaria "norte".
    degrees(public.ST_Azimuth(minha.geom::public.geometry, pos.geom::public.geometry)),
    pos.speed_mps,
    extract(epoch from (now() - pos.medida_em))::integer,
    extract(epoch from (now() - minha.medida_em))::integer
  from public.agents a
  join public.agent_positions pos on pos.agent_id = a.id
  cross join minha
  cross join eu
  where a.id <> eu.id
    and exists (
      select 1
        from public.memberships meu
        join public.memberships dele on dele.talk_group_id = meu.talk_group_id
       where meu.agent_id = eu.id
         and dele.agent_id = a.id
         and meu.talk_group_id = talk_group
    )
  order by 2
$$;

create or replace function private.agentes_no_raio(lon double precision, lat double precision, raio_m integer, excluir uuid)
returns table(agent_id uuid)
language sql stable security definer set search_path = ''
as $$
  select p.agent_id
    from public.agent_positions p
   where p.agent_id <> excluir
     and raio_m > 0
     and public.ST_DWithin(p.geom, public.ST_MakePoint(lon, lat)::public.geography, raio_m)
     -- Posição obsoleta não conta como "está perto": mandar apoio para onde o
     -- agente estava há meia hora é pior que não mandar. Com `updated_at` este
     -- filtro era enganável — bastava reconectar depois de 4 min sem sinal para
     -- a correção velha entrar como recém-chegada. Com `medida_em`, não é.
     and p.medida_em > now() - interval '5 minutes'
$$;

create or replace function public.consultar_posicao(indicativo text)
returns table (
  indicativo_alvo text,
  distancia_m double precision,
  azimute double precision,
  speed_mps real,
  idade_s integer,
  idade_solicitante_s integer
)
language plpgsql volatile security definer set search_path = ''
as $$
declare
  v_eu   uuid := private.current_agent_id();
  v_alvo uuid;
begin
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
        (select extract(epoch from (now() - p.medida_em))::integer
           from public.agent_positions p
          where p.agent_id = v_eu),
        2147483647
      )
    from private.posicao_relativa(v_eu, consultar_posicao.indicativo) r;
end;
$$;

commit;
