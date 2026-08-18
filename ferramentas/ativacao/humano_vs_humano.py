"""O embedding separa "Claryon" HUMANO de fala humana? Pergunta diferente da anterior.

O teste de transferencia mostrou que a CABECA treinada no Piper nao dispara em voz
humana. Isso pode ser (a) o embedding nao representa a palavra em voz humana, ou
(b) o embedding representa, mas o Piper ocupa uma regiao do espaco tao distante que
a fronteira aprendida la nao serve aqui. Sao coisas MUITO diferentes:

  (a) mata a receita inteira.
  (b) so diz que dados sinteticos sozinhos nao bastam — que e o esperado.

Aqui a cabeca e treinada e testada em HUMANO, com locutor deixado de fora:
treina nas 26 repeticoes do Guido, testa nos outros tres, que o treino nunca viu.
"""
import os, glob, wave, numpy as np, onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

JAN, ALVO = 76, 16000
mel = ort.InferenceSession("melspectrogram.onnx", providers=["CPUExecutionProvider"])
emb = ort.InferenceSession("embedding_model.onnx", providers=["CPUExecutionProvider"])
MI, EI = mel.get_inputs()[0].name, emb.get_inputs()[0].name

def ler(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)/32768.0

def centrar(x):
    e = np.convolve(x**2, np.ones(400)/400, mode="same")
    c = int((np.arange(len(x))*e).sum()/max(e.sum(), 1e-9)); ini = c - ALVO//2
    y = np.zeros(ALVO, np.float32); a, b = max(0, ini), min(len(x), ini+ALVO)
    y[a-ini:a-ini+(b-a)] = x[a:b]; return y

def vetor(p):
    m = mel.run(None, {MI: centrar(ler(p))[None, :]})[0].squeeze()/10.0 + 2.0
    js = [m[i:i+JAN] for i in range(0, m.shape[0]-JAN+1, 8)]
    return emb.run(None, {EI: np.stack(js)[..., None].astype(np.float32)})[0].reshape(-1)

def carregar(padrao): return [(f, vetor(f)) for f in sorted(glob.glob(padrao))]

guido_pos = carregar("humano/clipes/claryon-repetidas-vezes_guido__*.wav") + \
            carregar("humano/clipes/claryon_guido__*.wav")
guido_neg = carregar("humano/clipes/na-escuta_guido__*.wav")
outros_pos = sum((carregar(f"humano/clipes/claryon_{n}__*.wav") for n in ("carla","pedro","bruna")), [])
outros_neg = sum((carregar(f"humano/clipes/na-escuta_{n}__*.wav") for n in ("carla","pedro","bruna")), [])
comandos = carregar("humano/clipes/claryon_comandos__*.wav")

Xtr = np.array([v for _, v in guido_pos + guido_neg])
ytr = np.array([1]*len(guido_pos) + [0]*len(guido_neg))
print(f"treino: SO Guido — {len(guido_pos)} positivos, {len(guido_neg)} negativos")
print(f"teste : Carla, Pedro e Bruna — {len(outros_pos)} positivos, {len(outros_neg)} negativos")
print("        (locutores que o treino NUNCA viu)\n")

esc = StandardScaler().fit(Xtr)
clf = LogisticRegression(max_iter=4000, class_weight="balanced").fit(esc.transform(Xtr), ytr)

for nome, dados in (("POSITIVO inédito", outros_pos), ("controle inédito", outros_neg)):
    s = clf.predict_proba(esc.transform(np.array([v for _, v in dados])))[:, 1]
    for (f, _), v in zip(dados, s):
        print(f"  {nome:18s} {os.path.basename(f)[:-4]:42s} {v:.3f} " +
              ("█" if v > .9 else "▓" if v > .5 else "░" if v > .1 else "·"))
sp = clf.predict_proba(esc.transform(np.array([v for _, v in outros_pos])))[:, 1]
sn = clf.predict_proba(esc.transform(np.array([v for _, v in outros_neg])))[:, 1]
print(f"\n  positivos inéditos  méd {sp.mean():.3f}  mín {sp.min():.3f}")
print(f"  controles inéditos  méd {sn.mean():.3f}  máx {sn.max():.3f}")
print(f"  separa? {'SIM — todos os positivos acima de todos os controles' if sp.min() > sn.max() else 'NÃO'}")
os._exit(0)
