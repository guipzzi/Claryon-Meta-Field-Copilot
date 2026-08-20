"""Retreino com negativo duro de VERDADE: 54 min de fala espontanea.

O detector media 89,85/h de falso positivo em podcast — 180x a meta de 0,5/h, com
escore maximo de 0,997. Nao e detector hesitando na fronteira: e detector
convicto, e mexer no limiar nao conserta isso sem derrubar o recall junto.

O `duro.py` ja tinha feito esta correcao uma vez, com 3,65 min de leitura em voz
alta de um locutor. Funcionou para aquele material e nao generalizou: leitura e
negativo facil — um locutor, diccao cuidada, sem sobreposicao, sem risada, sem
musica. Fala espontanea de podcast tem tudo isso.

PROTOCOLO, e e ele que impede o numero de mentir: o podcast e cortado ao meio no
TEMPO. A primeira metade treina, a segunda mede. Treinar e medir no mesmo material
daria zero falso positivo e nao significaria nada — e a metade de teste nunca e
vista pelo treino, nem pelo escalador.
"""
import glob, wave, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
exec(open("guido.py").read().split("# ── dados do Guido")[0].replace("os._exit(0)", ""))

PODCAST = "/tmp/podcast/negativo.wav"

def embed_em_blocos(x, bloco=16000*60):
    """54 min de uma vez estoura a memoria. Blocos de 1 min, com sobreposicao de
    uma janela para nao perder as decisoes que caem na emenda."""
    saida = []
    i = 0
    while i < len(x):
        pedaco = x[i:i+bloco+ALVO]
        if len(pedaco) < ALVO: break
        e, _ = embed_janelas(pedaco)
        if len(e) >= 3:
            saida.append(np.stack([e[j:j+3].reshape(-1) for j in range(len(e)-2)]))
        i += bloco
    return np.vstack(saida)

print("lendo o podcast…")
pod = ler(PODCAST)
meio = len(pod)//2
print(f"  {len(pod)/TAXA/60:.1f} min → treino {meio/TAXA/60:.1f} min · TESTE {(len(pod)-meio)/TAXA/60:.1f} min")

neg_tr = embed_em_blocos(pod[:meio])
neg_te = embed_em_blocos(pod[meio:])
print(f"  janelas: treino {len(neg_tr)} · teste {len(neg_te)}")

# Positivos e o negativo antigo (leitura) continuam entrando: tirar a leitura
# trocaria um negativo por outro em vez de somar.
leitura = ler("humano/bruto/leitura-tp_guido.wav")
e_l, _ = embed_janelas(leitura[:len(leitura)//2])
neg_leitura = np.stack([e_l[i:i+3].reshape(-1) for i in range(len(e_l)-2)])

pos_f = sorted(glob.glob("humano/clipes/claryon-repetidas-vezes_guido__*.wav")) + \
        sorted(glob.glob("humano/clipes/claryon_guido__*.wav"))
tr_f, te_f = pos_f[:18], pos_f[18:]
Xp = np.stack([vetor(v) for f in tr_f for v in aumentar(ler(f))])
print(f"  positivos: {len(pos_f)} elocucoes → {len(Xp)} amostras aumentadas · teste {len(te_f)}")

X = np.vstack([Xp, neg_leitura, neg_tr])
y = np.concatenate([np.ones(len(Xp), int), np.zeros(len(neg_leitura)+len(neg_tr), int)])
esc = StandardScaler().fit(X)
clf = LogisticRegression(max_iter=8000, C=0.1, class_weight="balanced").fit(esc.transform(X), y)

def disparos(s, lim, refrat=12):
    a, n, i = s > lim, 0, 0
    while i < len(a):
        if a[i]: n += 1; i += refrat
        else: i += 1
    return n

s_neg = clf.predict_proba(esc.transform(neg_te))[:, 1]
s_pos = clf.predict_proba(esc.transform(np.array([vetor(ler(f)) for f in te_f])))[:, 1]
horas = (len(pod)-meio)/TAXA/3600

print(f"\nNA METADE NUNCA VISTA ({horas*60:.1f} min):")
print(f"  {'limiar':>7} {'disparos':>9} {'por hora':>9} {'recall':>8}")
melhor = None
for lim in (0.5, 0.7, 0.9, 0.95, 0.99, 0.995, 0.999):
    n = disparos(s_neg, lim); ph = n/horas
    rec = (s_pos > lim).mean()
    print(f"  {lim:>7.3f} {n:>9} {ph:>9.2f} {rec:>7.0%}")
    if melhor is None and ph <= 0.5 and rec >= 0.9: melhor = (lim, ph, rec)
print(f"  escore max no negativo: {s_neg.max():.4f} · min no positivo: {s_pos.min():.4f}")
if melhor: print(f"\n  ✓ limiar {melhor[0]}: {melhor[1]:.2f}/h com recall {melhor[2]:.0%}")
else: print("\n  ✗ NENHUM limiar atinge 0,5/h com recall >= 90% neste material")

w = clf.coef_[0]/esc.scale_; b = float(clf.intercept_[0] - (clf.coef_[0]*esc.mean_/esc.scale_).sum())
np.concatenate([w.astype(np.float32), np.float32([b])]).tofile("cabeca_podcast.f32")
print(f"\n  cabeca gravada: cabeca_podcast.f32 ({(len(w)+1)*4} bytes)")
