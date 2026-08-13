// core-glasses — ÚNICO módulo que tocará o Meta Wearables DAT.
// No M0: apenas a fachada GlassesFacade e os tipos de domínio que isolam o SDK.
// As dependências mwdat-* e a implementação real entram no M1, quando a versão
// vigente for confirmada via search_dat_docs (Regra Zero).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.glasses"
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
