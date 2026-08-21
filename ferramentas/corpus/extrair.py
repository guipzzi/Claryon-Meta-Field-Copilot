#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Extrator de trechos citáveis da legislação — `corpus/bruto/*.html` -> `corpus/trechos.jsonl`.

Uma linha por artigo VIGENTE (ou revogado, marcado como tal):

    {"norma": "CTB", "documento": "Lei 9.503/1997", "artigo": "Art. 301",
     "titulo": "", "texto": "...", "citacao": "Art. 301 do CTB",
     "revogado": false}

`titulo` é o nomen iuris — "Furto", "Roubo", "Lesão corporal", "Posse ou porte
ilegal de arma de fogo de uso restrito". Ele existe porque é o termo pelo qual o
agente pergunta, e em 84 artigos ele NÃO aparece em nenhum outro lugar do texto:
descartá-lo apagava "Infanticídio", "Maus-tratos" e "Perseguição" do corpus
inteiro. Fica em campo próprio, e não colado ao texto, porque é título e não
preceito. `""` quando a norma não dá um — CTB e Lei de Drogas não dão.

Só stdlib.

--------------------------------------------------------------------------------
As três armadilhas do HTML do Planalto, todas medidas neste corpus
--------------------------------------------------------------------------------

1. ENTIDADE HTML ANTES DE CONTAR. Metade dos artigos do CPP escreve
   `Art.&nbsp;1`. O padrão `Art\\.\\s*\\d+` não casa com `&nbsp;`, e por isso uma
   contagem ingênua devolveu 242 artigos num CPP que tem 811. Aqui o
   `html.unescape` acontece ANTES de qualquer casamento. Ver `corpus/PROCEDENCIA.md`.

2. VERSÃO REVOGADA ≠ ARTIGO REVOGADO. O texto "compilado" carrega as redações
   ANTERIORES riscadas (`<strike>`, `<del>`, `style="...line-through"`) logo acima
   da redação em vigor. Contar ocorrências de "Art. N" conta as duas. No
   Estatuto do Desarmamento são 58 ocorrências para 41 artigos: 17 são redações
   mortas. Aqui o riscado é rastreado por PILHA DE TAGS, e a decisão é tomada
   pelo caractere onde começa o rótulo "Art." — porque o Planalto às vezes risca
   só o rótulo e deixa a nota "(Vide ...)" fora do risco.

3. SUFIXO DE LETRA, E MAIS DE UM. `Art. 147-A`, e também `Art. 359-M-A` e
   `Art. 359-M-B` no CP. Um padrão que só capture `\\d+` funde os três em "359".

--------------------------------------------------------------------------------
Notas de alteração
--------------------------------------------------------------------------------
Por padrão as notas editoriais — "(Redação dada pela Lei nº ...)", "(Incluído
pela ...)", "(Vide ...)", "(Vigência)" — saem do texto: quem lê isto em voz alta
é o Piper, e elas viram ruído a cada parágrafo. "(VETADO)" e "(Revogado ...)"
FICAM, porque são o conteúdo do artigo. Use `--com-notas` para mantê-las.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import unicodedata
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
BRUTO = RAIZ / "corpus" / "bruto"
SAIDA = RAIZ / "corpus" / "trechos.jsonl"

# --------------------------------------------------------------------------- #
# Normas
# --------------------------------------------------------------------------- #

NORMAS = [
    # arquivo,             norma,                        documento,                  preposição da citação
    ("ctb",            "CTB",                        "Lei 9.503/1997",           "do"),
    ("cpp",            "CPP",                        "Decreto-Lei 3.689/1941",   "do"),
    ("cp",             "CP",                         "Decreto-Lei 2.848/1940",   "do"),
    ("drogas",         "Lei de Drogas",              "Lei 11.343/2006",          "da"),
    ("desarmamento",   "Estatuto do Desarmamento",   "Lei 10.826/2003",          "do"),
]

