-- ═══════════════════════════════════════════════════════════════════════════
-- `renovar_canal` passa a conferir PERTENCIMENTO ao talk group.
--
-- ## O buraco, e por que ele não aparecia em teste feliz
--
-- A `0005` conferia membership em UM lugar: `pedir_canal` (`:78-82`). Na hora de
-- pedir, quem não é do grupo é recusado — e é por isso que a proteção parecia
-- completa. Mas o piso não é um instante, é uma janela: enquanto o agente fala,
-- `renovar_canal` estende a concessão a cada poucos segundos, e ele conferia
-- apenas três coisas — `transmissao_id`, `agent_id` e validade. Nenhuma delas
-- muda quando a pessoa é removida da guarnição.
--
-- Consequência observada na bateria de caos de 22/08: um agente removido do talk
-- group no meio da fala **para de ser ouvido** (a política de `realtime.messages`
-- da `0012` derruba a entrega) e **continua detendo o canal**. A guarnição
-- legítima é recusada com "ocupado" por alguém que já não pertence a ela, e o
-- silêncio dura até o TTL vencer — 30 s, com uma P1 como única válvula. Do lado
-- do removido não há sinal nenhum: o broadcast sai com `ack: false`.
--
-- ## Por que APAGAR a concessão e não só recusar a renovação
--
-- Recusar devolveria `false`, o aparelho pararia de falar (correto), e a linha
-- de `floor_grants` ficaria de pé até `expira_em`. Quem paga esse tempo é a
-- guarnição que ficou, e ela não fez nada. Apagar devolve o canal na hora — é a
-- mesma lógica pela qual o TTL existe: uma trava que sobrevive a quem a criou
-- cala gente que não tem como destravá-la.
--
-- ## Arquivo novo, e a `0005` intacta
--
-- Migração é histórico aplicado: reescrever a `0005` faria os ambientes que já a
-- rodaram divergirem em silêncio de qualquer ambiente novo. `create or replace`
-- substitui o corpo da função sem tocar na tabela nem nos `grant`s.
--
-- A identidade continua **não sendo parâmetro** — sai de `private.current_agent_id()`.
-- Ver `CLAUDE.md` §2: função de servidor que recebe quem pergunta é vetor de
-- negação de serviço contra a guarnição.
-- ═══════════════════════════════════════════════════════════════════════════

create or replace function renovar_canal(
  p_transmissao_id uuid,
  p_ttl_segundos integer default 30
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_eu uuid := private.current_agent_id();
  v_grupo uuid;
begin
  if v_eu is null then
    return false;
  end if;

  -- `for update` pelo mesmo motivo de `pedir_canal`: entre ler e apagar não pode
  -- caber a concessão de outro agente.
  select talk_group_id into v_grupo
    from public.floor_grants
   where transmissao_id = p_transmissao_id
     and agent_id = v_eu
     and expira_em > now()
   for update;

  -- Não é seu, ou já venceu. `false` = pare de transmitir.
  if v_grupo is null then
    return false;
  end if;

  -- Saiu do grupo com o piso na mão: o canal volta AGORA, não no TTL.
  if not exists (
    select 1 from public.memberships
     where agent_id = v_eu and talk_group_id = v_grupo
  ) then
    delete from public.floor_grants
     where talk_group_id = v_grupo and transmissao_id = p_transmissao_id;
    return false;
  end if;

  update public.floor_grants
     set expira_em = now() + make_interval(secs => p_ttl_segundos)
   where talk_group_id = v_grupo and transmissao_id = p_transmissao_id;

  return true;
end;
$$;

-- `create or replace` preserva os privilégios da `0005`; repetidos aqui para que
-- este arquivo continue correto se rodado num banco limpo, fora de ordem.
revoke execute on function renovar_canal(uuid, integer) from public, anon;
grant execute on function renovar_canal(uuid, integer) to authenticated, service_role;
