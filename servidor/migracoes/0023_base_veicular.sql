-- ─────────────────────────────────────────────────────────────────────────────
-- 0023 — Base de consulta veicular: a resposta que não pode ser inventada
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O QUE ESTA MIGRAÇÃO EXISTE PARA IMPEDIR
--
-- `ClaryonIntentExecutor` recusa a consulta de placa hoje, e a recusa é honesta:
-- *"Sem base, a resposta honesta é dizer que não dá — jamais um 'sem restrição'
-- inventado."* Esta migração dá a base, e o desenho inteiro parte de uma única
-- pergunta: **o que impede o aparelho de dizer "sem restrição" quando ele não
-- sabe?**
--
-- Num contexto de abordagem, "sem restrição" é a frase que libera o veículo. Dita
-- por engano sobre um carro roubado, ela não é um defeito de software — é o agente
-- devolvendo a chave para quem ele deveria prender. Por isso "sem restrição" aqui
-- **não é o estado de repouso do sistema**: é um valor que alguém teve de escrever
-- na base, sob `not null`, e que só chega ao aparelho dentro de uma resposta que
-- também declara de qual base veio.
--
-- As quatro decisões que sustentam isso:
--
-- 1. **A função nunca devolve conjunto vazio.** Ela devolve SEMPRE exatamente uma
--    linha, com `situacao` explícita. Conjunto vazio é a origem clássica do "sem
--    restrição" inventado: o cliente recebe zero linhas, não encontra restrição
--    nenhuma e conclui que está limpo. Aqui "não encontrada" é uma AFIRMAÇÃO do
--    servidor, não a ausência de uma.
--
-- 2. **`restricao` é `not null`.** Um veículo cadastrado sem situação declarada
--    obrigaria a função a escolher um padrão, e o padrão seria "limpo". A base é
--    obrigada a se comprometer, ou o `insert` falha.
--
-- 3. **Toda resposta carrega `procedencia`.** Inclusive a que não achou nada. Ver
--    o bloco sobre demonstração × oficial, abaixo.
--
-- 4. **O solicitante vem do JWT.** Regra dura do `CLAUDE.md` §2, e aqui ela vale
--    por um motivo próprio: consulta veicular com solicitante em parâmetro é uma
--    porta para consultar em nome de outro — o log incrimina quem não consultou, e
--    a corregedoria passa a ter prova falsa com aparência de registro.
--
-- POR QUE A TABELA VIVE EM `private`, E NÃO EM `public` COM RLS
--
-- Medi antes de escrever: as seis tabelas de `private` deste projeto
-- (`acessos_a_posicao`, `turnos`, as partições de `trilha_de_posicao`) têm RLS
-- **desligada** e **zero políticas**. O controle nelas não é RLS — é o schema não
-- exposto ao PostgREST somado à ausência de GRANT, com a função `security definer`
-- como única porta. RLS é o padrão das tabelas de `public`, que o PostgREST
-- alcança.
--
-- Uma tabela de veículos em `public` com RLS "authenticated lê" seria exatamente o
-- desenho errado: o PostgREST publicaria `/rest/v1/veiculos`, e um `GET` devolveria
-- **a base inteira** — a lista de todos os veículos com restrição de roubo da
-- corporação, num arquivo, sem passar pelo log de acesso. O dump em massa é o risco
-- característico desta tabela, e RLS por linha não o impede: cada linha é
-- legítima; o que não é legítimo é levá-las todas.
--
-- **Esta migração mesmo assim liga RLS**, divergindo das outras seis. Não é
-- cerimônia: sem GRANT, RLS não muda nada hoje; muda no dia em que alguém escrever
-- `grant select on private.veiculos to authenticated` achando que resolve um
-- problema de leitura. Com RLS ligada e nenhuma política, esse GRANT continua
-- negando. É a tranca que sobrevive ao erro futuro, e custa uma linha. A função
-- `security definer` pertence a `postgres`, dona da tabela, e portanto contorna RLS
-- — sem `force row level security`, de propósito.

begin;