# Contagens de referência.
#
# A segunda coluna é a contagem INGÊNUA: `Art\.\s*\d+` aplicado ao HTML cru, sem
# resolver entidade. Era o número que `corpus/PROCEDENCIA.md` trazia para quatro
# das cinco normas (389 / 430 / 114 / 52) e que o próprio documento já tinha
# desmascarado na quinta (242 num CPP de 811 artigos). Ela fica aqui como TESTE
# DE REGRESSÃO com sinal invertido: se o extrator produzir esse número, ele
# voltou a ter o defeito. Ver o cabeçalho deste arquivo para as três causas.
#
# A primeira coluna foi medida por este extrator e conferida contra a estrutura
# da norma (FAIXAS, abaixo): numeração contígua, lacunas nomeadas uma a uma.
ESPERADO = {
    # norma:                     (artigos, contagem ingênua que NÃO se deve reproduzir)
    "CTB":                      (391, 389),
    "CPP":                      (851, 242),
    "CP":                       (434, 430),
    "Lei de Drogas":            (100, 114),
    "Estatuto do Desarmamento": (41,  52),
}

# Numeração base esperada por norma: (primeiro, último, lacunas).
# A lacuna é o artigo que NÃO tem uma linha no `trechos.jsonl` — nem como
# revogado. É a lista de perguntas que o copiloto vai responder "não encontrei".
FAIXAS = {
    "CTB":                      (1, 341, ()),
    "CPP":                      (1, 811, (194, 611)),
    "CP":                       (1, 361, ()),
    "Lei de Drogas":            (1, 75,  ()),
    "Estatuto do Desarmamento": (1, 37,  ()),
}

# --------------------------------------------------------------------------- #
# Varredura do HTML: texto + máscara de riscado + máscara de negrito
# --------------------------------------------------------------------------- #

VAZIOS = {"br", "img", "hr", "meta", "link", "input", "area", "base",
          "col", "param", "embed", "source", "p"}
RISCANTES = {"strike", "del"}
NEGRITANTES = {"b", "strong"}
QUEBRAM_BLOCO = {"p", "br", "tr", "td", "th", "div", "blockquote", "li",
                 "table", "ul", "ol", "center", "h1", "h2", "h3", "h4"}

TAG = re.compile(r"<[^>]*>")
NOME_TAG = re.compile(r"</?\s*([a-zA-Z0-9]+)")
ESTILO = re.compile(r'style\s*=\s*(["\'])(.*?)\1', re.I | re.S)


class Bloco:
    """Um bloco de nível de parágrafo, com as máscaras alinhadas ao texto."""

    __slots__ = ("texto", "riscado", "negrito")

    def __init__(self, texto: str, riscado: list[bool], negrito: list[bool]):
        self.texto = texto
        self.riscado = riscado
        self.negrito = negrito

    def vivo(self) -> str:
        """Só os caracteres que não estão riscados."""
        return "".join(c for c, r in zip(self.texto, self.riscado) if not r)

    def riscado_em(self, i: int) -> bool:
        return bool(self.riscado[i]) if i < len(self.riscado) else False

    def todo_negrito(self) -> bool:
        pares = [(c, n) for c, n in zip(self.texto, self.negrito) if not c.isspace()]
        return bool(pares) and all(n for _, n in pares)


# Nota de alteração dentro de um <a href="...">. Casar pelo ELEMENTO, e não por
# parênteses, é o que resolve os casos truncados do próprio Planalto:
# "(Incluído dad", "(Incluído pela Lei nº 14.599, de 2023" (sem fechar),
# "Incluído pela Lei nº 12.971, de 2014)" (sem abrir) e "Vide Lei nº 8.072, de
# 25.7.90" (sem nenhum). Uma régua por pontuação erra em todos os quatro.
# `(Revogado ...)` fica FORA da lista: é o conteúdo do artigo, não nota sobre ele.
ANCORA = re.compile(r"(?is)<a\b[^>]*>(.*?)</a>")
NOTA_ANCORA = re.compile(
    r"^\W*(?:Reda[çc][ãa]o dada|Inclu[íi]d[oa]|Acrescentad[oa]|Renumerad[oa]|"
    r"Revigorad[oa]|Convertid[oa]|Produ[çc][ãa]o de efeitos?|Execu[çc][ãa]o suspensa|"
    # "(VETADO)" NÃO entra: é o conteúdo do artigo. Tê-lo aqui esvaziou
    # 13 artigos — arts. 6º e 8º a 15 da Lei de Drogas entre eles.
    r"Vide|Vig[êe]ncia|Regulamento)\b", re.I)
