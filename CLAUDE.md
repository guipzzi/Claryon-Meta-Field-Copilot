# Claryon Field — fonte única da verdade

@ESTADO.md

Companion **Android/Kotlin** para Ray-Ban Meta **sem display**: o código roda no
celular, os óculos são sensores e alto-falantes. Copiloto de voz para segurança
pública. Hackathon AI Glasses Brasil — **18/09/2026**.

> Este arquivo é a **fonte única da verdade** do projeto: produto, regras de
> negócio e regras de engenharia. Documento em `docs/` que contradiga este arquivo
> está errado por definição. O que não couber aqui é **referência** ou **história**,
> e vive linkado — nunca colado, porque documento inteiro colado faz o modelo perder
> o meio dele. Onde estamos hoje: [`ESTADO.md`](ESTADO.md). Plano:
> [`ROADMAP.md`](ROADMAP.md).

---

## 1. O produto — três pilares

| Pilar | O que é | Regra de negócio que o define |
|---|---|---|
| **P1 · Rede de comunicação** | PTT/walkie-talkie entre operadores, com transcrição | **A transcrição ocorre na ORIGEM**, antes de trafegar. Todos os receptores exibem exatamente o mesmo texto, sem divergência — e o servidor nunca precisa transcrever |
| **P2 · Geolocalização** | Posição da guarnição, atualizada e persistida | O servidor devolve **grandezas** (distância, rumo), **nunca coordenada** de terceiro |
| **P3 · IA on-device** | Copiloto especialista em segurança pública | **100% local.** Wake word "Hey Claryon". Nada de IA na nuvem em caminho nenhum |

**Casos de uso do P3:** *"Hey Claryon, envie um resumo da última hora"* ·
*"Hey Claryon, onde está a guarnição do Sgt. Paiva?"*

---

## 2. Proibido — sem versão, sem flag, sem exceção

- ❌ **Reconhecimento facial ou base biométrica.**
- ❌ **Transcrever, classificar ou indexar a fala de terceiros.** O beamforming isola
  quem veste os óculos — transcrevemos o agente, não o interlocutor. É intencional,
  não conserte. O pré-roll do PTT vive em RAM e nunca é persistido.
- ❌ **Enviar áudio, transcrição ou frame para serviço externo no caminho crítico.**
- ❌ **Função de servidor que receba a identidade de quem pergunta como parâmetro.**
  Com ela, distâncias trilateram a posição absoluta de qualquer par. O solicitante
  vem do JWT; o parâmetro só existe dentro do schema `private`.
- ❌ **Áudio pelo DAT.** Não existe `session.audioStream` — verificado por `javap`:
  `Stream` expõe `videoStream`, `errorStream`, `state`, `start`, `stop`,
  `capturePhoto`, e nada de áudio. Microfone e alto-falante dos óculos são
  **HFP/SCO**. Classe de áudio em pacote `internal` não é API pública.
- ❌ **LLM escolhendo ação.** Ele só preenche campos de intenção já definida.
- ❌ **Credencial versionada.** Evidência fora de `EncryptedFile` + Keystore.
- ❌ **Assinatura de API escrita de memória** (DAT, MapLibre, sherpa-onnx, Android).
  Confirme por `javap` no artefato ou na doc oficial. Idem dependência nova: tamanho,
  licença, alternativa nativa. **Não confirmou? Pare e pergunte.**

---

## 3. Stack e módulos

Kotlin 2.2 · Compose (sem navigation-compose) · JDK 17 · AGP 8.9.2 · minSdk 31 ·
compileSdk 35 · NDK 27 · mwdat **0.9.0** · MapLibre 11.11.0 · whisper.cpp +
sherpa-onnx (locais) · Supabase.

`app` orquestra. `core-*` só dependem de `core-common`. **Todo acesso ao DAT passa
por `GlassesFacade`** em `core-glasses`.

```
./gradlew build · :app:installDebug · connectedAndroidTest
adb logcat -s ClaryonField
python3 servidor/executar_sql.py <arquivo.sql>
```

Projeto vive em `~/Downloads`, **não** em `~/Desktop`: o iCloud já esvaziou
`.git/index` uma vez (flag `dataless`) e derrubou o versionamento.

---

## 4. Invariantes que o compilador ou um teste sustentam

