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

// Lint reabilitado com AGP 8.9.2 (o 8.7.2 quebrava com Kotlin 2.2 —
// IncompatibleClassChangeError no NonNullableMutableLiveDataDetector). Ver DECISIONS.md.