RISCO_SOLTO = re.compile(r"(?i)</?strike|</?del\b|line-through")


def _sem_notas_ancoradas(raw: str) -> str:
    def troca(m: re.Match) -> str:
        interno = m.group(1)
        # Âncora que abre ou fecha risco dentro de si desequilibraria a pilha.
        if RISCO_SOLTO.search(interno):
            return m.group(0)
        texto = html.unescape(re.sub(r"<[^>]*>", "", interno)).replace("\xa0", " ")
        # O Planalto quebra linha DENTRO da frase: "(Redação\ndada pela Lei...".
        # Sem colapsar o espaço aqui, `Reda[çc][ãa]o dada` não casa e a nota fica.
        texto = re.sub(r"\s+", " ", texto).strip()
        return " " if NOTA_ANCORA.match(texto) else m.group(0)
    return ANCORA.sub(troca, raw)


def _ordinais(raw: str) -> str:
    """`1<sup><u>o</u></sup>` é o "1º" do Planalto. Vira "º" ANTES de perder as tags."""
    for a, b in (("o", "º"), ("a", "ª")):
        raw = re.sub(rf"(?is)<u>\s*<sup>\s*{a}\s*</sup>\s*</u>", b, raw)
        raw = re.sub(rf"(?is)<sup>\s*<u>\s*{a}\s*</u>\s*</sup>", b, raw)
        raw = re.sub(rf"(?is)<sup>\s*{a}\s*</sup>", b, raw)
    return raw


def blocos(caminho: Path, com_notas: bool = False) -> list[Bloco]:
    # O arquivo se declara latin-1, mas 209 bytes na faixa 0x80–0x9F dizem
    # cp1252: 0x96 é o travessão de "Pena – reclusão" (209 ocorrências) e
    # 0x93/0x94 são as aspas curvas. Em latin-1 viram caracteres de controle e
    # vazam para dentro do texto que o Piper vai ler. cp1252 é superconjunto de
    # latin-1 nos imprimíveis, então a troca não custa nada no resto.
    bruto = caminho.read_bytes()
    try:
        raw = bruto.decode("cp1252")
    except UnicodeDecodeError:
        raw = bruto.decode("latin-1")
    raw = re.sub(r"(?is)<head>.*?</head>", "", raw)
    raw = re.sub(r"(?s)<!--.*?-->", "", raw)
    raw = _ordinais(raw)
    if not com_notas:
        raw = _sem_notas_ancoradas(raw)

    saida: list[Bloco] = []
    buf_t: list[str] = []
    buf_r: list[bool] = []
    buf_n: list[bool] = []
    pilha: list[tuple[str, bool, bool | None]] = []  # (nome, riscante, negrito explícito)
    pos = 0

    def risco() -> bool:
        return any(r for _, r, _ in pilha)

    def bold() -> bool:
        for _, _, n in reversed(pilha):
            if n is not None:
                return n
        return False

    def fecha_bloco() -> None:
        nonlocal buf_t, buf_r, buf_n
        if buf_t:
            texto = "".join(buf_t)
            saida.append(Bloco(texto, buf_r, buf_n))
            buf_t, buf_r, buf_n = [], [], []

    for m in TAG.finditer(raw):
        pedaco = raw[pos:m.start()]
        if pedaco:
            # `html.unescape` AQUI: antes de qualquer casamento de "Art. N".
            limpo = html.unescape(pedaco).replace("\xa0", " ")
            buf_t.append(limpo)
            buf_r.extend([risco()] * len(limpo))
            buf_n.extend([bold()] * len(limpo))
        pos = m.end()

        tag = m.group(0)
        casa = NOME_TAG.match(tag)
        if not casa:
            continue
        nome = casa.group(1).lower()
        fecha = tag.startswith("</")

        if nome in QUEBRAM_BLOCO:
            fecha_bloco()

        if fecha:
            for i in range(len(pilha) - 1, -1, -1):
                if pilha[i][0] == nome:
                    del pilha[i:]
                    break
            continue

        if nome in VAZIOS or tag.rstrip().endswith("/>"):
            continue

        estilo = ESTILO.search(tag)
        estilo = estilo.group(2).lower() if estilo else ""
        riscante = nome in RISCANTES or "line-through" in estilo
        if nome in NEGRITANTES:
            negrito: bool | None = True
        elif "font-weight" in estilo:
            negrito = not re.search(r"font-weight\s*:\s*normal", estilo)
        else:
            negrito = None
        pilha.append((nome, riscante, negrito))

    if raw[pos:]:
        limpo = html.unescape(raw[pos:]).replace("\xa0", " ")
        buf_t.append(limpo)
        buf_r.extend([risco()] * len(limpo))
        buf_n.extend([bold()] * len(limpo))
    fecha_bloco()
    return saida


