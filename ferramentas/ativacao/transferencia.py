"""O experimento que decide: treina SO com Piper, testa em VOZ HUMANA.

Todo numero anterior foi Piper contra Piper — mede se o detector reconhece o
Piper. Aqui o treino nao ve um unico humano, e o teste nao ve um unico sintetico.
Se o escore dos positivos humanos ficar em cima do dos negativos humanos, dados
sinteticos servem e a receita inteira esta liberada. Se nao, nao servem.

O controle esta embutido: os clipes de "na escuta" sao fala humana real das mesmas
quatro pessoas, gravada no mesmo aparelho, e NAO contem a palavra. Se o detector
disparar neles tanto quanto nos positivos, ele esta reagindo a "tem voz", nao a
"tem Claryon".
"""
import os, glob, wave, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

JAN, ALVO, TAXA = 76, 16000, 16000
mel = ort.InferenceSession("melspectrogram.onnx", providers=["CPUExecutionProvider"])
emb = ort.InferenceSession("embedding_model.onnx", providers=["CPUExecutionProvider"])
MI, EI = mel.get_inputs()[0].name, emb.get_inputs()[0].name

def ler(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)/32768.0

def centrar(x):
    e = np.convolve(x**2, np.ones(400)/400, mode="same")
    c = int((np.arange(len(x))*e).sum()/max(e.sum(), 1e-9))
    ini = c - ALVO//2
    y = np.zeros(ALVO, np.float32); a, b = max(0, ini), min(len(x), ini+ALVO)
    y[a-ini:a-ini+(b-a)] = x[a:b]
    return y

def banda_estreita(x):
    """8 kHz e volta, com FIR simples — o que o HFP entrega."""
    from numpy.fft import rfft, irfft
    X = rfft(x); n = len(X)
    X[int(n*0.5):] = 0                       # corta acima de 4 kHz
    return irfft(X, len(x)).astype(np.float32)

def vetor(x):
    m = mel.run(None, {MI: centrar(x)[None, :]})[0].squeeze()/10.0 + 2.0
    js = [m[i:i+JAN] for i in range(0, m.shape[0]-JAN+1, 8)]
    e = emb.run(None, {EI: np.stack(js)[..., None].astype(np.float32)})[0].reshape(len(js), -1)
    return e.reshape(-1)

# ── treino: SO Piper ────────────────────────────────────────────────────────
Xtr, ytr = [], []
for p in sorted(glob.glob("corpus/estreita/*.wav")):
    Xtr.append(vetor(ler(p))); ytr.append(int(os.path.basename(p).startswith("pos")))
Xtr, ytr = np.array(Xtr), np.array(ytr)
esc = StandardScaler().fit(Xtr)
clf = LogisticRegression(max_iter=4000, class_weight="balanced").fit(esc.transform(Xtr), ytr)
print(f"treino: {len(Xtr)} clipes do Piper ({ytr.sum()} positivos) — nenhum humano\n")

# ── teste: SO humano ────────────────────────────────────────────────────────
grupos = {
    "POSITIVO Guido (repetições)": "humano/clipes/claryon-repetidas-vezes_guido__*.wav",
    "POSITIVO Guido":              "humano/clipes/claryon_guido__*.wav",
    "POSITIVO Carla":              "humano/clipes/claryon_carla__*.wav",
    "POSITIVO Pedro":              "humano/clipes/claryon_pedro__*.wav",
    "POSITIVO Bruna":              "humano/clipes/claryon_bruna__*.wav",
    "controle 'na escuta' Guido":  "humano/clipes/na-escuta_guido__*.wav",
    "controle 'na escuta' Carla":  "humano/clipes/na-escuta_carla__*.wav",
    "controle 'na escuta' Pedro":  "humano/clipes/na-escuta_pedro__*.wav",
    "controle 'na escuta' Bruna":  "humano/clipes/na-escuta_bruna__*.wav",
}
for etiqueta, banda in (("BANDA CHEIA", lambda v: v), ("BANDA ESTREITA (HFP)", banda_estreita)):
    print(f"{'='*72}\n{etiqueta}")
    tudo = {}
    for nome, padrao in grupos.items():
        fs = sorted(glob.glob(padrao))
        if not fs: continue
        s = clf.predict_proba(esc.transform(np.array([vetor(banda(ler(f))) for f in fs])))[:, 1]
        tudo[nome] = s
        barra = "".join("█" if v > .9 else "▓" if v > .5 else "░" if v > .1 else "·" for v in s)
        print(f"  {nome:30s} n={len(s):2d}  méd {s.mean():.3f}  mín {s.min():.3f}  {barra}")
    pos = np.concatenate([v for k, v in tudo.items() if k.startswith("POSITIVO")])
    neg = np.concatenate([v for k, v in tudo.items() if k.startswith("controle")])
    print(f"\n  positivos humanos n={len(pos)}  méd {pos.mean():.3f}")
    print(f"  controles humanos n={len(neg)}  méd {neg.mean():.3f}  máx {neg.max():.3f}")
    print(f"  recall com ZERO falso positivo: {(pos > neg.max()).mean()*100:.1f}%\n")
os._exit(0)
