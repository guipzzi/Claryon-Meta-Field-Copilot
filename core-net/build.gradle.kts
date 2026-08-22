import java.util.Properties

// core-net — transporte da rede tática: PTT ao vivo, alertas e posições.
//
// A regra que governa este módulo: **o caminho ao vivo nunca espera durabilidade**.
// Quadros Opus de 20 ms saem enquanto o agente fala; o arquivamento no Storage é
// assíncrono e posterior. Gravar o arquivo inteiro e depois enviar transformaria
// rádio em áudio de mensagem — 10 s de atraso em vez de 300–600 ms.
//
// Política (pré-roll, jitter, piso, sequenciamento) é pura e testável em JVM;
// mecanismo (MediaCodec, WebSocket) fica isolado nas implementações.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Credenciais do Supabase para o teste de integração do transporte. Lidas de
// `local.properties` (não versionado) ou das envs correspondentes. Ausentes, o
// teste de integração se declara pulado em vez de falhar — ninguém deve precisar
// de credencial para rodar a suíte.
val props = Properties().apply {
    val arquivo = rootProject.file("local.properties")
    if (arquivo.exists()) arquivo.inputStream().use { load(it) }
}
fun segredo(chave: String, env: String): String =
    System.getenv(env) ?: props.getProperty(chave) ?: ""

android {
    namespace = "com.claryon.net"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${segredo("supabase_url", "SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${segredo("supabase_anon_key", "SUPABASE_ANON_KEY")}\"")
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
            // **Sem isto, `android.util.Log` lança `RuntimeException("not mocked")`
            // em teste JVM — e o que fica intestável é justamente o CAMINHO DE
            // FALHA, porque é ele que loga.** O `app` já carrega esta linha, com o
            // mesmo argumento por escrito; `core-net` ficou sem ela e tem
            // `android.util.Log` em seis arquivos de `src/main`. O preço apareceu
            // ao cobrir `PublicadorDePosicaoSupabase`: a publicação sem token, a
            // recusa por HTTP e a queda de rede passaram a registrar a causa — e
            // as três viraram testes que explodiam na infraestrutura antes de
            // chegar na asserção.
            //
            // `returnDefaultValues` devolve 0/null/false para todo método de
            // framework não implementado. Não é substituto do Robolectric: só
            // torna o stub silencioso em vez de explosivo.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.kotlinx.coroutines.core)
    // WebSocket do Supabase Realtime. TCP_NODELAY é configurado no cliente:
    // o algoritmo de Nagle agruparia quadros e somaria dezenas de milissegundos.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Sem isto, `JSONObject.put` lança "not mocked" no unit test e o caminho de
    // renovação de sessão fica inteiro atrás de uma exceção de infraestrutura.
    testImplementation(libs.json.jvm)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
