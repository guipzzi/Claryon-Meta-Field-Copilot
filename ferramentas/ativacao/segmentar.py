"""Corta cada gravação em enunciados isolados, por energia.

As pessoas gravaram varias repeticoes num arquivo so. O detector precisa de
clipes de uma elocucao cada, e no MESMO formato do pipeline: 1,0 s centrado na
energia. Aqui so encontramos as fronteiras; o recorte final e o de sempre.
"""
import os, glob, wave, numpy as np

TAXA, MIN_MS, GAP_MS = 16000, 140, 220

def ler(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)/32768.0

def segmentos(x):
    win = 320                                     # 20 ms
    n = len(x)//win
    e = np.array([np.sqrt((x[i*win:(i+1)*win]**2).mean()) for i in range(n)])
    if e.max() <= 0: return []
    # limiar adaptativo: ruido de fundo = mediana dos quadros mais baixos
    piso = np.median(np.sort(e)[:max(3, n//4)])
    lim = max(piso*3.5, e.max()*0.10)
    ativo = e > lim
    segs, ini = [], None
    silencio = 0
    for i, a in enumerate(ativo):
        if a:
            if ini is None: ini = i
            silencio = 0
        elif ini is not None:
            silencio += 1
            if silencio*20 >= GAP_MS:
                if (i-silencio-ini)*20 >= MIN_MS: segs.append((ini*win, (i-silencio)*win))
                ini, silencio = None, 0
    if ini is not None and (len(ativo)-ini)*20 >= MIN_MS:
        segs.append((ini*win, len(ativo)*win))
    return segs

def escrever(p, x):
    with wave.open(p, "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(TAXA)
        w.writeframes((np.clip(x, -1, 1)*32767).astype(np.int16).tobytes())

os.makedirs("humano/clipes", exist_ok=True)
for f in sorted(glob.glob("humano/bruto/*.wav")):
    nome = os.path.basename(f)[:-4]
    x = ler(f)
    segs = segmentos(x)
    durs = [(b-a)/TAXA for a, b in segs]
    for k, (a, b) in enumerate(segs):
        pad = int(0.12*TAXA)                      # respiro nas duas pontas
        escrever(f"humano/clipes/{nome}__{k:03d}.wav", x[max(0,a-pad):min(len(x),b+pad)])
    print(f"{nome:44s} {len(segs):3d} segmentos  dur {np.mean(durs) if durs else 0:.2f}s "
          f"(min {min(durs) if durs else 0:.2f} max {max(durs) if durs else 0:.2f})")
os._exit(0)
