// core-sync — Supabase, MessagingGateway (WhatsApp) e fila offline (WorkManager).
// A mensagem que trafega é um objeto tipado preenchendo um template aprovado,
// nunca a transcrição bruta. Sem rede, a mensagem entra na fila e o TTS confirma
// "apoio na fila" — NÃO mente dizendo que enviou. No M0, apenas os contratos;
// implementação no M7.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.sync"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
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
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
