// core-audio — roteamento HFP/SCO e captura/reprodução PCM.
// Áudio NÃO passa pelo DAT: microfone e alto-falantes são acessados por
// AudioManager/AudioRecord/AudioTrack via perfis Bluetooth. GlassesAudioManagerImpl
// roteia o SCO (contagem de referência, para um caminho não derrubar a rota de
// outro) e entrega AudioRecord VOICE_COMMUNICATION → Flow<ShortArray>.
// O eco HFP final ainda precisa de validação com fone Bluetooth físico.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.audio"
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
