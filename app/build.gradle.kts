import java.io.File
import java.io.FileInputStream
import java.util.Properties

// app — camada de orquestração e UI Compose.
// A tela NÃO é canal de resposta ao usuário final (a saída rica é áudio); existe
// para onboarding, diagnóstico e o painel de demo à banca. `app` depende de
// todos os core-* e faz a orquestração (boot, ciclo de voz, encerramento).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.claryon.field"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claryon.field"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ABIs consistentes com o whisper nativo (o AAR do sherpa traz todas; sem
        // isto o APK carregaria .so de sherpa para ABIs sem o whisper compilado).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Endereço do projeto e chave anônima, de `local.properties` (fora do
        // versionamento). A chave anônima é pública por desenho — é ela que o RLS
        // pressupõe, e sozinha não dá acesso a nada — mas o ENDEREÇO do projeto do
        // piloto não precisa estar num repositório aberto, e manter as duas juntas
        // evita que alguém acrescente uma credencial de verdade "no mesmo lugar
        // das outras".
        //
        // Ausentes, o app compila e as capacidades de rede se anunciam como
        // indisponíveis — nunca falham em silêncio.
        val propriedadesLocais = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }?.let { arquivo ->
            FileInputStream(arquivo).use { propriedadesLocais.load(it) }
        }
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${propriedadesLocais.getProperty("supabase_url", "")}\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${propriedadesLocais.getProperty("supabase_anon_key", "")}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG gateia o MockDeviceKit; e traz a config do Supabase
    }

    androidResources {
        // Modelos on-device empacotados em assets/models/. Comprimir não vale a
        // pena: são binários já densos (ganho de tamanho ínfimo) e a
        // descompressão custa tempo e pico de memória a cada carga. O whisper lê
        // por AASSET_MODE_STREAMING, então funcionaria comprimido — isto é
        // otimização de tempo de carga, não pré-requisito.
        noCompress += listOf("bin", "onnx")
    }
    // Com Kotlin 2.x o Compose Compiler vem do plugin `compose-compiler`
    // (versão casada ao Kotlin); não há mais kotlinCompilerExtensionVersion.

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Sem isto, `android.util.Log` lança `RuntimeException("not mocked")`
            // em teste JVM — e o que fica intestável é justamente o CAMINHO DE
            // FALHA, porque é ele que loga. `RadioTatico` registra o toque
            // ignorado por repique, a captura que falhou e a recepção
            // encerrada; a `PrioritySoundQueue` loga a exceção que ela existe
            // para não deixar escapar. Todos eram inalcançáveis por teste.
            //
            // `returnDefaultValues` devolve 0/null/false para todo método de
            // framework não implementado. Não é substituto do Robolectric: só
            // torna o stub silencioso em vez de explosivo. Onde o comportamento
            // do framework importa de verdade, o teste continua sendo
            // instrumentado (`app/src/androidTest`).
            isReturnDefaultValues = true
        }
    }

    // **`MarcaTest` lê o `VectorDrawable` do disco, e o Gradle não sabia disso.**
    //
    // Ele compara `res/drawable/marca_claryon.xml` com as frações de
    // `GeometriaDaMarca` — é o que impede a marca do ícone e a marca da abertura
    // de virarem duas formas diferentes. Sem esta linha o arquivo não é entrada da
    // tarefa: depois de uma rodada verde, mexer SÓ no vetor deixava o teste
    // `UP-TO-DATE`, ele nem rodava, e a divergência passava. Medido: a primeira
    // tentativa de contra-teste "passou" exatamente assim.
    //
    // **Mesma armadilha, segundo arquivo (22/08):** `TelaDePericiaTest` exige que a
    // ressalva do R8 — *`Confere` não é inforjável* — exista **na tela e no
    // relatório de impacto**, para que o produto e o documento não possam divergir.
    // O `.md` não é entrada de compilação de nada: sem esta linha, apagar a ressalva
    // do relatório deixava o teste `UP-TO-DATE` e a divergência passava. A tela é
    // literal de string, então ela já invalida a tarefa por compilação.
    testOptions.unitTests.all {
        it.inputs.file("src/main/res/drawable/marca_claryon.xml")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        it.inputs.file(rootProject.file("docs/RELATORIO_DE_IMPACTO_LGPD.md"))
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
    // Lint ATIVO (o AGP 8.9.2 corrigiu o bug com Kotlin 2.2). O único achado
    // suprimido é o MissingPermission do AudioRecord, com justificativa no
    // próprio ponto de uso.
}

