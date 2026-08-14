// Edge Function `locate` — resolve a consulta de posição (C2).
//
// **Devolve distância, rumo e estado de movimento — NUNCA coordenadas.** O
// aparelho de um agente jamais recebe a posição bruta de outro. Essa é a
// diferença entre coordenação e rastreamento, e ela mora aqui, no servidor.
//
// A consulta não precisa de LLM: "onde está a guarnição Alfa Dois?" é uma
// consulta a banco. Dezenas de milissegundos, resposta previsível, auditável, e
// que nunca inventa uma posição. Alucinação aqui não é erro cosmético — é dizer
// a um policial que o apoio está a 800 m quando está a 6 km.

import { createClient } from 'jsr:@supabase/supabase-js@2'

const RUMOS = ['norte','nordeste','leste','sudeste','sul','sudoeste','oeste','noroeste']

Deno.serve(async (req) => {
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
  )
  const { solicitante_id, indicativo } = await req.json()

  const { data } = await supabase.rpc('posicao_relativa', { solicitante_id, indicativo })
  if (!data || data.length === 0) {
    // Honestidade: não sabemos onde está. Nunca uma posição plausível inventada.
    return Response.json({ encontrado: false })
  }

  const p = data[0]
  // Arredondamento para a precisão real do GPS: dizer "1.237 metros" sugere uma
  // exatidão que o sinal não tem.
  const distancia = p.distancia_m < 1000
    ? Math.round(p.distancia_m / 50) * 50
    : Math.round(p.distancia_m / 100) * 100

  return Response.json({
    encontrado: true,
    indicativo: p.indicativo,
    distancia_m: distancia,
    rumo: RUMOS[Math.round(((p.azimute % 360) + 360) % 360 / 45) % 8],
    em_movimento: p.speed_mps > 1,
    // Obsolescência: mostrar posição velha como atual é pior que não mostrar.
    atualizado_ha_s: p.idade_s,
  })
})
