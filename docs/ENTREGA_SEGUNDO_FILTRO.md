# Entrega do Segundo Filtro — CEIA/Meta AI Glasses Brasil 2026

Equipe **Claryon**. Este documento versiona o que foi submetido, para que o
conteúdo da entrega e o repositório não divirjam. Os limites de caracteres são
do formulário oficial e todos foram respeitados.

**Título da solução:** Claryon Field — copiloto de voz para guarnições de segurança pública

**Resumo:** Copiloto de voz que roda 100% no celular: lê placas pela câmera dos óculos, liga a guarnição por rádio com transcrição e consulta a norma sem nuvem.

---

## Seção A

### A1 — O problema

Numa blitz da PMERJ, o agente de checagem faz de 60 a 150 consultas de placa por turno. Cada uma custa no mínimo 2,5 minutos: tirar o celular, abrir o app, esperar carregar, entrar no Sinesp Cidadão e digitar. São 2h30 de uma operação de 4 a 6 horas. E o custo maior não é o tempo — é olhar para a tela justamente na abordagem. Pela voz, menos de 1 segundo.

### A2 — Usuário-alvo

Policial militar de 25 a 45 anos, em blitz de 4 a 6 horas ativas com 6 a 10 agentes. Aborda de luva, com as mãos ocupadas e em rua barulhenta. A guarnição soma de 300 a 500 abordagens por operação. Três policiais da PMERJ, um de Batalhão Rodoviário, descreveram esta rotina.

### A3 — Walkthrough de interação

1. O agente, parado atrás de um veículo, diz "Claryon" — o microfone dos óculos está sempre aberto por HFP/SCO e a palavra é detectada 100% no celular por uma cabeça de wake word em ONNX. Earcon grave confirma a escuta.
2. Ele completa: "leia essa placa". O áudio vai pelo HFP para o celular e é transcrito localmente por whisper.cpp (ggml-small-q5_1, 181 MiB). Nada de áudio sai do aparelho.
3. O roteador de intenções, determinístico, classifica em 93 µs e aciona a câmera dos óculos pelo Meta Wearables Device Access Toolkit 0.9.0 (`withCamera`, perfil de 7 fps).
4. Dois frames chegam ao celular. O ML Kit Text Recognition, on-device, lê o plano Y em 8 ms (p50) e um validador estrito confere o padrão Mercosul/antigo. Em 31 cenas sintéticas com degradação controlada — chuva, contraluz, oclusão, barro, noite — nenhuma placa errada foi aceita. Ainda não medimos em fotografia real.
5. A placa validada é consultada; o servidor devolve situação, nunca dado de terceiro.
6. A resposta chega ao ouvido pelo alto-falante dos óculos, sintetizada localmente pelo Piper: "Placa regular, sem restrição." Teto de sete palavras, por regra do produto.
7. Ciclo completo medido de 873 a 945 ms, do fim da fala ao início do áudio, em emulador arm64 API 35.
8. Se a placa estiver ilegível, o passo 4 recusa em vez de adivinhar — o que acontece no A4.

### A4 — Walkthrough de exceção

Placa coberta de barro. (1) O validador rejeita: 6 caracteres não formam padrão Mercosul nem antigo, e afrouxar transformaria DEF4567 em DEF456 — outro carro. (2) O sistema não consulta e não arrisca; degrada para recusa tipada, não para silêncio. (3) O agente ouve um earcon de falha e "Placa ilegível. Aproxime." — em 31 cenas sintéticas, zero placas erradas aceitas.

## A5 — Decisões técnicas e trade-offs

### Decisão 1

**A decisão.** Fizemos áudio por HFP/SCO em vez de pelo SDK dos óculos

**Por que esse lado.** O DAT 0.9.0 não expõe áudio — conferido por javap no AAR: Stream tem vídeo, erro e foto, e som nenhum. HFP é o único caminho para microfone e alto-falante.

**O que isso custou.** Custa 8 kHz mono e perder o A2DP enquanto o SCO está ativo. Mitigamos reamostrando para 16 kHz com filtro anti-alias e sustentando a rota entre turnos.


### Decisão 2

**A decisão.** Fizemos toda a IA no celular em vez de na nuvem

**Por que esse lado.** Fala de policial em ocorrência é dado sensível. Local, o ciclo fecha em 873–945 ms e funciona em área sem sinal, que é onde a patrulha mais precisa.

**O que isso custou.** Custa 181 MiB de whisper residente e RAM disputada com mapa e rádio. Medido em emulador arm64 API 35; portão no boot degrada em celular fraco.


