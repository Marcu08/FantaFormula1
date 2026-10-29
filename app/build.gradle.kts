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

// Firma RELEASE: nessun keystore nel repository. I valori arrivano solo
// dall'ambiente (locale o GitHub Actions Secrets). Se mancano, il build
// release fallisce esplicitamente: non esiste un fallback alla chiave debug.
fun envVar(name: String, trim: Boolean = true): String? {
    val value = System.getenv(name) ?: return null
    return (if (trim) value.trim() else value).takeIf { it.isNotEmpty() }
}

val releaseKeystorePath = envVar("RELEASE_KEYSTORE_PATH")
// Le password non vengono trimmate: spazi iniziali/finali fanno parte del
// segreto e non devono essere alterati.
val releaseKeystorePassword = envVar("RELEASE_KEYSTORE_PASSWORD", trim = false)
val releaseKeyAlias = envVar("RELEASE_KEY_ALIAS")
val releaseKeyPassword = envVar("RELEASE_KEY_PASSWORD", trim = false)
val releaseKeystoreFile = releaseKeystorePath?.let { rootProject.file(it) }
val releaseSigningConfigured =
    releaseKeystoreFile?.isFile == true &&
        releaseKeystorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

android {
    namespace = "com.fantaformula1.app"
    // 36 e' richiesto da androidx.browser:1.9.0 (transitivo di activity-compose),
    // che dichiara nelle AAR metadata "compile against version 36 or later".
    // targetSdk resta 35: cambia solo cosa il build puo' usare, non il runtime.
    compileSdk = 36

    /**
     * Debug: usa la firma debug standard di Gradle (keystore locale della
     * macchina, mai versionato). Release: solo keystore esterno passato via
     * variabili d'ambiente. Nessuna delle due firme legge file dal repository.
     */
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.fantaformula1.app"
        // 21 = Android 5.0: massima compatibilita' con dispositivi vecchi.
        // java.time richiede desugaring (attivato sotto) per girare prima di API 26.
        minSdk = 21
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Accetta sia i nomi maiuscoli del README che i vecchi nomi camelCase.
        buildConfigField("String", "DEFAULT_SUPABASE_URL", "\"${localProp("SUPABASE_URL", "supabase.url")}\"")
        buildConfigField("String", "DEFAULT_SUPABASE_ANON_KEY", "\"${localProp("SUPABASE_ANON_KEY", "supabase.anonKey")}\"")
    }

    buildTypes {
        debug {
            // Firma debug standard: nessuna chiave condivisa e nessun segreto
            // nel repository. La firma e' quella del keystore debug locale.
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Null se la configurazione release manca: il task di validazione
            // sotto blocca assembleRelease/bundleRelease prima della firma.
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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

// Fail-closed: una release firmata senza keystore esterno non deve produrre
// un artefatto non firmato o, peggio, firmato con la chiave debug. Il task
// elenca solo i NOMI delle variabili mancanti, mai i valori.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Verifica che la firma release sia configurata tramite variabili d'ambiente."
    doLast {
        if (!releaseSigningConfigured) {
            val missing = buildList {
                if (releaseKeystorePath == null) add("RELEASE_KEYSTORE_PATH")
                if (releaseKeystorePassword == null) add("RELEASE_KEYSTORE_PASSWORD")
                if (releaseKeyAlias == null) add("RELEASE_KEY_ALIAS")
                if (releaseKeyPassword == null) add("RELEASE_KEY_PASSWORD")
            }
            val detail = if (missing.isEmpty()) {
                "RELEASE_KEYSTORE_PATH non punta a un file esistente"
            } else {
                "variabili mancanti: ${missing.joinToString(", ")}"
            }
            throw GradleException(
                "Firma release non configurata ($detail). " +
                    "Imposta il keystore esterno e riprova; la chiave debug non viene usata come fallback.",
            )
        }
    }
}

tasks.matching {
    it.name == "assembleRelease" || it.name == "bundleRelease" || it.name == "packageRelease"
}.configureEach {
    dependsOn(validateReleaseSigning)
}

kotlin {
    // Toolchain 21: la compilazione Kotlin/Java usa sempre una JDK 21 anche se
    // JAVA_HOME punta a una versione piu' recente (il JDK 27 di sistema non
    // funziona con AGP 8.x). Il bytecode resta 17 (come compileOptions sotto):
    // Kotlin e Java devono dichiarare lo stesso target altrimenti il build
    // fallisce con "Inconsistent JVM-target compatibility".
    jvmToolchain(21)
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

// -----------------------------------------------------------------------------
// Controllo caratteri non latini (CJK) nei sorgenti: rifuso ricorrente degli
// strumenti di editing. Il build `check` fallisce se ne trova uno.
// Alberi controllati: app/src, supabase (migrations+tests) e README.md.
// -----------------------------------------------------------------------------
val cjkRegex = Regex("[\\p{InCJKUnifiedIdeographs}\\p{InHiragana}\\p{InKatakana}\\p{InHangulSyllables}]")
tasks.register("checkNoCjkCharacters") {
    doLast {
        val roots = listOf(
            file("src"),
            file("../supabase"),
            file("../README.md"),
        )
        val offenders = mutableListOf<String>()
        roots.forEach { root ->
            val files: List<File> = when {
                root.isFile -> listOf(root)
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile }
                    .filter { it.extension.lowercase() in setOf("kt", "kts", "sql", "md", "xml") }
                    .toList()
                else -> emptyList()
            }
            files.forEach { f ->
                val text = try {
                    f.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    return@forEach
                }
                text.lines().forEachIndexed { idx, line ->
                    if (cjkRegex.containsMatchIn(line)) {
                        offenders += "${f.path}:${idx + 1}"
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "CARATTERI CJK TROVATI (rifusi di editing): correggere i commenti in:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}
tasks.matching { it.name == "check" }.configureEach {
    dependsOn("checkNoCjkCharacters")
}
