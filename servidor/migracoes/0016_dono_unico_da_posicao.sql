-- ─────────────────────────────────────────────────────────────────────────────
-- 0016 — Dono único da posição: `publicar_posicao` passa a ser a ÚNICA porta
-- ─────────────────────────────────────────────────────────────────────────────
--
-- O BURACO, DEMONSTRADO E NÃO SUPOSTO
--
-- `positions_insert` e `positions_update` (`0002_rls.sql:112,116`) nunca foram
-- removidas. A `0010` reescreveu só a de leitura. Com elas de pé, um agente
-- autenticado escreve a própria linha por PostgREST e **contorna todas as
-- validações** de `publicar_posicao`: domínio da coordenada, recusa de NaN, e o
-- carimbo de tempo.
--
-- Explorado em 18/08 com o JWT do agente de teste, num POST direto:
--
--   updated_at = 2099-01-01, speed_mps = 999  → gravou.
--
-- A consequência é pior que escrita indevida. Toda política de frescor do sistema
-- pergunta `updated_at > now() - interval '5 minutes'` (`0003:35`, `0007:44`,
-- `0009:50`). Uma linha datada de 2099 é **permanentemente fresca**: o agente
-- apareceria para a guarnição inteira como atual, numa posição que ele escolheu,
-- para sempre. Num sistema onde a posição decide quem vai atender a ocorrência,
-- isso não é vazamento — é desinformação operacional.
--
-- POR QUE FECHAR AQUI E NÃO SÓ NO CLIENTE
--
-- O item do roadmap é "`ColetorDePosicao` como único escritor". Um escritor único
-- **no aplicativo** não vale nada se o servidor aceita escrita de qualquer um que
-- tenha o APK e um token — e este projeto já mediu o que acontece quando a porta
-- do servidor fica aberta confiando na disciplina do cliente.
--
-- `publicar_posicao` é `security definer` (`0008:24`), então ela continua
-- escrevendo com os privilégios do dono, sem depender destas políticas.

begin;

drop policy if exists positions_insert on public.agent_positions;
drop policy if exists positions_update on public.agent_positions;

-- Sem política de escrita, RLS nega por padrão. Explícito para quem ler depois:
-- não é esquecimento, é a porta única.
comment on table public.agent_positions is
  'Escrita EXCLUSIVAMENTE por public.publicar_posicao (security definer). As '
  'políticas de INSERT/UPDATE foram removidas na 0016 porque permitiam contornar '
  'a validação de coordenada e forjar updated_at — uma linha datada no futuro fica '
  'permanentemente "fresca" para toda política de obsolescência do sistema.';

commit;