# --------------------------------------------------------------------------- #
# Reconhecimento do rótulo de artigo
# --------------------------------------------------------------------------- #

# `Art. 1º`, `Art. 10.`, `Art. 7º-A`, `Art. 359-M-B`.
# O ponto é OPCIONAL: o CP escreve "Art 187." e "Art 188." e o CTB escreve
# "Art 67-B." — três artigos que um `Art\.` obrigatório perde em silêncio.
# O sufixo exige o hífen COLADO ao número/letra: assim "Art. 5º - A prisão..."
# não vira o artigo "5-A".
INICIO = re.compile(
    r"^Art(?:\.\s*|\s+)(\d+)\s*(?:[ºo°]\.?)?((?:-[A-Z])*)(?![0-9A-Za-zÀ-ÿ])")

CABECALHO = re.compile(
    r"^(LIVRO|T[ÍI]TULO|CAP[ÍI]TULO|SE[ÇC][ÃA]O|SUBSE[ÇC][ÃA]O|PARTE|ANEXO|"
    r"DISPOSI[ÇC][ÕO]ES)\b", re.I)
ENCERRAMENTO = re.compile(
    r"^(Bras[íi]lia|Rio de Janeiro)\s*,|^Este texto n[ãa]o substitui", re.I)

# Notas editoriais. "(VETADO)" e "(Revogado ...)" ficam de fora de propósito.
NOTA = re.compile(
    r"\(\s*(?:Reda[çc][ãa]o dada|Inclu[íi]d[oa]|Acrescentad[oa]|Vide|Vig[êe]ncia|"
    r"Regulamento|Renumerad[oa]|Produ[çc][ãa]o de efeito|Revigorad[oa]|"
    r"Convertid[oa]|Execu[çc][ãa]o suspensa)[^()]*\)",
    re.I)
REVOGA_CABECA = re.compile(r"^\(?\s*Revogad[oa]s?\b", re.I)
# "Art. 556. a Art. 560 (Revogado ...)" e "Art. 561. e Art. 562. (Revogado ...)".
# Cinco artigos do CPP existem só assim. Sem expandir, perguntar pelo art. 558
# devolve "não encontrei" — que é justamente o que este corpus não pode dizer.
GRUPO = re.compile(r"^(a|e)\s+Art\.\s*(\d+)\b", re.I)
SOBRAS = re.compile(r"^[\s.\-–—:]+")

