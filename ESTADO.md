# Onde estamos — 2026-08-15 · `2d3e8ff`

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

1. **O ciclo de voz não tem porta de entrada.** `DiagnosticsScreen` é a única tela que chama
   `runCommand`/`falarComando`/`cicloDeVoz`, e o nome só aparece uma vez no projeto: na
   própria definição (`ui/DiagnosticsScreen.kt:48`). C2, C3 e C4 estão mortos por voz; o app
   entregue é 100% toque, que é o oposto da premissa do produto.
2. **Duas instâncias de `GlassesAudioManagerImpl`** sobre o mesmo estado global de áudio do
   aparelho (`radio/RadioViewModel.kt:64` e `ui/DiagnosticsViewModel.kt:150`).
3. **Todos os earcons do rádio são engolidos:** `radio/RadioViewModel.kt:154` passa
   `emitir = { }`. Num produto sem display, isso é falha virando silêncio.
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

1. Porta de entrada do ciclo de voz — sem ela, nada acima importa.
2. Dono único da saída de áudio (item 2 e 3 acima saem juntos).
3. 16 kHz ponta a ponta e um `AudioTrack` só.
4. Revisão humana de `specs/gatilho-por-voz.spec.md` — está **proposta** e sobrepõe uma regra
   dura vigente; sobrepor regra dura é decisão humana, não do agente.
5. Instrumentar `Telemetry`. "Métrica adicionada no fim nunca é adicionada."
6. Entregáveis da Etapa 5 do edital — prazo **22/08**.

**Pendências que não se resolvem sozinhas:** rotacionar o PAT do GitHub exposto no setup ·
`security-crypto` em `1.1.0-alpha06` · `MockDeviceKitStreamTest` roda isolado · conferir se o
documento submetido menciona WhatsApp (§14.1 do edital veda alteração de escopo).
