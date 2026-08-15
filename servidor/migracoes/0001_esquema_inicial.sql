-- Claryon Field — esquema da rede tática.
--
-- Quatro decisões que precisam sobreviver à revisão:
--
-- 1. `transmissions.id` é gerado NO CLIENTE (UUIDv7). Torna o envio idempotente:
--    retry após queda de rede não duplica transmissão. Sem isso, a fila durável
--    do app produziria mensagens repetidas.
-- 2. `deliveries` separada é o que permite a confirmação falada. "Quatro unidades
--    receberam" é `count(*) where delivered_at is not null`. É a funcionalidade
--    que o rádio estruturalmente não tem.
-- 3. `transmissions` é append-only. Sem UPDATE, sem DELETE. Expiração é lógica
--    (`expira_em`), não física — é registro operacional auditável.
-- 4. A transcrição NUNCA substitui o áudio. Ambos coexistem; o que toca no ouvido
--    é sempre a voz original.

create extension if not exists postgis;
create extension if not exists pgcrypto;

create table units (
  id   uuid primary key default gen_random_uuid(),
  nome text not null
);

create table agents (
  id           uuid primary key default gen_random_uuid(),
  -- Liga o agente ao usuário autenticado. É o que `current_agent_id()` resolve,
  -- e portanto a base de toda política de linha.
  auth_user_id uuid unique,
  matricula    text not null unique,
  indicativo   text not null,                  -- "Alfa Dois" — nunca nome ou matrícula no mapa
  unit_id      uuid not null references units(id),
  device_id    text,                           -- device binding
  fcm_token    text
);

create table talk_groups (
  id      uuid primary key default gen_random_uuid(),
  unit_id uuid not null references units(id),
  nome    text not null,
  tipo    text not null check (tipo in ('guarnicao','setor','batalhao','operacao'))
);

create table memberships (
  agent_id      uuid not null references agents(id),
  talk_group_id uuid not null references talk_groups(id),
  primary key (agent_id, talk_group_id)
);

-- Posição corrente. Sobrescrita; o histórico vai para tabela própria com
-- retenção definida — manter todo o rastro por padrão seria vigilância, não
-- coordenação.
create table agent_positions (
  agent_id   uuid primary key references agents(id),
  geom       geography(Point,4326) not null,
  heading    real,
  speed_mps  real,
  accuracy_m real,
  updated_at timestamptz not null default now()
);
create index on agent_positions using gist (geom);

-- Registro append-only. Serve C1 (PTT) e C3 (alerta).
create table transmissions (
  id              uuid primary key,            -- GERADO NO CLIENTE (idempotência)
  author_agent_id uuid not null references agents(id),
  talk_group_id   uuid references talk_groups(id),
  tipo            text not null check (tipo in ('ptt','alerta')),
  prioridade      smallint not null check (prioridade between 1 and 3),
  audio_path      text,                        -- Storage; nulo em alerta sem voz
  duracao_ms      integer,
  transcricao     text,                        -- metadado, pode ser nulo
  estruturado     jsonb,                       -- {evento, logradouro, referencia}
  origem_geom     geography(Point,4326),
  raio_m          integer,
  criada_em       timestamptz not null default now(),
  expira_em       timestamptz not null
);

create table deliveries (
  transmission_id uuid not null references transmissions(id),
  agent_id        uuid not null references agents(id),
  delivered_at    timestamptz,
  played_at       timestamptz,
  ack_tipo        text check (ack_tipo in ('a_caminho','ciente','indisponivel')),
  ack_at          timestamptz,
  primary key (transmission_id, agent_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Índices de chave estrangeira.
--
-- O Postgres **não** indexa FK automaticamente. Sem estes, cada JOIN e cada
-- CASCADE vira varredura completa — e pior: `deliveries.agent_id` e
-- `transmissions.author_agent_id` são exatamente as colunas que as políticas de
-- linha consultam, então a falta de índice multiplicaria o custo de TODA leitura
-- com RLS ativo.
--
-- Nas tabelas de chave composta, a PK só cobre a PRIMEIRA coluna: `deliveries`
-- tem PK (transmission_id, agent_id) e portanto `agent_id` fica descoberto — que
-- é justamente por onde a política de entrega filtra.
-- ─────────────────────────────────────────────────────────────────────────────
create index agents_unit_id_idx           on agents (unit_id);
create index talk_groups_unit_id_idx      on talk_groups (unit_id);
create index memberships_talk_group_idx   on memberships (talk_group_id);
create index transmissions_author_idx     on transmissions (author_agent_id);
create index transmissions_talk_group_idx on transmissions (talk_group_id);
create index deliveries_agent_idx         on deliveries (agent_id);
