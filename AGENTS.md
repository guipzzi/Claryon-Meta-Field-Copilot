# Claryon Field — regras permanentes

App Android/Kotlin companion para Ray-Ban Meta (**sem display**). Copiloto de voz
para agentes de segurança pública. Hackathon AI Glasses Brasil, 18/09/2026.

**Stack:** Kotlin · Compose (sem navigation-compose) · minSdk 31 · JDK 17 ·
mwdat **0.9.0** · MapLibre 11.11.0 · whisper.cpp + sherpa-onnx (locais) · Supabase.

**Módulos:** `app` orquestra. `core-*` não dependem uns dos outros (exceto
`core-common`). Todo acesso ao DAT passa por `GlassesFacade` em `core-glasses`.

## Proibido

- ❌ Reconhecimento facial ou base biométrica. Nenhuma versão, nenhuma flag.
- ❌ Enviar áudio, transcrição ou frame para serviço externo no caminho crítico.
- ❌ LLM escolhendo ação. Ele só preenche campos de intenção já definida.
- ❌ Credencial versionada. Evidência fora de `EncryptedFile` + Keystore.
- ❌ Escrever assinatura do DAT ou do MapLibre de memória. Confirme no artefato
  (`javap`) ou na doc oficial. Não confirmou? **Pare e pergunte.**
- ❌ Dependência nova sem justificar tamanho, licença e alternativa nativa.

## Invariantes que o compilador sustenta

- Capturar exige `GlassesAudioRoute`. Gravar pelo microfone do celular não compila.
- `utteranceFor` aceita **apenas** `ActionOutcome` — a fala deriva do resultado da
  ação, nunca do comando. Há teste que falha se alguém acrescentar sobrecarga.
- `IntentExecutor` nunca lança: falha vira `ActionOutcome.Falhou` tipado.
- Falha nunca é silêncio: todo caminho de erro tem earcon próprio.
- Máximo 7 palavras por resposta de TTS operacional (há teste).

## Comandos

```
./gradlew build · :app:installDebug · connectedAndroidTest · adb logcat -s ClaryonField
```

## Antes de dizer "pronto"

Leia o código procurando o caminho de falha, não o feliz. Teste verde prova que o
caminho feliz existe; não prova que os outros existem. **Toda capacidade precisa de
caminho alcançável pelo agente** — construir, testar e não ligar já aconteceu cinco
vezes neste projeto.

Detalhe e justificativa: [`docs/INDICE.md`](docs/INDICE.md).
