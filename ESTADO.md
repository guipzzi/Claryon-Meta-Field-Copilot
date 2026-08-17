# Onde estamos — 2026-08-16 · Fase 1 FECHADA (aceite completo)

Fonte única de "estado da conversa". **Reescrito ao fim de cada sessão, nunca acrescentado.**
Teto duro: **60 linhas**. O que não couber é história e vai para `DECISIONS.md`. Aqui só
entra o que muda a próxima decisão: o que funciona, o que está quebrado, o que vem.

## O que funciona hoje

- `./gradlew build` verde · **578 testes, 0 falhas**. JDK 17 · AGP 8.9.2 · Kotlin 2.2.0 ·
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
- **Telemetria com chamador real, as DUAS classes.** `TelemetriaDoRadio`: toque→1º quadro
  p50 **168 ms**, codificação p50 23 / p95 26 ms. `Telemetry.mark` ganhou a primeira
  implementação (`TelemetriaDoCicloDeVoz`) com os 4 estágios do ciclo + os 2 de **reprodução
  real** (marcados quando o PCM entra no `AudioTrack`, nunca no enfileiramento) + a preempção
  de P1. Os dois relatórios saem no `logcat` ao fechar o rádio.
- **`DiagnosticsViewModel` quebrado**: 818 → **723** linhas. Saíram `SessaoDoAgente` (dono de
  processo — era o bloqueio que impedia o corte, porque `MainActivity` precisava do ViewModel
  só para o portão de login) e `MapaViewModel` (161 linhas). Verificado no emulador: mapa
  mostra "RECEBENDO" e a seta do portador.
- **StrictMode limpou o que achou**: `SyncManager.outbox` na Main (965 ms) → **zero**. A causa
  era `context.filesDir` (o `ensurePrivateDirExists` do framework), não o `mkdirs` do
  `FileOutbox` — o primeiro conserto mirou a peça errada e o log mostrou isso.
- Edge Functions `transmit`/`ack` deployadas; PTT → `transmissions` verificado ponta a ponta.
- Verificado por `javap` no artefato: `rotaDeTeste` existe no AAR **debug** e **não existe** no
  release — a fábrica de rota falsa não pode vazar para produção.

## O que está quebrado, e nós sabemos

1. **Meta de 120 ms não atingida no emulador:** toque→1º quadro p50 = 168 ms (melhor que os
   245 ms da primeira medição, mas ainda acima). Falta medir em hardware real com fone HFP; o
   emulador usa codec por software e o número embute isso.
2. **`CofreDeSessaoCifrado.getPrefs` faz E/S de Keystore na Main** (`:26`) — próximo achado do
   StrictMode, na fila do mesmo tratamento que o `SyncManager` acabou de receber.
3. **O ciclo de voz não tem amostra de telemetria no emulador:** sem entrada de áudio real o
   VAD nunca fecha janela, então as metas de earcon e resposta aparecem como "sem amostras" —
   que é o correto, e não zero.
4. **A quebra do ViewModel parou no meio, e é deliberado:** `CopilotoViewModel` e
   `EvidenciaViewModel` NÃO foram extraídos. `executor` e `gravacaoJob` são a exclusão mútua
   entre os dois (o handle de gravação vive dentro do executor), e separá-los sem um dono
   único do executor produziria manifesto aberto e vazio — a mentira que o KDoc do cofre diz
   ter vindo corrigir.
5. **`AgrupadorDeQuadros` continua não existindo** — decisão registrada, não esquecimento: são
   ~50 msg/s com ~274 B de envelope para ~30 B de voz (11% de aproveitamento). Agrupar quebra
   o receptor em 3 pontos (`sequencia` é quadro E mensagem), e o comentário mentiroso em
   `Transmissao.kt` foi corrigido para dizer a verdade.
6. `WakeWordDetector` é interface sem implementação, e `PowerPolicy` declara `wakeWordAtiva =
   true`. O modo **Standby** é inalcançável.
7. Metas de STT (≥92%), wake word (≤1 falso positivo/h) e bateria (≤12%/h) seguem **sem
   instrumento** — as duas primeiras dependem de peças da Fase 2.
8. `Stream.errorStream` nunca é coletado: perdemos `PERMISSIONS_DENIED`, `HINGE_CLOSED`,
   `THERMAL_HOT`, `BATTERY_LOW` tipados. `STOPPED` não é tratado como terminal.
9. A permissão de câmera do DAT nunca é pedida em produção. Em hardware real, a leitura de
   placa quebra no primeiro uso.
10. `CopilotService`: `stopSelf()` antes de qualquer `startForeground()` (`:88`) e `parar()` usa
   `startService` em vez de `startForegroundService` (`:215`).

## O que vem a seguir

Plano completo em [`ROADMAP.md`](ROADMAP.md). Caminho crítico:

1. **Medir em hardware real** — óculos + fone HFP. Sem isso, os números da Fase 1 são de emulador.
2. **Fase 2: gatilho por voz** ("Hey Claryon" / "guarnição N na escuta") — depende da fonte
   única, que agora existe.
3. **Transcrição na origem** (Pilar 1) — acumulador + Whisper no `finally` do PTT.
4. **Entregáveis da Etapa 5 — prazo 22/08.**

**Push:** o `~/.gitconfig` roteia `github.com` só pelo `gh`, cujo token está **inválido**. O
keychain tem credencial válida. Consertar com `gh auth login` ou removendo o desvio
(`git config --global --unset-all credential.https://github.com.helper`).

**Pendências que não se resolvem sozinhas:** `security-crypto` em `1.1.0-alpha06` ·
`MockDeviceKitStreamTest` roda isolado · conferir se o documento submetido menciona WhatsApp
(§14.1 do edital veda alteração de escopo).
