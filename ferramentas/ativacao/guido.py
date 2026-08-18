"""Detector do Guido: treino com aumento de dados, avaliacao em fluxo continuo.

Com 27 elocucoes, o que decide nao e o classificador — e o aumento. Cada clipe
vira varias versoes: deslocado no tempo, com ganho diferente, com ruido, e pela
banda estreita do HFP. Sem isso a cabeca decora 27 pontos.

A avaliacao NAO e por clipe recortado. E por janela deslizante sobre as gravacoes
LONGAS, que e como o detector roda em producao: 80 ms de passo, decisao continua.
O que se mede e o que importa no produto — dispara uma vez por "Claryon", e fica
calado no resto, inclusive durante o comando que vem logo depois.
"""
import os, glob, wave, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

JAN, ALVO, TAXA, HOP_MEL = 76, 16000, 16000, 8
rng = np.random.default_rng(7)
mel = ort.InferenceSession("melspectrogram.onnx", providers=["CPUExecutionProvider"])
emb = ort.InferenceSession("embedding_model.onnx", providers=["CPUExecutionProvider"])
MI, EI = mel.get_inputs()[0].name, emb.get_inputs()[0].name

def ler(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)/32768.0

def estreita(x):
    from numpy.fft import rfft, irfft
    X = rfft(x); X[int(len(X)*0.5):] = 0
    return irfft(X, len(x)).astype(np.float32)

def centrar(x, desloca=0):
    e = np.convolve(x**2, np.ones(400)/400, mode="same")
    c = int((np.arange(len(x))*e).sum()/max(e.sum(), 1e-9)) + desloca
    ini = c - ALVO//2
    y = np.zeros(ALVO, np.float32); a, b = max(0, ini), min(len(x), ini+ALVO)
    if b > a: y[a-ini:a-ini+(b-a)] = x[a:b]
    return y

def embed_janelas(x):
    m = mel.run(None, {MI: x[None, :].astype(np.float32)})[0].squeeze()/10.0 + 2.0
    if m.shape[0] < JAN: return np.zeros((0, 96*1)), m
    js = [m[i:i+JAN] for i in range(0, m.shape[0]-JAN+1, HOP_MEL)]
    e = emb.run(None, {EI: np.stack(js)[..., None].astype(np.float32)})[0].reshape(len(js), -1)
    return e, m

def vetor(x):
    return embed_janelas(centrar(x))[0].reshape(-1)

def aumentar(x):
    """Uma elocucao vira 12: deslocamento x ganho x ruido x banda."""
    saida = []
    for d in (-1600, -600, 0, 600, 1600):              # +-100 ms e +-40 ms
        base = centrar(x, d)
        for g in (0.5, 1.0, 1.7):
            y = np.clip(base*g, -1, 1)
            saida.append(y)
            saida.append(np.clip(y + rng.normal(0, 0.006, ALVO).astype(np.float32), -1, 1))
            saida.append(estreita(y))
    return saida

# ── dados do Guido ──────────────────────────────────────────────────────────
pos_f = sorted(glob.glob("humano/clipes/claryon-repetidas-vezes_guido__*.wav")) + \
        sorted(glob.glob("humano/clipes/claryon_guido__*.wav"))
neg_f = sorted(glob.glob("humano/clipes/na-escuta_guido__*.wav"))
# negativos extras: janelas de fundo da propria gravacao, entre as elocucoes
fundo = ler("humano/bruto/claryon-repetidas-vezes_guido.wav")

tr_f, te_f = pos_f[:18], pos_f[18:]
print(f"positivos do Guido: {len(pos_f)}  →  treino {len(tr_f)} · teste {len(te_f)} (momentos diferentes)")

Xtr, ytr = [], []
for f in tr_f:
    for y in aumentar(ler(f)): Xtr.append(embed_janelas(y)[0].reshape(-1)); ytr.append(1)
for f in neg_f:
    for y in aumentar(ler(f)): Xtr.append(embed_janelas(y)[0].reshape(-1)); ytr.append(0)
