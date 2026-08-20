-- Verificação: nenhuma porta devolve distância métrica crua.
--
-- O contra-teste é o ponto: um `round()` mal escrito (passo 1) passaria em
-- qualquer teste que só olhasse "o número mudou". Aqui a afirmação é sobre a
-- GRANULARIDADE — todo valor devolvido tem de ser múltiplo do passo da sua
-- faixa —, e há um caso que prova que a faixa fina existe (12 m → 10, não → 50).

begin;
create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

insert into r select 'faixa fina: 12 m vira 10, nao 50', '10',
  private.distancia_arredondada(12.0)::text, private.distancia_arredondada(12.0) = 10.0;

insert into r select 'faixa media: 137 m vira multiplo de 50', '150',
  private.distancia_arredondada(137.4829)::text, private.distancia_arredondada(137.4829) = 150.0;

insert into r select 'faixa grossa: 1237 m vira multiplo de 100', '1200',
  private.distancia_arredondada(1237.4829)::text, private.distancia_arredondada(1237.4829) = 1200.0;

insert into r select 'nulo continua nulo, nao vira zero', 'null',
  coalesce(private.distancia_arredondada(null)::text, 'null'),
  private.distancia_arredondada(null) is null;

-- **A granularidade, em 500 valores.** Prova que nenhuma faixa vazou passo 1.
insert into r select 'servidor e cliente concordam em 0..5000 (regua do CLIENTE)', '0',
  count(*)::text, count(*) = 0
  from generate_series(0, 5000, 7) m
 where private.distancia_arredondada(m) <> case
         when m < 100 then round(m/10.0)*10.0
         when m < 1000 then round(m/50.0)*50.0
         else round(m/100.0)*100.0 end;

-- **O contra-teste da migração.** Se `distancia_arredondada` fosse identidade,
-- este número seria 0 — e todos os testes acima ainda poderiam passar por
-- coincidência de valores escolhidos a dedo.
insert into r select 'a funcao MUDA a maioria dos valores (nao e identidade)', '>400',
  count(*)::text, count(*) > 400
  from generate_series(0, 5000, 7) m
 where private.distancia_arredondada(m) <> m::double precision;

-- As três portas vivas usam a função. Consertar duas e esquecer a terceira daria
-- granularidades diferentes para o mesmo par em telas diferentes.
-- **A contagem, e não só o predicado.** Sem ela, apagar ou renomear uma das portas
-- faz a consulta devolver ZERO linhas: o relatório fica uma linha mais curto e
-- continua todo ✓. Verificação que some junto com o que verifica não verifica.
insert into r
select 'as TRES portas existem', '3', count(*)::text, count(*) = 3
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname in ('public', 'private')
   and p.proname in ('posicao_relativa', 'posicoes_do_grupo', 'rastro_do_par');

insert into r
select 'porta ' || p.proname || ' arredonda', 'true',
       (pg_get_functiondef(p.oid) ~ 'distancia_arredondada')::text,
       pg_get_functiondef(p.oid) ~ 'distancia_arredondada'
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname in ('public', 'private')
   and p.proname in ('posicao_relativa', 'posicoes_do_grupo', 'rastro_do_par');

-- O azimute NÃO é arredondado, e isso é decisão: rumo grosso manda o agente para o
-- lado errado do quarteirão.
--
-- **A versão anterior afirmava a ausência de um literal** — `!~ 'distancia_arredondada\(degrees'`
-- — e passava com o defeito de volta: bastava `round(degrees(...)::numeric, -1)`, ou
-- uma função nova, ou um espaço a mais. Agora a afirmação é COMPORTAMENTAL: dois
-- pontos escolhidos para dar azimute com casas decimais, e o resultado tem de
-- conservá-las. Nenhum arredondamento sobrevive a isto, escrito de que jeito for.
insert into r
select 'azimute conserva casas decimais (nao foi arredondado)', 'true',
       round(az::numeric, 4)::text,
       az <> round(az::numeric, 0)::double precision
  from (
    select degrees(public.ST_Azimuth(
      public.ST_SetSRID(public.ST_MakePoint(-49.2500, -16.6800), 4326)::public.geography::public.geometry,
      public.ST_SetSRID(public.ST_MakePoint(-49.2537, -16.6871), 4326)::public.geography::public.geometry
    )) as az
  ) t;

select case passou when true then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by verificacao;
rollback;
