"""O avaliador alinhado concorda com o aparelho? Contra-teste do item 3."""
import numpy as np, onnxruntime as ort, wave
from avaliador_alinhado import disparos_como_no_aparelho, TAXA
mel = ort.InferenceSession("melspectrogram.onnx", providers=["CPUExecutionProvider"])
emb = ort.InferenceSession("embedding_model.onnx", providers=["CPUExecutionProvider"])
MI, EI = mel.get_inputs()[0].name, emb.get_inputs()[0].name
c = np.fromfile("cabeca_v3.f32", np.float32); w, b = c[:-1], float(c[-1])
def ler(p):
    with wave.open(p) as f: return np.frombuffer(f.readframes(f.getnframes()), np.int16).astype(np.float32)/32768.0
# O aparelho mediu 1 disparo em 115,5 min, em fatia_06@1906,6 s do 1º podcast.
x = ler("/tmp/podcast/negativo.wav")
n, maior, quando = disparos_como_no_aparelho(x, mel, emb, MI, EI, w, b)
print(f"1º podcast ({len(x)/TAXA/60:.1f} min): {n} disparo(s), maior escore {maior:.3f}")
print(f"  em: {[f'{q:.1f}s' for q in quando]}")
print(f"  aparelho disse: 1 disparo, escore 0,508, aos 1906,6 s")
