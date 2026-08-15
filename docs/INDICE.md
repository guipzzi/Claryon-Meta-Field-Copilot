# Índice da documentação

Uma linha por arquivo. **Leia só o que a tarefa em curso pede** — carregar tudo é
o que faz um agente perder o meio do documento e misturar versão de API.

As regras permanentes ficam em [`AGENTS.md`](../AGENTS.md), na raiz, e cabem numa
tela. Este índice existe para o resto: o material grosso, quebrado por assunto.

## Regras e decisões

| Arquivo | Consulte quando |
|---|---|
| [`PADROES_DE_ENGENHARIA.md`](PADROES_DE_ENGENHARIA.md) | Sequência de boot, tabela de armadilhas, design de áudio, energia, metas. É a fonte das regras que o `AGENTS.md` resume |
| [`../DECISIONS.md`](../DECISIONS.md) | "Por que está assim?" — uma linha por decisão não óbvia, com a alternativa descartada e o motivo. Ordem cronológica |
| [`COMPLIANCE.md`](COMPLIANCE.md) | O que o edital exige × o que existe. Antes de afirmar que uma capacidade está pronta |

## Arquitetura e implementação

| Arquivo | Consulte quando |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Módulos, fronteiras, quem depende de quem, onde uma peça nova deve morar |
| [`GUIA_TECNICO.md`](GUIA_TECNICO.md) | Setup do zero: NDK, download de modelos, Supabase, emulador |
| [`VERIFICACOES_COM_HARDWARE.md`](VERIFICACOES_COM_HARDWARE.md) | O que só pode ser medido com óculos e fone reais — e o que ainda não foi |

## Histórico

| Arquivo | Consulte quando |
|---|---|
| [`DIARIO_DE_BORDO.md`](DIARIO_DE_BORDO.md) | Narrativa por marco, com o que quebrou e como foi achado |

## Specs

Uma por feature, em [`../specs/`](../specs/). Critério de aceite em EARS, cada um
mapeando para pelo menos um teste. **É o que se revisa antes de existir diff.**

| Spec | Estado |
|---|---|
| [`gatilho-por-voz.spec.md`](../specs/gatilho-por-voz.spec.md) | Proposta — aguarda revisão humana |

## Fontes externas

- **DAT (Regra Zero):** MCP `search_dat_docs`, `wearables.developer.meta.com/llms.txt?full=true`,
  e o repo oficial `facebook/meta-wearables-dat-android` — que traz dez `SKILL.md`
  da própria Meta em `plugins/mwdat-android/skills/`.
- **Assinatura não confirmável na doc:** confirme por `javap` no AAR do cache do
  Gradle. Não conseguiu confirmar nem assim? **Pare e pergunte.**
