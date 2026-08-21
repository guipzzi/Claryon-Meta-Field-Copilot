"""Retreino com os CINCO podcasts: 364 min de fala espontanea, 5x o negativo anterior.

A pergunta nao e "melhorou". E se a conclusao anterior — treinar com os quatro
locutores PIORA o falso positivo — se mantem quando o negativo cresce 5x. Havia
duas leituras possiveis do 1,36/h vs 3,39/h medidos com dois podcasts:

  (i)  os 1-2 clipes por pessoa alargam mesmo a fronteira, e mais negativo nao
       conserta porque o problema esta no positivo;
  (ii) 88,5 min retidos sao pouco material — 2 disparos contra 5 e diferenca de
       tres eventos, que uma amostra maior pode desfazer.

Para separar as duas, o desenho e FATORIAL. Duas escolhas de positivo (so Guido /
todos os 22) x duas de negativo de TREINO (2 podcasts / 5 podcasts), e as quatro
cabecas medidas no MESMO negativo retido. Sem isso, "mudou" nao distingue efeito
do positivo de efeito do tamanho da amostra de teste.

PROTOCOLO QUE NAO MUDA: cada podcast e cortado ao meio no TEMPO, SEPARADAMENTE.
A primeira metade treina, a segunda mede. Concatenar antes de cortar poria um
programa inteiro no treino e outro no teste — e a medida viraria "generaliza para
outro podcast", que e pergunta diferente. Como esta, cada programa aparece nos
dois lados, e nenhum segundo de audio esta nos dois.
"""
import glob, os, sys, time, wave, zlib, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

os.chdir(os.path.dirname(os.path.abspath(__file__)))
exec(open("guido.py").read().split("# ── dados do Guido")[0].replace("os._exit(0)", ""))

PODCASTS = [f"/tmp/podcast/negativo{s}.wav" for s in ("", "2", "3", "4", "5")]
JA_USADOS = 2          # negativo.wav e negativo2.wav ja tinham entrado em treino
LIMIARES = (0.5, 0.7, 0.9, 0.95, 0.99, 0.995, 0.999)
REFRAT = 12            # ~1 s, como um wake word real


def log(*a):
    print(*a); sys.stdout.flush()


def embed_em_blocos(x, bloco=16000 * 60):
    """Blocos de 1 min, com sobreposicao de uma janela para nao perder as decisoes
    que caem na emenda. Identico ao podcast3.py, para os numeros serem comparaveis."""
    saida, i = [], 0
    while i < len(x):
        pedaco = x[i:i + bloco + ALVO]
        if len(pedaco) < ALVO:
            break
        e, _ = embed_janelas(pedaco)
        if len(e) >= 3:
            saida.append(np.stack([e[j:j + 3].reshape(-1) for j in range(len(e) - 2)]))
        i += bloco
    return np.vstack(saida).astype(np.float32)


def disparos(s, lim, refrat=REFRAT):
    a, n, i = s > lim, 0, 0
    while i < len(a):
        if a[i]:
            n += 1; i += refrat
        else:
            i += 1
    return n


# ── negativo: cada podcast ao meio, separadamente ───────────────────────────
t0 = time.perf_counter()
neg_tr, neg_te, dur_te, dur_tr = [], [], [], []
for p in PODCASTS:
    pod = ler(p)
    meio = len(pod) // 2
    tr, te = embed_em_blocos(pod[:meio]), embed_em_blocos(pod[meio:])
    neg_tr.append(tr); neg_te.append(te)
    dur_tr.append(meio / TAXA); dur_te.append((len(pod) - meio) / TAXA)
    log(f"  {p.split('/')[-1]:<14} {len(pod)/TAXA/60:6.1f} min → treino {len(tr):>6} jan · "
        f"teste {len(te):>6} jan ({dur_te[-1]/60:.1f} min)")
    del pod
log(f"  ({time.perf_counter()-t0:.0f} s de mel+embedding)")

NEG_TR_2 = np.vstack(neg_tr[:JA_USADOS])
NEG_TR_5 = np.vstack(neg_tr)
NEG_TE_2 = np.vstack(neg_te[:JA_USADOS])
NEG_TE_5 = np.vstack(neg_te)
neg_tr.clear()          # ja copiados nas duas pilhas acima; 157 MB de sobra sem isso
H_TE_2 = sum(dur_te[:JA_USADOS]) / 3600
H_TE_5 = sum(dur_te) / 3600
log(f"\n  negativo de TREINO: 2 podcasts {len(NEG_TR_2)} jan ({sum(dur_tr[:JA_USADOS])/60:.1f} min) · "
    f"5 podcasts {len(NEG_TR_5)} jan ({sum(dur_tr)/60:.1f} min)")
