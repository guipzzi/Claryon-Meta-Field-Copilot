# Onde estamos — 2026-08-17 · Fase 1 fechada, com UM critério de aceite não atingido

Fonte única de "estado da conversa". **Reescrito ao fim de cada sessão, nunca acrescentado.**
Teto duro: **60 linhas**. O que não couber é história e vai para `DECISIONS.md`. Aqui só
entra o que muda a próxima decisão: o que funciona, o que está quebrado, o que vem.

## O que funciona hoje

- `./gradlew build` verde · **579 testes, 0 falhas**. JDK 17 · AGP 8.9.2 · Kotlin 2.2.0 ·
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
- **`DiagnosticsViewModel` quebrado**: 769 → **205** linhas. Saíram `SessaoDoAgente` (dono de
  processo — era o bloqueio do corte, porque `MainActivity` precisava do ViewModel só para o
  portão de login), `MapaViewModel` (161) e `CopilotoViewModel` (534, com a evidência junto).
  O que sobrou é diagnóstico de verdade: fachada do DAT, MockDeviceKit e eco.
- **Keystore fora da Main**: o portão de login chamava `autenticado()` na composição e pagava
  468 ms de `MasterKey.Builder.build()`. `SessaoDoAgente.estado` é um `StateFlow` com
  `Verificando` — sem esse terceiro estado, tirar da Main faria a tela de login piscar na cara
  de quem já tem sessão. Violações do StrictMode: 20 → 10.
- **StrictMode limpou o que achou**: `SyncManager.outbox` na Main (965 ms) → **zero**. A causa
  era `context.filesDir` (o `ensurePrivateDirExists` do framework), não o `mkdirs` do
  `FileOutbox` — o primeiro conserto mirou a peça errada e o log mostrou isso.
- Edge Functions `transmit`/`ack` deployadas; PTT → `transmissions` verificado ponta a ponta.
- Verificado por `javap` no artefato: `rotaDeTeste` existe no AAR **debug** e **não existe** no
  release — a fábrica de rota falsa não pode vazar para produção.

## O que está quebrado, e nós sabemos

1. **Aceite (d) da Fase 1 NÃO foi atingido:** "contagem de mensagens cai de ~50/s para ~17/s
   medida em `TransporteRealtime`". Não caiu e não há contador — consequência direta da decisão
   de não construir o `AgrupadorDeQuadros` (item 5). O ROADMAP e este arquivo chegaram a dizer
   "aceite completo"; era falso. Ou o critério é reescrito, ou a fase tem uma pendência aberta.
2. **Meta de 120 ms não atingida no emulador:** toque→1º quadro p50 = 168-221 ms. Falta medir em
   hardware real com fone HFP; o emulador usa codec por software e o número embute isso.
3. **A preempção de P1 tem instrumento mas nenhuma amostra em runtime:** provada por teste
   (`PrioritySoundQueueTest`), medida por `aoInterromper`, e o relatório mostra "sem amostras"
   porque no emulador o ciclo de voz nunca fecha janela de VAD. E o que ela mede é
   chegada→`cancel`, não chegada→silêncio: é limite inferior do que o aceite descreve.
4. **O ciclo de voz não tem amostra de telemetria no emulador:** sem entrada de áudio real o
   VAD nunca fecha janela, então as metas de earcon e resposta aparecem como "sem amostras" —
   que é o correto, e não zero.
5. **`EvidenciaViewModel` não existe, e é decisão:** evidência é um MODO do copiloto, não um
   vizinho. `executor` guarda o handle da gravação e `gravacaoJob` é a exclusão mútua entre os
   dois — duplicar daria manifesto aberto e vazio. A auditoria propôs separar; está errada
   nesse ponto. Alternativa não explorada: um `ExecutorDoAgente` dono de processo permitiria o
   corte sem duplicar. Fica registrado.
6. **`AgrupadorDeQuadros` continua não existindo** — decisão registrada, não esquecimento: são
   ~50 msg/s com ~274 B de envelope para ~30 B de voz (11% de aproveitamento). Agrupar quebra
   o receptor em 3 pontos (`sequencia` é quadro E mensagem), e o comentário mentiroso em
   `Transmissao.kt` foi corrigido para dizer a verdade.
7. `WakeWordDetector` é interface sem implementação, e `PowerPolicy` declara `wakeWordAtiva =
   true`. O modo **Standby** é inalcançável.
8. Metas de STT (≥92%), wake word (≤1 falso positivo/h) e bateria (≤12%/h) seguem **sem
   instrumento** — as duas primeiras dependem de peças da Fase 2.
9. `Stream.errorStream` nunca é coletado: perdemos `PERMISSIONS_DENIED`, `HINGE_CLOSED`,
   `THERMAL_HOT`, `BATTERY_LOW` tipados. `STOPPED` não é tratado como terminal.
10. A permissão de câmera do DAT nunca é pedida em produção. Em hardware real, a leitura de
   placa quebra no primeiro uso.
11. `CopilotService`: `stopSelf()` antes de qualquer `startForeground()` (`:88`) e `parar()` usa
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
