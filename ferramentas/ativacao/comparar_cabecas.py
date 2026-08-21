"""As cabecas GRAVADAS, medidas no mesmo negativo retido — nao as reconstruidas.

Comparar a candidata com um `guido/n2` treinado de novo responde "a receita nova
e melhor que a receita velha". A decisao de implantar precisa de outra coisa: a
cabeca que esta NO APK hoje (`cabeca_v3.f32`, byte a byte igual a
`app/src/main/assets/models/ativacao/cabeca.f32`) contra a candidata, no mesmo
audio. E o arquivo, com os pesos que ele tem, nao uma reproducao dele.

Os 182,1 min sao retidos para TODAS as cabecas aqui: nenhuma viu a segunda metade
de podcast nenhum. As v3/v4 nem sequer viram a primeira metade dos podcasts 3, 4 e 5.

O escore e calculado como o Kotlin calcula — produto escalar sobre o vetor CRU,
sem escalador, porque escalador e regressao ja foram dobrados numa camada so.
E o refratario e 12 janelas = 960 ms, que e o `refratarioMs = 1_000` do
`DetectorDeAtivacao`, para o disparo contado aqui ser o disparo que o aparelho da.
"""
import os, sys, numpy as np, onnxruntime as ort
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "guido.py"))
     .read().split("# ── dados do Guido")[0].replace("os._exit(0)", ""))
os.chdir(os.path.dirname(os.path.abspath(__file__)))

CACHE = os.environ.get("CACHE_ATIVACAO", "/tmp/cache_ativacao_v5")
NOMES = ["negativo", "negativo2", "negativo3", "negativo4", "negativo5"]
LIMIARES = (0.5, 0.7, 0.9, 0.95, 0.99, 0.995, 0.999)
# `cabeca_v5_todos.f32` so existe se alguem tiver rodado o podcast5.py e guardado:
# ele perdeu em 10 de 10 sementes e nao fica no disco. Este script mede o que achar.
CABECAS = [n for n in ("cabeca_v3.f32", "cabeca_v4.f32", "cabeca_v5.f32", "cabeca_v5_todos.f32")
           if os.path.exists(n)]

neg_te = [np.load(f"{CACHE}/{n}_te.npy") for n in NOMES]
dur = [float(np.load(f"{CACHE}/{n}_dur.npy")) for n in NOMES]
X = np.vstack(neg_te); H = sum(dur) / 3600

import glob
guido = sorted(glob.glob("humano/clipes/claryon-repetidas-vezes_guido__*.wav")) + \
        sorted(glob.glob("humano/clipes/claryon_guido__*.wav"))
outros = [f for n in ("bruna", "carla", "pedro") for f in sorted(glob.glob(f"humano/clipes/claryon_{n}__*.wav"))]
P = np.array([vetor(ler(f)) for f in guido[18:]], np.float32)      # 9 retidas do Guido
O = np.array([vetor(ler(f)) for f in outros], np.float32)          # 4 de voz nova

def escore(c, V):
    w, b = c[:-1], c[-1]
    return 1.0 / (1.0 + np.exp(-(V.astype(np.float64) @ w.astype(np.float64) + float(b))))

def disparos(s, lim, refrat=12):
    a, n, i = s > lim, 0, 0
    while i < len(a):
        if a[i]: n += 1; i += refrat
        else: i += 1
    return n

print(f"negativo retido: {H*60:.1f} min = {H:.2f} h · positivos retidos: {len(P)} do Guido, {len(O)} de voz nova\n")
S = {}
for nome in CABECAS:
    c = np.fromfile(nome, np.float32)
    assert len(c) == 289, f"{nome}: {len(c)} floats"
    S[nome] = (escore(c, X), escore(c, P), escore(c, O), [escore(c, t) for t in neg_te])

print(f"{'limiar':>7} " + " ".join(f"{n.replace('cabeca_','').replace('.f32',''):>22}" for n in CABECAS))
print("        " + " ".join(f"{'FP/h  (disp)  recall':>22}" for _ in CABECAS))
for lim in LIMIARES:
    cel = []
    for n in CABECAS:
        sn, sp, _, _ = S[n]
        d = disparos(sn, lim)
        cel.append(f"{d/H:>6.2f} {'('+str(d)+')':>6} {(sp>lim).mean():>7.0%}")
    print(f"{lim:>7.3f} " + " ".join(f"{c:>22}" for c in cel))

print(f"\n{'escore max no negativo':<26}" + " ".join(f"{S[n][0].max():>10.4f}" for n in CABECAS))
print(f"{'escore min nas 9 retidas':<26}" + " ".join(f"{S[n][1].min():>10.4f}" for n in CABECAS))
print(f"{'voz nova >0,5 (de 4)':<26}" + " ".join(f"{str((S[n][2]>0.5).sum())+'/4':>10}" for n in CABECAS)
      + "    ← so e medida para v3 e v5 (v4/v5_todos treinaram nessas vozes)")
print(f"{'escore min voz nova':<26}" + " ".join(f"{S[n][2].min():>10.4f}" for n in CABECAS))

print(f"\nFP/h no limiar 0,5, por podcast (metade retida):")
print(f"{'podcast':<12} {'min':>6} " + " ".join(f"{n.replace('cabeca_','').replace('.f32',''):>16}" for n in CABECAS))
for i, nm in enumerate(NOMES):
    h = dur[i] / 3600
    print(f"{nm:<12} {h*60:>6.1f} " +
          " ".join(f"{disparos(S[n][3][i],0.5)/h:>10.2f} ({disparos(S[n][3][i],0.5)})" for n in CABECAS))
