// Edge Function `ack` — registra entrega, reprodução e reconhecimento ativo.
//
// É o que sustenta a confirmação falada "Quatro unidades receberam": sem o
// registro por destinatário, o emissor só saberia que enviou, não que chegou.

import { createClient } from 'jsr:@supabase/supabase-js@2'

Deno.serve(async (req) => {
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
  )
  const { transmission_id, agent_id, estagio, ack_tipo } = await req.json()

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
