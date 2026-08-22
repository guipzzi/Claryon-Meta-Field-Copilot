// core-evidence — cofre cifrado e cadeia de custódia.
// Evidência SEMPRE em EncryptedFile + chave no Android Keystore; jamais em
// storage inseguro. Cada segmento recebe SHA-256 encadeado (adulterar 1 byte
// quebra a cadeia de forma detectável e demonstrável em juízo — verificado em
// teste instrumentado). O encadeamento sozinho é CEGO A REMOÇÃO NO FIM: quem
// apaga os últimos segmentos e as linhas correspondentes deixa uma cadeia
// perfeita. Por isso o manifesto v3 leva ÂNCORA DE FIM (HMAC do Keystore) e a
// conferência fecha por falta — sem âncora válida, não há veredito de
// integridade. Leia AncoraDeFim antes de citar isso: ela para quem tem o disco,
// não quem executa como o app.
// Implementação: EncryptedEvidenceVault + HashChain + AncoraDeFim.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.evidence"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // Repouso cifrado: EncryptedFile (AEAD) + MasterKey no Android Keystore.
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)

    // O cofre real depende do Keystore → verificação em teste instrumentado.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
