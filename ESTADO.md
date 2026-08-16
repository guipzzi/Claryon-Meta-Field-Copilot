# Onde estamos — 2026-08-16 · Fase 0 FECHADA

Fonte única de "estado da conversa". **Reescrito ao fim de cada sessão, nunca acrescentado.**
Teto duro: **60 linhas**. O que não couber é história e vai para `DECISIONS.md`. Aqui só
entra o que muda a próxima decisão: o que funciona, o que está quebrado, o que vem.

## O que funciona hoje

- `./gradlew build` verde. JDK 17 · Gradle 8.11.1 · AGP 8.9.2 · Kotlin 2.2.0 · compileSdk 35 ·
  minSdk 31 · NDK 27. Build offline após a primeira sincronização.
- **O app tem três telas compostas** (`MainActivity.kt:153`), atrás de permissões e login:
  guarnição (rádio com PTT por toque), mapa MapLibre acompanhando o portador com rumo,
  e perfil.
- `ClaryonIntentExecutor` existe em produção (`app/src/main/kotlin/com/claryon/field/agent/`)
  e `core-sound`, `core-evidence` e `core-sync` são importados por `app/src/main`.
- Modelos embarcados em `app/src/main/assets/models/` — o APK de produção contém
  `ggml-tiny.bin` e a voz Piper. Nada de IA na nuvem em caminho nenhum.
- Posição: `public.consultar_posicao(indicativo)` tira o solicitante do JWT e
  `private.posicao_relativa` está revogada de `authenticated`
  (`servidor/migracoes/0003_consultas.sql:97`). A garantia é do servidor, não do cliente.
- Verificado no artefato por `javap`: o DAT 0.9.0 **não** expõe microfone e o MockDeviceKit
  **não** simula áudio. Fone Bluetooth com HFP continua sendo a única bancada honesta.

## O que está quebrado, e nós sabemos

0. ~~PTT não demonstrável~~ **RESOLVIDO** — `RadioViewModel` passa
   `sampleRateHz = audio.taxaDeAmostragemHz` e `ConfigOpus` deriva da mesma fonte. Travado
   por `TaxaDeAmostragemTest`. ~~O PTT NÃO ERA DEMONSTRÁVEL.~~ `RadioTatico.kt:88` tem `sampleRateHz = 8_000` como
   padrão e `RadioViewModel` não sobrescreve, enquanto a captura entrega 16 kHz. A voz
   transmitida sai **uma oitava abaixo, com o dobro da duração**. Meia sessão de conserto,
   e é a maior alavanca do projeto.
0b. ~~Edge Functions nunca deployadas~~ **RESOLVIDO** — `transmit` e `ack` deployadas e
   verificadas ponta a ponta: PTT → `RegistroDeTransmissao` → função → `transmissions` →
   fio do canal. Três defeitos no caminho: `NetworkOnMainThreadException` (o `execute()` do
   OkHttp na Main, exceção de mensagem nula), `tipo = "fala"` contra o CHECK que aceita
   `('ptt','alerta')`, e `optString` devolvendo a **string** `"null"` para JSON nulo. `grep "functions/v1"
   --include=*.kt` devolve zero. Logo `transmissions` nunca recebe INSERT e
   `HistoricoDoCanal.falas()` devolve lista vazia **sempre** — o fio do canal que acabou de
   ser construído mostra só as inserções otimistas locais, que somem em 10 s na recarga.
   A superfície visível do Pilar 1 está vazia em produção.
0c. `AgrupadorDeQuadros` **não existe no repositório**, apesar de `Transmissao.kt:28` afirmar
   que existe. São 50 mensagens/s de ~300 B para 30 B de voz.
