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

  const author_agent_id = await agenteDoJwt(req)
  if (!author_agent_id) {
    return Response.json({ erro: 'nao_autenticado' }, { status: 401 })
  }

  const body = await req.json()
  // `author_agent_id` NÃO sai daqui — ver `agenteDoJwt`.
  const { id, talk_group_id, tipo, prioridade, origem, duracao_ms } = body

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
  } else if (origem?.lon != null && origem?.lat != null) {
    const raio = RAIO_POR_PRIORIDADE[prioridade] ?? 0
    const { data } = await supabase.schema('private').rpc('agentes_no_raio', {
      lon: origem.lon, lat: origem.lat, raio_m: raio, excluir: author_agent_id,
    })
    destinatarios = (data ?? []).map((a: { agent_id: string }) => a.agent_id)
  } else {
    // Alerta sem origem: cai no talk group, não estoura.
    //
    // A versão anterior lia `origem.lon` sem guarda — enquanto a linha do INSERT,
    // vinte linhas abaixo, já fazia `origem ? ... : null`. Duas leituras opostas
    // do mesmo campo no mesmo arquivo: uma admitia que ele pode faltar, a outra
    // não. Quem faltasse com a origem recebia 500, e como o cliente engolia a
    // falha, o sintoma era a tabela ficar vazia sem sinal nenhum.
    //
    // Degradar para o talk group é a escolha certa: um alerta sem coordenada
    // ainda tem destinatários óbvios — a própria guarnição —, e entregar a menos
    // gente é infinitamente melhor que não entregar a ninguém.
    const { data } = await supabase
      .from('memberships').select('agent_id').eq('talk_group_id', talk_group_id)
    destinatarios = (data ?? []).map((m) => m.agent_id).filter((a) => a !== author_agent_id)
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
