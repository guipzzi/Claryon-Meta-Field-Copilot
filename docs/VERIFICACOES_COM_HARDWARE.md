# Verificações que exigem hardware

Lista fechada do que **não é verificável no emulador** e precisa de aparelho
físico. Cada item traz o comando, o que observar e por que importa — para rodar
sem reconstruir o raciocínio.

O emulador não tem rádio Bluetooth. A parcela de latência da saída de áudio
(40–150 ms) e todo o comportamento do perfil de voz (HFP/SCO) são propriedades de
hardware e firmware: nenhum software os reproduz. O MockDeviceKit também **não
simula áudio**.

**Estado (18/08):** celular Android disponível. **Falta um dispositivo HFP** — fone
Bluetooth com microfone ou os próprios óculos. É o que trava três cláusulas do
aceite da Fase 2; ver a seção logo abaixo.

---

## FASE 2 — as três cláusulas que só fecham com hardware

O aceite da Fase 2 tem oito afirmações. Cinco são código e estão comigo. **Três
dependem de um dispositivo HFP**, e é isto que falta para a fase fechar:

| cláusula do aceite | o que ela exige |
|---|---|
| *"fone HFP no ouvido, nenhum toque na tela"* | qualquer dispositivo SCO |
| *"30 pronúncias reais gravadas **por HFP** dão recall ≥ 90%"* | ~10 min gravando |
| *"8 h de rádio ambiente não abrem canal nenhuma vez"* | 8 h de aparelho parado |

Mais uma quarta, que **não** precisa de fone: *"transmissão que um segundo ouvinte
recebe"*. Pelo precedente D7 ela fecha com sessão headless — é código meu.

### Por que o microfone do celular não serve no lugar

As 27 gravações que existem hoje são do microfone do celular a 48 kHz. O HFP é
outro caminho: **codec CVSD a 8 kHz**, com quantização própria, microfone dos
óculos e AGC no *uplink*. O que as bancadas chamam de "banda estreita" corta a
banda e **não** simula o codec — está declarado no código, não é descuido.

Este projeto já pagou por essa diferença exata: *"foi por medir em banda cheia que
a análise de 14/08 aprovou 'Claryon' e errou"*. Um recall de 90% no microfone do
celular não prevê o recall no fone.

### O que fazer, quando houver o dispositivo

**Serve qualquer fone Bluetooth com microfone** — para o Android ele é o mesmo
`TYPE_BLUETOOTH_SCO` dos óculos (ver a primeira seção). Os óculos são melhores
porque trazem o beamforming, mas não são pré-requisito.

1. **30 pronúncias, ~10 min.** "Claryon" isolado, com pausa entre cada, variando
   distância, volume e pressa. Fecha o recall ≥ 90% por HFP.
2. **8 h de ambiente, ~5 min de preparo.** Rádio, podcast ou TV tocando no cômodo
   com o aparelho gravando. **É tempo de aparelho parado, não de trabalho.** Fecha
   a cláusula das 8 h **e** dá o intervalo de confiança que falta ao falso
   positivo: hoje 1,8 min de leitura retida dão limite superior de ~99 falsos/h, e
   a meta de 0,5/h precisa da ordem de 6 h com zero disparo.
3. **A ida e volta completa**, com o aparelho no bolso: "Hey Claryon, guarnição 3
   na escuta" → earcon → BIP → quadros no ar.

### O que eu entrego antes disso

Tela de captura dentro do app, gravando **pela rota HFP** e nomeando os arquivos no
formato que o treino já espera. Sem ela a gravação sai pelo microfone do celular e
não vale para a cláusula — que é precisamente o buraco em que as 27 atuais caíram.

**Enquanto o dispositivo não chega:** o bloco de código da Fase 2 fecha sem ele —
fiação do detector (feita em 20/08: `EscutaDeAtivacao` sob `PowerPolicy.hfpAberto`), gazetteer em produção,
duas instâncias de Silero, fecho por silêncio e as três marcas p95. A fase fica
com as três cláusulas marcadas como pendentes de hardware, no mesmo padrão que o
D7 já estabeleceu para o segundo aparelho na Fase 3.

---

## Por que um fone barato resolve quase tudo

O código nunca roda nos óculos — roda no celular. Para o Android, os óculos são
apenas um dispositivo `TYPE_BLUETOOTH_SCO`. **Um fone com microfone é o mesmo
`TYPE_BLUETOOTH_SCO`.** O sistema não distingue. Só ficam de fora a latência
específica do firmware dos óculos e o comportamento do beamforming.

