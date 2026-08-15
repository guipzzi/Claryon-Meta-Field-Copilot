-- Seed do piloto — uma guarnição de três em Goiânia.
--
-- Existe para que as telas possam ser **vistas com conteúdo**. Um app de rádio
-- demonstrado com o canal vazio não mostra nada do que foi construído: a régua
-- de presença, o histórico transcrito, o esmaecimento por idade e a faixa de
-- prioridade só existem quando há tráfego.
--
-- Idempotente: pode rodar quantas vezes quiser. Os identificadores são fixos,
-- e não gerados, exatamente para isso.
--
--   python3 servidor/executar_sql.py servidor/seed_piloto.sql

begin;

-- Identificadores fixos para o seed ser repetível.
insert into public.units (id, nome)
values ('11111111-0000-0000-0000-000000000001', 'GTA-3 — Goiânia Centro')
on conflict (id) do update set nome = excluded.nome;

insert into public.talk_groups (id, unit_id, nome, tipo)
values (
  '22222222-0000-0000-0000-000000000001',
  '11111111-0000-0000-0000-000000000001',
  'GTA-3 Alfa',
  'guarnicao'
)
on conflict (id) do update set nome = excluded.nome;

-- Três agentes. `auth_user_id` fica nulo: vincular exige criar usuário no
-- GoTrue, que é o passo manual do onboarding. Sem o vínculo, estes agentes
-- aparecem no mapa e no histórico mas ninguém entra como eles — que é
-- exatamente o que se quer numa demonstração.
insert into public.agents (id, matricula, indicativo, unit_id)
values
  ('33333333-0000-0000-0000-000000000001', '41882', 'Alfa Um',   '11111111-0000-0000-0000-000000000001'),
  ('33333333-0000-0000-0000-000000000002', '41917', 'Alfa Dois', '11111111-0000-0000-0000-000000000001'),
  ('33333333-0000-0000-0000-000000000003', '42043', 'Bravo Um',  '11111111-0000-0000-0000-000000000001')
on conflict (id) do update set indicativo = excluded.indicativo;

insert into public.memberships (agent_id, talk_group_id)
select id, '22222222-0000-0000-0000-000000000001' from public.agents
 where id in (
   '33333333-0000-0000-0000-000000000001',
   '33333333-0000-0000-0000-000000000002',
   '33333333-0000-0000-0000-000000000003'
 )
on conflict do nothing;

-- ── Posições ────────────────────────────────────────────────────────────────
--
-- Idades diferentes de propósito: uma recente, uma esmaecida (>2 min) e uma
-- antiga (>10 min). É o único jeito de a demonstração mostrar a regra que mais
-- importa no mapa — **o marcador para de afirmar posição quando envelhece**.
--
-- Coordenadas reais de Goiânia, a alguns quarteirões umas das outras.
insert into public.agent_positions (agent_id, geom, heading, speed_mps, accuracy_m, updated_at)
values
  -- Alfa Um: o portador. Praça Cívica.
  ('33333333-0000-0000-0000-000000000001',
   public.ST_SetSRID(public.ST_MakePoint(-49.2550, -16.6799), 4326)::public.geography,
   0, 0.2, 8, now()),
  -- Alfa Dois: ~1,2 km a nordeste, deslocando. Marcador cheio.
  ('33333333-0000-0000-0000-000000000002',
   public.ST_SetSRID(public.ST_MakePoint(-49.2470, -16.6720), 4326)::public.geography,
   45, 8.4, 12, now() - interval '20 seconds'),
  -- Bravo Um: ~2,8 km a sudoeste, parado há 6 min. Marcador esmaecido.
  ('33333333-0000-0000-0000-000000000003',
   public.ST_SetSRID(public.ST_MakePoint(-49.2760, -16.6960), 4326)::public.geography,
   210, 0.1, 22, now() - interval '6 minutes')