log(f"  negativo RETIDO:    2 podcasts {H_TE_2*60:.1f} min · 5 podcasts {H_TE_5*60:.1f} min")

# a leitura em voz alta continua entrando: tira-la trocaria um negativo por outro
leitura = ler("humano/bruto/leitura-tp_guido.wav")
e_l, _ = embed_janelas(leitura[:len(leitura) // 2])
NEG_LEITURA = np.stack([e_l[i:i + 3].reshape(-1) for i in range(len(e_l) - 2)]).astype(np.float32)

# ── positivos ───────────────────────────────────────────────────────────────
guido = sorted(glob.glob("humano/clipes/claryon-repetidas-vezes_guido__*.wav")) + \
        sorted(glob.glob("humano/clipes/claryon_guido__*.wav"))
outros = {n: sorted(glob.glob(f"humano/clipes/claryon_{n}__*.wav")) for n in ("bruna", "carla", "pedro")}
TR_GUIDO, TE_GUIDO = guido[:18], guido[18:]
F_OUTROS = [f for v in outros.values() for f in v]

_cache = {}
def aumentado(f):
    """45 versoes por elocucao. A semente vem do NOME do arquivo, nao da ordem das
    chamadas — assim dois modelos que compartilham um clipe veem exatamente as
    mesmas amostras, e a diferenca entre eles nao pode vir do ruido sorteado."""
    if f not in _cache:
        globals()["rng"] = np.random.default_rng(zlib.crc32(f.encode()) & 0x7FFFFFFF)
        _cache[f] = np.stack([vetor(v) for v in aumentar(ler(f))]).astype(np.float32)
    return _cache[f]

def vetores(fs):
    return np.array([vetor(ler(f)) for f in fs], np.float32)

TE_POS = vetores(TE_GUIDO)              # 9 elocucoes do Guido, nunca vistas por modelo nenhum
TE_OUTROS = vetores(F_OUTROS)           # 4 elocucoes de voz nova (so retidas nos modelos "so Guido")
log(f"\n  positivos: Guido {len(guido)} ({len(TR_GUIDO)} treino · {len(TE_GUIDO)} retidas) · "
    + " · ".join(f"{n} {len(v)}" for n, v in outros.items()))


def treinar(files_pos, neg_pod):
    Xp = np.vstack([aumentado(f) for f in files_pos])
    X = np.vstack([Xp, NEG_LEITURA, neg_pod])
    y = np.concatenate([np.ones(len(Xp), np.int8), np.zeros(len(NEG_LEITURA) + len(neg_pod), np.int8)])
    esc = StandardScaler().fit(X)
    clf = LogisticRegression(max_iter=8000, C=0.1, class_weight="balanced").fit(esc.transform(X), y)
    return esc, clf

def escores(m, X):
    esc, clf = m
    return clf.predict_proba(esc.transform(X))[:, 1].astype(np.float64)


# ── as quatro cabecas do desenho fatorial ───────────────────────────────────
MODELOS = {}
for np_, fp in (("guido", TR_GUIDO), ("todos", TR_GUIDO + F_OUTROS)):
    for nn, neg in (("n2", NEG_TR_2), ("n5", NEG_TR_5)):
        t = time.perf_counter()
        MODELOS[f"{np_}/{nn}"] = treinar(fp, neg)
        log(f"  treinada {np_}/{nn} ({len(fp)} elocucoes positivas) em {time.perf_counter()-t:.0f} s")

S = {k: {"te2": escores(m, NEG_TE_2), "te5": escores(m, NEG_TE_5),
         "pos": escores(m, TE_POS), "out": escores(m, TE_OUTROS),
         "pod": [escores(m, t) for t in neg_te]} for k, m in MODELOS.items()}


def tabela(k, alvo, horas, rot):
    s_neg, s_pos = S[k][alvo], S[k]["pos"]
    log(f"\n  {k.upper():<12} no negativo retido de {rot} ({horas*60:.1f} min = {horas:.2f} h)")
    log(f"  {'limiar':>7} {'disparos':>9} {'FP/h':>8} {'recall':>8}  (recall = {len(s_pos)} elocucoes retidas do Guido)")
    for lim in LIMIARES:
        n = disparos(s_neg, lim)
        log(f"  {lim:>7.3f} {n:>9} {n/horas:>8.2f} {(s_pos>lim).mean():>7.0%}")
    log(f"  escore max no negativo {s_neg.max():.4f} · min no positivo retido {s_pos.min():.4f}")


log("\n" + "=" * 78)
log("REPRODUCAO: mesmo treino de antes (2 podcasts), mesmo teste de antes (88,5 min)")
log("=" * 78)
tabela("guido/n2", "te2", H_TE_2, "2 podcasts")
tabela("todos/n2", "te2", H_TE_2, "2 podcasts")

log("\n" + "=" * 78)
log("CONTROLE: treino velho (2 podcasts), teste NOVO e 2x maior — o 1,36/h aguenta?")
log("=" * 78)
tabela("guido/n2", "te5", H_TE_5, "5 podcasts")
tabela("todos/n2", "te5", H_TE_5, "5 podcasts")

log("\n" + "=" * 78)
log("NOVO: treino com os 5 podcasts, medido no mesmo negativo retido de 5 podcasts")
log("=" * 78)
tabela("guido/n5", "te5", H_TE_5, "5 podcasts")
tabela("todos/n5", "te5", H_TE_5, "5 podcasts")

log("\n" + "=" * 78)
log("(c) O POSITIVO DE 4 LOCUTORES AINDA PIORA?  FP/h no MESMO negativo retido (5 podcasts)")
log("=" * 78)
log(f"  {'limiar':>7} " + " ".join(f"{k:>12}" for k in MODELOS))
for lim in LIMIARES:
    log(f"  {lim:>7.3f} " + " ".join(f"{disparos(S[k]['te5'],lim)/H_TE_5:>12.2f}" for k in MODELOS))
log(f"\n  recall nas 9 elocucoes retidas do Guido, por limiar:")
log(f"  {'limiar':>7} " + " ".join(f"{k:>12}" for k in MODELOS))
for lim in LIMIARES:
    log(f"  {lim:>7.3f} " + " ".join(f"{(S[k]['pos']>lim).mean():>11.0%}" for k in MODELOS))
log(f"\n  escore nas 4 elocucoes de VOZ NOVA (bruna/carla/pedro) — so e 'retido' nos modelos guido/*:")
for k in MODELOS:
    o = S[k]["out"]
    log(f"    {k:<12} min {o.min():.3f} · med {np.median(o):.3f} · max {o.max():.3f} · "
        f"acima de 0,5: {(o>0.5).sum()}/{len(o)}" + ("" if k.startswith("guido") else "   (VISTAS NO TREINO — nao e medida)"))

log("\n  FP/h por podcast no limiar 0,5 (metade retida de cada um):")
log(f"  {'podcast':<14} {'min':>6} " + " ".join(f"{k:>12}" for k in MODELOS))
for i, p in enumerate(PODCASTS):
    h = dur_te[i] / 3600
    log(f"  {p.split('/')[-1]:<14} {h*60:>6.1f} " +
        " ".join(f"{disparos(S[k]['pod'][i],0.5)/h:>12.2f}" for k in MODELOS))

# ── deixando-um-locutor-de-fora, agora com o negativo grande ────────────────
# Com 1 ou 2 elocucoes por pessoa nao da para dividir cada locutor: ou ele treina,
# ou ele mede. A saida e treinar quatro vezes, cada uma sem uma pessoa, e medir o
# recall NA pessoa que ficou de fora — estimativa de generalizacao para voz nova
# sem desperdicar elocucao nenhuma.
log("\n" + "=" * 78)
log("DEIXANDO-UM-LOCUTOR-DE-FORA (treino com os 5 podcasts como negativo)")
log("=" * 78)
todos_loc = dict(outros); todos_loc["guido"] = guido
for fora in todos_loc:
    tr = [f for n, v in todos_loc.items() if n != fora for f in (v[:18] if n == "guido" else v)]
    m = treinar(tr, NEG_TR_5)
    alvo = TE_GUIDO if fora == "guido" else todos_loc[fora]
    sp, sn = escores(m, vetores(alvo)), escores(m, NEG_TE_5)
    log(f"  sem {fora:<6} → recall@0,5 {(sp>0.5).mean():>4.0%} ({len(alvo)} elocucoes) · "
        f"escore min {sp.min():.3f} · FP@0,5 {disparos(sn,0.5)/H_TE_5:>5.2f}/h")
    del m

# ── gravacao das cabecas ────────────────────────────────────────────────────
def gravar(k, nome):
    esc, clf = MODELOS[k]
    w = clf.coef_[0] / esc.scale_
    b = float(clf.intercept_[0] - (clf.coef_[0] * esc.mean_ / esc.scale_).sum())
    np.concatenate([w.astype(np.float32), np.float32([b])]).tofile(nome)
    log(f"\n  gravada {nome}: {k} ({(len(w)+1)*4} bytes)")

gravar("guido/n5", "cabeca_v5.f32")
# A cabeca "todos" e gravada para poder ser MEDIDA pelo comparar_cabecas.py, nao
# para ser implantada: ela perdeu em 10 de 10 sementes (podcast5_semente.py). Foi
# apagada do disco depois da comparacao; rodar este script de novo a reconstroi.
gravar("todos/n5", "cabeca_v5_todos.f32")