-- ─────────────────────────────────────────────────────────────────────────────
-- A base
-- ─────────────────────────────────────────────────────────────────────────────
--
-- **Sem coluna de proprietário, e a ausência é a decisão.** A pergunta operacional
-- da abordagem é "este veículo tem restrição?", nunca "de quem é este veículo".
-- Uma coluna de nome transformaria a base num índice de pessoas por placa — dado
-- pessoal que o caso de uso não precisa, mantido por um sistema que roda no celular
-- de quem está na rua. O que não é coletado não vaza.
--
-- **Sem índice por `restricao`, e a ausência também é a decisão** — mesmo
-- raciocínio do índice geográfico recusado em `0019`. A consulta legítima é sempre
-- placa → situação, com a placa em mãos. Um índice por restrição barateia a
-- pergunta inversa, "me dê todos os veículos com alerta de roubo", que é a
-- extração em massa que este desenho recusa. A chave primária serve a consulta
-- legítima e só ela.
create table if not exists private.veiculos (
  -- Normalizada na escrita e na leitura: A–Z e 0–9, sem hífen nem espaço. As duas
  -- formas brasileiras, conferidas contra `PlacaValidator` (core-agent), que é
  -- quem já normaliza no cliente — as duas pontas precisam concordar ou a consulta
  -- erra por formatação.
  placa         text primary key
                check (placa ~ '^[A-Z]{3}[0-9][A-Z][0-9]{2}$'   -- Mercosul ABC1D23
                    or placa ~ '^[A-Z]{3}[0-9]{4}$'),            -- antiga  ABC1234

  -- **O campo que separa demonstração de verdade.** `not null`: não existe linha
  -- que se omita sobre a própria origem.
  procedencia   text not null check (procedencia in ('demonstracao', 'oficial')),

  -- Qual base, por extenso. É o que a corregedoria pergunta primeiro quando uma
  -- consulta é contestada: "isso veio de onde?".
  fonte         text not null,

  -- `not null` deliberado — ver a decisão 2 no cabeçalho. `sem_restricao` é um
  -- valor que alguém escreveu, nunca um padrão que o sistema assumiu.
  restricao     text not null check (restricao in (
                  'sem_restricao',
                  'roubo_furto',
                  'bloqueio_judicial',
                  'apreensao',
                  'licenciamento_vencido',
                  'clonagem_suspeita'
                )),

  marca_modelo  text,
  cor           text,
  ano           smallint,
  atualizado_em timestamptz not null default now()
);

comment on table private.veiculos is
  'Base de consulta veicular. Vive em private e a única porta é public.consultar_placa '
  '(security definer): em public com RLS, o PostgREST publicaria a tabela e um GET '
  'devolveria a base inteira sem passar pelo log. Sem coluna de proprietário e sem '
  'índice por restricao — as duas ausências são decisões, não esquecimento.';

comment on column private.veiculos.procedencia is
  'demonstracao | oficial. NOT NULL para que nenhuma linha possa ser apresentada sem '
  'declarar sua origem. Ver private.procedencia_da_base().';

comment on column private.veiculos.restricao is
  'NOT NULL de propósito: sem isto a função teria de escolher um padrão para o '
  'veículo cadastrado sem situação, e o padrão seria "limpo" — o "sem restrição" '
  'inventado que este arquivo inteiro existe para impedir.';

-- Segunda tranca. Ver o cabeçalho: hoje não muda nada, porque não há GRANT.
alter table private.veiculos enable row level security;

