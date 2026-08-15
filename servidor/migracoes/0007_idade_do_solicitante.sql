-- 0007 — A consulta de posição passa a devolver a idade da posição do SOLICITANTE.
--
-- Achado da revisão: `consultar_posicao` devolvia `idade_s` do **alvo** e nada
-- sobre quem perguntou. Mas a distância e o rumo são calculados a partir de
-- `pos_sol` — a última posição do solicitante **no banco**, não a correção que o
-- celular tem agora.
--
-- Sem esse campo, o modo de falha grave é silencioso: o agente entra num prédio,
-- fica 40 minutos sem rede, sai, e pergunta onde está o par. O GPS local está
-- fresco, então o app deixa a consulta passar; o servidor calcula a partir de
-- onde o agente **estava** há 40 minutos; e a resposta sai afirmada como atual.
-- O erro pode ser de quilômetros, e nada no payload permitia detectá-lo.
--
-- O app agora recusa a resposta quando a própria posição publicada está velha, e
-- diz isso — em vez de falar uma distância errada com confiança.

begin;

drop function if exists public.consultar_posicao(text);

create or replace function public.consultar_posicao(indicativo text)
returns table (
  indicativo_alvo text,
  distancia_m double precision,
  azimute double precision,
  speed_mps real,
  idade_s integer,
  -- Há quantos segundos foi publicada a posição a partir da qual a distância e o
  -- rumo acima foram medidos.
  idade_solicitante_s integer
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    r.indicativo_alvo,
    r.distancia_m,
    r.azimute,
    r.speed_mps,
    r.idade_s,
    coalesce(
      (select extract(epoch from (now() - p.updated_at))::integer
         from public.agent_positions p
        where p.agent_id = private.current_agent_id()),
      -- Sem posição publicada nenhuma. Não é zero: zero significaria
      -- "publicada agora", que é o oposto do que aconteceu.
      2147483647
    )
  from private.posicao_relativa(private.current_agent_id(), consultar_posicao.indicativo) r
$$;

comment on function public.consultar_posicao(text) is
  'C2: distância, rumo e idade de um par do mesmo talk group. Nunca coordenadas. '
  'O solicitante vem do JWT — jamais do parâmetro, sob pena de trilateração. '
  'Devolve também a idade da posição DO SOLICITANTE: é dela que a distância foi '
  'medida, e uma posição própria velha torna a resposta inteira falsa.';

revoke all on function public.consultar_posicao(text) from public, anon;
grant execute on function public.consultar_posicao(text) to authenticated;

commit;
