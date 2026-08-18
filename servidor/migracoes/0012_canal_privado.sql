-- ─────────────────────────────────────────────────────────────────────────────
-- 0012 — Canal Realtime privado: quem entra em `tg-<uuid>` tem de ser do grupo
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O DEFEITO QUE ISTO FECHA
--
-- Hoje `TransporteRealtime` conecta com `?apikey=<anon>` e o `phx_join` não leva
-- token nenhum. A chave anon está dentro do APK, e o tópico é derivável: qualquer
-- portador do aplicativo entra em `realtime:tg-<uuid>` de **qualquer guarnição** e
-- recebe todos os quadros de voz e todos os indicativos. O ROADMAP chama este item
-- de "maior risco do sistema pelo menor esforço", e a expressão é literal.
--
-- COMO O SUPABASE RESOLVE, CONFIRMADO NA DOC (ver DECISIONS.md, 18/08)
--
-- Canal com `config.private: true` passa a consultar políticas de linha em
-- `realtime.messages`. `realtime.topic()` devolve o tópico da mensagem e
-- `realtime.messages.extension` diz se é `broadcast` ou `presence`. Sem política
-- que autorize, o `phx_join` é **recusado** — que é o comportamento desejado.
--
-- ESTA MIGRAÇÃO É ADITIVA, E POR ISSO PODE ENTRAR ANTES DO APP
--
-- Política em `realtime.messages` só vale para canal **privado**. O app de hoje
-- entra como público e continua funcionando exatamente igual depois daqui. Nada
-- quebra no intervalo entre aplicar isto e o cliente passar a mandar
-- `private: true`, o que permite verificar a política com o par headless antes de
-- mexer no rádio de produção.
--
-- A ORDEM CERTA DE VIRAR A CHAVE, DEPOIS DESTA MIGRAÇÃO
--
--   1. `node servidor/par_headless.mjs --grupo <uuid> --privado` → tem de ENTRAR
--   2. o mesmo, com um agente que não é do grupo             → tem de ser RECUSADO
--   3. só então `TransporteRealtime` passa a mandar `private: true`
--
-- Inverter a ordem deixa o rádio dependendo de uma política nunca exercitada.

begin;

-- ─────────────────────────────────────────────────────────────────────────────
-- O tópico carrega o grupo; a identidade vem do JWT, nunca de parâmetro
-- ─────────────────────────────────────────────────────────────────────────────
--
-- `CLAUDE.md` §2 proíbe função de servidor que receba **a identidade de quem
-- pergunta** como parâmetro — com ela, respostas de um agente trilateram a
-- posição de qualquer outro. Aqui o parâmetro é o **tópico**, que é dado da
-- mensagem e não do solicitante; quem pergunta continua saindo de
-- `private.current_agent_id()`, que lê `auth.uid()`.
--
-- O casamento do formato vem **antes** do cast. `substring(...)::uuid` sobre um
-- tópico arbitrário levanta `invalid input syntax`, e uma política que levanta
-- exceção derruba a conexão em vez de negá-la: o cliente veria erro de servidor
-- onde devia ver "não autorizado". Tópico que não seja `tg-<uuid>` simplesmente
-- não casa com grupo nenhum, e a política nega.
create or replace function private.membro_do_topico(topico text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
      from public.memberships m
     where m.agent_id = (select private.current_agent_id())
       and m.talk_group_id = (
             case
               when topico ~ '^tg-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
               then substring(topico from 4)::uuid
             end
           )
  )
$$;

grant execute on function private.membro_do_topico(text) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- As duas políticas: ouvir e falar no grupo de que se é membro
-- ─────────────────────────────────────────────────────────────────────────────
--
-- `extension` é conferida em ambas. Sem esse recorte a política valeria também
-- para `presence`, e presença revela quem está on-line em qual guarnição — que é
-- exatamente o tipo de vazamento lateral que este projeto trata como defeito, não
-- como detalhe. Presença fica sem política, portanto negada por padrão; quando
-- for necessária, entra num diff próprio e com decisão escrita.

drop policy if exists agente_ouve_o_proprio_grupo on realtime.messages;
create policy agente_ouve_o_proprio_grupo
on realtime.messages
for select
to authenticated
using (
  realtime.messages.extension = 'broadcast'
  and private.membro_do_topico((select realtime.topic()))
);

drop policy if exists agente_fala_no_proprio_grupo on realtime.messages;
create policy agente_fala_no_proprio_grupo
on realtime.messages
for insert
to authenticated
with check (
  realtime.messages.extension = 'broadcast'
  and private.membro_do_topico((select realtime.topic()))
);

commit;