on conflict (agent_id) do update set
  geom = excluded.geom,
  heading = excluded.heading,
  speed_mps = excluded.speed_mps,
  accuracy_m = excluded.accuracy_m,
  updated_at = excluded.updated_at;

-- ── Tráfego do canal ────────────────────────────────────────────────────────
--
-- Fala real de rádio: curta, sem cortesia, com o endereço no meio da frase. As
-- prioridades cobrem os três níveis para que a faixa lateral apareça inteira na
-- tela — e a mais grave é a última, como acontece de verdade: a ocorrência
-- escala.
insert into public.transmissions
  (id, author_agent_id, talk_group_id, tipo, prioridade, duracao_ms, transcricao, criada_em, expira_em)
values
  ('44444444-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000002',
   '22222222-0000-0000-0000-000000000001', 'ptt', 3, 3200,
   'Alfa Dois em deslocamento pela Anhanguera, sentido centro.',
   now() - interval '14 minutes', now() + interval '30 days'),

  ('44444444-0000-0000-0000-000000000002', '33333333-0000-0000-0000-000000000003',
   '22222222-0000-0000-0000-000000000001', 'ptt', 3, 2600,
   'Bravo Um posicionado na Marginal Botafogo, sem movimento.',
   now() - interval '11 minutes', now() + interval '30 days'),

  ('44444444-0000-0000-0000-000000000003', '33333333-0000-0000-0000-000000000001',
   '22222222-0000-0000-0000-000000000001', 'ptt', 3, 2100,
   'Entendido. Mantenho posição na Praça Cívica.',
   now() - interval '10 minutes', now() + interval '30 days'),

  ('44444444-0000-0000-0000-000000000004', '33333333-0000-0000-0000-000000000002',
   '22222222-0000-0000-0000-000000000001', 'alerta', 2, 4100,
   'Roubo de veículo na Rui Barbosa, suspeito em fuga a pé.',
   now() - interval '6 minutes', now() + interval '30 days'),

  ('44444444-0000-0000-0000-000000000005', '33333333-0000-0000-0000-000000000003',
   '22222222-0000-0000-0000-000000000001', 'ptt', 3, 1900,
   'Bravo Um a caminho, dois minutos.',
   now() - interval '5 minutes', now() + interval '30 days'),

  ('44444444-0000-0000-0000-000000000006', '33333333-0000-0000-0000-000000000002',
   '22222222-0000-0000-0000-000000000001', 'alerta', 1, 3600,
   'Tiroteio na Rui Barbosa, suspeito armado. Guarnição em risco.',
   now() - interval '2 minutes', now() + interval '30 days')
on conflict (id) do update set transcricao = excluded.transcricao;

-- ── Entregas ────────────────────────────────────────────────────────────────
--
-- **Sem isto o histórico fica invisível, e a política está certa.**
--
-- `transmissions_read` deixa um agente ler o que ele mesmo transmitiu ou o que
-- foi **entregue** a ele — não tudo que passou pelo talk group. É um modelo mais
-- forte que "membro vê tudo": produz trilha auditável de quem foi de fato
-- informado, que num contexto de segurança pública é a diferença entre
-- "a guarnição foi avisada" e "alguém falou no rádio".
--
-- A primeira versão deste seed criou as transmissões e esqueceu as entregas. O
-- app mostrava exatamente uma fala — a própria — e parecia defeito de consulta.
-- Era o modelo funcionando como projetado, com dado pela metade.
insert into public.deliveries (transmission_id, agent_id, delivered_at, played_at)
select t.id, m.agent_id, t.criada_em + interval '1 second', t.criada_em + interval '3 seconds'
  from public.transmissions t
  join public.memberships m on m.talk_group_id = t.talk_group_id
 where t.talk_group_id = '22222222-0000-0000-0000-000000000001'
   -- Quem falou não recebe a própria fala: a política já a libera pela autoria,
   -- e uma linha de entrega para si mesmo poluiria a auditoria.
   and m.agent_id <> t.author_agent_id
on conflict do nothing;

commit;
