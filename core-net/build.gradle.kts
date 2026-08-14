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
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.kotlinx.coroutines.core)
    // WebSocket do Supabase Realtime. TCP_NODELAY é configurado no cliente:
    // o algoritmo de Nagle agruparia quadros e somaria dezenas de milissegundos.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
