// core-common — fundação pura (Kotlin/JVM), sem dependência de Android.
// Todos os demais módulos dependem deste; ele não depende de ninguém.
// Ser JVM puro permite testar Result/telemetria/roteamento em JUnit local,
// sem emulador nem Android SDK. Ver DECISIONS.md (2026-08-13).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Exposto como `api` para que todo consumidor (inclusive módulos Android)
// receba as coroutines transitivamente — os contratos usam Flow/StateFlow.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
