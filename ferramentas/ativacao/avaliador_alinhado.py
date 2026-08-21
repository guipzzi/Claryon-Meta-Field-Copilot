"""Avaliação com o MESMO laço do aparelho — e por que isso importa.

O Python previu 0,00/h na metade retida; o aparelho deu 0,52/h. Os dois estavam
certos sobre coisas diferentes, e enquanto divergirem todo ajuste feito aqui
precisa ser reconferido lá — o que dobra o ciclo e faz o número daqui não valer
como decisão.

As três diferenças, lidas de `DetectorDeAtivacao.aceitar`:

1. **O anel é circular e contínuo.** Aqui eu embutia blocos independentes com
   sobreposição de uma janela; lá o anel de 1 s corre amostra a amostra e a decisão
   sai a cada 1280 amostras a partir do INÍCIO do fluxo, não do início de um bloco.
   As janelas caem em posições diferentes.

2. **O refratário é em MILISSEGUNDOS, não em janelas.** `refratarioMs = 1000` conta
   amostras desde o disparo; eu contava 12 janelas de 80 ms = 960 ms. Perto, e
   diferente o bastante para mudar a contagem numa fronteira.

3. **O anel só decide depois de CHEIO.** As primeiras 16 000 amostras de cada fluxo
   não produzem decisão nenhuma. Eu decidia desde a primeira janela de mel.
"""
import numpy as np

TAXA, ALVO, PASSO, JAN, HOP = 16000, 16000, 1280, 76, 8

def disparos_como_no_aparelho(x, mel, emb, MI, EI, w, b, limiar=0.5, refratario_ms=1000):
    """Replica `DetectorDeAtivacao.aceitar` sobre um fluxo contínuo.

    Devolve (n_disparos, maior_escore, posicoes_em_segundos).
    """
    refrat = int(refratario_ms * TAXA / 1000)
    n, maior, quando = 0, 0.0, []
    desde_disparo = 10**9
    # O anel do aparelho é circular; aqui a janela é a fatia [i-ALVO, i), que é
    # exatamente o conteúdo dele quando cheio.
    i = ALVO
    while i <= len(x):
        desde_disparo += PASSO
        janela = x[i-ALVO:i].astype(np.float32)
        m = mel.run(None, {MI: janela[None, :]})[0].squeeze()/10.0 + 2.0
        if m.shape[0] >= JAN:
            js = [m[k:k+JAN] for k in range(0, m.shape[0]-JAN+1, HOP)]
            e = emb.run(None, {EI: np.stack(js)[..., None].astype(np.float32)})[0].reshape(len(js), -1)
            if len(e) >= 3:
                v = e[-3:].reshape(-1)
                s = 1/(1+np.exp(-(v @ w + b)))
                maior = max(maior, float(s))
                if s > limiar and desde_disparo >= refrat:
                    n += 1; desde_disparo = 0
                    quando.append(i/TAXA)
        i += PASSO
    return n, maior, quando
