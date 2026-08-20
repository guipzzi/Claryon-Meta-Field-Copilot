-- ─────────────────────────────────────────────────────────────────────────────
-- 0022 — O rastro também datava por hora de upload
-- ─────────────────────────────────────────────────────────────────────────────
--
-- A `0020` trocou cinco funções de `updated_at` para `medida_em` e a verificação
-- `0009` até asserta que "nenhuma função viva calcula idade por updated_at".
-- `rastro_do_par` passou por baixo dessa asserção porque a coluna dela **não se
-- chama `updated_at`**: na trilha o carimbo de upload se chama `em`. Procurei o
-- token, não o conceito, e o `grep` concordou comigo.
--
-- O efeito é o pior possível justamente aqui. As outras funções devolvem uma
-- idade; esta devolve uma SÉRIE de idades, e é a série que o agente lê como
-- "por onde ele passou e quando". Um par que ficou 20 min sem sinal e reconectou
-- despejava cinco correções de uma vez: todas com `em = agora`, todas
-- apresentadas como "agora". O rastro mostrava um trajeto de vinte minutos
-- comprimido no instante presente — a trajetória certa, com o tempo todo errado.
--
-- Medido: as cinco publicações de teste, com idades declaradas de 20, 15, 10, 5
-- e 0 minutos, voltaram todas com `idade_s = 1`.
--
-- A JANELA TAMBÉM MUDA, E PELA MESMA RAZÃO
--
-- O recorte de 30 minutos existe para dizer "por onde o par andou na última meia
-- hora". Filtrando por `em`, um lote de correções de uma hora atrás entregue
-- agora entra na janela inteiro. Filtrando por `medida_em`, entra o que foi
-- MEDIDO na última meia hora, que é o que a frase promete.
--
-- `coalesce(medida_em, em)` porque a `0020` deixou `medida_em` nulo nas linhas
-- de trilha anteriores a ela — deliberadamente, para não inventar idade em
-- arquivo que a corregedoria pode ler. Para essas, `em` é a melhor estimativa
-- disponível e continua sendo melhor que nulo.

begin;

create or replace function public.rastro_do_par(indicativo text)
returns table(distancia_m double precision, azimute double precision, idade_s integer)
language sql stable security definer set search_path = ''
as $$
  select
    private.distancia_arredondada(public.ST_Distance(tr.geom, pos_sol.geom)),
    degrees(public.ST_Azimuth(pos_sol.geom::public.geometry, tr.geom::public.geometry)),
    extract(epoch from (now() - coalesce(tr.medida_em, tr.em)))::integer
  from public.agents alvo
  join private.trilha_de_posicao tr on tr.agent_id = alvo.id
  -- Reciprocidade: quem vê é visto. O `join` na própria posição é o mesmo
  -- mecanismo de `posicao_relativa` — sem publicar, não há de onde medir e não há
  -- resposta.
  join public.agent_positions pos_sol on pos_sol.agent_id = private.current_agent_id()
  where lower(alvo.indicativo) = lower(rastro_do_par.indicativo)
    and alvo.id <> private.current_agent_id()
    and alvo.ativo
    -- MEDIDO na última meia hora, não ENTREGUE na última meia hora.
    and coalesce(tr.medida_em, tr.em) > now() - interval '30 minutes'
    -- Mesmo grupo, sempre.
    and exists (
      select 1 from public.memberships m1
      join public.memberships m2 on m2.talk_group_id = m1.talk_group_id
     where m1.agent_id = private.current_agent_id() and m2.agent_id = alvo.id
    )
  order by coalesce(tr.medida_em, tr.em)
$$;

commit;
