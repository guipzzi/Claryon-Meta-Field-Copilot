-- Verificação da `0022`: o rastro data pela MEDIÇÃO, não pelo upload.
--
-- A `0020` migrou cinco funções e a régua da `0009` afirmava "nenhuma função viva
-- calcula idade por updated_at" — `rastro_do_par` passou por baixo porque na trilha
-- a coluna de upload se chama `em`. A `0009` foi corrigida para caçar o conceito;
-- esta aqui prova o comportamento.
--
-- O cenário é o real e é o pior: um par que ficou sem sinal e reconectou despeja
-- várias correções de uma vez. Todas com `em = agora`. Se a idade sair de `em`, um
-- trajeto de 20 minutos aparece inteiro como "agora".

begin;
create temp table r (verificacao text, esperado text, obtido text, passou boolean) on commit drop;

set local role authenticated;
set local request.jwt.claims = '{"sub":"44444444-0000-0000-0000-000000000009"}';
select public.iniciar_turno();
set local role postgres;
delete from private.trilha_de_posicao where agent_id = '33333333-0000-0000-0000-000000000009';

-- Bravo Dois reconectando: quatro correções de 20, 15, 10 e 5 min, TODAS agora.
set local role authenticated;
select public.publicar_posicao(-16.6900, -49.2600, 8.0, 4.0, null, 1200000);
select public.publicar_posicao(-16.6880, -49.2570, 8.0, 4.0, null,  900000);
select public.publicar_posicao(-16.6860, -49.2545, 8.0, 4.0, null,  600000);
select public.publicar_posicao(-16.6840, -49.2520, 8.0, 4.0, null,  300000);

-- Bravo Um precisa de posição para haver de onde medir (reciprocidade).
set local request.jwt.claims = '{"sub":"44444444-0000-0000-0000-000000000003"}';
select public.iniciar_turno();
select public.publicar_posicao(-16.6800, -49.2500, 8.0, 0.0, null, 0);

-- Materializa como `authenticated` (a função lê o JWT) e mede como `postgres`
-- (a temp table é dele). Sem esta separação: "permission denied for table r".
create temp table rastro1 on commit drop as select * from public.rastro_do_par('Bravo Dois');
set local role postgres;

insert into r
select 'o rastro tem as quatro correcoes', '4', count(*)::text, count(*) = 4 from rastro1;

-- **O defeito escrito como comportamento.** Com idade por `em`, os quatro pontos
-- viriam com o MESMO valor (a hora da transação). A afirmação é que diferem, e nos
-- intervalos certos: 1200, 900, 600 e 300 segundos.
insert into r
select 'as idades SAO distintas (nao todas "agora")', '4',
       count(distinct idade_s)::text, count(distinct idade_s) = 4 from rastro1;

insert into r
select 'a mais velha tem ~20 min, nao 0 s', '1200',
       max(idade_s)::text, abs(max(idade_s) - 1200) < 10 from rastro1;

-- Contra-prova: pela coluna de UPLOAD todas dariam a mesma idade. Se este número
-- fosse 4, o teste acima não estaria medindo nada.
insert into r
select 'e por `em` (upload) elas seriam TODAS iguais', '1',
       count(distinct extract(epoch from (now() - em))::integer)::text,
       count(distinct extract(epoch from (now() - em))::integer) = 1
  from private.trilha_de_posicao
 where agent_id = '33333333-0000-0000-0000-000000000009';

-- A janela de 30 min também filtra por medição: um lote de uma hora atrás entregue
-- agora não pode entrar inteiro.
set local role authenticated;
set local request.jwt.claims = '{"sub":"44444444-0000-0000-0000-000000000009"}';
select public.publicar_posicao(-16.7000, -49.2700, 8.0, 4.0, null, 3600000);
set local request.jwt.claims = '{"sub":"44444444-0000-0000-0000-000000000003"}';
create temp table rastro2 on commit drop as select * from public.rastro_do_par('Bravo Dois');
set local role postgres;

insert into r
select 'correcao de 1 h fica FORA da janela de 30 min', '4',
       count(*)::text, count(*) = 4 from rastro2;

-- E arredondada, como as outras duas portas (`0021`).
insert into r
select 'as distancias do rastro sao arredondadas', '0',
       count(*) filter (where distancia_m <> private.distancia_arredondada(distancia_m))::text,
       count(*) filter (where distancia_m <> private.distancia_arredondada(distancia_m)) = 0
  from rastro2;
select case passou when true then '✓' else '✗ FALHOU' end as st, verificacao, esperado, obtido
  from r order by verificacao;
rollback;
