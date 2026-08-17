# Onde estamos — 2026-08-16 · Fase 1 FECHADA

Fonte única de "estado da conversa". **Reescrito ao fim de cada sessão, nunca acrescentado.**
Teto duro: **60 linhas**. O que não couber é história e vai para `DECISIONS.md`. Aqui só
entra o que muda a próxima decisão: o que funciona, o que está quebrado, o que vem.

## O que funciona hoje

- `./gradlew build` verde · **570 testes, 0 falhas**. JDK 17 · AGP 8.9.2 · Kotlin 2.2.0 ·
  compileSdk 35 · minSdk 31 · NDK 27. Build offline após a primeira sincronização.
- Três telas compostas atrás de permissões e login: guarnição (PTT por toque + botão do
  copiloto), mapa MapLibre acompanhando o portador, e perfil.
- **Um `AudioRecord` no processo inteiro** (`FonteUnicaDeMicrofone`): `SharedFlow` com
  fan-out, contagem por assinatura, e reconferência de rota **durante** o stream a cada
  200 ms. Medido no emulador com PTT e ciclo de voz simultâneos: `AudioRecord aberto` = **1**.
- **Uma fila de prioridade** (`SaidaUnica`): rádio e copiloto compartilham `PrioritySoundQueue`,
  então P1 interrompe fala em curso. O `SupressorDeSaidaPropria` também é compartilhado — a
  fala do copiloto passou a suprimir a captura do rádio, furo que existia desde sempre.
- **Opus fora da Main** (`MediaCodecOpus(dispatcher)`): medido p50=22 ms por quadro, que na
  Main seriam ~50 bloqueios de 22 ms por segundo. StrictMode instalado em `ClaryonApp`:
  **zero violações durante as transmissões**.
- **Telemetria com chamador real.** Medido no aparelho: toque→1º quadro p50 **245 ms** (n=2),
  codificação p50 22 ms / p95 27 ms, 185 quadros enviados. Sai no `logcat` ao fechar o rádio.
- Edge Functions `transmit`/`ack` deployadas; PTT → `transmissions` verificado ponta a ponta.
- Verificado por `javap` no artefato: `rotaDeTeste` existe no AAR **debug** e **não existe** no
  release — a fábrica de rota falsa não pode vazar para produção.

## O que está quebrado, e nós sabemos

1. **Meta de 120 ms não atingida no emulador:** toque→1º quadro p50 = 245 ms. Falta medir em
   hardware real com fone HFP; o emulador usa codec por software e o número embute isso.
2. **`SyncManager.outbox` faz ~965 ms de leitura de disco na Main** (`SyncManager.kt:30`, via
   `DiagnosticsViewModel.<init>:426`) — achado NOVO, do StrictMode que esta fase instalou.
   `FileOutbox.init` chama `mkdirs()` no construtor. Startup, não transmissão.
3. **`AgrupadorDeQuadros` continua não existindo** — decisão registrada, não esquecimento: são
   ~50 msg/s com ~274 B de envelope para ~30 B de voz (11% de aproveitamento). Agrupar quebra
   o receptor em 3 pontos (`sequencia` é quadro E mensagem), e o comentário mentiroso em
   `Transmissao.kt` foi corrigido para dizer a verdade.
4. `WakeWordDetector` é interface sem implementação, e `PowerPolicy` declara `wakeWordAtiva =
   true`. O modo **Standby** é inalcançável.
5. `Telemetry` (o de `core-common`, dos estágios do ciclo de voz) continua **sem chamador** —
   só `TelemetriaDoRadio` foi ligada. As metas de STT, wake word e bateria seguem sem instrumento.
6. `Stream.errorStream` nunca é coletado: perdemos `PERMISSIONS_DENIED`, `HINGE_CLOSED`,
   `THERMAL_HOT`, `BATTERY_LOW` tipados. `STOPPED` não é tratado como terminal.
7. A permissão de câmera do DAT nunca é pedida em produção. Em hardware real, a leitura de
   placa quebra no primeiro uso.
8. `CopilotService`: `stopSelf()` antes de qualquer `startForeground()` (`:88`) e `parar()` usa
   `startService` em vez de `startForegroundService` (`:215`).
9. `DiagnosticsViewModel` tem 800+ linhas e 5 subsistemas. A quebra em três não foi feita: a
   auditoria achou 6 bloqueios reais (`executor`, `saida`, `gravacaoJob`, `autenticacao`,
   `tokenCorrente`, `audio`) que precisam sair dos ViewModels ANTES do corte.

## O que vem a seguir

Plano completo em [`ROADMAP.md`](ROADMAP.md). Caminho crítico:

1. **Medir em hardware real** — óculos + fone HFP. Sem isso, os números da Fase 1 são de emulador.
2. **Fase 2: gatilho por voz** ("Hey Claryon" / "guarnição N na escuta") — depende da fonte
   única, que agora existe.
3. **Transcrição na origem** (Pilar 1) — acumulador + Whisper no `finally` do PTT.
4. **Entregáveis da Etapa 5 — prazo 22/08.**

**Pendências que não se resolvem sozinhas:** `security-crypto` em `1.1.0-alpha06` ·
`MockDeviceKitStreamTest` roda isolado · conferir se o documento submetido menciona WhatsApp
(§14.1 do edital veda alteração de escopo).
