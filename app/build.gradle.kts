// app — camada de orquestração e UI Compose.
// A tela NÃO é canal de resposta ao usuário final (a saída rica é áudio); existe
// para onboarding, diagnóstico e o painel de demo à banca. `app` depende de
// todos os core-* e faz a orquestração (boot, ciclo de voz, encerramento).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.claryon.field"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.claryon.field"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Deve casar exatamente com a versão do Kotlin (1.9.24 ↔ 1.5.14).
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
    // Módulos do projeto — o app orquestra todos.
    implementation(project(":core-common"))
    implementation(project(":core-agent"))
    implementation(project(":core-glasses"))
    implementation(project(":core-audio"))
    implementation(project(":core-voice"))
    implementation(project(":core-sound"))
    implementation(project(":core-evidence"))
    implementation(project(":core-sync"))

    // AndroidX + Compose.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