### Decisão 3

**A decisão.** Fizemos câmera a 7 fps sob comando em vez de vídeo contínuo

**Por que esse lado.** A bateria crítica é a DOS ÓCULOS. O ML Kit resolve em 8 ms; 30 fps só aqueceriam a armação e encurtariam o plantão. 7 fps é escolha, não teto do SDK.

**O que isso custou.** Custa perder placa de veículo em movimento rápido, que precisa de segunda tentativa. Mitigamos com recusa falada imediata, para o agente reposicionar.


### Decisão 4

**A decisão.** Fizemos o LLM embarcado e DESLIGADO em vez de ligado no produto

**Por que esse lado.** Medimos Qwen2.5-1.5B no aparelho: 1 a 2 respostas utilizáveis em 20, e o filtro de lastro não vê negação. Falar lei errada com confiança é pior que não falar.

**O que isso custou.** Custa a demo vistosa de resposta gerada. O produto fala a citação exata do artigo, verificável, e o modelo fica embarcado e medido para religar quando passar.


### Decisão 5

**A decisão.** Fizemos o servidor devolver distância e rumo em vez de coordenada

**Por que esse lado.** Coordenada de terceiro no cliente permite montar o rastro de qualquer par. Com grandezas, o mapa mostra o companheiro sem que ninguém baixe a posição dele.

**O que isso custou.** Custa não desenhar rota até o par e assumir que duas medidas trilateram. Mitigamos no banco: migrações que barram dump em massa e consulta a par arbitrário.

---

## A6 — Âncora de originalidade

**Meta AI nativa dos Ray-Ban Meta** — A Meta AI é generalista e depende de nuvem. Nós rodamos offline, com corpus de norma brasileira, rádio entre guarnições e recusa explícita quando não sabemos.

**Axon Body 4 + Axon Respond** — A Axon grava para evidência e transcreve na nuvem. Nós respondemos em tempo real, transcrevemos na origem e não indexamos a fala do abordado.

---

## A7 — Os cinco checkpoints do §8.1

| Checkpoint | Como cumprimos |
|---|---|
| **Uso de IA** | Cinco modelos ativos no celular: whisper.cpp, Silero VAD, wake word ONNX, Piper VITS e ML Kit. O LLM Qwen2.5-1.5B gera no aparelho, desligado por medição (A5[4]). Emulador: ciclo 873–945 ms. |
| **Câmera ou microfone** | Usamos os três canais dos óculos: microfone sempre aberto por HFP/SCO com ativação por "Claryon", câmera sob comando via Meta DAT 0.9.0 withCamera a 7 fps, e alto-falante na resposta. |
| **Output por áudio** | Piper sintetiza no celular e o som volta pelo alto-falante dos óculos, com earcons e fila de prioridade. Teto de sete palavras por resposta; emergência interrompe o resto. |
| **Privacidade e dados** | Áudio, frame e transcrição não saem do aparelho. O beamforming isola quem veste: a fala do abordado nunca é transcrita. Sem reconhecimento facial nem base biométrica, em versão nenhuma. |
| **Eficiência de bateria** | PowerPolicy corta por modo de operação: Standby fecha o HFP — em SCO, o maior consumidor contínuo da armação. Câmera sobe sob intenção e desce ao fim. Cascata barata: wake word, VAD, só então STT. |

---

## Seção B — Diagrama de arquitetura

O código-fonte Mermaid está em [`ARQUITETURA.mmd`](ARQUITETURA.mmd). Ele começa
com `flowchart TD`, marca os cinco checkpoints como `CP1` a `CP5` dentro dos nós,
nomeia tecnologias e APIs com versão, e separa o que roda no dispositivo do que
atravessa para a nuvem.

Renderize em <https://mermaid.live> ou com:

```bash
npx -y @mermaid-js/mermaid-cli@11 -i docs/ARQUITETURA.mmd -o arquitetura.png -w 2200 -b white -s 2
```

---

## O que este documento não esconde

Todos os números aqui foram medidos em **emulador arm64 API 35**, e o corpus de
placas é **sintético** — 31 cenas geradas por `Canvas` em tempo de teste, com
degradação controlada. Não há fotografia de placa no repositório. Nada foi medido
em óculos reais — não temos o hardware, e o edital não o exige. Onde isso muda a
leitura de um número, está dito no próprio campo.

As capacidades que existem no código e **não** estão ligadas em runtime estão
listadas em [`CAPACIDADES_DESLIGADAS.md`](CAPACIDADES_DESLIGADAS.md), com o motivo
de cada uma.
