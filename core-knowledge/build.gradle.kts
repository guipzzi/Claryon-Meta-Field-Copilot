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

// O corpus de lei entra no artefato do módulo como RECURSO, copiado do único
// lugar onde ele mora — `corpus/trechos.jsonl`, na raiz, que é o arquivo que o
// conferidor de procedência valida. Copiar em vez de versionar uma segunda cópia
// evita a falha clássica: duas cópias que divergem, e o teste medindo a que não
// vai para o aparelho.
//
// Recurso, e não `assets/`, por dois motivos concretos: este módulo é JVM puro e
// não tem `assets`; e recurso de módulo viaja para dentro do APK de quem
// declarar a dependência, sem o chamador precisar abrir nada nem saber o caminho.
// Custo medido: 1 173 KB crus, 267 KB deflacionados — 0,07% dos 378 MB do APK.
//
// ATENÇÃO: nenhuma linha aqui pode declarar dependência de projeto nova. A lista
// acima é a fronteira, e `FronteiraDoConhecimentoTest` derruba o build se crescer.
val corpusEmbarcado by tasks.registering(Copy::class) {
    from(rootProject.file("corpus/trechos.jsonl"))
    into(layout.buildDirectory.dir("recursos-do-corpus/corpus"))
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("recursos-do-corpus"))

tasks.named("processResources") { dependsOn(corpusEmbarcado) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
