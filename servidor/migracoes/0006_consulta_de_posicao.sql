-- 0006 — Consulta de posição por voz (C2), exposta ao aplicativo.
--
-- `private.posicao_relativa` já faz o cálculo, mas vive no schema `private`,
-- que o PostgREST não expõe — de propósito. Esta migração cria o invólucro
-- público, e a diferença entre os dois é a coisa mais importante do arquivo:
--
--   private.posicao_relativa(solicitante_id uuid, indicativo text)
--   public.consultar_posicao(indicativo text)
--                            ^ o solicitante SUMIU da assinatura
--
-- O solicitante deixa de ser parâmetro e passa a ser derivado do JWT. Se ele
-- fosse parâmetro, qualquer agente autenticado poderia perguntar "onde está
-- Alfa Dois em relação a Bravo Um" e, variando o segundo argumento, trilaterar
-- a posição absoluta de qualquer um — usando apenas distâncias, que é o dado
-- que a API foi desenhada para poder devolver. A checagem de talk group
-- continuaria passando, porque o solicitante forjado é membro legítimo.
--
-- É o mesmo defeito de classe que tirou `agentes_no_raio` do schema público na
-- 0003: uma função que aceita a identidade de quem pergunta em vez de descobri-la.

begin;

create or replace function public.consultar_posicao(indicativo text)
returns table (
  indicativo_alvo text,
  distancia_m double precision,
  azimute double precision,
  speed_mps real,
  idade_s integer
)
language sql
stable
security definer
set search_path = ''
as $$
  select *
    from private.posicao_relativa(
      -- Do JWT, nunca do cliente. Vive em `private` junto do resto dos
      -- auxiliares de RLS: nada que derive identidade é exposto ao PostgREST.
      private.current_agent_id(),
      consultar_posicao.indicativo
    )
$$;

comment on function public.consultar_posicao(text) is
  'C2: distância, rumo e idade de um par do mesmo talk group. Nunca coordenadas. '
  'O solicitante vem do JWT — jamais do parâmetro, sob pena de trilateração.';

-- `authenticated` apenas. `anon` não tem agente correspondente, e
-- `current_agent_id()` devolveria nulo — mas negar explicitamente é melhor que
-- depender de a função se comportar bem com um nulo.
revoke all on function public.consultar_posicao(text) from public, anon;
grant execute on function public.consultar_posicao(text) to authenticated;

commit;
