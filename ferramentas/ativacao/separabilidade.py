"""O teste que mata a ideia barato: o embedding pré-treinado separa "Claryon"
das vizinhas acústicas em português?

O KWS por texto morreu porque o modelo acústico era inglês e a voz portuguesa. O
mesmo risco existe aqui: o embedding do openWakeWord foi treinado em inglês. Se ele
não transferir, a cabeça de classificação treina sobre features que não separam nada
— e é melhor descobrir isso hoje, sem tocar no build do Android.

Dois cortes, do mais fácil para o mais duro:
  A) por rendição — treina nas rendições 0-7, testa nas 8-11. Mesmas palavras.
  B) por PALAVRA  — as vizinhas de teste (clarim, clarao, claro, cambiando...)
     nunca aparecem no treino. É este que diz se generaliza.
"""
import os, sys, glob, wave
import numpy as np
import onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

RAIZ = os.path.dirname(os.path.abspath(__file__))
JANELA = 76          # quadros de mel que o embedding consome
HOP = 8              # ~80 ms entre janelas

mel = ort.InferenceSession(f"{RAIZ}/melspectrogram.onnx", providers=["CPUExecutionProvider"])
emb = ort.InferenceSession(f"{RAIZ}/embedding_model.onnx", providers=["CPUExecutionProvider"])
MEL_IN, EMB_IN = mel.get_inputs()[0].name, emb.get_inputs()[0].name


def ler_wav(p):
    with wave.open(p) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return x.astype(np.float32) / 32768.0


def vetor(p):
    """Um clipe vira um vetor: media e maximo dos embeddings da janela deslizante."""
    x = ler_wav(p)[None, :]
    m = mel.run(None, {MEL_IN: x})[0].squeeze() / 10.0 + 2.0   # escala do openWakeWord
    if m.shape[0] < JANELA:                                     # clipe curto: preenche
        m = np.pad(m, ((0, JANELA - m.shape[0]), (0, 0)), constant_values=m.min())
    janelas = [m[i:i + JANELA] for i in range(0, m.shape[0] - JANELA + 1, HOP)]
    lote = np.stack(janelas)[..., None].astype(np.float32)
    e = emb.run(None, {EMB_IN: lote})[0].reshape(len(janelas), -1)
    return np.concatenate([e.mean(0), e.max(0)])


def carregar(banda):
    X, y, palavra, rend = [], [], [], []
    for p in sorted(glob.glob(f"{RAIZ}/corpus/{banda}/*.wav")):
        nome = os.path.basename(p)[:-4]
        rot, i = nome.rsplit("_", 1)
        X.append(vetor(p)); y.append(1 if rot.startswith("pos") else 0)
        palavra.append(rot); rend.append(int(i))
    return np.array(X), np.array(y), np.array(palavra), np.array(rend)


def avaliar(Xtr, ytr, Xte, yte):
    esc = StandardScaler().fit(Xtr)
    clf = LogisticRegression(max_iter=2000, C=1.0, class_weight="balanced")
    clf.fit(esc.transform(Xtr), ytr)
    s = clf.predict_proba(esc.transform(Xte))[:, 1]
    pos, neg = s[yte == 1], s[yte == 0]
    if len(neg) == 0 or len(pos) == 0:
        return None
    # o numero que importa: recall com ZERO falso positivo
    corte = neg.max()
    rec0 = float((pos > corte).mean())
    return rec0, float(pos.mean()), float(neg.mean()), float(pos.min()), float(neg.max())


for banda in ("cheia", "estreita"):
    X, y, palavra, rend = carregar(banda)
    print(f"\n{'='*70}\nBANDA {banda.upper()}  —  {len(X)} clipes, {int(y.sum())} positivos")

    # Corte A: por rendicao
    tr, te = rend <= 7, rend >= 8
    a = avaliar(X[tr], y[tr], X[te], y[te])
    print(f"  A) por rendição  recall com 0 falso positivo: {a[0]*100:5.1f}%   "
          f"escore médio  pos {a[1]:.3f} / neg {a[2]:.3f}")

    # Corte B: por palavra — negativas de teste nunca vistas
    fora = {"neg_clarim", "neg_clarao", "neg_claro", "neg_cambiando", "neg_alerta"}
    trB = ~np.isin(palavra, list(fora)) & (rend <= 9)
    teB = np.isin(palavra, list(fora)) | ((y == 1) & (rend >= 10))
    b = avaliar(X[trB], y[trB], X[teB], y[teB])
    print(f"  B) por PALAVRA   recall com 0 falso positivo: {b[0]*100:5.1f}%   "
          f"escore médio  pos {b[1]:.3f} / neg {b[2]:.3f}")
    print(f"     pior positivo {b[3]:.3f}  ·  pior negativo {b[4]:.3f}"
          f"  ·  {int((np.isin(palavra, list(fora))).sum())} negativos inéditos")

os._exit(0)
