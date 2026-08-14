// Edge Function `transmit` — recebe uma transmissão, resolve destinatários,
// persiste e difunde.
//
// Duas regras que não podem ser afrouxadas:
//
//  1. **Idempotência por `transmission_id`.** O id vem do cliente. Se já existe,
//     devolve o estado atual em vez de criar outra: retry após queda de rede não
//     pode duplicar uma transmissão.
//  2. **`allSettled`, nunca `all`.** Falha de FCM não pode abortar a entrega por
//     WebSocket. Fan-out parcial entregue vale infinitamente mais que fan-out
//     completo abortado.

import { createClient } from 'jsr:@supabase/supabase-js@2'

// Raios por prioridade, configuráveis por unidade — densidade urbana e rural
// exigem valores diferentes.
const RAIO_POR_PRIORIDADE: Record<number, number> = {
  1: 5000, // P1 emergência: 5 km + batalhão
  2: 2000, // P2 apoio: 2 km + talk group
  3: 0,    // P3 informativo: só o talk group
}

Deno.serve(async (req) => {
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
  )

  const body = await req.json()
  const { id, author_agent_id, talk_group_id, tipo, prioridade, origem, duracao_ms } = body

  // 1) Idempotência.
  const { data: existente } = await supabase
    .from('transmissions').select('id').eq('id', id).maybeSingle()
  if (existente) {
    const { count } = await supabase
      .from('deliveries').select('*', { count: 'exact', head: true })
      .eq('transmission_id', id).not('delivered_at', 'is', null)
    return Response.json({ destinatarios: count ?? 0, idempotente: true })
  }

  // 2) Resolução de destinatários — SEMPRE no servidor.
  let destinatarios: string[] = []
  if (tipo === 'ptt') {
    const { data } = await supabase
      .from('memberships').select('agent_id').eq('talk_group_id', talk_group_id)
    destinatarios = (data ?? []).map((m) => m.agent_id).filter((a) => a !== author_agent_id)
  } else {
    const raio = RAIO_POR_PRIORIDADE[prioridade] ?? 0
    const { data } = await supabase.rpc('agentes_no_raio', {
      lon: origem.lon, lat: origem.lat, raio_m: raio, excluir: author_agent_id,
    })
    destinatarios = (data ?? []).map((a: { agent_id: string }) => a.agent_id)
  }

  // 3) Transação: transmissão + entregas.
  const expira = new Date(Date.now() + 24 * 3600 * 1000).toISOString()
  const { error } = await supabase.from('transmissions').insert({
    id, author_agent_id, talk_group_id, tipo, prioridade, duracao_ms,
    origem_geom: origem ? `SRID=4326;POINT(${origem.lon} ${origem.lat})` : null,
    raio_m: RAIO_POR_PRIORIDADE[prioridade] ?? null,
    expira_em: expira,
  })
  if (error) return Response.json({ erro: error.message }, { status: 500 })

  if (destinatarios.length > 0) {
    await supabase.from('deliveries').insert(
      destinatarios.map((agent_id) => ({ transmission_id: id, agent_id })),
    )
  }

  // 4) Fan-out. Parcial entregue > completo abortado.
  const canal = supabase.channel(`tg-${talk_group_id}`)
  await Promise.allSettled([
    canal.send({ type: 'broadcast', event: 'transmissao.nova', payload: { id, tipo, prioridade } }),
    enviarFcm(supabase, destinatarios, id, prioridade),
  ])

  return Response.json({ destinatarios: destinatarios.length })
})

/** FCM de alta prioridade alcança quem está em Standby (atravessa o Doze). */
async function enviarFcm(
  supabase: ReturnType<typeof createClient>,
  agentes: string[],
  transmissionId: string,
  prioridade: number,
) {
  if (agentes.length === 0) return
  const { data } = await supabase
    .from('agents').select('fcm_token').in('id', agentes).not('fcm_token', 'is', null)
  const tokens = (data ?? []).map((a: { fcm_token: string }) => a.fcm_token)
  // Deduplicação por transmission_id no cliente: uma transmissão pode chegar
  // pelos dois caminhos (WebSocket e FCM).
  await Promise.allSettled(
    tokens.map((t) => fetch(Deno.env.get('FCM_ENDPOINT')!, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${Deno.env.get('FCM_TOKEN')}` },
      body: JSON.stringify({
        to: t,
        priority: 'high',
        data: { transmission_id: transmissionId, prioridade: String(prioridade) },
      }),
    })),
  )
}