# Nomen iuris — "Feminicídio", "Juiz das Garantias", "Crime impossível". No CP
# ele quase nunca está em negrito, então a forma é o único sinal: frase curta,
# capitalizada, SEM pontuação final, SEM algarismo e SEM marcador de lista.
# As três exclusões é que a tornam segura: "Pena - reclusão, de 12 (doze) a 30
# (trinta) anos" tem algarismo, "III – se a vítima é maior de 60 anos" tem
# algarismo e marcador, "§ 5º" tem os dois.
# O travessão depois de "Pena" é obrigatório: "Pena – reclusão" é preceito
# secundário, "Pena cumprida no estrangeiro" é o título do art. 8º do CP.
LISTA = re.compile(
    r"^(?:(?:Pena|Penas|Infra[çc][ãa]o|Penalidade|Medida)\s*[-–—:]|"
    r"Par[áa]grafo|§|[IVXLCDM]+\s*[-–—.)]|[a-z]\s*[).])", re.I)


def caixa_alta(t: str) -> bool:
    """"DAS LESÕES CORPORAIS" é nome de capítulo; nomen iuris nunca é maiúsculo."""
    letras = [c for c in t if c.isalpha()]
    return bool(letras) and all(c.isupper() for c in letras)


# "Da Autuação", "Do Desaforamento", "Da sentença" — nome de SEÇÃO, não nomen
# iuris. Todos os 36 casos assim eram seção; nenhum nome de crime começa por aí.
SECAO = re.compile(r"^(?:D[aeo]s?|N[ao]s?)\s", re.I)


def parece_titulo(t: str) -> bool:
    return (len(t) <= 130
            and not caixa_alta(t)
            and not SECAO.match(t)
            # ")" fica FORA do conjunto: "Intimidação sistemática (bullying)" é
            # título. "(VETADO)" e "(Revogado ...)" já caem no teste de inicial
            # maiúscula e no de algarismo.
            and not re.search(r"[.:;!?]$", t)
            and not re.search(r"[\d§]", t)
            and bool(re.match(r"[A-ZÀ-Ý]", t))
            and not LISTA.match(t))


def rotulo(numero: int, sufixos: tuple[str, ...]) -> str:
    base = f"{numero}º" if numero <= 9 else str(numero)
    return "Art. " + base + "".join("-" + s for s in sufixos)


def normalizar(texto: str, com_notas: bool) -> str:
    texto = unicodedata.normalize("NFC", texto)
    texto = texto.replace("​", "").replace("﻿", "")
    if not com_notas:
        texto = NOTA.sub(" ", texto)
    texto = re.sub(r"\s+", " ", texto)
    texto = re.sub(r"\s+([,;:.])", r"\1", texto)
    texto = re.sub(r"\(\s*\)", "", texto)
    return re.sub(r"\s+", " ", texto).strip()


# --------------------------------------------------------------------------- #
# Extração
# --------------------------------------------------------------------------- #

class Artigo:
    __slots__ = ("numero", "sufixos", "partes", "revogado", "titulo")

    def __init__(self, numero: int, sufixos: tuple[str, ...], cabeca: str,
                 revogado: bool | None = None, titulo: str = ""):
        self.numero = numero
        self.sufixos = sufixos
        self.partes = [cabeca]
        self.titulo = titulo
        # O rótulo sai antes do teste: "Art. 35. (Revogado pela Lei...)" deixa
        # um "." na frente, e `^\(?Revogad` não casaria com ele.
        corpo = SOBRAS.sub("", INICIO.sub("", cabeca))
        self.revogado = bool(REVOGA_CABECA.match(corpo)) if revogado is None else revogado


