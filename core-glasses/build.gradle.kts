// core-glasses — ÚNICO módulo que toca o Meta Wearables DAT.
// A fachada GlassesFacade e os tipos de domínio isolam o SDK: quando a preview
// quebrar assinaturas, conserta-se um arquivo. Implementações: DatGlassesFacade
// (DAT 0.9) e MockDeviceController (debug, sem hardware). Toda assinatura do SDK
// é confirmada na fonte viva antes de virar código (Regra Zero).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.glasses"
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

    // Meta Wearables Device Access Toolkit — apenas aqui.
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    // mockdevice: compileOnly aqui (a classe compila), mas NÃO é empacotado por
    // este módulo. O `app` empacota só em DEBUG (debugImplementation). Assim o
    // MockDeviceController não entra no APK de release — a classe nunca é
    // carregada em release (o chamador gateia por BuildConfig.DEBUG).
    compileOnly(libs.mwdat.mockdevice)

    testImplementation(libs.junit)
}
