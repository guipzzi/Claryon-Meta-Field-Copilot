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
        // Os artefatos do Meta Wearables Device Access Toolkit (mwdat-core,
        // mwdat-camera, mwdat-mockdevice) são distribuídos via GitHub Packages
        // e exigem um Personal Access Token clássico com escopo `read:packages`.
        // NÃO habilitado no M0: depende de credencial (Regra Zero: parar e
        // perguntar) e da confirmação da versão vigente via `search_dat_docs`.
        // Quando for habilitar, ler o token de `local.properties`/`GITHUB_TOKEN`
        // (nunca versionar). Ver DECISIONS.md (2026-08-13).
        //
        // maven {
        //     url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
        //     credentials {
        //         username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
        //         password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
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