def extrair(arquivo: str, com_notas: bool) -> tuple[list[Artigo], dict]:
    bl = blocos(BRUTO / f"{arquivo}.html", com_notas)

    artigos: list[Artigo] = []
    atual: Artigo | None = None
    # Nomen iuris visto e ainda não gasto. Vale só se o PRÓXIMO bloco for o
    # começo de um artigo — qualquer corpo de texto no meio o invalida.
    pendente = ""
    diag = {"blocos": len(bl), "inicios": 0, "inicios_riscados": 0,
            "nomen_iuris": 0, "colisoes": [], "grupos": []}

    for b in bl:
        bruto = re.sub(r"\s+", " ", b.texto).strip()
        if not bruto:
            continue

        # O rótulo pode vir precedido de espaços no texto original; o índice do
        # "A" de "Art." na string ORIGINAL é o que decide se está riscado.
        deslocamento = len(b.texto) - len(b.texto.lstrip())
        casa = INICIO.match(bruto)

        if casa:
            diag["inicios"] += 1
            if b.riscado_em(deslocamento):
                # Redação anterior, riscada. Não é artigo — e o corpo dela vem
                # riscado nos blocos seguintes, que `vivo()` já esvazia. O título
                # NÃO é consumido aqui: ele pertence à redação viva que vem
                # depois. Consumi-lo deixava o art. 16 do Estatuto sem
                # "Posse ou porte ilegal de arma de fogo de uso restrito".
                diag["inicios_riscados"] += 1
                atual = None
                continue
            titulo, pendente = pendente, ""
            numero = int(casa.group(1))
            sufixos = tuple(casa.group(2).split("-")[1:]) if casa.group(2) else ()
            cabeca = normalizar(b.vivo(), com_notas)
            resto = SOBRAS.sub("", INICIO.sub("", cabeca))
            # O texto passa a começar pelo rótulo canônico: o CP escreve
            # "Art 187." e o CTB "Art 67-B.", e quem lê isto em voz alta não
            # deve herdar a datilografia de 1940.
            cabeca = rotulo(numero, sufixos) + cabeca[casa.end():]

            faixa = GRUPO.match(resto)
            if faixa and not sufixos:
                fim = int(faixa.group(2))
                alcance = (range(numero, fim + 1) if faixa.group(1).lower() == "a"
                           else (numero, fim))
                cauda = resto[faixa.end():].strip()
                morto = bool(re.search(r"\(\s*Revogad[oa]s?\b", cauda, re.I))
                diag["grupos"].append(cabeca[:90])
                for n in alcance:
                    artigos.append(
                        Artigo(n, (), f"{rotulo(n, ())}. {cauda}".strip(), revogado=morto))
                atual = None
                continue

            atual = Artigo(numero, sufixos, cabeca, titulo=titulo)
            artigos.append(atual)
            continue

        if CABECALHO.match(bruto) or ENCERRAMENTO.match(bruto):
            atual = None
            pendente = ""
            continue

        # Nomen iuris ("Perseguição", "Feminicídio"): bloco curto, inteiro em
        # negrito, sem pontuação final. Sai do corpo do artigo anterior e fica
        # guardado para o próximo — ele NÃO encerra o artigo, porque no CP
        # aparece no MEIO dele, antes de cada parágrafo. Encerrar aqui custou os
        # §§ 1º a 7º do art. 121 (privilégio, qualificadoras) na primeira versão.
        # Um só teste de forma, em duas posições. NO MEIO de um artigo ele só
        # vale com negrito, porque ali um bloco também pode ser corpo. ENTRE
        # dois artigos — depois de um CAPÍTULO, ou de uma redação riscada — não
        # pode ser corpo de nada, e a forma basta: é o que recupera "Lesão
        # corporal" (art. 129 do CP), que não está em negrito nenhum no HTML.
        if parece_titulo(bruto) and (atual is None or b.todo_negrito()):
            diag["nomen_iuris"] += 1
            pendente = normalizar(bruto, com_notas)
            continue

        vivo = normalizar(b.vivo(), com_notas)
        if not vivo:
            # Bloco todo riscado: é corpo de redação anterior. Não invalida o
            # título pendente.
            continue
        pendente = ""
        if atual is not None:
            atual.partes.append(vivo)

    # O nomen iuris do PRÓXIMO artigo mora acima dele, e por isso cai na cauda
    # do artigo anterior quando não está em negrito — que é o caso comum no CP.
    # Só a cauda é aparada, e nunca a cabeça: sem essa regra o art. 15 do CP
    # termina em "Arrependimento posterior", que é o título do art. 16. E o que
    # sai daqui vai para o artigo seguinte, não para o lixo: "Infanticídio",
    # "Maus-tratos" e "Perseguição" não aparecem em NENHUM outro lugar do texto
    # dos arts. 123, 136 e 147-A — jogá-los fora apaga o termo pelo qual o
    # agente vai perguntar.
    for i, a in enumerate(artigos):
        while len(a.partes) > 1 and parece_titulo(a.partes[-1]):
            solto = a.partes.pop()
            diag["nomen_iuris"] += 1
            if i + 1 < len(artigos) and not artigos[i + 1].titulo:
                artigos[i + 1].titulo = solto

    # Colisão: mesmo identificador vivo duas vezes. O Planalto lista a redação
    # antiga antes da nova, então vence a última — mas isso fica registrado.
    vistos: dict[tuple, Artigo] = {}
    finais: list[Artigo] = []
    for a in artigos:
        chave = (a.numero, a.sufixos)
        if chave in vistos:
            diag["colisoes"].append(rotulo(*chave))
            finais[finais.index(vistos[chave])] = a
        else:
            finais.append(a)
        vistos[chave] = a
    return finais, diag


