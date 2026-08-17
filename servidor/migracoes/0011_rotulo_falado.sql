-- 0011 — O talk group ganha um nome que se pode DIZER.
--
-- **O problema.** O agente vai abrir transmissão pela voz: "guarnição 3 na
-- escuta". Para isso o aparelho precisa resolver "guarnição 3" em um `uuid` de
-- `talk_groups` — e hoje não há por onde. A tabela tem `nome` ('GTA-3 Alfa'), que
-- é rótulo de tela, não de fala.
--
-- **Por que NÃO derivar o número do `nome`.** A tentação é procurar o dígito por
-- substring. Isso quebra de três formas, e todas em campo:
--
--   'GTA-3 Alfa'      → casaria "3" por acidente do prefixo da viatura
--   'Setor 3 Bravo'   → casaria "3", mas é outro grupo
--   'Operação 13'     → "1" e "3" casariam com dois grupos diferentes
--
-- Um agente dizendo "guarnição 3" abriria o canal errado, e o erro só apareceria
-- quando alguém respondesse — ou não respondesse. Rótulo falado é **dado**, não
-- derivação de string.
--
-- **Unicidade por unidade, não global.** Dois batalhões podem ter, cada um, a sua
-- "guarnição 3", e isso é normal — o que não pode existir é ambiguidade dentro da
-- unidade em que o agente opera. `unique (unit_id, rotulo_falado)` é exatamente
-- essa regra, e o banco a sustenta: um cadastro que tente duplicar falha na
-- escrita, não na hora em que um agente pedir apoio.
--
-- **Normalização mora no cliente, e a coluna guarda a forma canônica.** O
-- casamento é contra léxico fechado depois de normalizar (minúsculas, sem
-- acento, espaços colapsados) — regra que já existe no roteador de intenções. A
-- coluna guarda o texto já nessa forma para o `unique` valer sobre o que de fato
-- se compara: sem isso, 'Guarnição 3' e 'guarnicao 3' passariam pelo índice como
-- distintos e colidiriam na hora do casamento.
--
-- **Nulo é permitido, e significa "não alcançável por voz".** Grupo sem rótulo
-- falado continua existindo e funcionando por toque — só não é endereçável pela
-- fala. Tornar a coluna obrigatória exigiria inventar rótulo para todo grupo
-- legado, e rótulo inventado é o que produz o canal errado.

alter table public.talk_groups
  add column if not exists rotulo_falado text;

comment on column public.talk_groups.rotulo_falado is
  'Como o agente CHAMA este grupo em voz alta, já normalizado (minúsculas, sem '
  'acento, espaços colapsados). Ex.: "guarnicao 3". NULL = não endereçável por '
  'voz. Nunca derivar de `nome`: ver o cabeçalho de 0011.';

-- Índice único parcial: `NULL` não colide com `NULL` no Postgres, mas o parcial
-- deixa a intenção explícita e mantém o índice menor quando poucos grupos têm
-- rótulo.
create unique index if not exists talk_groups_rotulo_falado_idx
  on public.talk_groups (unit_id, rotulo_falado)
  where rotulo_falado is not null;

-- **A função que o cliente chama, e por que ela existe em vez de um `select`.**
--
-- O aparelho precisa do mapa {rótulo → id} dos grupos DO AGENTE. Um `select`
-- direto em `talk_groups` já é limitado pela policy `talk_groups_read`
-- (0004:82-84), então funcionaria — mas devolveria a tabela inteira daquela
-- unidade, incluindo grupos de que o agente não é membro. Para resolver fala em
-- canal, o que interessa é **onde ele pode falar**, e isso é `memberships`.
--
-- `SECURITY DEFINER` **sem parâmetro de identidade**: quem pergunta sai do JWT,
-- por `private.meus_talk_groups()` (0004:25), que por sua vez usa
-- `private.current_agent_id()`. Função que recebe a identidade do solicitante é
-- proibição dura deste projeto — com ela, qualquer autenticado enumeraria os
-- grupos de qualquer outro.
--
-- Reusa `meus_talk_groups` em vez de rejuntar `memberships`: uma única definição
-- de "os grupos do agente", num lugar só. Duas definições divergem, e a que
-- divergir aqui vira canal aberto para quem não é membro.
--
-- `search_path = ''` e nomes qualificados, como as demais funções DEFINER deste
-- servidor — sem isso, um schema no caminho do chamador pode sequestrar a
-- resolução de nomes dentro de uma função que roda com privilégio do dono.
create or replace function public.meus_rotulos_falados()
returns table (talk_group_id uuid, rotulo_falado text, nome text)
language sql
security definer
set search_path = ''
stable
as $$
  select tg.id, tg.rotulo_falado, tg.nome
    from public.talk_groups tg
   where tg.id in (select private.meus_talk_groups())
     and tg.rotulo_falado is not null
$$;

revoke all on function public.meus_rotulos_falados() from public, anon;
grant execute on function public.meus_rotulos_falados() to authenticated;

-- Semente do piloto: casa com `servidor/seed_piloto.sql`, onde o grupo
-- '22222222-0000-0000-0000-000000000001' é o 'GTA-3 Alfa'. O rótulo é o que a
-- guarnição já diz no rádio, não uma tradução do nome de tela.
update public.talk_groups
   set rotulo_falado = 'guarnicao 3'
 where id = '22222222-0000-0000-0000-000000000001'
   and rotulo_falado is null;
