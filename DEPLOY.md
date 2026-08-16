# Deploy das Edge Functions

Duas funções vivem em `supabase/functions/`. O deploy é pelo CLI do Supabase, e
**não precisa de Docker** — Docker só serve para `supabase functions serve`, que
roda as funções localmente.

## Uma vez, por máquina

```bash
brew install supabase/tap/supabase
```

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && cp .envrc.exemplo .envrc && source .envrc
```

O `.envrc` exporta `SUPABASE_ACCESS_TOKEN` a partir do `local.properties`, e é ele
que faz o CLI funcionar em shell sem TTY.

**Por que isso é necessário, e não é contorno.** O CLI 2.x procura
`~/.supabase/profile`. Esse arquivo só é criado pelo `supabase login` **interativo**
— e mesmo depois de um login bem-sucedido ele não existe num shell sem terminal.
Sem o perfil e sem a variável, o CLI tenta abrir um prompt que ninguém responde e
**trava sem emitir uma única linha**. O `--debug` mostra a causa em uma frase:

```
NotFound: FileSystem.readFile (/Users/…/.supabase/profile)
```

`SUPABASE_ACCESS_TOKEN` é o caminho que a própria Supabase documenta para CI. Com
ele, o CLI nem procura o perfil.

## Deployar

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && source .envrc && supabase functions deploy transmit --project-ref dzrhfghbldwvycysnzpb
```

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && source .envrc && supabase functions deploy ack --project-ref dzrhfghbldwvycysnzpb
```

O `cd` faz parte do comando: o CLI resolve `supabase/functions/<nome>/index.ts`
relativo ao diretório atual, e rodar do home devolve `Entrypoint path does not exist`.

`WARNING: Docker is not running` é esperado e pode ser ignorado.

## Conferir

```bash
cd ~/Downloads/Claryon\ -\ Field\ Copilot && source .envrc && supabase functions list --project-ref dzrhfghbldwvycysnzpb
```

E o teste de fumaça — **`401` é o resultado bom**: significa que a função existe e
recusou chamada sem JWT. `404` significa que não subiu.

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "https://dzrhfghbldwvycysnzpb.supabase.co/functions/v1/transmit" -H "Content-Type: application/json" -d '{}'
```

## Variáveis de ambiente das funções

`SUPABASE_URL`, `SUPABASE_ANON_KEY` e `SUPABASE_SERVICE_ROLE_KEY` são injetadas
pelo próprio Supabase — não precisam ser configuradas.

`FCM_ENDPOINT` e `FCM_TOKEN` são usadas só no push, dentro de um
`Promise.allSettled`: **sem elas a função continua gravando a transmissão e
difundindo por WebSocket**, e só o push silencia.

```bash
supabase secrets set FCM_ENDPOINT=... FCM_TOKEN=... --project-ref dzrhfghbldwvycysnzpb
```

## Verificação ponta a ponta

Aperte o PTT duas vezes no app e rode:

```bash
python3 servidor/executar_sql.py --somente-leitura -c "select left(id::text,8) as id, tipo, prioridade, to_char(criada_em,'HH24:MI:SS') as hora from public.transmissions where criada_em > now() - interval '5 minutes' order by criada_em desc"
```

Devem aparecer linhas de agora, com `tipo = ptt`.

## Uma armadilha da Management API, se algum dia precisar dela

Ela recusa requisição **sem `User-Agent`** com `403` e corpo `error code: 1010` —
isso é Cloudflare, não Supabase, e não tem relação com escopo do token. Custou uma
leitura errada aqui: pareceu falta de permissão e não era.
