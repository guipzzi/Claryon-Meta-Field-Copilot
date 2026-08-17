---
feature: stt-portugues
capacidade: transversal — o STT alimenta P1 (transcrição na origem) e P3 (comando por voz)
estado: proposta — **item 3 exige decisão humana de licença**
autor: revisão humana pendente
criada: 2026-08-17
sobrepoe:
  - "ROADMAP.md § meta de STT ≥92% — o número é mantido, mas o caminho para ele muda"
depende_de:
  - silero-vad-embarcado
  - regua-de-wer
---

# STT em português: o que foi medido e o que se decide

## O problema, medido e não opinado

Medido no aparelho em 2026-08-17, com fala pt-BR sintetizada pelo Piper do próprio
APK, banda cheia de 16 kHz — o **melhor caso possível**, muito acima do que o HFP
entrega:

| Falado | Transcrito pelo `ggml-tiny` | WER |
|---|---|---|
| "Central, a guarnição está a caminho da ocorrência." | "central, agora nisso são está caminhinho da ocorrência." | 62,5% |
| "Claryon, mudar para guarnição quatro." | "O marcarion mudar para a guarnição 4." | 80,0% |
| a mesma frase, outra síntese | "Parão, mudar para a Goer Nissan 4." | 100,0% |

Duas palavras falham com consistência, e são justamente as duas de que o produto
depende:

- **"guarnição"** → "nissan", "agora nisso são", "agora a inição", "aguarmissão".
  É a palavra central do domínio: sem ela não há endereçamento de canal por voz.
- **"Claryon"** → "parão", "marcarion", "clarão". É a **palavra de ativação**, e
  todo o desenho mãos-livres de `gatilho-por-voz.spec.md` repousa nela.

Meta do `ROADMAP.md`: **≥92% de acurácia**. Medido: **0% a 37,5%**.

## O que NÃO é a causa

**A banda estreita de 8 kHz não explica isso.** A hipótese era natural — o HFP
corta acima de 4 kHz e é lá que vivem as fricativas. A evidência a refuta: a única
medição controlada disponível (AP-BWE, IEEE TASLP 2024, Tabela IV, Whisper sobre
VCTK) dá **3,67% de WER a 8 kHz interpolado contra 3,07% em banda cheia** — 1,2×
relativo. Uma degradação de 1,2× não transforma "guarnição" em "agora nisso são".

Corolário prático: **não construir *bandwidth extension***. No mesmo experimento,
extensão neural de banda entregou **3,72%** — pior que a interpolação simples.

A causa é o modelo. `ggml-tiny` é o menor da família e faz **20,1% de WER** em
FLEURS `pt_br` mesmo em banda cheia (Tabela 13 de arXiv:2212.04356).

## Decisão

### 1. `ggml-base-q5_1` no lugar de `ggml-tiny` — almoço grátis, já aplicado

Não é *trade-off*, é ganho nos dois eixos:

| | `ggml-tiny` | `ggml-base-q5_1` |
|---|---|---|
| bytes | 77 691 713 | **59 707 625** |
| APK | — | **−17,2 MiB** |
| WER pt, FLEURS `pt_br` | 20,1% | **13,0%** |
| WER pt, CommonVoice 9 | 35,2% | **23,7%** |
| WER pt, MLS | 31,3% | **21,9%** |
| licença | MIT | MIT |

Menor **e** melhor, porque o `tiny` estava em fp16 e este está quantizado em q5_1.
Não havia argumento para manter o `tiny`. *Tamanho conferido byte a byte no
download; WER extraído do PDF do paper.*

### 2. Configurar o que já está vendorizado — custo zero de disco, já aplicado

Todos os campos confirmados por leitura de `whisper.h`, não de memória:

- **`initial_prompt`** (`:527`) com o léxico do domínio. É *prior*, não lista
  obrigatória — e é exatamente onde ter "guarnição" no vocabulário pode ganhar de
  "nissan" quando as pistas espectrais não chegam.
  *`no_context = true` não anula isto*: em `whisper.cpp:6937-6940` o `clear()` roda
  **antes** do bloco que empilha o prompt (6961-6979). Ordem limpa-depois-empilha.
- **`suppress_nst`** (`:538`) — e o nome importa: `suppress_non_speech_tokens`
  **não existe** neste vendorizado e não compila.
- **`single_segment = true`** (`:498`). Fala de rádio é uma frase, não um discurso;
  vários segmentos num comando de 2 s gastam decodificação e convidam ao laço de
  repetição.
