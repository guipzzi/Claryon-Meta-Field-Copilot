"""Separabilidade SEM o atalho da duração.

A primeira rodada deu 100% e estava contaminada: 3 dos 4 positivos eram frases de
1,3 a 2,2 s e TODO negativo era palavra isolada de ate 0,85 s. Media e maximo sobre
um numero variavel de janelas carregam o comprimento, e o classificador achou o
atalho antes de olhar para a palavra.

Aqui todo clipe vira exatamente 1,0 s centrado na energia, e o positivo e so
"Claryon." isolado — mesma classe de duracao dos negativos.
"""
import os, glob, wave, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

JAN, TAXA, ALVO = 76, 16000, 16000
mel = ort.InferenceSession("melspectrogram.onnx", providers=["CPUExecutionProvider"])
emb = ort.InferenceSession("embedding_model.onnx", providers=["CPUExecutionProvider"])
MI, EI = mel.get_inputs()[0].name, emb.get_inputs()[0].name

def ler(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)/32768.0

def centrar(x):
    """Recorta/preenche para ALVO amostras, centrado no centroide de energia."""
    e = np.convolve(x**2, np.ones(400)/400, mode="same")
    c = int((np.arange(len(x))*e).sum()/max(e.sum(), 1e-9))
    ini = c - ALVO//2
    y = np.zeros(ALVO, np.float32)
    a, b = max(0, ini), min(len(x), ini+ALVO)
    y[a-ini:a-ini+(b-a)] = x[a:b]
    return y

def vetor(p):
    x = centrar(ler(p))[None, :]
    m = mel.run(None, {MI: x})[0].squeeze()/10.0 + 2.0
    js = [m[i:i+JAN] for i in range(0, m.shape[0]-JAN+1, 8)]
    e = emb.run(None, {EI: np.stack(js)[..., None].astype(np.float32)})[0].reshape(len(js), -1)
    return e.reshape(-1)           # janelas fixas: concatena, sem media/maximo

def carregar(banda):
    X, y, rot, r = [], [], [], []
    for p in sorted(glob.glob(f"corpus/{banda}/*.wav")):
        n = os.path.basename(p)[:-4]; a, i = n.rsplit("_", 1)
        if a.startswith("pos") and a != "pos0":     # so o "Claryon." isolado
            continue
        X.append(vetor(p)); y.append(int(a.startswith("pos"))); rot.append(a); r.append(int(i))
    return np.array(X), np.array(y), np.array(rot), np.array(r)

# Dez palavras inteiras fora do treino, escolhidas para serem as mais duras:
# as vizinhas de "clar-" e as rimas em -on que derrubaram o portão por transcrição.
FORA = ["neg_clarim", "neg_clarao", "neg_claro", "neg_clarinete", "neg_clarissimo",
        "neg_cordon", "neg_canon", "neg_trombone", "neg_cambiando", "neg_alerta"]
for banda in ("cheia", "estreita"):
    X, y, rot, r = carregar(banda)
    tr = ~np.isin(rot, FORA) & (r <= 39)
    te = np.isin(rot, FORA) | ((y == 1) & (r >= 40))
    esc = StandardScaler().fit(X[tr])
    clf = LogisticRegression(max_iter=4000, class_weight="balanced").fit(esc.transform(X[tr]), y[tr])
    s = clf.predict_proba(esc.transform(X[te]))[:, 1]
    pos, neg = s[y[te] == 1], s[y[te] == 0]
    corte = neg.max()
    print(f"\nBANDA {banda.upper()}  {len(X)} clipes, {int(y.sum())} positivos ('Claryon.' isolado)")
    print(f"   teste: {len(pos)} positivos · {len(neg)} negativos de palavras INÉDITAS ({', '.join(f.replace('neg_','') for f in FORA)})")
    print(f"   recall com ZERO falso positivo .. {(pos>corte).mean()*100:5.1f}%")
    print(f"   escore  pos {pos.mean():.3f} (pior {pos.min():.3f})  ·  neg {neg.mean():.3f} (pior {neg.max():.3f})")
    ordem = np.argsort(-s)
    rotTe = rot[te]
    print("   posição dos positivos no ranking: " +
          ", ".join(str(int(np.where(ordem == i)[0][0]) + 1) for i in np.where(y[te] == 1)[0][:8]) + " ...")
    piores = [(rotTe[i].replace("neg_", ""), s[i]) for i in ordem if y[te][i] == 0][:5]
    print("   negativos mais perigosos: " + " · ".join(f"{w} {v:.3f}" for w, v in piores))
os._exit(0)
