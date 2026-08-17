# Onde estamos — 2026-08-17 · Fase 2 em curso

**Reescrito ao fim de cada sessão, nunca acrescentado. Teto duro: 60 linhas.** O que não
couber é história e vai para `DECISIONS.md`. Aqui só o que muda a próxima decisão.

## O que funciona hoje

- `./gradlew build` verde. **605 testes JVM, 0 falhas.** Instrumentados: **29 executados,
  0 falhas, 5 pulados** por `Assume` de hardware — os pulados estão nomeados no motivo.
- Três telas atrás de permissões e login: guarnição (PTT por toque + "Perguntar ao
  copiloto"), mapa MapLibre acompanhando o portador com rumo, e perfil.
- **PTT ponta a ponta:** 16 kHz consistente, `transmit`/`ack` deployadas, fio do canal
  populado. Toque→1º quadro **31–48 ms** (meta 120). `AgrupadorDeQuadros`: 50 msg/s → ~17.
- **Fonte única de microfone** com fan-out: um `AudioRecord`, N consumidores. Dono único de
  saída (`SaidaUnica`) com fila de prioridade unificada — o P1 do rádio corta a fala do
  copiloto, medido no aparelho em **11 ms** (aceite: ≤200).
- **Rota de saída sustentada** (`RotaSustentada`): a rota SCO atravessa a rajada de fala em
  vez de cair a cada emissão — era o que fazia o earcon de P1 esperar uma remontagem.
- **VAD por rede neural** (Silero, 629 KB) no lugar do limiar de energia. Verificado no
  emulador: silêncio → 0 segmentos; senoide **alta** modulada (que o RMS aceitaria) → 0;
  fala pt-BR real do Piper (3 099 ms) → 1 segmento de 3 042 ms.
- **Ida e volta em português no aparelho:** Piper sintetiza, Whisper transcreve, sem rede —
  o `tiny` erra palavra rara ("guarnição" → "agora nisso são") e acerta o resto.
- Posição: `consultar_posicao` tira o solicitante do JWT e `private.posicao_relativa` está
  revogada de `authenticated`. Garantia do servidor, não do cliente.
- **Troca de grupo por voz ligada de ponta a ponta:** VAD → Whisper → roteador →
  `CanaisDoAgente` → `RadioTatico.trocarDeGrupo`, com chamador alcançável em runtime.
  `RotulosFalados` tinha **zero chamadores** e este arquivo afirmava o contrário.
- Verificado por `javap`: o DAT 0.9.0 **não** expõe microfone, e o MockDeviceKit não simula
  áudio nem alimenta a câmera emulada. Óculos e fone reais: única bancada honesta.

## O que está quebrado, e nós sabemos

1. **`Intent.TrocarDeGrupo` não existe.** A migração `0011` e `RotulosFalados` estão de pé,
   mas nada em `src/main` resolve um grupo falado nem recusa não-membro por voz. O canal
   ainda vem de `CanalDoPiloto` — constante, não fala.
2. **O verificador do gatilho ponta a ponta não existe.** VAD → Whisper → léxico fechado →
   resolução de grupo → earcon → piso → BIP → quadros: cada elo tem teste, a corrente não.
3. `WakeWordDetector` é interface sem implementação e `PowerPolicy` declara
   `wakeWordAtiva = true`: **Standby** é inalcançável. O `KeywordSpotter` está no AAR, sem chamador.
4. `Stream.errorStream` nunca é coletado: perdemos `PERMISSIONS_DENIED`, `HINGE_CLOSED`,
   `THERMAL_HOT`, `BATTERY_LOW` tipados. `STOPPED` não é tratado como terminal, então sem
   `camera.stop()` o próximo `addCamera` falha.
5. A permissão de câmera do DAT nunca é pedida em produção — em hardware real, a leitura de
   placa quebra no primeiro uso.
6. Transcrição na origem (P1) não existe: falta acumulador, Whisper no `finally` e o
   quarto evento no protocolo.
7. Rádio e ciclo de voz compartilham o dono único na **saída**, não na síntese.

## O que vem a seguir

Plano completo em [`ROADMAP.md`](ROADMAP.md). O caminho crítico:

1. **`Intent.TrocarDeGrupo`** com recusa falada para grupo de que o agente não é membro.
2. **Verificador do gatilho ponta a ponta** — o item 2 acima é o que sobra da Fase 2.
3. **Transcrição na origem** — acumulador, Whisper no `finally`, quarto evento.
4. **KWS como antecipação de earcon**, atrás de flag.
5. **Entregáveis da Etapa 5 — prazo 22/08.**

**Pendências:** `security-crypto` em `1.1.0-alpha06` · `MockDeviceKitStreamTest` roda
isolado · conferir se o documento submetido menciona WhatsApp (§14.1 veda mudar escopo).
