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
    compileSdk = 34

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

    testImplementation(libs.junit)
}
