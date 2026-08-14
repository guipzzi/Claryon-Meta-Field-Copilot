// core-evidence — cofre cifrado e cadeia de custódia.
// Evidência SEMPRE em EncryptedFile + chave no Android Keystore; jamais em
// storage inseguro. Cada segmento recebe SHA-256 encadeado (adulterar 1 byte
// quebra a cadeia de forma detectável e demonstrável em juízo). No M0, apenas
// os contratos; implementação no M6.
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
