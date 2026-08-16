#!/usr/bin/env python3
"""Faz deploy de uma Edge Function pela Management API, sem o CLI.

    python3 servidor/deploy_funcao.py transmit
    python3 servidor/deploy_funcao.py ack
    python3 servidor/deploy_funcao.py --listar

Existe porque o CLI do Supabase **para de responder neste ambiente**: os dois
primeiros deploys funcionaram e, a partir daí, `supabase functions deploy` passou
a travar sem emitir uma linha sequer — nem o aviso de Docker que ele imprimia
antes. Sem saída não há diagnóstico, e um passo de entrega que depende de um
comando que às vezes trava não é um passo de entrega.

Dois achados que este arquivo carrega, e que custaram tempo:

1. **A Management API recusa requisição sem `User-Agent`.** O 403 com corpo
   `error code: 1010` é o Cloudflare, não o Supabase, e não tem nada a ver com o
   escopo do token. Com `User-Agent` a mesma chamada devolve 200. Foi por isso que
   a primeira tentativa de listar funções pareceu "sem permissão".

2. **`entrypoint_path` é relativo a `source/`, que o servidor já prefixa.**
   Passar `source/index.ts` produz `.../source/source/index.ts` e um 400 cujo
   texto diz exatamente isso, se alguém ler com atenção.

O corpo publicado é um bundle ESZIP, mas não é preciso montá-lo: o endpoint
`/functions/deploy` aceita `multipart/form-data` com o TypeScript cru e empacota
do lado do servidor.

Credenciais: `SUPABASE_ACCESS_TOKEN` no ambiente, senão `supabase_access_token`
em `local.properties` — o mesmo caminho de `executar_sql.py`. O token nunca é
impresso.
"""

import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request
import uuid

RAIZ = pathlib.Path(__file__).resolve().parent.parent
API = "https://api.supabase.com"

# Sem isto o Cloudflare devolve 403/1010. Ver o cabeçalho deste arquivo.
AGENTE = "curl/8.7.1"


def credenciais() -> tuple[str, str]:
    props = dict(
        re.findall(r"^(\w+)\s*=\s*(.+)$", (RAIZ / "local.properties").read_text(), re.M)
    )
    token = os.environ.get("SUPABASE_ACCESS_TOKEN") or props.get(
        "supabase_access_token", ""
    ).strip()
    if not token:
        sys.exit("Sem token: defina SUPABASE_ACCESS_TOKEN ou supabase_access_token.")
    url = props.get("supabase_url", "")
    ref = re.search(r"https://([a-z0-9]+)\.supabase", url)
    if not ref:
        sys.exit(f"Não consegui extrair o ref do projeto de supabase_url={url!r}.")
    return token, ref.group(1)


def chamar(metodo: str, caminho: str, corpo=None, content_type=None):
    token, _ = credenciais()
    headers = {"Authorization": f"Bearer {token}", "User-Agent": AGENTE}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(
        API + caminho, data=corpo, headers=headers, method=metodo
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def listar() -> None:
    _, ref = credenciais()
    status, corpo = chamar("GET", f"/v1/projects/{ref}/functions")
    if status != 200:
        sys.exit(f"HTTP {status}: {corpo[:200].decode(errors='replace')}")
    for f in json.loads(corpo):
        print(f"{f['slug']:14s} v{f['version']:<4} {f['status']}")


def deploy(slug: str) -> None:
    _, ref = credenciais()
    fonte = RAIZ / "supabase" / "functions" / slug / "index.ts"
    if not fonte.exists():
        sys.exit(f"Não encontrei {fonte.relative_to(RAIZ)}.")

    # `verify_jwt` falso porque a verificação acontece DENTRO da função, em
    # `agenteDoJwt`. Barrar no gateway esconderia o 401 tipado que o cliente
    # espera receber.
    metadata = json.dumps(
        {"name": slug, "entrypoint_path": "index.ts", "verify_jwt": False}
    ).encode()

    limite = f"----{uuid.uuid4().hex}"
    partes: list[bytes] = []

    def parte(nome, conteudo, filename=None, ct="application/octet-stream"):
        cab = f'--{limite}\r\nContent-Disposition: form-data; name="{nome}"'
        if filename:
            cab += f'; filename="{filename}"'
        cab += f"\r\nContent-Type: {ct}\r\n\r\n"
        partes.append(cab.encode() + conteudo + b"\r\n")

    parte("metadata", metadata, ct="application/json")
    parte("file", fonte.read_bytes(), filename="index.ts", ct="application/typescript")
    corpo = b"".join(partes) + f"--{limite}--\r\n".encode()

    status, resposta = chamar(
        "POST",
        f"/v1/projects/{ref}/functions/deploy?slug={slug}",
        corpo,
        f"multipart/form-data; boundary={limite}",
    )
    if status not in (200, 201):
        sys.exit(f"HTTP {status}: {resposta[:300].decode(errors='replace')}")
    dados = json.loads(resposta)
    print(f"{slug}: versão {dados['version']}, {dados['status']}")


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        sys.exit(__doc__)
    if sys.argv[1] == "--listar":
        listar()
    else:
        for slug in sys.argv[1:]:
            deploy(slug)
