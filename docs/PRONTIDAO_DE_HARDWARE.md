# Prontidão de hardware — o aparelho de referência e as duas bordas

**Critério §11.2 do edital — viabilidade técnica, 30 pontos:** *"Maturidade e
exequibilidade da solução proposta."*

Este documento responde à metade difícil: **exequibilidade**. O hackathon é de **um
dia** (§12.3, 11h00 às 17h30), e os óculos e o smartphone são **fornecidos pela
organização** (§9) — a equipe não leva o próprio aparelho. Então a pergunta que
importa não é "roda na nossa bancada", é **"roda num aparelho que nunca vimos, em seis
horas"**.

A resposta é uma faixa declarada, não um aparelho único.

---

## 1. O aparelho de referência: Samsung Galaxy M34

O desenvolvimento é calibrado para o **Samsung Galaxy M34** — de propósito. É um
aparelho de faixa média, e faixa média é o que uma corporação de segurança pública
compra em lote. Otimizar para o topo de linha produziria um número bonito e uma
solução que não escala para o cliente real.

| Referência | Valor |
|---|---|
| Aparelho | Samsung Galaxy M34 |
| RAM | 4–6 GB conforme SKU |
| `minSdk` do projeto | 31 |
| `compileSdk` | 35 |

---

## 2. O orçamento de memória, medido

Estes números são de medição em emulador API 35 arm64, `dumpsys meminfo`. Não são
estimativa.

| Estado | PSS | RSS |
|---|---|---|
| Tela de abertura | **171 MB** | 268 MB |
| Em operação (Piper carregado, whisper ainda não) | **264 MB** | 372 MB |

### Os modelos, bytes reais em disco

| Arquivo | MiB | Carga |
|---|---|---|
| `ggml-small-q5_1.bin` (STT) | **181,28** | preguiçosa, no 1º ciclo de voz |
| `pt_BR-faber-medium.onnx` (TTS) | 17,82 | preguiçosa, **não liberada** |
| `ativacao/` (mel + embedding + cabeça) | 2,31 | preguiçosa |
| `silero_vad.onnx` | 0,61 | por ciclo, liberado |

**APK:** debug 384,6 MiB · release 369,9 MiB.

### A correção que esta medição produziu

Cinco pontos do código raciocinavam sobre *"~75–78 MB"* de whisper residente. O valor
real é **181 MiB** — **2,4× errado**. E ele é lido por *streaming* para o heap nativo
(`core-voice/src/main/cpp/jni.c:98-147`, `AASSET_MODE_STREAMING`), **não** por `mmap`:
são 181 MiB de memória suja e não-descartável, não páginas que o sistema possa
recuperar sob pressão.

Projeção para o M34 com a Etapa A ativa: **≈420–560 MB de PSS**.

---

## 3. Por que 181 MB, e não 75

Esta é uma escolha, não um descuido, e o custo foi aceito com número na mão.

| Modelo | Tamanho | Por que não |
|---|---|---|
| `ggml-tiny` | ~75 MB | WER inaceitável em pt-BR |
| `ggml-base` | ~142 MB | melhor, ainda insuficiente para radiocomunicação |
| **`ggml-small-q5_1`** | **181 MB** | **WER 3,4% medido em pt-BR** — o escolhido |

Português é onde os modelos pequenos mais degradam, e o domínio piora: alfabeto militar,
indicativos, ordinais de placa. Um erro de transcrição aqui não é um typo — é uma placa
errada consultada, ou um endereço errado despachado.

**A regra que decidiu:** transcrição errada é pior que transcrição ausente, porque a
ausente o agente percebe. Por isso o projeto paga 106 MB a mais.

---

## 4. Preparados para pior: degradação declarada

A regra dura do produto é **falha nunca é silêncio**. Cada recurso ausente tem
comportamento definido e falado — não trava, não some, não mente.

| Se faltar | O que acontece | Verificável em |
|---|---|---|
| Modelo de STT | Ciclo de voz recusa com motivo; PTT continua | `Modelos.kt:52-60` → `null` |
| Modelo do LLM | `LerVerbatim(SEM_MODELO)` — cita a norma sem redigir | `PoliticaDeRedacao.kt:99` |
| Voz do Piper | Protegido por `existeModelo()` antes de tocar o nativo | `PiperTts.kt:69-73` |
| Detector de ativação | Log de aviso + estado `SEM_MODELO`; PTT segue | `EscutaDeAtivacao.kt:169` |
| Rota de áudio (sem óculos) | Estado `SEM_ROTA`; captura **não compila** pelo microfone do celular | `EscutaDeAtivacao.kt:174` |
| Rede | *"Sem dados. O canal depende da rede."* · `SEM SINAL` · `SEM POSIÇÃO PRÓPRIA` | medido com `airplane_mode 1` |
| Câmera dos óculos | Oito causas do SDK, faladas **por recuperação** | `FalhaDaCamera.kt` |
| RAM insuficiente para o LLM | Portão exige 3 GiB e 1,90× o modelo; recusa antes de tentar | `PoliticaDeRedacao.kt:50,57` |

