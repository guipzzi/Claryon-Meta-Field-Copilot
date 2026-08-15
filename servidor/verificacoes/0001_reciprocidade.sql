-- ═══════════════════════════════════════════════════════════════════════════
-- Verificação das políticas de linha — reciprocidade, append-only e isolamento.
--
-- Roda inteiramente dentro de uma transação que termina em ROLLBACK: nenhum dado
-- de teste sobrevive. Pode ser executada em produção sem deixar rastro.
--
-- O ponto central: **o SQL Editor roda como `postgres`, que é dono das tabelas e
-- portanto IGNORA RLS.** Verificar política sem trocar de papel provaria nada.
-- Por isso cada checagem assume o papel `authenticated` e forja o claim `sub`
-- do JWT, que é de onde `auth.uid()` lê — é assim que o cliente real chega.
-- ═══════════════════════════════════════════════════════════════════════════

begin;

create temp table resultado (
  verificacao text,
  esperado    text,
  obtido      text,
  passou      boolean
) on commit drop;
grant all on resultado to authenticated;

-- ── Cenário ────────────────────────────────────────────────────────────────
-- Uma unidade, dois talk groups.
--   Alfa e Bravo dividem o GTA-3.
--   Charlie está no GTA-7, sozinho — é o controle negativo.
insert into units (id, nome) values
  ('11111111-1111-1111-1111-111111111111', 'Batalhão de Teste');

insert into agents (id, auth_user_id, matricula, indicativo, unit_id) values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   'M-001', 'Alfa Um',    '11111111-1111-1111-1111-111111111111'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
   'M-002', 'Bravo Dois', '11111111-1111-1111-1111-111111111111'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'cccccccc-cccc-cccc-cccc-cccccccccccc',
   'M-003', 'Charlie Três','11111111-1111-1111-1111-111111111111');

insert into talk_groups (id, unit_id, nome, tipo) values
  ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', 'GTA-3', 'guarnicao'),
  ('55555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111', 'GTA-7', 'guarnicao');

insert into memberships (agent_id, talk_group_id) values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '44444444-4444-4444-4444-444444444444'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '44444444-4444-4444-4444-444444444444'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '55555555-5555-5555-5555-555555555555');

-- Posições em Goiânia. Bravo fica ~1,2 km ao norte de Alfa.
insert into agent_positions (agent_id, geom, heading, speed_mps, accuracy_m) values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   public.ST_MakePoint(-49.2648, -16.6869)::public.geography, 0, 0.0, 8),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
   public.ST_MakePoint(-49.2648, -16.6761)::public.geography, 45, 4.2, 8),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc',
   public.ST_MakePoint(-49.3000, -16.7000)::public.geography, 90, 0.0, 8);

-- ── 1. Reciprocidade: dentro do talk group, quem vê é visto ────────────────

set local role authenticated;
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}';

