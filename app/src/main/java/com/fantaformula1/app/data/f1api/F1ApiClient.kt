package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.data.f1api.model.F1PitStop
import com.fantaformula1.app.data.f1api.model.F1PitStopResponse
import com.fantaformula1.app.data.f1api.model.F1QualifyingResult
import com.fantaformula1.app.data.f1api.model.F1Race
import com.fantaformula1.app.data.f1api.model.F1Response
import com.fantaformula1.app.data.f1api.model.F1Result
import com.fantaformula1.app.data.f1api.model.F1SprintResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client per l'API Jolpica/Ergast F1.
 *
 * Endpoint usati:
 * - `f1/2026.json` -> calendario completo
 * - `f1/2026/{round}/results.json` -> risultati gara
 * - `f1/2026/{round}/qualifying.json` -> griglia di partenza (per mappare posizioni)
 * - `f1/2026/{round}/sprint/results.json` -> risultati sprint
 *
 * Ottimizzazioni: timeout espliciti, retry automatico su errori di rete/5xx/429
 * e throttle tra richieste per rispettare i rate-limit di Jolpica
 * (~4 richieste/secondo sul tier gratuito).
 */
class F1ApiClient {

    private val client: HttpClient = HttpClient(Android) {
        defaultRequest {
            url(BASE_URL)
        }
        install(ContentNegotiation) {
            json(F1Json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(MAX_RETRIES)
            // Il rate-limit (429) e' un errore temporaneo: si riprova dopo.
            retryIf { _, response -> response.status.value == 429 }
            exponentialDelay()
        }
        // Le 4xx/5xx diventano ResponseException con lo status leggibile:
        // senza, il body di errore HTML (es. 404 da rete interferente) esplode
        // come NoTransformationFoundException e il motivo va perso.
        expectSuccess = true
    }

    /**
     * Throttle: un intervallo minimo tra le richieste evita di superare i
     * limiti di Jolpica quando la sincronizzazione scarica piu' gare di fila.
     */
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    private suspend fun <T> throttled(block: suspend () -> T): T =
        requestMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestAt
            val wait = MIN_REQUEST_INTERVAL_MS - elapsed
            if (wait > 0) delay(wait)
            try {
                block()
            } finally {
                lastRequestAt = System.currentTimeMillis()
            }
        }

    /** Scarica il calendario completo della stagione (default: anno corrente). */
    suspend fun seasonCalendar(season: Int = currentSeason()): List<F1Race> = throttled {
        val response: F1Response = client
            .get("f1/$season.json")
            .body()
        response.mrData.raceTable.races
    }

    /** Risultati della gara per un determinato round. */
    suspend fun raceResults(season: Int, round: Int): List<F1Result> = throttled {
        val response: F1Response = client
            .get("f1/$season/$round/results.json")
            .body()
        response.mrData.raceTable.races.firstOrNull()?.results.orEmpty()
    }

    /** Risultati della qualifica per un determinato round. */
    suspend fun qualifyingResults(season: Int, round: Int): List<F1QualifyingResult> = throttled {
        val response: F1Response = client
            .get("f1/$season/$round/qualifying.json")
            .body()
        response.mrData.raceTable.races.firstOrNull()?.qualifyingResults.orEmpty()
    }

    /** Risultati dello sprint per un determinato round. */
    suspend fun sprintResults(season: Int, round: Int): List<F1SprintResult> = throttled {
        val response: F1Response = client
            .get("f1/$season/$round/sprint/results.json")
            .body()
        response.mrData.raceTable.races.firstOrNull()?.sprintResults.orEmpty()
    }

    /**
     * Pit stop per un determinato round (uno per ogni fermata dei box).
     * Il default Ergast e' 30 righe: con il limite a 100 si copre un GP
     * completo senza paginazione.
     */
    suspend fun pitStops(season: Int, round: Int): List<F1PitStop> = throttled {
        val response: F1PitStopResponse = client
            .get("f1/$season/$round/pitstops.json") {
                parameter("limit", PIT_STOPS_LIMIT)
            }
            .body()
        response.mrData.pitStops()
    }

    companion object {
        private const val BASE_URL = "https://api.jolpi.ca/ergast/"
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MAX_RETRIES = 2
        private const val MIN_REQUEST_INTERVAL_MS = 300L
        private const val PIT_STOPS_LIMIT = "100"

        /** Stagione di riferimento: anno corrente in UTC (N6). */
        fun currentSeason(): Int = java.time.LocalDate.now(java.time.ZoneOffset.UTC).year
    }
}

/**
 * Messaggio amichevole per un fallimento della sync Jolpica (funzione pura,
 * testata): niente tracce di ktor e un consiglio per ogni caso.
 *
 * Il 404 con body HTML non e' una risposta di Jolpica (l'API risponde quasi
 * sempre 200 JSON, anche con round inesistenti): arriva di solito da rete
 * locale interferente, quindi il consiglio e' "riprova / controlla il round".
 */
internal fun f1FailureMessage(statusCode: Int?, rawMessage: String?): String =
    when (statusCode) {
        404 -> "Jolpica non ha il risultato di questa gara: " +
            "riprova più tardi o controlla il round della gara"
        429 -> "Troppe richieste a Jolpica: riprova tra qualche minuto"
        null -> rawMessage ?: "Impossibile contattare Jolpica: controlla la connessione"
        else ->
            if (statusCode >= 500) {
                "Jolpica è temporaneamente indisponibile: riprova più tardi"
            } else {
                rawMessage ?: "Errore dal server Jolpica (HTTP $statusCode)"
            }
    }