// Edge Function `ack` — registra entrega, reprodução e reconhecimento ativo.
//
// É o que sustenta a confirmação falada "Quatro unidades receberam": sem o
// registro por destinatário, o emissor só saberia que enviou, não que chegou.

import { createClient } from 'jsr:@supabase/supabase-js@2'

// ── Identidade: do JWT, nunca do corpo ────────────────────────────────────────
//
// Estas funções recebiam o agente como PARÂMETRO — `author_agent_id` aqui,
// `agent_id` no ack, `solicitante_id` no locate (apagado). Com o corpo mandando
// quem é o autor, qualquer portador do APK escrevia transmissão no nome de
// qualquer agente, confirmava leitura no nome de qualquer agente, e — no locate —
// trilaterava a posição de qualquer par, reabrindo na borda o que a migração 0006
// fechou no banco.
//
// É a regra dura do projeto: "função de servidor que receba como parâmetro a
// identidade de quem pergunta" é proibida. A identidade sai do JWT do chamador e
// é resolvida contra `agents.auth_user_id` — o MESMO caminho que
// `private.current_agent_id()` usa no banco, para não existirem duas verdades
// sobre quem é o agente.
//
// NÃO se usa `user_metadata`: no Supabase Auth ele é editável pelo próprio
// usuário. Derivar identidade de lá seria trocar um buraco por outro com nome
// melhor.
async function agenteDoJwt(req: Request): Promise<string | null> {
  const authorization = req.headers.get('Authorization')
  if (!authorization) return null

  const comoUsuario = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_ANON_KEY')!,
    { global: { headers: { Authorization: authorization } } },
  )

  const { data: { user }, error } = await comoUsuario.auth.getUser()
  if (error || !user) return null

  const { data } = await comoUsuario
    .from('agents').select('id').eq('auth_user_id', user.id).maybeSingle()
  return data?.id ?? null
}


Deno.serve(async (req) => {
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
  )
  const agent_id = await agenteDoJwt(req)
  if (!agent_id) return Response.json({ erro: 'nao_autenticado' }, { status: 401 })

  // `agent_id` NÃO sai do corpo: confirmar leitura no nome de outro agente
  // produziria prova falsa de que a mensagem foi recebida.
  const { transmission_id, estagio, ack_tipo } = await req.json()

  const campos: Record<string, unknown> = {}
  if (estagio === 'delivered') campos.delivered_at = new Date().toISOString()
  if (estagio === 'played') campos.played_at = new Date().toISOString()
  if (estagio === 'ack') {
    campos.ack_at = new Date().toISOString()
    campos.ack_tipo = ack_tipo
  }

  const { error } = await supabase
    .from('deliveries').update(campos)
    .eq('transmission_id', transmission_id).eq('agent_id', agent_id)

  return error
    ? Response.json({ erro: error.message }, { status: 500 })
    : Response.json({ ok: true })
})
