import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Valori di default Supabase presi da local.properties (opzionali).
// I nomi ufficiali sono SUPABASE_URL e SUPABASE_ANON_KEY; per retrocompatibilita'
// vengono accettati anche supabase.url e supabase.anonKey.
// L'app permette di sovrascriverli a runtime dalla schermata Configurazione:
// in assenza di chiavi mostra stato "configurazione mancante" invece di crashare.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localProp(vararg keys: String): String {
    for (key in keys) {
        localProperties.getProperty(key)?.let { return it }
    }
    return ""
}

android {
    namespace = "com.fantaformula1.app"
    // 36 e' richiesto da androidx.browser:1.9.0 (transitivo di activity-compose),
    // che dichiara nelle AAR metadata "compile against version 36 or later".
    // targetSdk resta 35: cambia solo cosa il build puo' usare, non il runtime.
    compileSdk = 36

    /**
     * Firma FISSA condivisa (app tra amici, non commerciale): il keystore e'
     * nel repo cosi' OGNI APK da qualsiasi macchina o da CI ha la STESSA firma
     * e puo' aggiornare le installazioni precedenti (il debug keystore per-
     * macchina cambia da PC a PC e blocca gli update con "app non installata"
     * senza motivo visibile).
     * E' il keystore debug di questo progetto (alias androiddebugkey): gli APK
     * gia' installati dal primo build del proprietario restano aggiornabili
     * senza disinstallare. La chiave e' privata di valore: e' un keystore debug
     * condiviso volutamente per un progetto non commerciale.
     */
    signingConfigs {
        create("shared") {
            storeFile = file("fantaformula1.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.fantaformula1.app"
        // 21 = Android 5.0: massima compatibilita' con dispositivi vecchi.
        // java.time richiede desugaring (attivato sotto) per girare prima di API 26.
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Accetta sia i nomi maiuscoli del README che i vecchi nomi camelCase.
        buildConfigField("String", "DEFAULT_SUPABASE_URL", "\"${localProp("SUPABASE_URL", "supabase.url")}\"")
        buildConfigField("String", "DEFAULT_SUPABASE_ANON_KEY", "\"${localProp("SUPABASE_ANON_KEY", "supabase.anonKey")}\"")
    }

    buildTypes {
        debug {
            // Anche i build debug usano la firma condivisa: cosi' gli APK
            // distribuiti fra amici si aggiornano l'un l'altro.
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // Desugaring: java.time (deadline, parse ISO) compatibile anche su Android < 8
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Desugaring java.time per minSdk < 26
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Android / Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Supabase (BOM allinea auth/postgrest/realtime)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth.kt)
    implementation(libs.supabase.postgrest.kt)
    implementation(libs.supabase.realtime.kt)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // WorkManager per la sincronizzazione periodica API F1
    implementation(libs.androidx.work.runtime.ktx)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.espresso.core)
}