Não precisam de disciplina — quebram o build. Estão aqui para não serem removidas
por engano:

- Capturar exige `GlassesAudioRoute`. Gravar pelo microfone do celular **não compila**.
- `utteranceFor` aceita **apenas** `ActionOutcome` — a fala deriva do resultado da
  ação, nunca do comando. Teste falha se alguém acrescentar sobrecarga.
- `IntentExecutor` nunca lança: falha vira `ActionOutcome.Falhou` tipado.
- Máximo **7 palavras** por resposta de TTS operacional.
- Uma instância de `GlassesAudioManagerImpl` por processo (`AudioDoAgente`).

---

## 5. Antes de tocar — leia o trecho, não o documento

O gatilho é este. Sem ele, "leia só o que a tarefa pede" não dispara, porque o
agente não sabe o que não sabe.

| Vai mexer em… | Leia |
|---|---|
| áudio, HFP, PTT, rádio | `docs/PADROES_DE_ENGENHARIA.md` §Sequências · §Rota de áudio · §Rádio tático |
| posição, mapa, RPC | idem §Localização e mapa · `servidor/migracoes/0003,0006,0008,0010` |
| fala, earcon, TTS, energia | idem §Honestidade · §Design de áudio · §Energia |
| qualquer API do DAT | **Regra Zero**: MCP `search_dat_docs` + `javap` no AAR |

---

## 6. Antes de dizer "pronto"

**Teste verde prova que o caminho feliz existe; não prova que os outros existem.**

**Toda capacidade precisa de caminho alcançável pelo agente.** Construir, testar e
não ligar já aconteceu **seis** vezes neste projeto — a última descoberta em
16/08: as três Edge Functions não têm um único chamador em Kotlin, então
`transmissions` nunca recebe INSERT e o fio do canal é permanentemente vazio em
produção. "Construído" significa **tem chamador em `src/main` alcançável em
runtime**. Classe testada sem chamador é *escrita*, não construída.

Ao fechar o bloco: reescreva `ESTADO.md` e **`git push origin master`**.

---

## 7. Fluxo de trabalho

- Um marco por sessão. Ao concluir, apresente o critério de aceite atendido e pare
  para revisão humana.
- Mudança de comportamento começa por **diff de spec**, não por diff de código.
  Sobrepor regra dura é decisão humana: a spec entra como **proposta** e espera.
- Commits pequenos, mensagem explicando o **porquê**. `DECISIONS.md` ganha uma
  entrada por decisão não óbvia: data, alternativa descartada, motivo.

---

## 8. Índice — o que existe e quando abrir

| Arquivo | Abra quando |
|---|---|
| [`ESTADO.md`](ESTADO.md) | "Onde estamos?" — funciona / quebrado / próximo. Teto de 60 linhas, reescrito a cada sessão |
| [`ROADMAP.md`](ROADMAP.md) | "O que vem, em que ordem, e o que destrava o quê" |
| [`DECISIONS.md`](DECISIONS.md) | "Por que está assim?" — arqueologia cronológica, +1000 linhas. **Não** serve para saber onde estamos |
| [`specs/`](specs/) | Uma por feature, aceite em EARS. Revisada **antes** do diff |
| [`docs/PADROES_DE_ENGENHARIA.md`](docs/PADROES_DE_ENGENHARIA.md) | A referência longa: sequências de boot, tabela de armadilhas, design de áudio, energia, metas |
| [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md) | O que o edital exige × o que existe |
| [`docs/VERIFICACOES_COM_HARDWARE.md`](docs/VERIFICACOES_COM_HARDWARE.md) | O que só se mede com óculos e fone reais |
| [`docs/DIARIO_DE_BORDO.md`](docs/DIARIO_DE_BORDO.md) | Narrativa por marco. **Não contém estado atual** |
| [`README.md`](README.md) | Setup do zero: NDK, modelos, Supabase, emulador |

**Fontes externas:** MCP `search_dat_docs` · repo oficial
`facebook/meta-wearables-dat-android` (traz dez `SKILL.md` da própria Meta em
`plugins/mwdat-android/skills/`) · edital e material do curso em
`~/Desktop/Curso CEIA Meta` · capturas das palestras em `~/Desktop/Prints Ceia`.
