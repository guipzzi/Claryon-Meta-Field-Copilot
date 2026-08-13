// core-agent — modelo de intenções e roteador determinístico (Kotlin/JVM puro).
// Sem Android, sem LLM, sem rede: o roteamento é por padrão + verbos-chave
// sobre a transcrição, testável em JUnit local. Depende apenas de core-common.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-common"))

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