---

## V4 — Rota de áudio HFP

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.claryon.field.GlassesAudioRouteTest
```

O teste `rotaQueCaiuEntreORoteamentoEACaptura_naoGravaPeloCelular` está com
`Assume` e é **pulado sem SCO**. Com o fone pareado ele roda de verdade.

| O que observar | Por que importa |
|---|---|
| `setCommunicationDevice()` devolve `true`? | Hoje é suposição; é armadilha conhecida |
| `MODE_IN_COMMUNICATION` sobe? | Se não, testar `MODE_NORMAL` |
| Tempo até o SCO estabelecer | 500–1500 ms esperados. É o que justifica manter o canal aberto em modo Ativo |
| Taxa de amostragem efetiva do `AudioRecord` | **Se negociar mSBC (16 kHz)**, o corte sobe de 4 para 8 kHz, as fricativas sobrevivem e a análise da palavra de ativação muda |
| `AcousticEchoCanceler.isAvailable()` | Bloqueante do PTT; varia por aparelho |

## Eco de ponta a ponta

Painel de diagnóstico → **"Eco"**. Grava 3 s pelo fone e reproduz.
Primeira prova de que captura e reprodução funcionam pelo mesmo canal.

## Ciclo de voz com áudio real

Painel → **"Ciclo de voz completo"**. Até aqui, whisper e Piper só foram provados
com arquivo (`jfk.wav`) e com texto — nunca sobre áudio HFP capturado ao vivo, que
é mono e de banda estreita.

## V2 — Encoder Opus no `MediaCodec`

O decodificador Opus é comum; o **encoder não é garantido** em todo aparelho.
Enumerar `MediaCodecList` no aparelho real e verificar `audio/opus` como encoder.

- **Se existir:** caminho principal do C1.
- **Se não:** libopus via NDK — a toolchain (NDK 27 + CMake) já está montada do
  whisper, então o custo marginal é baixo. Descobrir isto em setembro custa dois
  dias; descobrir agora custa meia hora.

## Latência boca a ouvido

O método vale mais que qualquer instrumentação interna: **dois aparelhos lado a
lado, um estalo seco no microfone do emissor, gravação por um terceiro aparelho.**
Mede-se no arquivo a distância entre o estalo original e o reproduzido.

Mede o caminho **inteiro**, incluindo o Bluetooth — que nenhum log dentro do
aplicativo enxerga.

| Métrica | Meta |
|---|---|
| Toque → primeiro quadro na rede | ≤ 120 ms |
| Boca a ouvido, mesma rede Wi-Fi | ≤ 350 ms |
| Boca a ouvido, 4G | ≤ 600 ms |

> **Nenhum número de latência vai ao documento da Etapa 5 antes de ser medido
> assim.** O orçamento teórico (245–630 ms) é projeto, não resultado.

## Bateria por modo

```bash
adb shell dumpsys batterystats --reset
# operar 30 min em cada modo
adb shell dumpsys batterystats > bs.txt
```

Meta: ≤ 12 %/h em modo Ativo. Hoje a política de energia é testada como lógica
pura — o consumo real nunca foi medido.

## Detecção de uso — o achado do caos com MockDeviceKit

Medido no emulador com MDK 0.9.0: **dobrar as hastes e desligar o aparelho mudam
o estado observado pelo app (STREAMING → STOPPED); tirar do rosto não muda nada.**

A doc explica: a sessão reage ao *doff* "when wear detection is enabled" — e não
há API para habilitar. É ajuste do aparelho, no app Meta AI.

| O que verificar no aparelho real | Por que importa |
|---|---|
| Com detecção de uso **ligada**, o app é notificado ao tirar os óculos? | Se não for, óculos fora do rosto seguem com sessão e rota ativas |
| A rota SCO cai quando os óculos saem do rosto? | Se não cair, `GlassesAudioRoute` também não detecta |

**A consequência a carregar:** fora do rosto, o beamforming que isola quem os
veste deixa de valer. Um PTT apertado nessa condição difunde a conversa ao redor.
Mitigações que já existem no produto: o PTT é explícito, tem teto de 30 s, e o
pré-roll nunca é persistido — mas nenhuma delas substitui a notificação.

## Modo avião

Comando de voz com o rádio desligado: transcrição local, resposta falada, e o
despacho caindo na fila. Prova que nada sai pela rede no caminho crítico, e é o
primeiro passo do roteiro de demonstração.
