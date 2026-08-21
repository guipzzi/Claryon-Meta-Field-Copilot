"""Quanto do "3,39/h contra 1,36/h" era o positivo, e quanto era a SEMENTE?

O podcast5.py reproduziu 1,36/h exatos para a cabeca so-Guido — mesmo treino,
mesmo teste, mesmo numero. Mas o braco "todos os 22" deu 1,36/h onde o podcast3.py
tinha medido 3,39/h. A especificacao e identica; a unica diferenca e QUAL ruido
gaussiano o `aumentar` sorteou, porque no podcast3.py a cabeca "todos" era treinada
depois da validacao por locutor e herdava o `rng` ja consumido.

Se trocar a semente move o numero de 1,36 para 3,39, entao a comparacao original
media a semente, nao o positivo. 3,39/h em 88,5 min sao CINCO disparos; 1,36/h sao
DOIS. Concluir de uma diferenca de tres eventos exige saber o quanto tres eventos
variam sozinhos — e isso se mede: mesma receita, dez sementes, olha o espalhamento.

Sem essa barra de erro, qualquer ordenacao entre as duas cabecas e opiniao.
"""
import glob, os, sys, time, wave, zlib, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

os.chdir(os.path.dirname(os.path.abspath(__file__)))
exec(open("guido.py").read().split("# ── dados do Guido")[0].replace("os._exit(0)", ""))

CACHE = os.environ.get("CACHE_ATIVACAO", "/tmp/cache_ativacao_v5")
PODCASTS = [f"/tmp/podcast/negativo{s}.wav" for s in ("", "2", "3", "4", "5")]
SEMENTES = range(10)
REFRAT = 12

def log(*a):
    print(*a); sys.stdout.flush()

def embed_em_blocos(x, bloco=16000 * 60):
    saida, i = [], 0
    while i < len(x):
        pedaco = x[i:i + bloco + ALVO]
        if len(pedaco) < ALVO: break
        e, _ = embed_janelas(pedaco)
        if len(e) >= 3:
            saida.append(np.stack([e[j:j + 3].reshape(-1) for j in range(len(e) - 2)]))
        i += bloco
    return np.vstack(saida).astype(np.float32)

def disparos(s, lim, refrat=REFRAT):
    a, n, i = s > lim, 0, 0
    while i < len(a):
        if a[i]: n += 1; i += refrat
        else: i += 1
    return n

# ── embeddings do negativo, em cache (o corte ao meio e SEPARADO por podcast) ──
os.makedirs(CACHE, exist_ok=True)
neg_tr, neg_te, dur_te = [], [], []
for p in PODCASTS:
    nome = os.path.basename(p).replace(".wav", "")
    ftr, fte, fdur = (f"{CACHE}/{nome}_{s}.npy" for s in ("tr", "te", "dur"))
    if all(os.path.exists(f) for f in (ftr, fte, fdur)):
        neg_tr.append(np.load(ftr)); neg_te.append(np.load(fte)); dur_te.append(float(np.load(fdur)))
    else:
        pod = ler(p); meio = len(pod) // 2
        tr, te = embed_em_blocos(pod[:meio]), embed_em_blocos(pod[meio:])
        d = (len(pod) - meio) / TAXA
        np.save(ftr, tr); np.save(fte, te); np.save(fdur, np.array(d))
        neg_tr.append(tr); neg_te.append(te); dur_te.append(d)
        del pod
    log(f"  {nome}: treino {len(neg_tr[-1])} jan · teste {len(neg_te[-1])} jan ({dur_te[-1]/60:.1f} min)")

NEG_TR = {"n2": np.vstack(neg_tr[:2]), "n5": np.vstack(neg_tr)}
NEG_TE_2, NEG_TE_5 = np.vstack(neg_te[:2]), np.vstack(neg_te)
H2, H5 = sum(dur_te[:2]) / 3600, sum(dur_te) / 3600
neg_tr.clear(); neg_te.clear()

