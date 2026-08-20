#!/usr/bin/env python3
"""Experimenta política de linha **sem deixar o servidor quebrado**.

## Por que existe

Em 18/08 uma sonda respondeu uma pergunta que a documentação não respondia — se a
política de `realtime.messages` enxerga o `payload` do broadcast — e a resposta foi
não. O método estava certo: medir em vez de supor. **A execução não estava**: a
política de produção foi substituída à mão, e o broadcast ficou fora do ar até eu
lembrar de restaurar. Se a sonda tivesse falhado no meio, ou se eu tivesse sido
interrompido, o rádio ficaria mudo sem ninguém saber por quê.

O defeito não é ter experimentado — é a restauração depender de memória. Aqui ela
depende de `finally`, que roda inclusive em erro, em `Ctrl-C` e em `SIGTERM`.

## Como usar

    python3 servidor/sonda_de_politica.py \\
        --tabela realtime.messages \\
        --sql "alter policy agente_fala_no_proprio_grupo on realtime.messages
               with check (realtime.messages.payload is not null)" \\
        --comando "node servidor/par_headless.mjs --grupo <uuid> --privado --falar --segundos 10"

O script tira uma fotografia das políticas da tabela, aplica o experimento, roda o
comando, e **recoloca as políticas originais aconteça o que acontecer**. No fim
imprime a diferença entre o estado inicial e o final — se não for vazia, ele grita.

## Produção exige intenção declarada, não descuido

A restauração já não depende de memória — depende de `finally`. Faltava a outra
metade: **nada impedia a sonda de apontar para produção sem ninguém decidir isso**.
Agora ela recusa por padrão. Para insistir é preciso `--producao "<motivo>"`, com
motivo escrito, que vai para a tela e para o registro. Não se passa por acidente e
não se passa em pressa.

O certo continua sendo pilha separada, e quando houver Docker na máquina ela existe
de graça:

    supabase start                      # Postgres + Realtime + GoTrue locais
    export SUPABASE_URL=http://127.0.0.1:54321
    # e a sonda deixa de reclamar, porque o alvo não é mais o projeto de produção

Verificado em 18/08: `docker` e `supabase` estão instalados nesta máquina, mas o
daemon não sobe (não há Docker Desktop). Enquanto for assim, a barreira é esta.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
EXECUTAR = RAIZ / "servidor" / "executar_sql.py"


def sql(comando: str, leitura: bool = False, cru: bool = False):
    """Executa SQL. Com `cru`, devolve as linhas como lista de dicionários.

    `--json` e não a tabela formatada: `imprimir` usa `str()`, que devolve repr de
    Python com aspas simples. A primeira versão desta sonda tentou `json.loads`
    naquilo e quebrou — antes de aplicar o experimento, felizmente, porque a ordem
    aqui é fotografar primeiro.
    """
    args = [sys.executable, str(EXECUTAR)]
    if leitura:
        args.append("--somente-leitura")
    if cru:
        args.append("--json")
    args += ["-c", comando]
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"SQL falhou: {r.stderr.strip()[:300]}")
    return json.loads(r.stdout or "[]") if cru else r.stdout


def fotografar(tabela: str) -> list[dict]:
    """As políticas da tabela, com o texto completo, para poder recriá-las."""
    esquema, nome = tabela.split(".", 1)
    return sql(
        f"""select policyname as nome, cmd, roles::text as roles,
                   permissive as permissiva, qual as usando, with_check as checando
              from pg_policies
             where schemaname = '{esquema}' and tablename = '{nome}'
             order by policyname""",
        leitura=True,
        cru=True,
    )


def recriar(tabela: str, politicas: list[dict]) -> None:
    partes = []
    for p in politicas:
        papeis = p["roles"].strip("{}")
        corpo = f'create policy {p["nome"]} on {tabela}'
        corpo += " as permissive" if p["permissiva"] == "PERMISSIVE" else " as restrictive"
        corpo += f' for {p["cmd"].lower()} to {papeis}'
        if p.get("usando"):
            corpo += f' using ({p["usando"]})'
        if p.get("checando"):
            corpo += f' with check ({p["checando"]})'
        partes.append(f'drop policy if exists {p["nome"]} on {tabela}; {corpo};')
    if partes:
        sql("begin; " + " ".join(partes) + " commit;")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tabela", required=True, help="ex.: realtime.messages")
    ap.add_argument("--sql", required=True, help="o experimento")
    ap.add_argument("--comando", required=True, help="como observar o efeito")
    ap.add_argument(
        "--producao",
        metavar="MOTIVO",
        help="autoriza apontar para o projeto de produção, com o motivo escrito",
    )
    a = ap.parse_args()

    # ── A barreira ──────────────────────────────────────────────────────────
    #
    # Sem isto, apontar para produção era o **padrão**: a sonda usa o mesmo
    # `executar_sql.py` do dia a dia, que lê o projeto de `local.properties`. Foi
    # assim que um experimento deixou o broadcast fora do ar. A restauração já é
    # garantida por `finally`; esta parte garante que ninguém chegue lá sem querer.
    # O alvo sai de `local.properties`, que é o que `executar_sql.py` lê — **não**
    # de `SUPABASE_URL`. A primeira versão desta barreira olhava a variável de
    # ambiente e criava uma saída que PARECIA segura: com
    # `SUPABASE_URL=http://127.0.0.1` ela liberava a passagem e o comando ia para
    # produção do mesmo jeito. Guarda com desvio que imita o caminho certo é pior
    # que guarda nenhum, porque dá confiança.
    #
    # Enquanto `executar_sql.py` só souber falar com a Management API, **todo alvo
    # é produção** — e a barreira diz isso em vez de fingir alternativa.
    if not a.producao:
        print("recusando: o alvo é o projeto de PRODUÇÃO.")
        print()
        print("  `executar_sql.py` fala com a Management API do projeto de")
        print("  `local.properties`. Não há alvo local hoje — `SUPABASE_URL` não muda isso.")
        print()
        print("   · para insistir: --producao \"motivo pelo qual precisa ser em produção\"")
        print("   · para eliminar a necessidade: `supabase start` e um modo local no")
        print("     executor (precisa de Docker, que não sobe nesta máquina hoje)")
        print()
        print("  A restauração é garantida por `finally`, mas uma janela de segundos")
        print("  sem política é rádio mudo para quem estiver em campo neste instante.")
        return 3
    if a.producao:
        print(f"⚠️  EXPERIMENTO EM PRODUÇÃO — motivo: {a.producao}", flush=True)

    print(f"fotografando as políticas de {a.tabela}…", flush=True)
    antes = fotografar(a.tabela)
    print(f"  {len(antes)} política(s): {', '.join(p['nome'] for p in antes) or '(nenhuma)'}", flush=True)
    if not antes:
        print("  ⚠️  sem política nenhuma para restaurar — confira o nome da tabela")

    restauracao_falhou = False
    try:
        print("aplicando o experimento…", flush=True)
        sql(a.sql)
        print(f"rodando: {a.comando}\n" + "─" * 60, flush=True)
        subprocess.run(a.comando, shell=True)
        print("─" * 60, flush=True)
    finally:
        # A razão de este arquivo existir. Roda em erro, em Ctrl-C e em SIGTERM.
        print("restaurando…", flush=True)
        try:
            recriar(a.tabela, antes)
        except Exception as e:  # noqa: BLE001 — falha aqui é o pior caso possível
            # **Sem `return` aqui.** `return` dentro de `finally` descarta a exceção
            # que estava subindo — o operador veria "restauração falhou" e perderia
            # o erro que causou tudo. O Python avisa disso, e o aviso estava certo.
            restauracao_falhou = True
            print(f"  ❌ RESTAURAÇÃO FALHOU: {e}", flush=True)
            print(f"  o estado original era:\n{json.dumps(antes, indent=2, ensure_ascii=False)}", flush=True)

    if restauracao_falhou:
        return 2

    depois = fotografar(a.tabela)
    if depois == antes:
        print("  ✅ políticas idênticas às do início")
        return 0
    print("  ❌ O ESTADO MUDOU. Antes × depois:")
    print(json.dumps({"antes": antes, "depois": depois}, indent=2, ensure_ascii=False))
    return 2


if __name__ == "__main__":
    sys.exit(main())
