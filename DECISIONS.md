# DECISIONS.md — Claryon Field

Uma linha por decisão não óbvia: data · decisão · alternativa descartada · motivo.
Ordem cronológica inversa (mais recente no topo).

---

## 2026-08-13 — M0 (contexto e esqueleto)

- **Toolchain fixado: AGP 8.5.2 · Gradle 8.9 · Kotlin 1.9.24 · JDK 17 · compileSdk 34 · minSdk 31.**
  Alternativa: Kotlin 2.0 + plugin `compose`. Descartada por ora — o par Kotlin 1.9.24 ↔ Compose Compiler 1.5.14 é o caminho mais estável e sem surpresas para o primeiro build verde. `minSdk 31` porque as APIs centrais do projeto (`setCommunicationDevice`, `createOnDeviceSpeechRecognizer`, thermal headroom) são API 31+.

- **`core-common` e `core-agent` são módulos Kotlin/JVM puros; os demais `core-*` são `com.android.library`.**
  Alternativa: tudo `com.android.library`. Descartada porque a fundação (Result/telemetria) e o roteador determinístico não têm dependência de Android e ganham em testabilidade rodando em JUnit local, sem emulador nem Android SDK — coerente com "roteamento determinístico, testável, sem LLM".

- **Tipo `Result` próprio (sealed Success/Failure + `ClaryonError` tipado) em vez de `kotlin.Result`.**
  Motivo: erro auditável com `code` estável (telemetria e mapeamento erro→earcon) e caminho de falha visível na assinatura de toda operação de risco. "Falha nunca é silêncio."

- **`GlassesFacade` é o único ponto que tocará o DAT; nenhum outro módulo importa símbolos do SDK.**
  Motivo: isola a dependência de uma API em *developer preview*. Quando a 0.9 quebrar assinaturas, conserta-se um arquivo.

- **Sem `jvmToolchain(...)`; compila com o JDK 17 em execução (source/target 17, jvmTarget 17).**
  Alternativa: `kotlin { jvmToolchain(17) }`. Descartada para evitar provisionamento/resolução de toolchain (download) — a build roda sob `JAVA_HOME` = openjdk@17 e compila direto.

- **Dependências do DAT (`mwdat-core/camera/mockdevice`) NÃO adicionadas no M0.**
  Motivo (Regra Zero): dependem de credencial (PAT `read:packages`) e da versão vigente confirmada via `search_dat_docs`. O repositório Maven do GitHub Packages está como placeholder comentado em `settings.gradle.kts`. **Pendência para o M1.**

- **MCP de docs vivas do DAT configurado (escopo de projeto, `.mcp.json`).**
  Servidor `meta-wearables` → `https://mcp.developer.meta.com/wearables`, transporte HTTP, **sem autenticação** (verificado: `initialize` e `tools/list` respondem 200; ferramentas `search_dat_docs` e `search_webapps_docs` disponíveis). Escopo de projeto para toda a equipe herdar via `.mcp.json`. Só carrega em **nova sessão** do Claude Code (servidores MCP sobem no startup) e exige aprovação de confiança na primeira vez.

- **✅ Versão do SDK do DAT registrada: `mwdat = "0.9.0"`.**
  Fonte: `search_dat_docs` (MCP oficial `meta-wearables`), consulta "Android Gradle dependency setup", 2026-08-13. Grupo `com.meta.wearable`; artefatos `mwdat-core`, `mwdat-camera`, `mwdat-display`, `mwdat-mockdevice`. Repositório Maven: `https://maven.pkg.github.com/facebook/meta-wearables-dat-android` (GitHub Packages, exige PAT `read:packages`). A versão exata mais recente deve ser reconferida em GitHub Packages no início do M1. Fecha o último item de aceite do M0.

- **Credencial do GitHub Packages: `username = ""` + chave `github_token` em `local.properties` (ou env `GITHUB_TOKEN`).**
  Correção sobre o placeholder inicial, que supunha `gpr.user`/`gpr.token`. Forma alinhada à doc oficial (`search_dat_docs`, 2026-08-13). Placeholders em `settings.gradle.kts`, `README.md` e `local.properties` ajustados.

- **API real de câmera confirmada (não escrever de memória): `session.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))`.**
  Fonte: `search_dat_docs` "camera streaming setup on Android", 2026-08-13. `frameRate` válido ∈ {2,7,15,24,30}; `VideoQuality` ∈ {LOW 360×640, MEDIUM 504×896, HIGH 720×1280}; `StreamState`: STARTING→STARTED→STREAMING→PAUSED→STOPPING→STOPPED→CLOSED. Divergências vs. minhas suposições do M0 (`addStream`/`quality`) ficam ABSORVIDAS por `GlassesFacade` — nenhum outro módulo muda. A tradução concreta é escrita no M2.

- **`coroutines-core` exposto como `api` em `core-common`.**
  Motivo: os contratos usam `Flow`/`StateFlow`; expor uma vez evita repetir a dependência em cada módulo consumidor.
