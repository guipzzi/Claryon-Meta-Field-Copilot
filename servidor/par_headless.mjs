#!/usr/bin/env node
/**
 * Par headless — o segundo ouvinte que o aceite exige, sem segundo aparelho.
 *
 * ## Por que ele existe
 *
 * O aceite da Fase 3 pede **dois pares autenticados com JWTs distintos no mesmo
 * talk group**: A fala, B ouve, os dois exibem a mesma transcrição, e B recebe
 * recusa de piso vinda do servidor. A decisão D7 permite que B seja "uma sessão
 * headless com JWT distinto rodando no emulador ou num script" — este é o script.
 *
 * Ele prova protocolo, piso remoto, RLS e roteamento de transcrição. **Não** prova
 * qualidade de áudio: isso continua sendo espectrograma em aparelho físico.
 *
 * De quebra, ele fecha a cláusula *"transmissão que um segundo ouvinte recebe"*
 * que ficou pendente na Fase 2 — uma peça, duas pendências.
 *
 * ## Zero dependência, de propósito
 *
 * Node 22+ traz `WebSocket` e `fetch` embutidos. Nenhum `npm install`, nenhum
 * `package.json`, nada para envelhecer entre hoje e 18/09. Verificado aqui:
 * `node --version` → v24.15.0, `typeof WebSocket` → "function".
 *
 * ## O protocolo é o do app, lido do app
 *
 * Envelope, tópico e nomes de evento saem de `ProtocoloRealtime.kt`, não da minha
 * memória: envelope é **objeto** (`{topic, event, payload, ref}`), tópico é
 * `realtime:tg-<uuid>`, e broadcast aninha `{type, event, payload}`. Se o par
 * falasse um dialeto ligeiramente diferente, ele testaria a si mesmo.
 *
 * ## Credenciais
 *
 * Só por variável de ambiente. `CLAUDE.md` §2 proíbe credencial versionada, e um
 * script de teste é exatamente onde ela costuma vazar.
 *
 * ```
 * export SUPABASE_URL=https://xxxx.supabase.co
 * export SUPABASE_ANON_KEY=...
 * export PAR_EMAIL=segundo.agente@exemplo.gov.br
 * export PAR_SENHA=...
 * node servidor/par_headless.mjs --grupo <uuid> [--privado] [--segundos 60]
 * ```
 *
 * `--privado` liga `config.private: true`. Sem política RLS em `realtime.messages`
 * o servidor **recusa** o join — e é assim que se prova que a política existe.
 */

const args = process.argv.slice(2);
const opt = (nome, padrao = null) => {
  const i = args.indexOf(`--${nome}`);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : padrao;
};
const flag = (nome) => args.includes(`--${nome}`);

const URL_BASE = process.env.SUPABASE_URL?.replace(/\/$/, '');
const ANON = process.env.SUPABASE_ANON_KEY;
const EMAIL = process.env.PAR_EMAIL;
const SENHA = process.env.PAR_SENHA;
const GRUPO = opt('grupo');
const PRIVADO = flag('privado');
const SEGUNDOS = Number(opt('segundos', '60'));

const faltando = Object.entries({ SUPABASE_URL: URL_BASE, SUPABASE_ANON_KEY: ANON, PAR_EMAIL: EMAIL, PAR_SENHA: SENHA })
  .filter(([, v]) => !v)
  .map(([k]) => k);
if (faltando.length || !GRUPO) {
  console.error(`falta configurar: ${[...faltando, GRUPO ? null : '--grupo <uuid>'].filter(Boolean).join(', ')}`);
  console.error('  ver o cabeçalho deste arquivo');
  process.exit(2);
}

