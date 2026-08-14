import java.util.Properties

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

// Token do GitHub Packages para os artefatos do DAT. Ordem: env GITHUB_TOKEN,
// senão a chave `github_token` em local.properties (não versionado). Nunca
// commitar o valor. Ver DECISIONS.md.
val githubToken: String = System.getenv("GITHUB_TOKEN")
    ?: rootDir.resolve("local.properties").takeIf { it.exists() }
        ?.let { Properties().apply { it.inputStream().use(::load) }.getProperty("github_token") }
    ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // AAR pré-compilado do sherpa-onnx (Piper TTS) — não versionado, baixado
        // no setup (ver README). flatDir para o AGP tratá-lo como dependência real.
        flatDir { dirs("core-voice/libs") }

        // Meta Wearables Device Access Toolkit (com.meta.wearable:mwdat-*, 0.9.0).
        // Distribuído via GitHub Packages; exige PAT clássico read:packages.
        // Filtro de grupo: só este repo serve com.meta.wearable, evitando bater
        // no GitHub para todas as dependências.
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "" // não necessário (forma oficial da doc do DAT)
                password = githubToken
            }
            content {
                includeGroup("com.meta.wearable")
            }
        }
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
include(":core-net")
