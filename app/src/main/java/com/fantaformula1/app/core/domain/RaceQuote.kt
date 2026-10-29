package com.fantaformula1.app.core.domain

/**
 * Quota pubblicata dal server per una gara (tabella `race_quotes`, 0013).
 *
 * Sorgente di verita' UNICA per quota e soglia dei tipi a quota: il client
 * non le ricalcola piu' localmente. [key] e' la scelta esatta (vedi
 * [QuoteKeys]) e coincide col formato della migrazione 0013.
 */
data class RaceQuote(
    val type: PredictionType,
    val key: String,
    val threshold: Double?,
    val quota: Double,
)

/** True per i tipi la cui quota/soglia arriva SOLO dal server (0013). */
val PredictionType.usesServerQuote: Boolean
    get() = this == PredictionType.DRIVER_DNF ||
        this == PredictionType.DRIVER_POINTS_OVER_UNDER ||
        this == PredictionType.HEAD_TO_HEAD

/**
 * Chiave canonica della SCELTA di un pronostico a quota.
 *
 * E' l'UNICO punto in cui il formato e' definito lato client: la stessa
 * funzione viene usata per pubblicare, leggere e comporre il pronostico.
 * Deve restare identica alla migrazione `0013_server_quotes.sql`:
 *  - DRIVER_DNF / DRIVER_POINTS_OVER_UNDER -> `<driverId>|true|false`;
 *  - HEAD_TO_HEAD -> `<minId>|<maxId>|<pickedId>` (coppia canonica ordinata).
 */
object QuoteKeys {

    private const val SEPARATOR = "|"

    fun dnf(driverId: String, retires: Boolean): String = "$driverId$SEPARATOR$retires"

    fun points(driverId: String, over: Boolean): String = "$driverId$SEPARATOR$over"

    /**
     * Testa a testa: la COPPIA e' sempre in ordine canonico (stesso risultato
     * invertendo i piloti), il lato scelto resta nella chiave perche' la
     * quota e' asimmetrica (favorito 0.6x vs sorpresa 2.5x).
     */
    fun headToHead(picked: String, opponent: String): String {
        val first = minOf(picked, opponent)
        val second = maxOf(picked, opponent)
        return "$first$SEPARATOR$second$SEPARATOR$picked"
    }

    /**
     * Chiave della scelta contenuta nella voce, oppure null se la voce e'
     * incompleta o il tipo non ha quota.
     */
    fun of(entry: PredictionEntry): String? = when (entry.type) {
        PredictionType.DRIVER_DNF ->
            entry.driverIds.firstOrNull()?.let { id ->
                entry.booleanValue?.let { dnf(id, it) }
            }
        PredictionType.DRIVER_POINTS_OVER_UNDER ->
            entry.driverIds.firstOrNull()?.let { id ->
                entry.over?.let { points(id, it) }
            }
        PredictionType.HEAD_TO_HEAD -> {
            val picked = entry.driverIds.getOrNull(0)
            val opponent = entry.driverIds.getOrNull(1)
            if (picked != null && opponent != null) headToHead(picked, opponent) else null
        }
        else -> null
    }
}

/** Quota pubblicata per una scelta (tipo + chiave), null se assente. */
fun quoteFor(quotes: List<RaceQuote>, type: PredictionType, key: String): RaceQuote? =
    quotes.firstOrNull { it.type == type && it.key == key }

/**
 * Applica quota e soglia PUBBLICATE alla voce; null se per quella scelta non
 * esiste una quota (il tipo non e' compilabile finche' l'admin non pubblica).
 */
fun PredictionEntry.withPublishedQuote(quotes: List<RaceQuote>): PredictionEntry? {
    if (!type.usesServerQuote) return this
    val key = QuoteKeys.of(this) ?: return null
    val quote = quoteFor(quotes, type, key) ?: return null
    return copy(
        threshold = if (type == PredictionType.DRIVER_POINTS_OVER_UNDER) {
            quote.threshold
        } else {
            threshold
        },
        quota = quote.quota,
    )
}
