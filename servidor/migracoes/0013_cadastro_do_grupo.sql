-- ─────────────────────────────────────────────────────────────────────────────
-- 0013 — Cadastro do grupo: quem é quem, para o indicativo parar de ser palpite
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O DEFEITO QUE ISTO DESTRAVA
--
-- O anúncio de fala carrega `indicativo` como **string livre não verificada**. Quem
-- transmite escreve o que quiser, e a tela do colega mostra. Um P1 forjado em nome
-- de outra guarnição toma o canal por desenho, não por falha.
--
-- O caminho limpo seria o servidor recusar o anúncio forjado. **Ele não pode:**
-- medido em 18/08, a política de `realtime.messages` recebe `payload` NULO na
-- checagem — acrescentar `payload is not null` ao `with check` derrubou todo
-- broadcast. Está registrado em `DECISIONS.md`; não repita a hipótese.
--
-- Sobra resolver **no receptor**, contra um cadastro que o servidor filtrou. É o
-- que esta função entrega.
--
-- POR QUE NÃO SERVIU O QUE JÁ EXISTIA
--
-- `HistoricoDoCanal.membros()` deriva de `posicoes_do_grupo`, e por isso só enxerga
-- quem **publicou posição**. Um colega que ainda não publicou ficaria fora do
-- cadastro e seria tratado como desconhecido — o receptor descartaria a fala de um
-- agente legítimo. Pior: `MembroDoCanal.agentId` existe no tipo e é preenchido com
-- `""`, sempre. Campo que nunca foi populado é armadilha esperando chamador.
--
-- O QUE ELA DEVOLVE, E O QUE ELA RECUSA
--
-- Identificação (`agent_id`, `indicativo`) dos membros do grupo — **nunca posição**,
-- que continua saindo só por `posicoes_do_grupo` como grandeza. O solicitante vem de
-- `private.current_agent_id()`, ou seja, do JWT: `CLAUDE.md` §2 proíbe função que
-- receba a identidade de quem pergunta como parâmetro. O grupo é parâmetro porque é
-- **dado da pergunta**, não identidade — e a função recusa grupo de que o chamador
-- não seja membro, devolvendo conjunto vazio.

begin;

create or replace function public.cadastro_do_grupo(talk_group uuid)
returns table (
  agent_id uuid,
  indicativo text
)
language sql
stable
security definer
set search_path = ''
as $$
  select a.id, a.indicativo
    from public.memberships m
    join public.agents a on a.id = m.agent_id
   where m.talk_group_id = talk_group
     -- A porta: só responde a quem é membro do MESMO grupo. Sem esta linha, um
     -- agente autenticado leria o cadastro de qualquer guarnição do sistema
     -- passando o uuid — e uuid de talk group não é segredo, viaja no tópico.
     and exists (
       select 1 from public.memberships eu
        where eu.talk_group_id = talk_group
          and eu.agent_id = (select private.current_agent_id())
     )
$$;

grant execute on function public.cadastro_do_grupo(uuid) to authenticated;

commit;
