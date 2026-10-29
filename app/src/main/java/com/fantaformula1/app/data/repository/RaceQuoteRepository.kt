package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.RaceQuote

/**
 * Esito della lettura delle quote di una gara.
 *
 * Gli stati distinti servono alla UI per dire la verita':
 *  - [Published]: quote disponibili, i tipi a quota sono compilabili;
 *  - [NotPublished]: tabella raggiungibile ma nessuna riga (l'admin deve
 *    pubblicarle);
 *  - [ServerUnsupported]: migrazione 0013 non applicata (tabella o RPC
 *    assenti): messaggio dedicato, nessun crash;
 *  - [Unreachable]: rete/configurazione non disponibile.
 */
sealed interface RaceQuoteLoad {
    data class Published(val quotes: List<RaceQuote>) : RaceQuoteLoad
    data object NotPublished : RaceQuoteLoad
    data object ServerUnsupported : RaceQuoteLoad
    data object Unreachable : RaceQuoteLoad
}

/** Esito della pubblicazione (RPC `publish_race_quotes`). */
sealed interface QuotePublishOutcome {
    data object Published : QuotePublishOutcome

    /** Esistono gia' pronostici per la gara: modificare e' vietato. */
    data object Locked : QuotePublishOutcome

    /** Migrazione 0013 non applicata. */
    data object ServerUnsupported : QuotePublishOutcome

    data class Failed(val message: String) : QuotePublishOutcome
}

/**
 * Quote e soglie pubblicate dal server. Lettura per i membri, scrittura
 * riservata alla RPC admin (qualsiasi errore e' restituito, mai lanciato).
 */
interface RaceQuoteRepository {
    suspend fun quotes(raceId: String): RaceQuoteLoad
    suspend fun publish(raceId: String, quotes: List<RaceQuote>): QuotePublishOutcome
}

/**
 * Riconosce gli errori PostgREST/Postgres di schema mancante (tabella o
 * funzione non ancora create). Funzione pura, testata su JVM: dipende solo
 * dal testo dell'errore per restare indipendente dalla versione del client.
 */
internal fun isMissingSchemaError(message: String?): Boolean {
    val lower = message?.lowercase().orEmpty()
    return lower.contains("pgrst205") ||
        lower.contains("pgrst202") ||
        lower.contains("42p01") ||
        lower.contains("42883") ||
        lower.contains("could not find the table") ||
        lower.contains("could not find the function") ||
        lower.contains("relation \"public.race_quotes\" does not exist")
}