- **`audio_ctx`** dimensionado pela fala (`:515`). O Whisper preenche a entrada até
  30 s com zeros e roda o encoder sobre 1500 posições **mesmo para 2 s de fala**.
  Medido: **48 s → 14,9 s** de STT no emulador.
  ⚠️ O header rotula este bloco como *"[EXPERIMENTAL] speed-up techniques — note:
  these can significantly reduce the quality of the output"*. Não entra sem WER
  antes e depois, e a régua existe para isso.

### 3. ⚠️ DECISÃO HUMANA — o modelo que resolve tem licença não comercial

Existe, dentro do AAR do sherpa-onnx **já presente no repositório**, um modelo
português que resolve o problema. Foi **medido**, não deduzido:

**Preset 35 — NeMo FastConformer Hybrid pt-BR, ramo transducer (RNNT)**

- Acertou **"guarnição" em 4 de 4** frases do domínio, onde o `ggml-tiny` devolveu
  "agora nisso são". Saída idêntica à referência: *"Central, a guarnição está a
  caminho da ocorrência."*
- **8 kHz → 16 kHz: texto idêntico em 5 de 5.** O sherpa reamostra sozinho
  (`LinearResample` em `offline-stream.cc`), sem código nosso. A banda estreita
  deixa de ser problema para este modelo.
- **`hotwords` funcionam nele** — contextual biasing com o léxico do domínio,
  medido: sem viés erra "aguarmissão"; com viés em 1,0–1,5 recupera "guarnição".
  É a defesa contra ruído de viatura, e **só transducer aceita** (o ramo CTC, preset
  36, não).
- WER oficial NVIDIA: MCV16 pt **12,03%**, MLS pt 24,78%.
- 132 MB em disco.

**🔴 O bloqueio:** `CC BY-NC 4.0` — **não comercial**. Verificado no front-matter
do model card e no corpo: *"This model is ready for non-commercial use."*

Isto **não é decisão de engenharia**. As saídas:

| Saída | Consequência |
|---|---|
| **(a)** Usar no hackathon e declarar a restrição | Legítimo para pesquisa/competição. Impede uso comercial sem renegociar |
| **(b)** `parakeet-tdt-0.6b-v3` (preset 40), **CC BY 4.0 comercial** | FLEURS pt_br **4,76%**, o melhor medido. Mas **642 MB em disco** e 2,2× o custo — compete com o serviço de rádio pelo LMK, que este projeto já sabe matar o rádio primeiro |
| **(c)** Ficar no Whisper `base`/`small` | MIT limpo. `small-q5_1` faz **7,3%** em FLEURS pt_br, mas são 181 MiB e ~3,3× o encoder do `base` |
| **(d)** Licenciar o NeMo com a NVIDIA | Fora do alcance do prazo de 22/08 |

**Recomendação técnica:** (a) para o hackathon, com a restrição escrita no
documento submetido, e (c) com `small-q5_1` como caminho de produto — a decidir
depois de medir latência em **hardware real**, que é a lacuna que invalida
qualquer escolha feita hoje.

## O que ainda não sabemos, e só o aparelho responde

1. **Latência em arm64 real.** Todo número de latência de modelo desta pesquisa
   veio de um MacBook. Não existe benchmark público confiável de whisper.cpp por
   tamanho de modelo em Android arm64 — a coleção oficial tem **zero** Android.
   Se o fator Mac→celular for 10–20×, o STT sozinho come 500–1300 ms de um
   orçamento de 2000 ms.
2. **Se o SCO dos Ray-Ban Meta negocia mSBC (16 kHz) ou banda estreita.** A doc do
   DAT afirma 8 kHz em três lugares e nunca menciona mSBC — mas doc pode declarar o
   pior caso garantido em vez do que o firmware negocia. Se for mSBC, dobra a banda.
3. **Não existe preset pt *streaming*** em nenhum caminho. O STT só começa depois
   do fim da fala: não há como esconder o custo atrás da fala do agente.
4. **Nenhum corpus público mede a palavra que quebrou.** "guarni" aparece **zero**
   vezes em ~85 000 palavras de FLEURS `pt_br` + VoxForge pt. A régua geral e a
   régua do domínio têm de ser duas.

## Como se mede que funcionou

`VerificadorDoGatilhoTest.qualidadeDoSttNoComando`, instrumentado: sintetiza as
frases que o produto promete entender, transcreve com o modelo real, agrega por
`ΣE / ΣN` e reprova contra os ≥92% do ROADMAP. Duas sínteses por frase porque o
Piper é VITS com `noise_scale = 0.667` e a **mesma frase produz áudio diferente** —
uma rodada é uma amostra, não uma medição.
