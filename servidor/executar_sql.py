#!/usr/bin/env python3
"""Executa SQL no projeto Supabase pela Management API.

Existe porque a senha do banco não é o único caminho — e, neste projeto, não é um
caminho que funcione. A Management API executa SQL com um **personal access
token**, sem depender da senha do Postgres, o que devolve autonomia para aplicar
migrações e rodar verificações sem colar nada no painel.

    python3 servidor/executar_sql.py servidor/migracoes/0001_esquema_inicial.sql
    python3 servidor/executar_sql.py -c "select count(*) from agents"
    python3 servidor/executar_sql.py --somente-leitura -c "select now()"

Credenciais (nesta ordem): env `SUPABASE_ACCESS_TOKEN`, senão `supabase_access_token`
em `local.properties`. O `ref` do projeto sai de `supabase_url`. **O token nunca é
impresso** — nem em erro, nem em log.

Só biblioteca padrão: o build precisa funcionar offline, e uma dependência nova
para 120 linhas de HTTP não se justifica.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
API = "https://api.supabase.com/v1/projects/{ref}/database/query"


def propriedades(caminho: Path) -> dict[str, str]:
    if not caminho.exists():
        return {}
    saida: dict[str, str] = {}
    for linha in caminho.read_text(encoding="utf-8").splitlines():
        linha = linha.strip()
        if not linha or linha.startswith("#") or "=" not in linha:
            continue
        chave, valor = linha.split("=", 1)
        saida[chave.strip()] = valor.strip()
    return saida


def credenciais() -> tuple[str, str]:
    props = propriedades(RAIZ / "local.properties")

    token = os.environ.get("SUPABASE_ACCESS_TOKEN") or props.get("supabase_access_token", "")
    if not token:
        sair(
            "Falta o token de acesso.\n"
            "  Gere em: Supabase → Account → Access Tokens → Generate new token\n"
            "  Precisa da permissão 'database_write'.\n"
            "  Guarde em local.properties como:  supabase_access_token=sbp_..."
        )

    url = props.get("supabase_url", "")
    if not url:
        sair("Falta `supabase_url` em local.properties.")
    ref = url.replace("https://", "").split(".")[0]
    if not ref:
        sair(f"Não consegui extrair o ref do projeto de `supabase_url`.")

    return token, ref


def sair(mensagem: str) -> None:
    print(f"erro: {mensagem}", file=sys.stderr)
    sys.exit(1)


def executar(sql: str, somente_leitura: bool = False) -> list[dict]:
    token, ref = credenciais()
    corpo = json.dumps({"query": sql, "read_only": somente_leitura}).encode()

    req = urllib.request.Request(
        API.format(ref=ref),
        data=corpo,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            # O Cloudflare à frente da API recusa o User-Agent padrão do urllib
            # (`Python-urllib/3.x`) com "error code: 1010" — que parece problema
            # de credencial e não é. Identificar-se resolve.
            "User-Agent": "claryon-field/1.0 (+servidor/executar_sql.py)",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            texto = resp.read().decode()
    except urllib.error.HTTPError as e:
        detalhe = e.read().decode(errors="replace")
        # A mensagem do Postgres vem no corpo; é ela que interessa, não o status.
        try:
            detalhe = json.loads(detalhe).get("message", detalhe)
        except json.JSONDecodeError:
            pass
        sair(f"HTTP {e.code}\n{detalhe}")
    except urllib.error.URLError as e:
        sair(f"rede indisponível: {e.reason}")

    return json.loads(texto) if texto.strip() else []


def imprimir(linhas: list[dict]) -> None:
    if not linhas:
        print("(sem linhas)")
        return
    colunas = list(linhas[0].keys())
    larguras = {
        c: max(len(str(c)), max(len(str(l.get(c, ""))) for l in linhas)) for c in colunas
    }
    print(" | ".join(str(c).ljust(larguras[c]) for c in colunas))
    print("-+-".join("-" * larguras[c] for c in colunas))
    for l in linhas:
        print(" | ".join(str(l.get(c, "")).ljust(larguras[c]) for c in colunas))
    print(f"\n({len(linhas)} linha{'s' if len(linhas) != 1 else ''})")


def main() -> None:
    p = argparse.ArgumentParser(description="Executa SQL no Supabase pela Management API.")
    p.add_argument("arquivo", nargs="?", help="arquivo .sql a executar")
    p.add_argument("-c", "--comando", help="SQL inline")
    p.add_argument(
        "--json",
        action="store_true",
        help="devolve as linhas como JSON cru, para consumo por outro programa",
    )
    p.add_argument(
        "--somente-leitura",
        action="store_true",
        help="recusa qualquer escrita — use ao inspecionar produção",
    )
    args = p.parse_args()

    if args.comando:
        sql = args.comando
    elif args.arquivo:
        caminho = Path(args.arquivo)
        if not caminho.exists():
            sair(f"arquivo não encontrado: {caminho}")
        sql = caminho.read_text(encoding="utf-8")
        print(f"── {caminho} ({len(sql.splitlines())} linhas)", file=sys.stderr)
    else:
        sql = sys.stdin.read()

    linhas = executar(sql, somente_leitura=args.somente_leitura)
    # `--json` existe para outro programa consumir. `imprimir` formata com `str()`,
    # que devolve repr de Python (aspas simples) e não JSON — a `sonda_de_politica`
    # quebrou tentando reinterpretar isso, e reinterpretar tabela formatada seria
    # frágil de qualquer jeito.
    if args.json:
        print(json.dumps(linhas, ensure_ascii=False))
    else:
        imprimir(linhas)


if __name__ == "__main__":
    main()