1. ~~Ciclo de voz sem porta de entrada~~ **RESOLVIDO** — botão "Perguntar ao copiloto" em
   `TelaDeGuarnicao`, ligado a `diag::cicloDeVoz`. Verificado no emulador: o botão vira
   "OUVINDO…", o ciclo roda e o `finally` devolve o botão. **Era o defeito mais caro do
   projeto.** ~~Antes:~~ `DiagnosticsScreen` é a única tela que chama
   `runCommand`/`falarComando`/`cicloDeVoz`, e o nome só aparece uma vez no projeto: na
   própria definição (`ui/DiagnosticsScreen.kt:48`). C2, C3 e C4 estão mortos por voz; o app
   entregue é 100% toque, que é o oposto da premissa do produto.
2. ~~Duas instâncias de `GlassesAudioManagerImpl`~~ **RESOLVIDO** — dono único em
   `field/audio/AudioDoAgente.kt`. Era violação de compliance alcançável: o `liberar()` de
   uma derrubava a rota SCO sob o `AudioRecord` da outra, e como `microfonePcm` confere a
   rota só na abertura, o pré-roll passava a captar pelo microfone do celular e ia ao ar
   no PTT seguinte — captando terceiros por trás do `GlassesAudioRoute`.
3. ~~Earcons do rádio engolidos~~ **RESOLVIDO** — `RadioViewModel` tem `saidaDoRadio`.
   **Pendência que sobra:** são duas filas de prioridade (rádio e ciclo de voz) que não se
   enxergam, então um P1 do rádio não interrompe fala do copiloto. Só se resolve movendo o
   TTS para o dono único.
3b. ~~Captura sem tratamento de exceção~~ **RESOLVIDO** — os dois `escopo.launch` de
   `RadioTatico` (`:135` e `:215`) passam por `semDerrubarOProcesso`, que converte falha em
   earcon e **repropaga** `CancellationException`. Antes, óculos desconectando no toque do
   PTT matava o processo.
4. **Taxa de amostragem divergente:** o microfone entrega 16 kHz e `RadioTatico`/codec assumem
   8 kHz. Na recepção, um `AudioTrack` novo é criado e liberado **por quadro de 20 ms**.
5. `WakeWordDetector` é interface sem implementação, e `PowerPolicy` declara
   `wakeWordAtiva = true`. O modo **Standby** é inalcançável.
6. **Nenhuma das seis metas está instrumentada** — `Telemetry.mark` não tem chamador.
7. `Stream.errorStream` nunca é coletado: perdemos `PERMISSIONS_DENIED`, `HINGE_CLOSED`,
   `THERMAL_HOT`, `BATTERY_LOW` tipados. `STOPPED` não é tratado como terminal, então sem
   `camera.stop()` o próximo `addCamera` falha.
8. A permissão de câmera do DAT nunca é pedida em produção. Em hardware real, a leitura de
   placa quebra no primeiro uso.
9. `CopilotService`: `stopSelf()` antes de qualquer `startForeground()`
   (`service/CopilotService.kt:88`) e `parar()` usa `startService` em vez de
   `startForegroundService` (`:215`).

## O que vem a seguir

Plano completo e faseado em [`ROADMAP.md`](ROADMAP.md). O caminho crítico:

1. **16 kHz ponta a ponta** — sem isso não há vídeo, nem checkpoint, nem demo.
2. **Porta de entrada do ciclo de voz** — um botão em `TelaDeGuarnicao`. Whisper, Piper,
   roteador e executor já existem, testados e inalcançáveis.
3. **Fonte única de microfone com fan-out** — 1 e 2 não coexistem sem ela.
4. **JWT no canal + `ClienteDePisoRemoto`** — hoje o piso é resolvido em RAM do processo.
5. **Transcrição na origem** — acumulador, Whisper no `finally`, quarto evento no protocolo.
6. **Entregáveis da Etapa 5 — prazo 22/08.**

**Pendências que não se resolvem sozinhas:** rotacionar o PAT do GitHub exposto no setup ·
`security-crypto` em `1.1.0-alpha06` · `MockDeviceKitStreamTest` roda isolado · conferir se o
documento submetido menciona WhatsApp (§14.1 do edital veda alteração de escopo).
