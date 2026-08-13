pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // ── M1 (Setup do DAT) ─────────────────────────────────────────────
        // Artefatos do Meta Wearables DAT (com.meta.wearable:mwdat-*, versão
        // 0.9.0 — confirmada via search_dat_docs em 2026-08-13) são distribuídos
        // via GitHub Packages e exigem um PAT clássico com escopo `read:packages`.
        // NÃO habilitado no M0: depende de credencial (Regra Zero). Forma oficial
        // da credencial: username vazio + chave `github_token` em local.properties
        // (ou env GITHUB_TOKEN). Nunca versionar o token. Ver DECISIONS.md.
        //
        // val githubToken = providers.environmentVariable("GITHUB_TOKEN")
        //     .orElse(providers.gradleProperty("github_token"))
        // maven {
        //     url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
        //     credentials {
        //         username = "" // não necessário
        //         password = githubToken.get()
        //     }
        // }
    }
}

rootProject.name = "claryon-field"

include(":app")
include(":core-common")
include(":core-agent")
include(":core-glasses")
include(":core-audio")
include(":core-voice")
include(":core-sound")
include(":core-evidence")
include(":core-sync")