leitura = ler("humano/bruto/leitura-tp_guido.wav")
e_l, _ = embed_janelas(leitura[:len(leitura) // 2])
NEG_LEITURA = np.stack([e_l[i:i + 3].reshape(-1) for i in range(len(e_l) - 2)]).astype(np.float32)

guido = sorted(glob.glob("humano/clipes/claryon-repetidas-vezes_guido__*.wav")) + \
        sorted(glob.glob("humano/clipes/claryon_guido__*.wav"))
outros = [f for n in ("bruna", "carla", "pedro") for f in sorted(glob.glob(f"humano/clipes/claryon_{n}__*.wav"))]
POS = {"guido": guido[:18], "todos": guido[:18] + outros}
TE_POS = np.array([vetor(ler(f)) for f in guido[18:]], np.float32)
AUDIO = {f: ler(f) for f in set(guido[:18] + outros)}

def aumentado(f, semente):
    globals()["rng"] = np.random.default_rng((zlib.crc32(f.encode()) + semente * 1000003) & 0x7FFFFFFF)
    return np.stack([vetor(v) for v in aumentar(AUDIO[f])]).astype(np.float32)

def treinar(fs, neg, semente):
    Xp = np.vstack([aumentado(f, semente) for f in fs])
    X = np.vstack([Xp, NEG_LEITURA, neg])
    y = np.concatenate([np.ones(len(Xp), np.int8), np.zeros(len(NEG_LEITURA) + len(neg), np.int8)])
    esc = StandardScaler().fit(X)
    clf = LogisticRegression(max_iter=8000, C=0.1, class_weight="balanced").fit(esc.transform(X), y)
    return esc, clf

BRACOS = [(p, n) for p in ("guido", "todos") for n in ("n2", "n5")]
LIMIARES = (0.5, 0.7, 0.9, 0.95, 0.99, 0.995, 0.999)
res = {b: {"fp2_5": [], "fp5_5": [], "fp5_7": [], "fp5_9": [], "rec": [], "smax": [],
           "grade_fp": [], "grade_rec": []} for b in BRACOS}

log(f"\n  negativo retido: 2 podcasts {H2*60:.1f} min · 5 podcasts {H5*60:.1f} min")
log(f"  {len(list(SEMENTES))} sementes x 4 bracos\n")
for s in SEMENTES:
    linha = []
    for b in BRACOS:
        p, n = b
        m = treinar(POS[p], NEG_TR[n], s)
        esc, clf = m
        sn2 = clf.predict_proba(esc.transform(NEG_TE_2))[:, 1]
        sn5 = clf.predict_proba(esc.transform(NEG_TE_5))[:, 1]
        sp = clf.predict_proba(esc.transform(TE_POS))[:, 1]
        r = res[b]
        r["fp2_5"].append(disparos(sn2, 0.5) / H2)
        r["fp5_5"].append(disparos(sn5, 0.5) / H5)
        r["fp5_7"].append(disparos(sn5, 0.7) / H5)
        r["fp5_9"].append(disparos(sn5, 0.9) / H5)
        r["rec"].append(float((sp > 0.5).mean()))
        r["smax"].append(float(sn5.max()))
        r["grade_fp"].append([disparos(sn5, l) / H5 for l in LIMIARES])
        r["grade_rec"].append([float((sp > l).mean()) for l in LIMIARES])
        linha.append(f"{p}/{n} {disparos(sn2,0.5)/H2:5.2f}|{disparos(sn5,0.5)/H5:5.2f}")
        del m, esc, clf
    log(f"  semente {s}: " + "  ".join(linha))

def resumo(v):
    a = np.array(v)
    return f"med {np.median(a):5.2f}  min {a.min():5.2f}  max {a.max():5.2f}"

log("\n" + "=" * 86)
log("ESPALHAMENTO POR SEMENTE — mesma receita, so o ruido do aumento muda")
log("=" * 86)
for chave, rot, h in (("fp2_5", "FP/h @0,5 nos 88,5 min (o teste antigo)", H2),
                      ("fp5_5", "FP/h @0,5 nos 182,1 min", H5),
                      ("fp5_7", "FP/h @0,7 nos 182,1 min", H5),
                      ("fp5_9", "FP/h @0,9 nos 182,1 min", H5)):
    log(f"\n  {rot}")
    for b in BRACOS:
        a = np.array(res[b][chave])
        log(f"    {b[0]+'/'+b[1]:<12} {resumo(a)}   (disparos: {sorted(int(round(x*h)) for x in a)})")

log("\n  recall@0,5 nas 9 elocucoes retidas do Guido")
for b in BRACOS:
    a = np.array(res[b]["rec"])
    log(f"    {b[0]+'/'+b[1]:<12} med {np.median(a):.0%}  min {a.min():.0%}  max {a.max():.0%}")
log("\n  escore maximo no negativo retido de 182,1 min")
for b in BRACOS:
    a = np.array(res[b]["smax"])
    log(f"    {b[0]+'/'+b[1]:<12} med {np.median(a):.3f}  min {a.min():.3f}  max {a.max():.3f}")

log("\n" + "=" * 86)
log("A TABELA: limiar x falso-positivo x recall, nos 182,1 min retidos")
log("mediana das 10 sementes, com [min-max] entre elas — o intervalo E a barra de erro")
log("=" * 86)
for b in BRACOS:
    fp = np.array(res[b]["grade_fp"]); rc = np.array(res[b]["grade_rec"])
    log(f"\n  {b[0]}/{b[1]}")
    log(f"  {'limiar':>7} {'FP/h (med)':>11} {'[min–max]':>14} {'disp.':>7} {'recall(med)':>12} {'[min–max]':>12}")
    for j, l in enumerate(LIMIARES):
        c = fp[:, j] * H5
        log(f"  {l:>7.3f} {np.median(fp[:,j]):>11.2f} {f'[{fp[:,j].min():.2f}–{fp[:,j].max():.2f}]':>14} "
            f"{f'{int(c.min())}–{int(c.max())}':>7} {np.median(rc[:,j]):>11.0%} "
            f"{f'[{rc[:,j].min():.0%}–{rc[:,j].max():.0%}]':>12}")

# Zero disparo NAO e zero taxa. Com 0 eventos em 3,04 h, a regra dos tres da um
# teto de 95% de 3/3,04 = 0,99/h — ainda o dobro da meta. Para AFIRMAR 0,5/h com
# zero disparo seria preciso 3/0,5 = 6 h de negativo retido; temos 3,04.
log(f"\n  regra dos tres: 0 disparo em {H5:.2f} h → teto de 95% em {3/H5:.2f}/h "
    f"(a meta de 0,5/h exigiria {3/0.5:.0f} h retidas; temos {H5:.2f})")

log("\n  guido/n5 menos todos/n5, semente a semente (FP/h @0,5, 182,1 min):")
d = np.array(res[("guido", "n5")]["fp5_5"]) - np.array(res[("todos", "n5")]["fp5_5"])
log(f"    {[f'{x:+.2f}' for x in d]}")
log(f"    guido melhor em {int((d<0).sum())}/{len(d)} sementes · pior em {int((d>0).sum())} · empate em {int((d==0).sum())}")