dependencies {
    // Módulos do projeto — o app orquestra todos.
    implementation(project(":core-common"))
    implementation(project(":core-agent"))
    implementation(project(":core-glasses"))
    implementation(project(":core-audio"))
    implementation(project(":core-voice"))
    implementation(project(":core-sound"))
    implementation(project(":core-evidence"))
    // O cofre de evidência já usa esta biblioteca, mas por `implementation` — a
    // dependência não é transitiva. O `app` a usa por conta própria, para guardar
    // a sessão do agente com a mesma proteção que a evidência recebe.
    implementation(libs.androidx.security.crypto)
    implementation(project(":core-sync"))
    implementation(project(":core-net"))
    // Etapa B da Fase 4: llama.cpp como camada de REDAÇÃO. O módulo não tem
    // `core-agent` no classpath — a saída do modelo não consegue nomear ação.
    implementation(project(":core-knowledge"))
    implementation(project(":core-llm"))

    // sherpa-onnx (Piper TTS): o app empacota as .so + classes; core-voice usa compileOnly.
    implementation(":sherpa-onnx-1.13.5@aar")

    // OCR de placa on-device (M6). Modelo Latin embarcado → roda offline, sem rede.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.maplibre)
    implementation(libs.kotlinx.coroutines.core)

    // MockDeviceKit só no APK de DEBUG (o release não empacota o mock).
    debugImplementation(libs.mwdat.mockdevice)

    // AndroidX + Compose.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRAVA CONTRA TESTE QUE FICA VERDE SEM RODAR
// ═══════════════════════════════════════════════════════════════════════════════
//
// **Por que existe.** Em um único dia foram achados quatro casos em que um teste
// reportava sucesso sem ter executado uma linha do que promete testar:
//
//  1. `WhisperCppSttTest` procurava `models/ggml-tiny.bin`; o projeto embarca
//     `ggml-small-q5_1.bin`. O `assumeTrue` pulava SEMPRE — `OK (1 test)` em 0,008 s.
//  2. `scripts/caos_mdk.sh` imprimia "N/N verdes" sobre uma classe `@Ignore`d, cujo
//     resultado no XML é `tests="0"`.
//  3. `CaosDoAparelhoTest` tem `@Ignore` no nível da CLASSE — a suíte de caos física
//     inteira nunca roda, e nada avisa.
//  4. `UtteranceTest.todos` dizia "Todos os resultados possíveis" e faltavam quatro.
//
// Varredura única apodrece. Por isso isto é **trava**, não faxina.
//
// **Por que não é só um número.** A sugestão natural é versionar a CONTAGEM de
// pulados e quebrar quando ela subir. Ela é necessária e insuficiente, por dois
// motivos medidos aqui:
//
//   · Ela não pega o caso (1), que é o mais caro. Ali a contagem nunca se moveu: o
//     `assumeTrue` já existia e já era esperado. O que mudou foi a CONDIÇÃO ficar
//     permanentemente falsa. Uma contagem global é cega para isso; um inventário
//     por símbolo com o motivo escrito ao lado não é — quem lê a linha
//     "modelo whisper ausente" ao lado do nome do teste pergunta "ausente por quê?".
//   · Ela apodrece na direção contrária. Removido um `@Ignore`, a contagem
//     declarada fica folgada e passa a ABSORVER em silêncio o próximo pulo que
//     alguém acrescentar. Por isso a conferência é de CONJUNTO e vale nos dois
//     sentidos: entrada sem código também quebra.
//
// **Por que a trava é estática.** Medido em 2026-08-21: as 1147 asserções JVM que
// `./gradlew build` roda têm `skipped="0"` — ZERO. Os 123 `assume*` e os 2 `@Ignore`
// vivem todos em `src/androidTest`, que `build` nunca executa. Uma trava que só
// lesse XML de resultado seria vacuamente verde em `./gradlew build`, que é
// exatamente o defeito que ela existe para combater. A leitura de XML existe
// (`travaDeSuiteVazia`) e é o segundo dente, não o primeiro.
//
// **Como se conserta um build vermelho aqui.** `./gradlew :app:gravarPulados`
// reescreve o inventário preservando os motivos que já existem e marcando os novos
// com `MOTIVO PENDENTE`. Regenerar é fácil de propósito — e **não deixa verde**: a
// trava reprova `MOTIVO PENDENTE`. Escrever o motivo é o pedágio, e é o ponto.

/** Marcador que `gravarPulados` deixa e que a trava recusa. */
val motivoPendente = "MOTIVO PENDENTE"

/** Inventário versionado: um pulo por linha, com o motivo ao lado. */
val arquivoDePulados: File = rootProject.file("ferramentas/pulados.tsv")

/**
 * Raízes de código de teste do repositório **inteiro**, não só as do `app`.
 * A trava mora no `app` porque é o módulo que sempre entra em `./gradlew build`,
 * mas o que ela vigia é o corpus de teste do projeto.
 */
val arvoresDeTeste: List<File> =
    rootProject.projectDir.listFiles().orEmpty()
        .filter { it.isDirectory && it.name != "build" && !it.name.startsWith(".") }
        .flatMap { listOf(File(it, "src/test"), File(it, "src/androidTest")) }
        .filter { it.isDirectory }
        .sortedBy { it.invariantSeparatorsPath }

/**
 * Devolve as linhas com todo comentário apagado, preservando a numeração.
 *
 * Necessário porque o repositório fala SOBRE `@Ignore` em KDoc — dos 14 casamentos
 * crus de `grep`, só 4 são anotação. Contar prosa como pulo tornaria o inventário
 * ruído, e ruído é o que faz uma trava ser desligada.
 */
fun linhasSemComentario(linhas: List<String>): List<String> {
    var emBloco = false
    return linhas.map { bruta ->
        var l = bruta
        if (emBloco) {
            val fim = l.indexOf("*/")
            if (fim < 0) return@map ""
            emBloco = false
            l = " ".repeat(fim + 2) + l.substring(fim + 2)
        }
        while (true) {
            val abre = l.indexOf("/*")
            if (abre < 0) break
            val fecha = l.indexOf("*/", abre + 2)
            if (fecha < 0) {
                emBloco = true
                l = l.substring(0, abre)
                break
            }
            l = l.substring(0, abre) + " ".repeat(fecha + 2 - abre) + l.substring(fecha + 2)
        }
        val barras = l.indexOf("//")
        // Aspas ímpares antes das duas barras ⇒ estamos dentro de string (URL).
        if (barras >= 0 && l.take(barras).count { it == '"' } % 2 == 0) l = l.substring(0, barras)
        l
    }
}

/** Nome do `fun` ou da `class` que a anotação de pulo enfeita, olhando adiante. */
fun alvoAdiante(linhas: List<String>, i: Int): String? {
    val declFun = Regex("""\bfun\s+`?([^`(]+?)`?\s*\(""")
    val declTipo = Regex("""\b(?:class|object)\s+([A-Za-z0-9_]+)""")
    for (j in (i + 1)..minOf(i + 12, linhas.lastIndex)) {
        declTipo.find(linhas[j])?.let { return it.groupValues[1] }
        declFun.find(linhas[j])?.let { return it.groupValues[1].trim() }
    }
    return null
}

/**
 * Todo ponto do corpus de teste em que um teste pode passar **sem executar**:
 * `@Ignore` (o JUnit nem chama) e `Assume.*` (o JUnit aborta e conta como pulado).
 *
 * A chave é `arquivo::símbolo`, e não `arquivo:linha`, de propósito: número de linha
 * anda a cada edição e um inventário que precisa ser reescrito por causa de um
 * `import` novo é um inventário que ninguém mantém.
 */
fun varrerPulos(): List<Triple<String, String, String>> {
    val anotacaoIgnore = Regex("""^\s*@(?:org\.junit\.)?Ignore\b""")
    val chamadaAssume = Regex("""\bassume(?:True|False|NotNull|That)\s*\(""")
    val declFun = Regex("""^\s*(?:[a-z]+\s+)*fun\s+`?([^`(]+?)`?\s*\(""")
    val declTipo = Regex("""^\s*(?:[a-z]+\s+)*(?:class|object)\s+([A-Za-z0-9_]+)""")

    val achados = mutableListOf<Triple<String, String, String>>()
    arvoresDeTeste.forEach { arvore ->
        arvore.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { arq ->
                val rel = arq.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                val linhas = linhasSemComentario(arq.readLines())
                var funCorrente = ""
                var tipoCorrente = ""
                linhas.forEachIndexed { i, linha ->
                    declTipo.find(linha)?.let { tipoCorrente = it.groupValues[1] }
                    declFun.find(linha)?.let { funCorrente = it.groupValues[1].trim() }
                    if (anotacaoIgnore.containsMatchIn(linha)) {
                        val alvo = alvoAdiante(linhas, i)
                            ?: funCorrente.ifEmpty { tipoCorrente }
                        achados += Triple(rel, alvo, "Ignore")
                    }
                    repeat(chamadaAssume.findAll(linha).count()) {
                        achados += Triple(rel, funCorrente.ifEmpty { tipoCorrente }, "Assume")
                    }
                }
            }
    }
    return achados
}

/** `arquivo\tsímbolo` → `"Assume:3,Ignore:1"`. */
fun resumoDePulos(): Map<String, String> {
    val porChave = linkedMapOf<String, MutableMap<String, Int>>()
    varrerPulos().forEach { (arq, simbolo, tipo) ->
        val m = porChave.getOrPut("$arq\t$simbolo") { linkedMapOf() }
        m[tipo] = (m[tipo] ?: 0) + 1
    }
    return porChave.mapValues { (_, m) ->
        m.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    }
}

/** `arquivo\tsímbolo` → (resumo declarado, motivo escrito). */
fun lerInventarioDePulos(): Map<String, Pair<String, String>> {
    if (!arquivoDePulados.exists()) return emptyMap()
    return arquivoDePulados.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { linha ->
            val campos = linha.split('\t')
            if (campos.size < 4) return@mapNotNull null
            "${campos[0]}\t${campos[1]}" to (campos[2] to campos.drop(3).joinToString("\t"))
        }
        .toMap()
}

val cabecalhoDePulados = """
    # Inventário dos pulos de teste — a trava do `:app:travaDePulados`.
    #
    # UMA LINHA POR SÍMBOLO que pode fazer um teste passar SEM EXECUTAR.
    # Formato, separado por TAB:   arquivo <TAB> símbolo <TAB> resumo <TAB> motivo
    #
    # O build QUEBRA quando:
    #   · aparece um pulo no código que não está aqui        (pulo novo sem justificativa)
    #   · existe linha aqui cujo pulo sumiu do código        (entrada morta, que absorveria o próximo)
    #   · o resumo (quantos e de que tipo) não bate          (contagem mudou)
    #   · o motivo está vazio ou é `MOTIVO PENDENTE`         (regenerou e não escreveu)
    #
    # Para reconciliar:  ./gradlew :app:gravarPulados   — preserva os motivos que já
    # existem e marca os novos com MOTIVO PENDENTE. Regenerar NÃO deixa verde: o
    # pedágio é escrever o motivo.
    #
    # NÃO é licença para o corpo do teste apodrecer. Um `assume` cuja condição é
    # permanentemente falsa (o caso `ggml-tiny.bin`) satisfaz esta trava e mente
    # assim mesmo — o motivo escrito aqui é o que dá a alguém a chance de perceber.
""".trimIndent()

val travaDePulados = tasks.register("travaDePulados") {
    group = "verification"
    description = "Quebra se um teste puder ficar verde sem rodar e ninguém tiver escrito por quê."
    inputs.files(arvoresDeTeste).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(arquivoDePulados).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(layout.buildDirectory.file("trava-de-pulados.txt"))
    doLast {
        val atual = resumoDePulos()
        val declarado = lerInventarioDePulos()
        val problemas = mutableListOf<String>()

        (atual.keys + declarado.keys).sorted().forEach { chave ->
            val (arq, simbolo) = chave.split('\t')
            val encontrado = atual[chave]
            val linha = declarado[chave]
            when {
                encontrado != null && linha == null ->
                    problemas += "PULO NOVO SEM JUSTIFICATIVA   $arq :: $simbolo   ($encontrado)"
                encontrado == null && linha != null ->
                    problemas += "ENTRADA MORTA (o pulo sumiu do código)   $arq :: $simbolo"
                encontrado != linha!!.first ->
                    problemas += "CONTAGEM MUDOU   $arq :: $simbolo   " +
                        "declarado=${linha.first} encontrado=$encontrado"
                linha.second.isBlank() || linha.second.trim() == motivoPendente ->
                    problemas += "MOTIVO PENDENTE   $arq :: $simbolo"
            }
        }

        val total = atual.values.sumOf { resumo ->
            resumo.split(',').sumOf { it.substringAfter(':').toInt() }
        }
        val relatorio = layout.buildDirectory.file("trava-de-pulados.txt").get().asFile
        relatorio.parentFile.mkdirs()
        relatorio.writeText("$total pulos em ${atual.size} símbolos\n")

        if (problemas.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("A trava de pulados reprovou — ${problemas.size} problema(s):")
                    problemas.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Um teste que pula reporta VERDE. É por isso que isto quebra o build.")
                    appendLine("Reconcilie com:  ./gradlew :app:gravarPulados")
                    appendLine("e escreva o motivo de cada linha nova em ${arquivoDePulados.path}")
                },
            )
        }
        logger.lifecycle("trava de pulados: $total pulos em ${atual.size} símbolos, todos justificados")
    }
}

