# Aderência ao Meta Wearables Device Access Toolkit e ao hardware

**Critério §11.2 do edital — 20 pontos:** *"Uso efetivo do Meta Wearables Device Access
Toolkit e dos óculos."*

Este documento responde ao critério com **arquivo e linha**, não com descrição. Tudo
abaixo tem chamador alcançável em `src/main` — que é a definição de "construído" neste
projeto (`CLAUDE.md §6`).

---

## 1. Os três canais nativos, todos usados

O edital (§1) define o hardware: *"Os óculos Meta operam **sem display**, tendo como
canais principais a **câmera, o microfone e os alto-falantes**."* O Claryon usa os três.

| Canal | Uso | Cadeia alcançável |
|---|---|---|
| **Microfone** | PTT e palavra de ativação | HFP/SCO via `GlassesAudioManagerImpl` → `EscutaDeAtivacao` / `RadioTatico` |
| **Câmera** | OCR de placa veicular | `CopilotoDoAgente:260` → `PlacaPelaCamera:126` → `CapturaDePlaca:109` → `PlacaOcr` |
| **Alto-falante** | TTS, earcons, áudio do rádio | `SaidaUnica` → `RotaSustentada` → `AudioTrack` |

**Nenhum é decorativo.** Cada um está no caminho crítico de um dos três pilares.

---

## 2. Regra Zero: nenhuma assinatura escrita de memória

Este projeto tem uma regra dura (`CLAUDE.md §2`): **assinatura de API do DAT nunca é
escrita de memória** — é confirmada por `javap` no artefato ou pela documentação
oficial. Não confirmou, para e pergunta.

O caso que justifica a regra: a suposição de que existiria `session.audioStream`.
`javap` no AAR mostrou que `Stream` expõe `videoStream`, `errorStream`, `state`,
`start`, `stop`, `capturePhoto` — **e nada de áudio**. Microfone e alto-falante dos
óculos são **HFP/SCO**, não DAT. Classe de áudio em pacote `internal` não é API pública.

Consequência de projeto: toda a rota de áudio foi construída sobre
`AudioManager.setCommunicationDevice()` + `TYPE_BLUETOOTH_SCO`, e não sobre uma API
que não existe. Descobrir isso por `javap` em vez de por falha em produção é o
resultado que a regra compra.

---

## 3. Ciclo de vida da sessão: dono de processo

`SessaoDosOculos` (`app/…/oculos/SessaoDosOculos.kt:69`) é um `object` com **dono único
de processo**, e existe porque a alternativa falhou:

- `startSession()` tinha **zero chamadores em `src/main`** — a sessão do DAT **nunca era
  aberta em produção**, só pelo painel de diagnóstico e pelos testes.
- Prender a sessão a um `ViewModel` a mataria em `onCleared()`, ou seja, ao girar a tela.

Hoje: aberta no início do turno (`MainActivity:248 SessaoDosOculos.abrir()`), com um
vigia que só toca o SDK quando os óculos estão `REGISTERED`. **Não abre sob demanda**
porque `startSession()` tem teto de 12 s e a consulta de placa tem 5 s para o OCR
inteiro. Guardado por teste: `FachadaDoDatTemDonoUnicoTest:51`.

---

## 4. `errorStream`: a ordem importa, e há teste que a documenta

`DatGlassesFacade.kt:401` coleta `stream.errorStream`. O ponto não é coletar — é
**registrar antes do start**:

```
:381  launch { stream.state.collect { … } }
:398  launch { stream.errorStream.collect { … } }   ← registro
:114  facade.startCameraStream(…)                    ← só depois
```

`replay = 0`: um erro emitido entre o `start` e o `collect` seria **perdido para
sempre**. `StreamTerminalNoAparelhoTest:105` documenta a ordem no aparelho.

### `STOPPED` só é terminal depois de ter subido

`VidaDoStream.kt:50-77` mantém a flag `jaSubiu`, marcada apenas em `STARTED` e
`STREAMING`. `STOPPED` devolve `PARAR_CAMERA` somente se `jaSubiu && !jaParou`. Sem
isso, um `STOPPED` emitido **antes** de o stream subir encerraria a captura antes de
ela existir — falha que só aparece em hardware real.

---

## 5. As oito causas de falha do SDK chegam ao ouvido — e chegam DIFERENTES

Sem display, uma falha que soa igual a outra é a versão sonora do silêncio. Até 21/08 as
oito causas de `ErroDeStream` viravam *"Consulta indisponível."*: a causa tipada morria
num `Log.w` um passo antes do alto-falante.

O agrupamento é por **recuperação**, não por causa:

| Causa do SDK | O que o agente faz |
|---|---|
| `HINGE_CLOSED` | abre as hastes — dois segundos, resolve na hora |
| `PERMISSIONS_DENIED` | mexe no app da Meta — precisa parar e olhar o celular |
| `THERMAL_HOT` | espera esfriar — **insistir piora** |
| `BATTERY_LOW` / `PEAK_POWER_LIMIT` | põe no estojo — mesmo gesto, mesma fala |
| resto | tenta de novo |

A tradução (`FalhaDaCamera.kt`) é um `when` **sem `else`**: valor novo no SDK quebra a
compilação em vez de cair num balde genérico. `FalhaDaCameraTest` prova que recuperações
distintas produzem falas distintas — um teste que só verificasse "existe fala" passaria
com as oito colapsadas.

---

## 6. Trabalhar sem o hardware: `MockDeviceKit` e bancada honesta

A equipe **não tocará nos óculos antes de 18/09**. Todo o trabalho de HFP/SCO, câmera e
ciclo de vida de stream foi feito às cegas. A mitigação foi declarada, não improvisada:

- **`MockDeviceKit`** para os caminhos de erro do SDK.
- **Fone Bluetooth HFP** como bancada real de rota de áudio.
- **`docs/VERIFICACOES_COM_HARDWARE.md`** — a lista explícita do que **só** se mede com
  óculos reais, mantida como pendência declarada em vez de item verde.

Isso é o oposto de afirmar cobertura que não existe.

---

## 7. Fronteira arquitetural: `GlassesFacade`

**Todo** acesso ao DAT passa por `GlassesFacade` em `core-glasses`. `core-agent` não
conhece `core-glasses` e não deve: é o que impede o vocabulário de ação de crescer com
detalhe de hardware. `app` conhece os dois e é o único lugar onde a tradução existe.

A fronteira não é convenção — é sustentada por teste de reflexão de classpath.

---

## 8. O que ainda não foi exercitado no hardware

Coerente com a regra de não descrever capacidade não verificada:

1. **Recall da palavra de ativação pelo microfone dos óculos** — nunca medido por HFP.
   O número que existe (3/4 locutores) é do microfone do celular.
2. **Consumo de bateria dos óculos com a `DeviceSession` aberta** — não medido.
   É o preço direto do dono de processo, e está declarado como tal.
3. **Comportamento térmico** — `THERMAL_HOT` está tratado no código, nunca provocado.
4. **`startCameraStream`** continua sem chamador em `src/main`; o caminho de produção
   usa `withCamera(CameraProfile.OCR)`.

---

*Cada afirmação deste documento aponta arquivo e linha. Onde não há medição, está
escrito que não há.*
