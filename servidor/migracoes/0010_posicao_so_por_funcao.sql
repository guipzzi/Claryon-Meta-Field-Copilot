-- 0010 — A coordenada de um par deixa de ser legível pela API.
--
-- **O buraco.** `positions_read` era `agent_id IN (private.pares_do_talk_group())`.
-- Isso deixa qualquer agente autenticado fazer, pelo PostgREST:
--
--     GET /rest/v1/agent_positions?select=agent_id,geom
--
-- e receber a coordenada bruta de toda a guarnição. Não por derivação
-- trigonométrica a partir de distância e rumo — por **consulta direta**.
--
-- O projeto vinha afirmando que "o aparelho de um agente jamais recebe a posição
-- de outro", e a afirmação estava errada em dois níveis ao mesmo tempo: o cliente
-- pode derivar (matemática), e o cliente pode simplesmente pedir (esta política).
-- Toda a arquitetura de `consultar_posicao` e `posicoes_do_grupo` — devolver
-- grandezas em vez de coordenadas, calcular no servidor, esconder
-- `private.posicao_relativa` — protegia a porta da frente com a lateral aberta.
--
-- **A correção.** Leitura direta passa a ser só da própria linha. A posição dos
-- pares sai exclusivamente pelas funções `SECURITY DEFINER`, que devolvem
-- distância, rumo e idade — e que, por serem definer, seguem funcionando.
--
-- Isso é o que faz a garantia deixar de ser aspiração e virar propriedade do
-- sistema, verificável em `servidor/verificacoes/0005_posicao_inacessivel.sql`.

begin;

drop policy if exists positions_read on public.agent_positions;

create policy positions_read on public.agent_positions
for select
using (
  -- Só a própria. A do par vem por `posicoes_do_grupo` / `consultar_posicao`,
  -- em grandezas, nunca em coordenada.
  agent_id = (select private.current_agent_id())
);

comment on policy positions_read on public.agent_positions is
  'Leitura direta apenas da propria posicao. A posicao dos pares sai so pelas '
  'funcoes SECURITY DEFINER, em distancia e rumo — nunca coordenada. Ver 0010.';

commit;
