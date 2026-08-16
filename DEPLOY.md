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

## Passo a passo

**1. Instalar o CLI** (não está na máquina):

```bash
brew install supabase/tap/supabase
```

**2. Autenticar** — abre o navegador e pede login:

```bash
supabase login
```

**3. Deployar as duas funções**, da raiz do projeto:

```bash
supabase functions deploy transmit --project-ref dzrhfghbldwvycysnzpb
```

```bash
supabase functions deploy ack --project-ref dzrhfghbldwvycysnzpb
```

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
