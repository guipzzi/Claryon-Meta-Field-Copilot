-- ─────────────────────────────────────────────────────────────────────────────
-- 0015 — Autoria conferível: fecha a personificação entre membros do mesmo grupo
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O RESIDUAL QUE FICOU, E POR QUE ELE EXISTIA
--
-- A migração `0013` tirou o indicativo do fio: o receptor passou a resolver o nome
-- contra o cadastro do grupo, e id fora do cadastro virou "origem não confirmada".
-- Isso fechou o forjador de fora — que com o canal privado nem entra — mas deixou um
-- buraco declarado: **um membro do grupo podia reivindicar o id de outro membro**.
-- Ele estaria no cadastro, resolveria, e a tela mostraria o nome do colega.
--
-- O caminho óbvio de fechar — o servidor recusar o anúncio — continua impossível: a
-- política de `realtime.messages` recebe `payload` NULO (medido em 18/08, ver
-- `DECISIONS.md`). Ela não enxerga o que está sendo dito.
--
-- O QUE O SERVIDOR JÁ SABIA, E NINGUÉM PERGUNTAVA
--
-- `pedir_canal` (`0005`) grava `floor_grants.agent_id` a partir de
-- `private.current_agent_id()` — **do JWT, nunca de parâmetro**. Ou seja: para
-- transmitir com um `transmissao_id`, o agente teve de pedir o piso, e o servidor
-- carimbou quem ele é. Ninguém consegue um piso em nome de outro.
--
-- Então a prova de autoria já existia; faltava uma porta para consultá-la. É esta.
--
-- POR QUE NÃO CUSTA LATÊNCIA A QUEM FALA
--
-- A alternativa que eu tinha descartado era mediar o **envio**, o que poria uma ida e
-- volta antes do anúncio — dentro do orçamento de 1 200 ms até o BIP, que a Fase 2 já
-- deixou apertado. Aqui a conferência é no **receptor**, depois do anúncio: o áudio
-- toca imediatamente e o rótulo se confirma em seguida. Quem fala não paga nada.
--
-- Calar a voz enquanto confere seria a falha oposta e mais cara: pode ser um pedido
-- de apoio real, e áudio atrasado por conferência de rótulo é o tipo de zelo que mata.
--
-- AS DUAS JANELAS
--
-- Durante a fala a prova está em `floor_grants`. Encerrada, o piso é liberado e a
-- prova passa a ser `transmissions.author_agent_id`, que a Edge Function `transmit`
-- escreve derivando o autor da sessão (e cuja escrita direta é barrada por
-- `transmissions_no_direct_insert`). Conferir as duas cobre a janela inteira.

begin;

create or replace function public.autor_da_transmissao(p_transmissao_id uuid)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  -- Durante a fala: o piso concedido.
  select f.agent_id
    from public.floor_grants f
   where f.transmissao_id = p_transmissao_id
     -- A porta: só responde a quem é do mesmo grupo. Sem isto, um agente
     -- consultaria a autoria de transmissões de qualquer guarnição — e uuid de
     -- transmissão viaja no anúncio, não é segredo.
     and exists (
       select 1 from public.memberships m
        where m.talk_group_id = f.talk_group_id
          and m.agent_id = (select private.current_agent_id())
     )

  union all

  -- Depois dela: o registro, escrito pelo servidor.
  select t.author_agent_id
    from public.transmissions t
   where t.id = p_transmissao_id
     and exists (
       select 1 from public.memberships m
        where m.talk_group_id = t.talk_group_id
          and m.agent_id = (select private.current_agent_id())
     )

  limit 1
$$;

grant execute on function public.autor_da_transmissao(uuid) to authenticated;

commit;