-- **Esta verificação foi reescrita, e o motivo importa.**
--
-- Ela afirmava que Alfa LÊ a linha de `agent_positions` de Bravo — e passava.
-- Passava porque `positions_read` era `agent_id IN (pares_do_talk_group())`, o
-- que entregava `geom` bruto de toda a guarnição a qualquer agente autenticado.
-- A verificação estava, sem perceber, **certificando o buraco**: o produto
-- afirmava que a coordenada do par nunca chega ao aparelho, e o teste de
-- reciprocidade provava o contrário.
--
-- Reciprocidade não exige ler a coordenada. Exige que a informação relativa flua
-- nos dois sentidos e que ninguém observe sem ser observado. É isso que se
-- verifica agora — e a leitura direta passou a ser proibida, o que é conferido em
-- `0005_posicao_inacessivel.sql`.
insert into resultado
select 'Alfa NAO le a coordenada de Bravo', 'false',
       exists(select 1 from agent_positions
               where agent_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb')::text,
       not exists(select 1 from agent_positions
               where agent_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');

insert into resultado
select 'Alfa NAO enxerga Charlie (outro talk group)', 'false',
       exists(select 1 from agent_positions
               where agent_id = 'cccccccc-cccc-cccc-cccc-cccccccccccc')::text,
       not exists(select 1 from agent_positions
                   where agent_id = 'cccccccc-cccc-cccc-cccc-cccccccccccc');

set local request.jwt.claims = '{"sub":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}';

-- Simetria, pelo caminho que de fato existe: cada um obtém do outro distância e
-- rumo, e nenhum obtém coordenada. É a reciprocidade real — quem vê é visto, e
-- ambos veem a mesma classe de dado.
insert into resultado
select 'Bravo NAO le a coordenada de Alfa (simetria)', 'false',
       exists(select 1 from agent_positions
               where agent_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')::text,
       not exists(select 1 from agent_positions
               where agent_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');

-- Charlie, isolado, não pode ver ninguém além de si.
set local request.jwt.claims = '{"sub":"cccccccc-cccc-cccc-cccc-cccccccccccc"}';

insert into resultado
select 'Charlie so enxerga a si proprio', '1',
       count(*)::text, count(*) = 1
  from agent_positions;

reset role;

-- ── 2. Append-only em transmissions ────────────────────────────────────────

insert into transmissions
  (id, author_agent_id, talk_group_id, tipo, prioridade, duracao_ms, expira_em)
values
  ('99999999-9999-9999-9999-999999999999',
   'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   '44444444-4444-4444-4444-444444444444',
   'ptt', 2, 3200, now() + interval '1 day');

insert into deliveries (transmission_id, agent_id, delivered_at) values
  ('99999999-9999-9999-9999-999999999999',
   'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', now());

set local role authenticated;
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}';

-- A política de UPDATE é `using (false)`: nenhuma linha é elegível, então o
-- comando não altera nada em vez de estourar. Contar o efeito é a prova.
with tentativa as (
  update transmissions set transcricao = 'adulterado'
   where id = '99999999-9999-9999-9999-999999999999'
  returning 1
)
insert into resultado
select 'transmissions recusa UPDATE', '0 linhas', count(*)::text || ' linhas', count(*) = 0
  from tentativa;

with tentativa as (
  delete from transmissions
   where id = '99999999-9999-9999-9999-999999999999'
  returning 1
)
insert into resultado
select 'transmissions recusa DELETE', '0 linhas', count(*)::text || ' linhas', count(*) = 0
  from tentativa;

insert into resultado
select 'autor le a propria transmissao', 'true',
       exists(select 1 from transmissions
               where id = '99999999-9999-9999-9999-999999999999')::text,
       exists(select 1 from transmissions
               where id = '99999999-9999-9999-9999-999999999999');

-- Charlie não foi destinatário nem autor: não pode ler.
set local request.jwt.claims = '{"sub":"cccccccc-cccc-cccc-cccc-cccccccccccc"}';

insert into resultado
select 'terceiro NAO le transmissao alheia', 'false',
       exists(select 1 from transmissions
               where id = '99999999-9999-9999-9999-999999999999')::text,
       not exists(select 1 from transmissions
                   where id = '99999999-9999-9999-9999-999999999999');

reset role;

-- ── 3. A consulta por voz devolve grandezas, nunca coordenadas ─────────────

insert into resultado
select 'posicao_relativa: distancia ~1200 m', '1100-1300',
       round(distancia_m)::text,
       distancia_m between 1100 and 1300
  from private.posicao_relativa(
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Bravo Dois');

insert into resultado
select 'posicao_relativa: rumo norte (~0 graus)', '0 +/- 15',
       round(azimute)::text,
       abs(azimute) < 15 or abs(azimute - 360) < 15
  from private.posicao_relativa(
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Bravo Dois');

insert into resultado
select 'posicao_relativa: em movimento detectado', 'true',
       (speed_mps > 1)::text, speed_mps > 1
  from private.posicao_relativa(
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Bravo Dois');

-- Charlie não divide talk group: a função não responde sobre ele.
insert into resultado
select 'posicao_relativa NAO responde fora do talk group', '0 linhas',
       count(*)::text || ' linhas', count(*) = 0
  from private.posicao_relativa(
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Charlie Três');

-- A função devolve distancia/rumo/idade e NENHUMA coluna de geometria.
insert into resultado
select 'posicao_relativa nao expoe coordenada', '0 colunas geo',
       count(*)::text || ' colunas geo', count(*) = 0
  from information_schema.columns
 where table_schema = 'private'
   and udt_name in ('geography', 'geometry');

-- ── 4. As funções perigosas são inalcançáveis pelo cliente ─────────────────

insert into resultado
select 'anon NAO usa o schema private', 'false',
       has_schema_privilege('anon', 'private', 'usage')::text,
       not has_schema_privilege('anon', 'private', 'usage');

insert into resultado
select 'anon NAO executa agentes_no_raio', 'false',
       has_function_privilege('anon',
         'private.agentes_no_raio(double precision,double precision,integer,uuid)',
         'execute')::text,
       not has_function_privilege('anon',
         'private.agentes_no_raio(double precision,double precision,integer,uuid)',
         'execute');

insert into resultado
select 'authenticated NAO executa posicao_relativa', 'false',
       has_function_privilege('authenticated',
         'private.posicao_relativa(uuid,text)', 'execute')::text,
       not has_function_privilege('authenticated',
         'private.posicao_relativa(uuid,text)', 'execute');

insert into resultado
select 'service_role EXECUTA agentes_no_raio', 'true',
       has_function_privilege('service_role',
         'private.agentes_no_raio(double precision,double precision,integer,uuid)',
         'execute')::text,
       has_function_privilege('service_role',
         'private.agentes_no_raio(double precision,double precision,integer,uuid)',
         'execute');

-- ── Resultado ──────────────────────────────────────────────────────────────

select
  case when passou then '✓' else '✗ FALHOU' end as st,
  verificacao,
  esperado,
  obtido
from resultado
order by passou, verificacao;

rollback;