# fundo: trechos de 1 s sem fala, do mesmo microfone e da mesma sala
for i in range(0, len(fundo)-ALVO, ALVO//2):
    t = fundo[i:i+ALVO]
    if np.sqrt((t**2).mean()) < 0.01:
        Xtr.append(embed_janelas(t)[0].reshape(-1)); ytr.append(0)
Xtr, ytr = np.array(Xtr), np.array(ytr)
print(f"treino após aumento: {len(Xtr)} amostras ({int(ytr.sum())} positivas)")

esc = StandardScaler().fit(Xtr)
clf = LogisticRegression(max_iter=6000, C=0.1, class_weight="balanced").fit(esc.transform(Xtr), ytr)

# ── 1) elocucoes retidas ────────────────────────────────────────────────────
s_te = clf.predict_proba(esc.transform(np.array([vetor(ler(f)) for f in te_f])))[:, 1]
print(f"\n1) {len(te_f)} elocuções retidas do Guido: méd {s_te.mean():.3f} mín {s_te.min():.3f}  " +
      "".join("█" if v > .9 else "▓" if v > .5 else "·" for v in s_te))

# ── 2) fluxo continuo ───────────────────────────────────────────────────────
print("\n2) JANELA DESLIZANTE sobre as gravações longas (80 ms de passo)")
LIMIAR = 0.5
for nome in ("claryon_comandos", "claryon_pergunta-sobre-a-guarnicao",
             "na-escuta_guido", "claryon-repetidas-vezes_guido"):
    x = ler(f"humano/bruto/{nome}.wav")
    e, _ = embed_janelas(x)
    if len(e) == 0: continue
    # cada decisao consome uma PILHA de 3 embeddings (~1 s), igual ao treino,
    # deslizando de 1 embedding por vez — ou seja, decisao nova a cada 80 ms.
    P = 3
    if len(e) < P: continue
    pilhas = np.stack([e[i:i+P].reshape(-1) for i in range(len(e)-P+1)])
    s = clf.predict_proba(esc.transform(pilhas))[:, 1]
    acima = s > LIMIAR
    disparos, i = 0, 0
    while i < len(acima):
        if acima[i]:
            disparos += 1
            i += 12                      # refratario de ~1 s, como um wake word real
        else: i += 1
    dur = len(x)/TAXA
    perfil = "".join("█" if v > .9 else "▓" if v > .5 else "░" if v > .2 else "·" for v in s[::2])
    print(f"   {nome:36s} {dur:5.1f}s  {disparos:2d} disparo(s)")
    print(f"      {perfil}")

# ── 3) LATENCIA: onde o detector cruza o limiar, em relacao ao fim da palavra ──
import time
print("\n3) LATÊNCIA DA DECISÃO — fluxo das 26 repetições")
x = ler("humano/bruto/claryon-repetidas-vezes_guido.wav")
e, _ = embed_janelas(x)
pilhas = np.stack([e[i:i+3].reshape(-1) for i in range(len(e)-2)])
s_fluxo = clf.predict_proba(esc.transform(pilhas))[:, 1]
# cada pilha i termina na amostra: (i+3)*HOP_MEL*160 + JAN*160  (10 ms por quadro de mel)
def fim_da_pilha(i): return ((i + 3) * HOP_MEL + JAN) * 160

# fronteiras reais das elocucoes, pelo mesmo segmentador
win = 320; n = len(x)//win
en = np.array([np.sqrt((x[i*win:(i+1)*win]**2).mean()) for i in range(n)])
piso = np.median(np.sort(en)[:max(3, n//4)]); lim = max(piso*3.5, en.max()*0.10)
ativo = en > lim
segs, ini, sil = [], None, 0
for i, a in enumerate(ativo):
    if a: ini = i if ini is None else ini; sil = 0
    elif ini is not None:
        sil += 1
        if sil*20 >= 220:
            if (i-sil-ini)*20 >= 140: segs.append((ini*win, (i-sil)*win))
            ini, sil = None, 0
if ini is not None: segs.append((ini*win, len(ativo)*win))

atrasos = []
for a, b in segs:
    cruzou = [i for i in range(len(s_fluxo)) if s_fluxo[i] > 0.5 and a <= fim_da_pilha(i) <= b + 16000]
    if cruzou: atrasos.append((fim_da_pilha(cruzou[0]) - b)/TAXA*1000)
atrasos = np.array(atrasos)
print(f"   {len(atrasos)} de {len(segs)} elocuções detectadas")
print(f"   atraso após o FIM da palavra:  mediana {np.median(atrasos):6.0f} ms  "
      f"p90 {np.percentile(atrasos,90):6.0f} ms  máx {atrasos.max():6.0f} ms")
print(f"   (negativo = decidiu antes de a palavra terminar)")

# custo de computo por decisao
t0 = time.perf_counter(); N = 200
for i in range(N):
    j = i % (len(e)-2)
    clf.predict_proba(esc.transform(pilhas[j:j+1]))
t_cabeca = (time.perf_counter()-t0)/N*1000
quadro = np.zeros(ALVO, np.float32)
t0 = time.perf_counter()
for _ in range(20): embed_janelas(quadro)
t_feat = (time.perf_counter()-t0)/20*1000
print(f"\n   custo por decisão nesta máquina: mel+embedding {t_feat:.1f} ms (1 s de áudio) · cabeça {t_cabeca:.2f} ms")
print(f"   o passo é de 80 ms, então há folga de ~{80 - t_feat/12:.0f} ms por passo")
os._exit(0)