-- ─────────────────────────────────────────────────────────────────────────────
-- Demonstração × oficial: por que o flag está na RESPOSTA e não só na linha
-- ─────────────────────────────────────────────────────────────────────────────
--
-- A regra de produto é que o cliente **consiga distinguir** base de demonstração de
-- base real. Um flag só na linha encontrada não basta, e o motivo é o caso que mais
-- importa: **a placa que não está na base**.
--
-- "Não encontrada" numa base oficial é informação — o veículo não tem registro de
-- restrição. "Não encontrada" numa semente de seis linhas não é informação
-- nenhuma: quase tudo está fora dela. As duas respostas têm o mesmo texto e
-- significados opostos, e a linha que as distinguiria não existe justamente no caso
-- em que não houve linha. Por isso `procedencia` descreve **a base consultada**, é
-- devolvida em toda resposta, e não depende de ter havido acerto.
--
-- **A regra é assimétrica, e a assimetria é o ponto:** `oficial` exige prova
-- positiva — base não vazia e nenhuma linha de demonstração dentro dela. Qualquer
-- outro estado (vazia, mista, valor inesperado) responde `demonstracao`. Errar para
-- o lado de "isto é demonstração" degrada a confiança numa resposta boa; errar para
-- o outro lado apresenta a semente como verdade oficial. Só o segundo erro liberta
-- um carro roubado.
create or replace function private.procedencia_da_base()
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select case
    -- Base vazia não é oficial: é base ausente.
    when not exists (select 1 from private.veiculos) then 'demonstracao'
    -- Uma única linha de demonstração contamina a autoridade do conjunto, porque
    -- é o conjunto que responde pelas placas que não estão nele.
    when exists (select 1 from private.veiculos where procedencia <> 'oficial') then 'demonstracao'
    else 'oficial'
  end
$$;

comment on function private.procedencia_da_base() is
  'Procedência do CONJUNTO, não da linha — é ela que qualifica a resposta "não '
  'encontrada", onde não há linha para carregar o flag. Assimétrica de propósito: '
  '"oficial" exige prova positiva; todo o resto degrada para "demonstracao".';

-- ─────────────────────────────────────────────────────────────────────────────
-- O log: o ATO, e deliberadamente menos que em 0017
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Consulta de placa é dado sensível e a corregedoria precisa saber quem consultou o
-- quê. Mas o `ROADMAP.md` registra o risco de a auditoria virar o segundo banco de
-- vigilância, com mitigação obrigatória: **nunca gravar a resposta devolvida**.
--
-- Aqui isso vai um passo além de `0017`. Além de não gravar restrição, marca ou
-- modelo, **não se grava sequer se a placa foi encontrada**. Um booleano
-- "encontrou" transformaria o log num índice consultável de quais placas estão na
-- base de roubo — a informação mais sensível do sistema, reconstruída a partir do
-- registro criado para protegê-la. A placa consultada fica, porque ela é a
-- PERGUNTA (mesmo critério de `alvo_indicativo` em `0017`); tudo que veio de volta,
-- não.
create table if not exists private.consultas_de_placa (
  id               bigserial primary key,
  -- Nunca de parâmetro. Log com autor forjável é prova falsa.
  autor_agent_id   uuid not null references public.agents(id),
  -- A pergunta, como foi feita (já normalizada). Nula quando nem placa era.
  placa_consultada text,
  em               timestamptz not null default now()
);

comment on table private.consultas_de_placa is
  'Registro do ATO de consultar placa — nunca da resposta, e nem mesmo de se houve '
  'acerto: um booleano "encontrou" reconstruiria a base de roubo a partir do log. '
  'Autor sempre de private.current_agent_id().';

-- A varredura da retenção é por idade; a apuração, por autor.
create index if not exists consultas_de_placa_por_data_idx
  on private.consultas_de_placa (em);
create index if not exists consultas_de_placa_por_autor_idx
  on private.consultas_de_placa (autor_agent_id, em desc);

alter table private.consultas_de_placa enable row level security;

-- ─────────────────────────────────────────────────────────────────────────────
-- A porta única
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Assinatura: `consultar_placa(placa text)`. **O solicitante não está aqui** — sai
-- de `private.current_agent_id()`, que desde `0014` devolve nulo para agente
-- inativo. Revogação institucional fecha esta porta junto com as outras, sem código
-- novo.
create or replace function public.consultar_placa(placa text)
returns table (
  -- 'encontrado' | 'nao_encontrada' | 'placa_invalida' | 'base_indisponivel'
  situacao         text,
  procedencia      text,
  fonte            text,
  placa_consultada text,
  restricao        text,
  marca_modelo     text,
  cor              text,
  ano              smallint
)
language plpgsql
volatile                -- volatile porque ESCREVE o log. `stable` mataria o registro em silêncio.
security definer
set search_path = ''
as $$
declare
  v_eu    uuid := private.current_agent_id();
  v_placa text;
  v_proc  text;
  v_fonte text;
  v_linha private.veiculos%rowtype;