**O caso que ainda não é honesto:** se apenas os *tiles* do mapa forem bloqueados
(Wi-Fi de evento com filtro de DNS, Supabase acessível), `OnStyleLoaded` nunca dispara
e o mapa fica **preto e mudo** — sem marcador e sem mensagem. Não há
`addOnDidFailLoadingMapListener` no projeto. Declarado aqui como pendência.

---

## 5. Preparados para melhor: o que escala com o aparelho

A degradação é o piso; o teto também é aproveitado.

- **Etapa B (LLM local)** só liga onde há RAM: o portão mede antes de carregar
  (`PoliticaDeRedacao`, piso de 3 GB de RAM total e folga de 1,90× sobre o GGUF).

  > ⚠️ **Este item dizia "num aparelho de 8 GB o copiloto redige", e isso não é
  > verdade em produção** — corrigido em 22/08. `RedacaoDoCopiloto.redigir` tem
  > **zero chamadores em `src/main`**: o portão decide, o modelo carrega, e
  > ninguém pede a redação. Em qualquer aparelho, de 4 ou de 16 GB, o agente ouve
  > a **citação** (`"Art. 306, Lei 9.503"`). Pelo `CLAUDE.md` §6 isso é
  > capacidade **escrita**, não construída.
  >
  > E a medição de 22/08 diz que ligá-la, hoje, pioraria o produto. Com a
  > configuração de produção, sobre as 20 perguntas do banco de abordagem que
  > passam o limiar de 0,30 (emulador arm64 API 35, 2,5 GB):
  >
  > | | |
  > |---|---|
  > | sem texto nenhum (prazo de 2 500 ms estourado) | **7 de 20** |
  > | com texto | 13 |
  > | aprovadas pelo guarda de lastro | **10** |
  > | dessas 10, **utilizáveis** por leitura humana do log | **≈2** |
  >
  > O resto são ecos da própria pergunta (*"O número do motor foi remarcado."*),
  > vazamentos do andaime do prompt (*"TRECHO DA NORMA: …"*) e uma consequência
  > inventada. Ver `OrcamentoDaEtapaBNoAparelhoTest`.

- **A leitura do artigo em voz alta não existe** e não é o degrau de baixo da
  Etapa B: ela esbarra no teto de 7 palavras do `CLAUDE.md` §4 e está **proposta**
  em `specs/leitura-de-norma.spec.md`, esperando decisão humana.
- **Provedor de posição** acompanha o modo: `NETWORK_PROVIDER` em Standby,
  `GPS_PROVIDER` em Ativo e Ocorrência.
- **Freio térmico** com `NaN` tratado: em aparelho que aguenta mais, o freio não atua.

---

## 6. Contingência para o dia 18/09

Riscos ordenados por probabilidade, com resposta escrita **antes** do dia.

| # | Risco | Resposta |
|---|---|---|
| 1 | APK de 384 MB não instala ou demora demais | `adb install` durante o onboarding das 09h30, antes do desenvolvimento começar às 11h |
| 2 | Aparelho da organização com pouco espaço | Variante sem o `.gguf` (que já não vai no APK) e, no limite, `ggml-base` como plano B |
| 3 | Wi-Fi do local bloqueia o Supabase | O rádio degrada com motivo; a demonstração de placa por câmera e a consulta de norma são **100% locais** e não dependem de rede |
| 4 | Pareamento dos óculos falha | `MockDeviceKit` roda o fluxo inteiro; a falha é falada por recuperação |
| 5 | Óculos esquentam sob uso contínuo | `THERMAL_HOT` já mapeado; a fala manda esperar esfriar em vez de insistir |
| 6 | Bateria dos óculos no meio da tarde | Coffee break das 15h30 é explicitamente *"pausa para recarregar dispositivos"* (§12.3) |

---

## 7. O que não foi medido, e é preciso dizer

1. **Bateria dos óculos** com a `DeviceSession` aberta — não medida. É o item que mais
   importa para um plantão de 12 h e o que menos temos número.
2. **Comportamento térmico** em uso contínuo — tratado no código, nunca provocado.
3. **Consumo com whisper residente** — nunca disparamos um ciclo de voz completo em
   emulador, porque o HAL de áudio devolve `pcm_readi failed`.
4. **Footprint no M34 real** — as projeções acima vêm de emulador de 2,41 GB.

---

*Documento vivo. Cada número aqui é medição, e cada ausência de número está nomeada.*
