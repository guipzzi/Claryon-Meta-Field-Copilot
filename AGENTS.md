# Claryon Field — regras permanentes

Companion Android/Kotlin para Ray-Ban Meta **sem display**: o código roda no celular, os
óculos são sensores e alto-falantes. Copiloto de voz para segurança pública. Hackathon AI
Glasses Brasil, 18/09/2026. **Onde estamos hoje: [`ESTADO.md`](ESTADO.md) — leia primeiro.**

**Stack:** Kotlin 2.2 · Compose (sem navigation-compose) · JDK 17 · AGP 8.9.2 · minSdk 31 ·
mwdat **0.9.0** · MapLibre 11.11.0 · whisper.cpp + sherpa-onnx (locais) · Supabase. `app`
orquestra; `core-*` só dependem de `core-common`; todo DAT via `GlassesFacade`.
`./gradlew build · :app:installDebug · connectedAndroidTest` · `adb logcat -s ClaryonField`

## Proibido

- ❌ Reconhecimento facial ou base biométrica. Nenhuma versão, nenhuma flag.
- ❌ Transcrever, classificar ou indexar a fala de **terceiros**. O beamforming isola quem
  veste os óculos — transcrevemos o agente, não o interlocutor: é intencional, não conserte.
  O pré-roll do PTT vive em RAM e nunca é persistido.
- ❌ Enviar áudio, transcrição ou frame para serviço externo no caminho crítico.
- ❌ Função de servidor que receba como parâmetro a identidade de quem pergunta: com ela,
  distâncias trilateram a posição absoluta de qualquer par. Solicitante vem do JWT; o
  parâmetro só existe dentro do schema `private`.
- ❌ Áudio pelo DAT. Não existe `session.audioStream`: microfone e alto-falante dos óculos
  são HFP/SCO. Classe de áudio em pacote `internal` não é API pública.
- ❌ LLM escolhendo ação. Ele só preenche campos de intenção já definida.
- ❌ Credencial versionada. Evidência fora de `EncryptedFile` + Keystore.
- ❌ Assinatura do DAT ou do MapLibre escrita de memória — confirme por `javap` no artefato
  ou na doc oficial. Idem dependência nova: tamanho, licença, alternativa nativa. Não
  confirmou? **Pare e pergunte.**

## Antes de tocar, leia o trecho em `docs/PADROES_DE_ENGENHARIA.md`

- áudio, HFP, PTT, rádio → §Sequências · §Rota de áudio · §Rádio tático
- posição, mapa, RPC → §Localização e mapa · e `servidor/migracoes/0003,0006,0010`
- fala, earcon, TTS, energia → §Honestidade · §Design de áudio · §Energia

## Antes de dizer "pronto"

Teste verde prova que o caminho feliz existe; não prova que os outros existem. **Toda
capacidade precisa de caminho alcançável pelo agente** — construir, testar e não ligar já
aconteceu cinco vezes aqui. Ao fechar o bloco: atualize `ESTADO.md`, `git push origin master`
(o iCloud já esvaziou o índice deste repositório uma vez). Índice: `docs/INDICE.md`.
