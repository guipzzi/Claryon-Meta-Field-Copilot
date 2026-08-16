# Deploy das Edge Functions

Duas funções vivem em `supabase/functions/` e **nunca foram deployadas** — foi o
achado que fechou a Fase 0. Enquanto elas não existirem no projeto:

- `POST /functions/v1/transmit` devolve **404**;
- `public.transmissions` nunca recebe INSERT (a RLS bloqueia escrita direta de
  propósito — `servidor/migracoes/0002_rls.sql:77`);
- o fio do canal na tela da guarnição mostra só as inserções locais, que somem em
  10 s na recarga.

O código Kotlin que as chama já existe (`core-net/.../RegistroDeTransmissao.kt`) e
está ligado. Falta só o destino.

## Caminho recomendado: o script

O CLI do Supabase **para de responder neste ambiente** — os dois primeiros deploys
funcionaram e, a partir daí, `supabase functions deploy` passou a travar sem emitir
uma linha sequer. Sem saída não há diagnóstico, e passo de entrega que às vezes
trava não é passo de entrega. Use:

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && python3 servidor/deploy_funcao.py transmit ack
```

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && python3 servidor/deploy_funcao.py --listar
```

Não precisa de CLI, de Docker nem de `supabase login`: usa o mesmo
`supabase_access_token` de `local.properties` que o `executar_sql.py` já usa.

Dois achados que o script carrega, e que custaram tempo:

- **A Management API recusa requisição sem `User-Agent`.** O `403` com corpo
  `error code: 1010` é do **Cloudflare**, não do Supabase, e não tem nada a ver
  com escopo de token. Foi por isso que a primeira tentativa pareceu "sem
  permissão".
- **`entrypoint_path` é relativo a `source/`, que o servidor já prefixa.** Passar
  `source/index.ts` produz `.../source/source/index.ts` e um 400 que diz
  exatamente isso.

## Caminho alternativo: o CLI (quando ele coopera)

**1. Instalar o CLI** (não está na máquina):

```bash
brew install supabase/tap/supabase
```

**2. Autenticar** — abre o navegador e pede login:

```bash
supabase login
```

**3. Deployar as duas funções.** O `cd` faz parte do comando: o CLI procura
`supabase/functions/<nome>/index.ts` **relativo ao diretório atual**, e rodar do
home devolve `Entrypoint path does not exist`.

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && supabase functions deploy transmit --project-ref dzrhfghbldwvycysnzpb
```

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && supabase functions deploy ack --project-ref dzrhfghbldwvycysnzpb
```

`WARNING: Docker is not running` é esperado e pode ser ignorado. Docker só serve
para rodar as funções localmente (`supabase functions serve`); o deploy empacota e
envia para a nuvem, sem container.

**4. Conferir que subiram** — o esperado é **401** e não 404. 401 significa que a
função existe e recusou a chamada sem JWT, que é o comportamento correto:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "https://dzrhfghbldwvycysnzpb.supabase.co/functions/v1/transmit" -H "Content-Type: application/json" -d '{}'
```

## Sobre variáveis de ambiente

`SUPABASE_URL`, `SUPABASE_ANON_KEY` e `SUPABASE_SERVICE_ROLE_KEY` são injetadas
pelo próprio Supabase — não precisam ser configuradas.

`FCM_ENDPOINT` e `FCM_TOKEN` são usadas só na notificação push, e o `transmit`
as chama dentro de `Promise.allSettled`: **sem elas a função continua gravando a
transmissão e difundindo por WebSocket**, e só o push silencia. Configure quando
o push entrar no escopo:

```bash
supabase secrets set FCM_ENDPOINT=... FCM_TOKEN=... --project-ref dzrhfghbldwvycysnzpb
```

## O que verificar depois do deploy

Aperte o PTT duas vezes no app e rode:

```bash
python3 servidor/executar_sql.py --somente-leitura -c "select id, tipo, prioridade, duracao_ms, criada_em from public.transmissions order by criada_em desc limit 5"
```

Devem aparecer linhas com `criada_em` de agora e ids que **não** começam com
`44444444` (esses são do seed). Aí o fio do canal deixa de ser vazio em produção
e a Fase 0 fecha.

## Por que não fiz isto por você

Não há CLI do Supabase nesta máquina, e o endpoint de funções da Management API
recusou o token com **403**. `supabase login` abre navegador e exige a sua sessão
— é autenticação sua, não do agente.
