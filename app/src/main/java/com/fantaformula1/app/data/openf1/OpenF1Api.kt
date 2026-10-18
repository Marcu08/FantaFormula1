package com.fantaformula1.app.data.openf1

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// Fonte dati OpenF1 (https://openf1.org): messaggi Race Control con le
// attivazioni SAFETY CAR / VIRTUAL SAFETY CAR. Affianca (NON sostituisce)
// l'API Jolpica/Ergast che resta la fonte dei risultati di gara: da OpenF1 si
// importano SOLO i conteggi SC/VSC, revisionati dall'amministratore prima di
// finire nel form. API pubblica, senza autenticazione, storic dal 2023.
// ---------------------------------------------------------------------------

/**
 * Sessione OpenF1: la chiave e' [sessionKey] (non il round Jolpica: i due
 * numeri NON coincidono, il mapping e' in [OpenF1SessionMatcher]).
 */
@Serializable
data class OpenF1Session(
    @SerialName("session_key") val sessionKey: Int,
    @SerialName("meeting_key") val meetingKey: Int,
    @SerialName("session_name") val sessionName: String,
    @SerialName("session_type") val sessionType: String,
    @SerialName("country_name") val countryName: String,
    @SerialName("country_code") val countryCode: String,
    val location: String = "",
    @SerialName("circuit_short_name") val circuitShortName: String = "",
    @SerialName("date_start") val dateStart: String? = null,
    val year: Int,
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
)

/** Messaggio FIA Race Control (giorno del round, categoria, testo, giro). */
@Serializable
data class OpenF1RaceControlMessage(
    val category: String,
    val message: String? = null,
    val date: String? = null,
    @SerialName("lap_number") val lapNumber: Int? = null,
)

/**
 * Fonte astratta per rendere il mapping e il parser collaudabili su JVM:
 * la singola implementazione concreta parla col client HTTP.
 */
interface OpenF1Source {
    suspend fun sessions(year: Int): List<OpenF1Session>
    suspend fun raceControl(sessionKey: Int): List<OpenF1RaceControlMessage>
}

/** Implementazione concreta via ktor (gio bei timeout generosi ma strict). */
class OpenF1Client : OpenF1Source {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(openF1Json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    override suspend fun sessions(year: Int): List<OpenF1Session> =
        client.get("$BASE_URL/v1/sessions?year=$year").body()

    override suspend fun raceControl(sessionKey: Int): List<OpenF1RaceControlMessage> =
        client.get("$BASE_URL/v1/race_control?session_key=$sessionKey").body()

    companion object {
        private const val BASE_URL = "https://api.openf1.org"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /** Stagione supportata (allineata all'app: Jolpica e cache stagione). */
        const val SEASON = 2026

        /** Unknown keys: l'API aggiunge campi senza preavviso; vietato rompere l'app. */
        private val openF1Json = Json { ignoreUnknownKeys = true }
    }
}

/** Istante di parse delle date OpenF1 (ISO con offset) — condiviso coi test. */
internal fun openF1DateOrNull(raw: String?): java.time.Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        java.time.Instant.parse(raw)
    } catch (e: Exception) {
        try {
            java.time.OffsetDateTime.parse(raw).toInstant()
        } catch (e2: Exception) {
            null
        }
    }
}
