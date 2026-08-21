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
    testOptions.unitTests.all {
        it.inputs.file("src/main/res/drawable/marca_claryon.xml")
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