begin
  -- Agente ativo, ou nada. Cobre os três casos de uma vez: `anon` (sem GRANT nem
  -- chega aqui), usuário autenticado sem linha em `agents`, e agente revogado.
  if v_eu is null then
    raise exception 'sem agente ativo' using errcode = '42501';
  end if;

  -- Mesma normalização de `PlacaValidator.normalizar` no cliente: as duas pontas
  -- precisam concordar, senão "ABC-1D23" não acha "ABC1D23".
  v_placa := upper(regexp_replace(coalesce(consultar_placa.placa, ''), '[^a-zA-Z0-9]', '', 'g'));

  -- Registra ANTES de responder, como em `0017`: um erro no meio deixaria consulta
  -- atendida sem rastro, e o rastro que falta é sempre o da consulta que alguém
  -- quis esconder. Inclusive a placa inválida — varredura por tentativa e erro é
  -- exatamente o que não pode sumir do log.
  insert into private.consultas_de_placa (autor_agent_id, placa_consultada)
  values (v_eu, nullif(v_placa, ''));

  v_proc := private.procedencia_da_base();

  -- Formato antes de tudo: é uma afirmação sobre a ENTRADA, verdadeira
  -- independentemente do estado da base. O STT erra placa com frequência, e
  -- "não encontrada" para um ruído faria o agente concluir que o veículo está
  -- limpo quando ninguém chegou a consultar coisa alguma.
  if v_placa !~ '^[A-Z]{3}[0-9][A-Z][0-9]{2}$' and v_placa !~ '^[A-Z]{3}[0-9]{4}$' then
    return query select
      'placa_invalida'::text, v_proc, null::text, nullif(v_placa, ''),
      null::text, null::text, null::text, null::smallint;
    return;
  end if;

  -- Base vazia responde "indisponível", nunca "não encontrada". A semente que não
  -- foi aplicada é uma falha de implantação, e ela tem de soar como falha — não
  -- como um veículo sem restrição.
  if not exists (select 1 from private.veiculos) then
    return query select
      'base_indisponivel'::text, v_proc, null::text, v_placa,
      null::text, null::text, null::text, null::smallint;
    return;
  end if;

  select * into v_linha from private.veiculos v where v.placa = v_placa;

  if not found then
    -- A fonte do CONJUNTO: "não encontrada" é uma afirmação da base inteira, e quem
    -- ouvir precisa saber de qual base.
    select string_agg(distinct v.fonte, ' + ') into v_fonte from private.veiculos v;
    return query select
      'nao_encontrada'::text, v_proc, v_fonte, v_placa,
      null::text, null::text, null::text, null::smallint;
    return;
  end if;

  return query select
    'encontrado'::text,
    -- A procedência da LINHA quando há linha: uma base mista devolve `oficial` para
    -- o registro oficial que ela de fato tem, e `demonstracao` para o de mentira.
    v_linha.procedencia,
    v_linha.fonte,
    v_placa,
    v_linha.restricao,
    v_linha.marca_modelo,
    v_linha.cor,
    v_linha.ano;
end;
$$;

comment on function public.consultar_placa(text) is
  'Consulta veicular. Devolve SEMPRE exatamente uma linha com situacao explícita — '
  'conjunto vazio é a origem clássica do "sem restrição" inventado. O solicitante '
  'vem do JWT, jamais de parâmetro. Toda resposta declara a procedencia da base.';

revoke all on function public.consultar_placa(text) from public, anon;
grant execute on function public.consultar_placa(text) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Retenção — no job que já roda
-- ─────────────────────────────────────────────────────────────────────────────
--
-- **Sem prazo novo, e isto é aplicação da regra que `0019` escreveu**: "prazo
-- espalhado por cinco lugares é prazo que diverge no primeiro ajuste". O log de
-- placa é da mesma classe do log de posição — quem perguntou o quê — então divide
-- `private.prazo_do_log_de_acesso()` em vez de inaugurar um segundo prazo que
-- alguém ajustaria pela metade.
--
-- **Sem job novo, pelo mesmo motivo.** `cron.job` já tem `retencao_claryon`
-- (03:17, ativo — conferido). Um segundo agendamento seria outro lugar para
-- desalinhar. O passo entra em `executar_retencao`, isolado no seu próprio bloco
-- `begin/exception` como todos os outros: `0019` registra que um bloco único
-- abortava a função inteira e deixava as partições vencidas de pé.
--
-- A função é reproduzida na íntegra porque `create or replace` substitui o corpo
-- todo. O texto abaixo foi extraído da definição VIVA em `pg_proc` antes do diff,
-- não do arquivo `0019` — o arquivo poderia estar defasado em relação ao banco.
create or replace function private.executar_retencao()
returns text
language plpgsql volatile security definer set search_path = ''
as $$
declare
  v_particoes int := 0;
  v_turnos int;
  v_acessos int;
  v_transmissoes int;
  v_placas int;
  r record;