/** Autentica o SEGUNDO agente. JWT distinto é o ponto do aceite. */
async function entrar() {
  const r = await fetch(`${URL_BASE}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { apikey: ANON, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, password: SENHA }),
  });
  if (!r.ok) throw new Error(`login falhou (${r.status}): ${(await r.text()).slice(0, 200)}`);
  const j = await r.json();
  const sub = JSON.parse(Buffer.from(j.access_token.split('.')[1], 'base64url')).sub;
  return { token: j.access_token, sub };
}

const topico = (g) => `realtime:tg-${g}`;

let ref = 1;
const proximo = () => String(ref++);

const contagem = { anuncio: 0, quadro: 0, quadros: 0, fim: 0, transcricao: 0, outro: 0 };
const transmissoes = new Map();

function classificar(bruto) {
  let raiz;
  try { raiz = JSON.parse(bruto); } catch { return; }
  const payload = raiz.payload;
  if (!payload) return;

  // phx_reply do join: é aqui que RLS aprova ou recusa, e é o que o aceite
  // verifica no "terceiro cliente sem sessão válida recebe erro".
  if (raiz.event === 'phx_reply') {
    const st = payload.status;
    console.log(`  ← phx_reply status=${st}${st !== 'ok' ? ` · ${JSON.stringify(payload.response)}` : ''}`);
    if (st !== 'ok') {
      console.log('\n  RECUSADO no join. Se veio com --privado, é a política RLS de');
      console.log('  realtime.messages dizendo não — que é o comportamento correto');
      console.log('  quando ela ainda não existe ou o agente não é do grupo.');
      process.exit(1);
    }
    return;
  }
  if (raiz.event === 'system') {
    console.log(`  ← system: ${JSON.stringify(payload).slice(0, 160)}`);
    return;
  }

  const evento = payload.event ?? raiz.event;
  const dados = payload.payload ?? {};
  switch (evento) {
    case 'fala.anuncio':
      contagem.anuncio++;
      transmissoes.set(dados.transmissaoId, { indicativo: dados.indicativo, quadros: 0, texto: null });
      console.log(`  ANÚNCIO  ${dados.transmissaoId?.slice(0, 8)} · ${dados.indicativo} · ${dados.prioridade}`);
      break;
    case 'fala.quadro':
      contagem.quadro++;
      transmissoes.get(dados.transmissaoId) && transmissoes.get(dados.transmissaoId).quadros++;
      break;
    case 'fala.quadros':
      contagem.quadros++;
      { const t = transmissoes.get(dados.transmissaoId); if (t) t.quadros += (dados.quadros?.length ?? 0); }
      break;
    case 'fala.fim':
      contagem.fim++;
      console.log(`  FIM      ${dados.transmissaoId?.slice(0, 8)} · ${transmissoes.get(dados.transmissaoId)?.quadros ?? 0} quadro(s)`);
      break;
    // O quarto evento, que a Fase 3 acrescenta. Já é reconhecido aqui para o
    // par não precisar mudar quando ele existir.
    case 'fala.transcricao':
      contagem.transcricao++;
      { const t = transmissoes.get(dados.transmissaoId); if (t) t.texto = dados.texto; }
      console.log(`  TEXTO    ${dados.transmissaoId?.slice(0, 8)} · "${dados.texto}"`);
      break;
    default:
      contagem.outro++;
  }
}

// Falha de login com pilha crua não diz ao operador o que houve. O código de
// saída já era 1 — o instrumento não mentia —, mas "fetch failed" não informa
// que a URL está errada, e quem roda isto às 3h da manhã merece a frase.
let token, sub;
try {
  ({ token, sub } = await entrar());
} catch (e) {
  console.error(`não foi possível autenticar em ${URL_BASE}`);
  console.error(`  ${e.cause?.message ?? e.message}`);
  console.error('  confira SUPABASE_URL, SUPABASE_ANON_KEY, PAR_EMAIL e PAR_SENHA');
  process.exit(1);
}
console.log(`par headless · agente ${sub}`);
console.log(`grupo ${GRUPO} · canal ${PRIVADO ? 'PRIVADO' : 'público (sem RLS)'} · ouvindo ${SEGUNDOS}s\n`);

const ws = new WebSocket(`${URL_BASE.replace(/^http/, 'ws')}/realtime/v1/websocket?apikey=${ANON}&vsn=1.0.0`);

ws.addEventListener('open', () => {
  // `access_token` no TOPO do payload, irmão de `config` — confirmado na doc
  // oficial e anotado em DECISIONS.md. Dentro de `config` o servidor ignora e o
  // canal continua autorizado só pela chave anon, que é o defeito de hoje.
  ws.send(JSON.stringify({
    topic: topico(GRUPO),
    event: 'phx_join',
    payload: {
      config: { broadcast: { ack: false }, private: PRIVADO },
      access_token: token,
    },
    ref: proximo(),
  }));
  console.log('  → phx_join com access_token' + (PRIVADO ? ' e private=true' : ''));

  setInterval(() => {
    ws.send(JSON.stringify({ topic: 'phoenix', event: 'heartbeat', payload: {}, ref: proximo() }));
  }, 25_000);
});

ws.addEventListener('message', (e) => classificar(e.data));
ws.addEventListener('error', (e) => console.error('  erro de socket:', e.message ?? e));
ws.addEventListener('close', (e) => console.log(`  socket fechado (${e.code})`));

setTimeout(() => {
  console.log(`\nresumo em ${SEGUNDOS}s`);
  console.log(`  anúncios ${contagem.anuncio} · quadros ${contagem.quadro} · lotes ${contagem.quadros} · fins ${contagem.fim} · transcrições ${contagem.transcricao}`);
  for (const [id, t] of transmissoes) {
    console.log(`  ${id.slice(0, 8)} · ${t.indicativo} · ${t.quadros} quadro(s) · texto: ${t.texto ?? '(nenhum)'}`);
  }
  if (contagem.anuncio === 0) {
    console.log('\n  nenhuma transmissão ouvida. Se o app transmitiu neste intervalo,');
    console.log('  o problema é de canal — tópico, autorização ou grupo diferente.');
  }
  process.exit(contagem.anuncio > 0 ? 0 : 1);
}, SEGUNDOS * 1000);
