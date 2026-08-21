// core-knowledge — recuperação extrativa de norma (Kotlin/JVM puro).
//
// A Etapa A da Fase 4 é RAG **extrativo**: recupera um trecho de lei já escrito e
// o Piper o lê VERBATIM, citando o documento. Nada é redigido por modelo, então
// não há alucinação a filtrar — não existe texto novo em lugar nenhum.
//
// **A lista de dependências abaixo é a fronteira, e ela é normativa.** Este módulo
// não conhece intenção, não conhece ação e não conhece executor: sem os tipos de
// `core-agent` no classpath, nenhuma linha daqui consegue nomear um efeito no
// mundo, mesmo por engano. Acrescentar `implementation(project(":core-agent"))`
// aqui — ou expor `core-agent` como `api` a partir de `core-common` — reabre esse
// caminho em silêncio, e é exatamente isso que `FronteiraDoConhecimentoTest`
// existe para derrubar. Ele lê este arquivo e também varre o classpath de teste.
//
// Não há dependência de Android de propósito: o limiar e a recusa são decisões
// puras, testáveis em JUnit local. O embedder e o índice vetorial (item 3 da
// Etapa A) entram depois, e é lá que a discussão de ONNX/Android acontece.
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