tasks.register("gravarPulados") {
    group = "verification"
    description = "Reescreve ferramentas/pulados.tsv preservando os motivos já escritos."
    doLast {
        val motivos = lerInventarioDePulos().mapValues { it.value.second }
        val atual = resumoDePulos()
        val novos = atual.keys.count { motivos[it].isNullOrBlank() }
        arquivoDePulados.parentFile.mkdirs()
        arquivoDePulados.writeText(
            buildString {
                appendLine(cabecalhoDePulados)
                appendLine()
                atual.entries.sortedBy { it.key }.forEach { (chave, resumo) ->
                    val motivo = motivos[chave]?.takeIf { it.isNotBlank() } ?: motivoPendente
                    appendLine("$chave\t$resumo\t$motivo")
                }
            },
        )
        logger.lifecycle(
            "gravado ${arquivoDePulados.path}: ${atual.size} símbolos, $novos com $motivoPendente",
        )
    }
}

// ── Segundo dente: nada reporta verde sobre `tests="0"` ─────────────────────────
//
// O primeiro dente lê CÓDIGO; este lê RESULTADO. Ele pega o que a varredura
// estática não pode ver: a suíte que o runner não executou (classe `@Ignore`d ⇒
// `tests="0"`, o caso do `caos_mdk.sh`) e o pulo que nasce em runtime por um
// caminho que não é `assume` literal — uma `AssumptionViolatedException` lançada
// por regra, base ou auxiliar.
val travaDeSuiteVazia = tasks.register("travaDeSuiteVazia") {
    group = "verification"
    description = "Reprova resultado de teste com tests=\"0\" ou pulo em runtime não declarado."
    doLast {
        val pastas = rootProject.projectDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap {
                listOf(
                    File(it, "build/test-results"),
                    File(it, "build/outputs/androidTest-results"),
                )
            }
            .filter { it.isDirectory }
        val xmls = pastas.flatMap { p ->
            p.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        }
        if (xmls.isEmpty()) {
            logger.lifecycle("trava de suíte vazia: nenhum resultado de teste no disco")
            return@doLast
        }

        val declarados = lerInventarioDePulos().keys.map { it.substringAfter('\t') }.toSet()
        val problemas = mutableListOf<String>()
        var suites = 0
        var pulos = 0

        xmls.forEach { xml ->
            val texto = xml.readText()
            // `</testsuite>` ausente = arquivo ainda sendo escrito por outra tarefa
            // em paralelo. Acusar suíte vazia sobre XML pela metade seria a trava
            // mentindo — e trava que dá alarme falso é trava que alguém desliga.
            if (!texto.contains("</testsuite>")) return@forEach
            val cabeca = Regex("""<testsuite\b[^>]*>""").find(texto)?.value ?: return@forEach
            suites++
            val nome = Regex("""name="([^"]*)"""").find(cabeca)?.groupValues?.get(1).orEmpty()
            val quantos = Regex("""\btests="(\d+)"""").find(cabeca)?.groupValues?.get(1)?.toInt() ?: -1
            if (quantos == 0) {
                problemas += "SUÍTE VAZIA (tests=\"0\") — $nome — ${xml.name}: " +
                    "o runner não executou nada e o resultado é verde. " +
                    "@Ignore no nível da classe faz exatamente isto."
            }
            texto.split("<testcase").drop(1).forEach caso@{ pedaco ->
                val corpo = pedaco.substringBefore("</testcase>").substringBefore("<testcase")
                if (!corpo.contains("<skipped")) return@caso
                pulos++
                val caso = Regex("""name="([^"]*)"""").find(pedaco)?.groupValues?.get(1).orEmpty()
                if (caso !in declarados && nome.substringAfterLast('.') !in declarados) {
                    problemas += "PULO EM RUNTIME NÃO DECLARADO — $nome#$caso " +
                        "(sem linha em ${arquivoDePulados.name})"
                }
            }
        }

        if (problemas.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("A trava de suíte vazia reprovou — ${problemas.size} problema(s):")
                    problemas.forEach { appendLine("  $it") }
                },
            )
        }
        logger.lifecycle("trava de suíte vazia: $suites suítes, $pulos pulos, nenhuma vazia")
    }
}

tasks.named("check") { dependsOn(travaDePulados) }
tasks.withType<Test>().configureEach { finalizedBy(travaDeSuiteVazia) }
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }
    .configureEach { finalizedBy(travaDeSuiteVazia) }
