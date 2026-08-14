// core-sound — earcons, fila de prioridade e protocolo de laconicidade.
// Num sistema sem display, o áudio é o único canal rico: cada erro tem earcon
// próprio (falha nunca é silêncio) e resultado sensível sai codificado, nunca
// falado (o alto-falante open-ear vaza som). SoundScheduler é a política (pura e
// testada); PrioritySoundQueue é o mecanismo; EarconSynthesizer sintetiza os tons.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.sound"
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

    testImplementation(libs.junit)
}
