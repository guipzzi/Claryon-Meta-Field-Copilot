// Raiz do projeto multi-módulo Claryon Field.
// Os plugins são declarados aqui com `apply false` para fixar as versões
// (via version catalog) num único lugar; cada módulo os aplica conforme sua
// natureza. Ver gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Workaround temporário: o Android Lint embarcado no AGP 8.7.2 quebra com
// IncompatibleClassChangeError (NonNullableMutableLiveDataDetector) ao analisar
// UAST de código compilado com Kotlin 2.2 — versão exigida pelo DAT 0.9.0. Não é
// o nosso código. Desligamos as tasks de lint até fixar uma combinação AGP/lint
// compatível com Kotlin 2.2. A compilação e os testes unitários seguem ativos.
// Ver DECISIONS.md (2026-08-13). TODO: reativar o lint no marco de energia (M8).
subprojects {
    tasks.matching { it.name.startsWith("lint") }.configureEach {
        enabled = false
    }
}
