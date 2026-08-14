# Verificações que exigem hardware

Lista fechada do que **não é verificável no emulador** e precisa de aparelho
físico. Cada item traz o comando, o que observar e por que importa — para rodar
sem reconstruir o raciocínio.

O emulador não tem rádio Bluetooth. A parcela de latência da saída de áudio
(40–150 ms) e todo o comportamento do perfil de voz (HFP/SCO) são propriedades de
hardware e firmware: nenhum software os reproduz. O MockDeviceKit também **não
simula áudio**.

**Estado:** aguardando celular Android (previsto para 15/08/2026) e fone
Bluetooth com HFP.

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

## Modo avião

Comando de voz com o rádio desligado: transcrição local, resposta falada, e o
despacho caindo na fila. Prova que nada sai pela rede no caminho crítico, e é o
primeiro passo do roteiro de demonstração.