def linha(a: Artigo, norma: str, documento: str, prep: str, com_notas: bool) -> dict:
    art = rotulo(a.numero, a.sufixos)
    texto = normalizar(" ".join(a.partes), com_notas)
    return {
        "norma": norma,
        "documento": documento,
        "artigo": art,
        # Nomen iuris — o nome do crime. "" quando a norma não dá um.
        "titulo": a.titulo,
        "texto": texto,
        "citacao": f"{art} {prep} {norma}",
        "revogado": a.revogado,
    }


# --------------------------------------------------------------------------- #
# Conferência — falha ruidosa
# --------------------------------------------------------------------------- #

RESIDUO = re.compile(r"[<>]|&[a-zA-Z#][a-zA-Z0-9]{1,8};|[\x00-\x08\x0b-\x1f\x7f-\x9f]|\s\s")


def conferir(resumo: dict, linhas: list[dict]) -> list[str]:
    erros: list[str] = []

    # Tag, entidade não resolvida, byte de controle, espaço duplo. Qualquer um
    # deles significa que a limpeza parou de acontecer em algum caminho.
    sujos = [r["citacao"] for r in linhas if RESIDUO.search(r["texto"])]
    if sujos:
        erros.append(f"{len(sujos)} textos com tag/entidade/controle/espaço duplo: "
                     f"{', '.join(sujos[:6])}")

    # A citação tem de ser derivável do próprio registro, e o texto tem de
    # começar pelo artigo que ele diz ser.
    torto = [r["citacao"] for r in linhas
             if not r["citacao"].startswith(r["artigo"] + " ")
             or not r["texto"].startswith(r["artigo"])]
    if torto:
        erros.append(f"{len(torto)} citações ou rótulos fora de sincronia: "
                     f"{', '.join(torto[:6])}")

    for norma, (esperado, ingenua) in ESPERADO.items():
        obtido = resumo[norma]["total"]
        if obtido != esperado:
            erros.append(
                f"{norma}: {obtido} artigos, esperado {esperado} "
                f"(diferença {obtido - esperado:+d})")
        if obtido == ingenua and esperado != ingenua:
            erros.append(
                f"{norma}: a contagem bateu com a régua INGÊNUA ({ingenua}). "
                f"Entidade HTML voltou a não ser resolvida, ou o riscado voltou a contar.")

    for norma, (lo, hi, lacunas) in FAIXAS.items():
        base = resumo[norma]["numeros_base"]
        if (min(base), max(base)) != (lo, hi):
            erros.append(f"{norma}: numeração {min(base)}–{max(base)}, esperado {lo}–{hi}")
        obtidas = tuple(sorted(set(range(lo, hi + 1)) - base))
        if obtidas != lacunas:
            erros.append(
                f"{norma}: lacunas {obtidas or '()'} — esperado {lacunas or '()'}. "
                f"Cada lacuna é um artigo sobre o qual o copiloto dirá "
                f"'não encontrei' em vez de 'revogado'.")

    for norma, dados in resumo.items():
        vazios = dados["vazios"]
        if vazios:
            erros.append(f"{norma}: {len(vazios)} artigos com texto vazio: "
                         f"{', '.join(vazios[:8])}")
    return erros


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--saida", type=Path, default=SAIDA)
    ap.add_argument("--com-notas", action="store_true",
                    help="mantém '(Redação dada pela ...)' e afins no texto")
    args = ap.parse_args()

    resumo: dict[str, dict] = {}
    linhas: list[dict] = []

    for arquivo, norma, documento, prep in NORMAS:
        artigos, diag = extrair(arquivo, args.com_notas)
        registros = [linha(a, norma, documento, prep, args.com_notas) for a in artigos]
        linhas.extend(registros)
        resumo[norma] = {
            "total": len(registros),
            "revogados": sum(1 for r in registros if r["revogado"]),
            "com_sufixo": sum(1 for a in artigos if a.sufixos),
            "numeros_base": {a.numero for a in artigos},
            "vazios": [r["artigo"] for r in registros
                       if len(INICIO.sub("", r["texto"]).strip()) < 3],
            "caracteres": sum(len(r["texto"]) for r in registros),
            **diag,
        }

    args.saida.parent.mkdir(parents=True, exist_ok=True)
    with args.saida.open("w", encoding="utf-8") as fh:
        for r in linhas:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"\n{args.saida}  —  {len(linhas)} trechos\n")
    cab = (f"{'norma':<26}{'artigos':>8}{'esperado':>9}{'ingênua':>9}{'revog.':>8}"
           f"{'-A':>5}{'riscados':>10}{'títulos':>9}{'colisões':>10}")
    print(cab)
    print("-" * len(cab))
    for _, norma, _, _ in NORMAS:
        d = resumo[norma]
        print(f"{norma:<26}{d['total']:>8}{ESPERADO[norma][0]:>9}{ESPERADO[norma][1]:>9}"
              f"{d['revogados']:>8}{d['com_sufixo']:>5}{d['inicios_riscados']:>10}"
              f"{d['nomen_iuris']:>9}{len(d['colisoes']):>10}")
    print("-" * len(cab))
    print(f"{'TOTAL':<26}{len(linhas):>8}{sum(v[0] for v in ESPERADO.values()):>9}")
    print("\n'ingênua' = o que `Art\\.\\s*\\d+` sem resolver entidade devolveria. "
          "Não é meta:\né o número que este extrator NÃO pode reproduzir.")
    print("'riscados' = redações anteriores descartadas · 'títulos' = nomen iuris "
          "descartado.")
    for _, norma, _, _ in NORMAS:
        for g in resumo[norma]["grupos"]:
            print(f"  grupo expandido em {norma}: {g}")

    erros = conferir(resumo, linhas)
    if erros:
        print("\n*** CORPUS REPROVADO — não use este arquivo ***", file=sys.stderr)
        for e in erros:
            print(f"  ! {e}", file=sys.stderr)
        return 1

    for _, norma, _, _ in NORMAS:
        lo, hi, lacunas = FAIXAS[norma]
        marca = ", ".join(map(str, lacunas)) if lacunas else "nenhuma"
        print(f"  {norma:<26} numeração {lo}–{hi}   lacunas: {marca}")
    print("\nLacuna = artigo sem UMA linha no corpus. No CPP são os arts. 194 e 611: "
          "revogados e RETIRADOS do texto compilado — não há redação para citar, e "
          "inventar uma seria pior que a lacuna.")
    print("Conferência OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
