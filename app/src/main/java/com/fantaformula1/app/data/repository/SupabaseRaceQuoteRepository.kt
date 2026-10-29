package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.RaceQuote
import com.fantaformula1.app.core.domain.usesServerQuote
import com.fantaformula1.app.data.supabase.SupabaseHolder
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Riga grezza di `race_quotes` (snake_case non serve: i nomi coincidono). */
@Serializable
internal data class RaceQuoteRowDto(
    val type: String,
    val key: String,
    val threshold: Double? = null,
    val quota: Double,
)

/**
 * Mappa le righe del server in quote di dominio. Funzione pura, testata:
 * scarta i tipi sconosciuti/non a quota e i punti senza soglia (un dato
 * incompleto non deve diventare una quota giocabile).
 */
internal fun mapQuoteRows(rows: List<RaceQuoteRowDto>): List<RaceQuote> =
    rows.mapNotNull { row ->
        val type = PredictionType.entries.firstOrNull { it.name == row.type }
            ?: return@mapNotNull null
        if (!type.usesServerQuote) return@mapNotNull null
        if (type == PredictionType.DRIVER_POINTS_OVER_UNDER && row.threshold == null) {
            return@mapNotNull null
        }
        RaceQuote(
            type = type,
            key = row.key,
            threshold = row.threshold,
            quota = row.quota,
        )
    }

/**
 * Stato di lettura a partire dalle righe: nessuna riga valida = quote non
 * ancora pubblicate (l'admin deve pubblicarle).
 */
internal fun quoteLoadFromRows(rows: List<RaceQuoteRowDto>): RaceQuoteLoad {
    val quotes = mapQuoteRows(rows)
    // Una sola riga non rende giocabili TUTTI i tipi a quota: senza almeno
    // una quota per ciascuno dei tre tipi lo stato resta "non pubblicate".
    // La completezza per singola scelta viene comunque verificata in
    // PredictionViewModel prima del salvataggio.
    val hasAllTypes = PredictionType.entries
        .filter { it.usesServerQuote }
        .all { type -> quotes.any { it.type == type } }
    return if (hasAllTypes) RaceQuoteLoad.Published(quotes) else RaceQuoteLoad.NotPublished
}

/**
 * Implementazione Supabase: SELECT su `race_quotes` e RPC
 * `publish_race_quotes` (0013). Gli errori sono tradotti in esiti, mai
 * propagati come eccezioni generiche: se la migrazione non e' applicata la
 * UI mostra un messaggio comprensibile invece di un crash.
 */
class SupabaseRaceQuoteRepository(
    private val holder: SupabaseHolder,
) : RaceQuoteRepository {

    override suspend fun quotes(raceId: String): RaceQuoteLoad {
        val client = holder.get() ?: return RaceQuoteLoad.Unreachable
        return try {
            val rows = client.from("race_quotes").select {
                filter { eq("race_id", raceId) }
            }.decodeList<RaceQuoteRowDto>()
            quoteLoadFromRows(rows)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            if (isMissingSchemaError(throwable.message)) {
                RaceQuoteLoad.ServerUnsupported
            } else {
                RaceQuoteLoad.Unreachable
            }
        }
    }

    override suspend fun publish(
        raceId: String,
        quotes: List<RaceQuote>,
    ): QuotePublishOutcome {
        val client = holder.get() ?: return QuotePublishOutcome.ServerUnsupported
        val payload = buildJsonArray {
            quotes.forEach { quote ->
                add(
                    buildJsonObject {
                        put("type", quote.type.name)
                        put("key", quote.key)
                        put("quota", quote.quota)
                        quote.threshold?.let { put("threshold", it) }
                    },
                )
            }
        }

        return try {
            client.postgrest.rpc(
                function = "publish_race_quotes",
                parameters = buildJsonObject {
                    put("p_race", raceId)
                    put("p_quotes", payload)
                },
            )
            QuotePublishOutcome.Published
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val message = throwable.message.orEmpty()
            when {
                // Immutabilita' dopo il primo pronostico (0013): non e' un
                // errore da mostrare all'utente, la pubblicazione si ferma.
                message.contains("Non puoi modificare le quote", ignoreCase = true) ->
                    QuotePublishOutcome.Locked
                isMissingSchemaError(message) -> QuotePublishOutcome.ServerUnsupported
                else -> QuotePublishOutcome.Failed(
                    message.ifBlank { "Impossibile pubblicare le quote" },
                )
            }
        }
    }
}
