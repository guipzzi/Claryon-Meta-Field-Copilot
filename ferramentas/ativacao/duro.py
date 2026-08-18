"""Retreino com negativo DURO: a leitura de 3,65 min entra como fala contra.

O detector anterior viu 3 negativos humanos e 428 falsos por hora apareceram no
primeiro material de fala continua. A correcao nao e mexer no limiar — e mostrar
fala ao treino.

Protocolo, que e o que impede o numero de mentir: a leitura e cortada ao meio no
TEMPO. A primeira metade treina, a segunda mede. Treinar e medir na mesma leitura
daria zero falso positivo e nao significaria nada.
"""
import os, glob, wave, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
exec(open("guido.py").read().split("# ── 1) elocucoes")[0].replace("os._exit(0)",""))

leitura = ler("humano/bruto/leitura-tp_guido.wav")
meio = len(leitura)//2
e_tr, _ = embed_janelas(leitura[:meio])
e_te, _ = embed_janelas(leitura[meio:])
neg_tr = np.stack([e_tr[i:i+3].reshape(-1) for i in range(len(e_tr)-2)])
neg_te = np.stack([e_te[i:i+3].reshape(-1) for i in range(len(e_te)-2)])
print(f"leitura: {len(leitura)/TAXA:.0f} s → treino {len(neg_tr)} janelas · TESTE {len(neg_te)} janelas")
print(f"positivos: treino {len(tr_f)} elocuções · teste {len(te_f)} (Xtr já tem {len(Xtr)} amostras aumentadas)\n")

X2 = np.vstack([Xtr, neg_tr])
y2 = np.concatenate([ytr, np.zeros(len(neg_tr), int)])
esc2 = StandardScaler().fit(X2)
clf2 = LogisticRegression(max_iter=8000, C=0.1, class_weight="balanced").fit(esc2.transform(X2), y2)

s_neg = clf2.predict_proba(esc2.transform(neg_te))[:, 1]
s_pos = clf2.predict_proba(esc2.transform(np.array([vetor(ler(f)) for f in te_f])))[:, 1]
dur_h = (len(leitura)-meio)/TAXA/3600

def disparos(s, lim, refrat=12):
    a, n, i = s > lim, 0, 0
    while i < len(a):
        if a[i]: n += 1; i += refrat
        else: i += 1
    return n

print(f"{'limiar':>8} {'falsos':>7} {'por hora':>9} {'IC95 sup':>9}   recall retido")
melhor = None
for lim in (0.5, 0.9, 0.99, 0.999):
    d = disparos(s_neg, lim)
    sup = (3.0 if d == 0 else d + 1.96*np.sqrt(d)) / dur_h
    rec = (s_pos > lim).mean()*100
    print(f"{lim:8.3f} {d:7d} {d/dur_h:9.1f} {sup:9.1f}   {rec:5.1f}% ({int((s_pos>lim).sum())}/{len(s_pos)})")
    if d == 0 and melhor is None: melhor = lim

print(f"\nANTES (3 negativos no treino):  limiar 0,5 → 428 falsos/h")
print(f"escore da leitura retida: méd {s_neg.mean():.4f}  p99 {np.percentile(s_neg,99):.4f}  máx {s_neg.max():.4f}")
print(f"escore dos positivos retidos: mín {s_pos.min():.4f}")
print(f"\nmargem = pior positivo − pior negativo = {s_pos.min()-s_neg.max():+.4f}")

w2v, b2v = clf2.coef_[0]/esc2.scale_, float(clf2.intercept_[0] - (clf2.coef_[0]*esc2.mean_/esc2.scale_).sum())
np.concatenate([w2v.astype(np.float32), np.float32([b2v])]).tofile("cabeca_guido_v2.f32")
print(f"cabeça v2 salva: {os.path.getsize('cabeca_guido_v2.f32')} bytes")
os._exit(0)