begin
  -- Amanhã precisa existir antes de amanhã.
  perform private.garantir_particao_da_trilha((now() + interval '1 day')::date);

  -- DROP, não DELETE: instantâneo e sem resto.
  -- **Partições de verdade, via `pg_inherits`** — não `relname like 'trilha_2%'`.
  -- `pg_class` também guarda ÍNDICES, e os das partições se chamam
  -- `trilha_20260819_agent_id_em_idx`: o filtro por nome os pegaria e o `drop
  -- table` falharia neles. O defeito só apareceria no primeiro vencimento, 90 dias
  -- depois de ninguém mais estar olhando — e o prazo simplesmente não seria
  -- executado. Foi um erro meu na consulta de conferência que o revelou aqui.
  for r in
    select c.relname
      from pg_inherits h
      join pg_class c on c.oid = h.inhrelid
     where h.inhparent = 'private.trilha_de_posicao'::regclass
       and c.relkind = 'r'
       and to_date(right(c.relname, 8), 'YYYYMMDD') < (now() - private.prazo_da_trilha())::date
  loop
    execute format('drop table private.%I', r.relname);
    v_particoes := v_particoes + 1;
  end loop;

  -- **Cada passo isolado.** A primeira versão era um bloco só, e a exclusão de
  -- `transmissions` estourou por chave estrangeira de `deliveries` — o que abortava
  -- a função inteira e deixava as partições vencidas de pé. Job de retenção que não
  -- executa NADA porque o último passo falhou é pior que job nenhum: ele figura no
  -- relatório como existente e o prazo continua não sendo cumprido.
  begin
    delete from private.acessos_a_posicao
     where em < now() - private.prazo_do_log_de_acesso();
    get diagnostics v_acessos = row_count;
  exception when others then
    v_acessos := -1;
    raise warning 'retencao do log de acesso falhou: %', sqlerrm;
  end;

  -- O log de consulta veicular, mesmo prazo e mesmo isolamento.
  begin
    delete from private.consultas_de_placa
     where em < now() - private.prazo_do_log_de_acesso();
    get diagnostics v_placas = row_count;
  exception when others then
    v_placas := -1;
    raise warning 'retencao do log de placa falhou: %', sqlerrm;
  end;

  begin
    v_turnos := private.encerrar_turnos_inativos();
  exception when others then
    v_turnos := -1;
    raise warning 'encerramento de turnos falhou: %', sqlerrm;
  end;

  -- `expira_em` de `transmissions` era campo lógico sem executor: a linha dizia
  -- que expirava e continuava lá.
  --
  -- As entregas saem ANTES, e não por elegância: `deliveries` referencia
  -- `transmissions`, e apagar o pai primeiro levanta 23503. Sem esta ordem, o
  -- prazo de transmissão nunca seria executado.
  begin
    delete from public.deliveries d
     using public.transmissions t
     where d.transmission_id = t.id
       and t.expira_em is not null and t.expira_em < now();
    delete from public.transmissions where expira_em is not null and expira_em < now();
    get diagnostics v_transmissoes = row_count;
  exception when others then
    v_transmissoes := -1;
    raise warning 'retencao de transmissions falhou: %', sqlerrm;
  end;

  return format(
    'particoes_removidas=%s acessos_removidos=%s placas_removidas=%s turnos_encerrados=%s transmissoes_expiradas=%s',
    v_particoes, v_acessos, v_placas, v_turnos, v_transmissoes
  );
end;
$$;

commit;
