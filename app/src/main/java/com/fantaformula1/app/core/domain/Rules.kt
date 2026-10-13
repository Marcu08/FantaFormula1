package com.fantaformula1.app.core.domain

import kotlinx.serialization.Serializable

/**
 * Regole di una lega — il jsonb salvato in `leagues.rules`.
 *
 * Questa classe e' la **sorgente di verita'** del formato: il SQL fa una
 * validazione minima, l'applica lato client. I preset sono valori di default
 * modificabili dall'admin dalla schermata "Regole".
 */
@Serializable
data class Rules(
    val version: Int = CURRENT_VERSION,
    val name: String = PRESET_F1_OFFICIAL,
    /** Tipi di pronostico attivi per la lega. */
    val enabledTypes: Set<PredictionType> = PredictionType.entries.toSet(),
    /** Punti base assegnati per ogni tipo di pronostico indovinato. */
    val points: Map<PredictionType, Double> = defaultPoints(),
    /**
     * Punti per il tipo PODIUM, per posizione occupata correttamente:
     * indice 0 = 1º, indice 1 = 2º, indice 2 = 3º. L'indice 3+ e' di riserva.
     */
    val podiumPoints: List<Double> = DEFAULT_PODIUM_POINTS,
    /** Fattore applicato quando la posizione esatta e' mancata di 1 posto. */
    val exactPositionNearMissFactor: Double = 0.5,
    /**
     * Ignorato (B11): il campo resta nel JSON per compatibilita' con le leghe
     * gia' create ma non ha piu' effetto sul calcolo dei punti.
     */
    @Deprecated(
        message = "Ignorato: un pilota DNF non ha posizione e non viene piu' penalizzato via moltiplicatore",
        level = DeprecationLevel.WARNING,
    )
    val dnfMultiplier: Double = 0.5,
    /** Moltiplicatore applicato ai punti parziali quando il pilota scelto ha penalita'. */
    val penaltyMultiplier: Double = 0.75,
    /** Moltiplicatore sui pronostici legati allo sprint. */
    val sprintPointsMultiplier: Double = 0.5,
    /** Come si sblocca la classifica in caso di parita'. */
    val tieBreaker: TieBreaker = TieBreaker.RACE_WINS,
    /** Se true, i pronostici degli altri diventano visibili solo dopo la deadline. */
    val revealOthersAfterDeadline: Boolean = true,
    /** Se false, i pronostici non si possono piu' modificare anche prima della deadline. */
    val allowEditUntilDeadline: Boolean = true,
) {
    fun pointsFor(type: PredictionType): Double = points[type] ?: 0.0

    /**
     * Controlla la coerenza dei valori numerici. Restituisce la lista degli
     * errori in italiano (vuota se tutto valido) — usata dall'editor regole.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (name.trim().length !in 2..60) {
            errors += "Il nome delle regole deve essere lungo tra 2 e 60 caratteri"
        }
        if (enabledTypes.isEmpty()) {
            errors += "Deve essere attivo almeno un tipo di pronostico"
        }
        enabledTypes.forEach { type ->
            val p = points[type]
            if (p == null || !p.isFinite() || p < 0.0 || p > MAX_POINTS) {
                errors += "Punti non validi per \"$type\" (atteso tra 0 e $MAX_POINTS)"
            }
        }
        if (podiumPoints.size < 3) {
            errors += "Il podio richiede almeno 3 valori di punti"
        }
        podiumPoints.forEachIndexed { index, value ->
            if (!value.isFinite() || value < 0.0 || value > MAX_POINTS) {
                errors += "Punti podio non validi in posizione ${index + 1} (atteso tra 0 e $MAX_POINTS)"
            }
        }
        if (podiumPoints.isNotEmpty() && !isDescending(podiumPoints.take(3))) {
            errors += "I punti del podio devono essere decrescenti (1º >= 2º >= 3º)"
        }

        fun checkRange(value: Double, min: Double, max: Double, label: String) {
            if (!value.isFinite() || value < min || value > max) {
                errors += "$label deve essere tra $min e $max"
            }
        }

        checkRange(exactPositionNearMissFactor, 0.0, 1.0, "Il fattore di posizione quasi indovinata")
        // dnfMultiplier e' deprecato (B11): non viene piu' validato ne' applicato.
        checkRange(penaltyMultiplier, 0.0, 1.0, "Il moltiplicatore penalita'")
        checkRange(sprintPointsMultiplier, 0.0, MAX_MULTIPLIER, "Il moltiplicatore sprint")

        if (enabledTypes.contains(PredictionType.PODIUM) && podiumPoints.take(3).all { it == 0.0 }) {
            errors += "Il tipo Podio e' attivo ma tutti i punti del podio sono a 0"
        }

        return errors
    }

    private fun isDescending(values: List<Double>): Boolean =
        values.zipWithNext().all { (a, b) -> a >= b }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_POINTS = 200.0
        const val MAX_MULTIPLIER = 5.0

        const val PRESET_F1_OFFICIAL = "Preset F1 ufficiale"
        const val PRESET_ARCADE = "Preset Arcade"

        val DEFAULT_PODIUM_POINTS: List<Double> =
            listOf(25.0, 18.0, 15.0, 12.0, 10.0, 8.0, 6.0, 4.0, 2.0, 1.0)

        /** Punti di default, ispirati alla punteggiatura F1 (vittoria 25). */
        fun defaultPoints(): Map<PredictionType, Double> = mapOf(
            PredictionType.WINNER to 25.0,
            PredictionType.PODIUM to 15.0,
            PredictionType.POLE to 10.0,
            PredictionType.FASTEST_LAP to 8.0,
            PredictionType.DRIVER_EXACT_POSITION to 12.0,
            PredictionType.DRIVER_POSITION_RANGE to 6.0,
            PredictionType.HEAD_TO_HEAD to 8.0,
            PredictionType.DRIVER_DNF to 5.0,
            PredictionType.DRIVER_POINTS_OVER_UNDER to 7.0,
            PredictionType.SPRINT_WINNER to 10.0,
            PredictionType.SAFETY_CAR_COUNT_OVER_UNDER to 5.0,
            PredictionType.PIT_STOPS_OVER_UNDER to 5.0,
        )

        /**
         * Regole iniziali di una nuova lega: tutti i tipi attivi, punteggi F1.
         */
        fun default(): Rules = Rules()

        /** Preset "F1 ufficiale": identico ai default, usato dall'editor per ripristinare. */
        fun presetF1Official(): Rules = Rules(name = PRESET_F1_OFFICIAL)

        /**
         * Preset "Arcade": gara, sprint e testa a testa, moltiplicatore sprint pieno.
         * Utile per mostrare che i parametri sono davvero configurabili.
         */
        fun presetArcade(): Rules = Rules(
            name = PRESET_ARCADE,
            enabledTypes = setOf(
                PredictionType.WINNER,
                PredictionType.PODIUM,
                PredictionType.SPRINT_WINNER,
                PredictionType.HEAD_TO_HEAD,
                PredictionType.POLE,
            ),
            points = mapOf(
                PredictionType.WINNER to 10.0,
                PredictionType.PODIUM to 6.0,
                PredictionType.SPRINT_WINNER to 5.0,
                PredictionType.HEAD_TO_HEAD to 4.0,
                PredictionType.POLE to 4.0,
            ),
            podiumPoints = listOf(10.0, 8.0, 6.0),
            exactPositionNearMissFactor = 0.0,
            penaltyMultiplier = 1.0,
            sprintPointsMultiplier = 1.0,
            tieBreaker = TieBreaker.BEST_SINGLE_RACE,
        )
    }
}

/** Come si risolve una parità' in classifica. */
@kotlinx.serialization.Serializable
enum class TieBreaker {
    /** Punti totali (nessun spareggio oltre al totale). */
    TOTAL_POINTS,

    /** Numero di gare vinte (miglior punteggio nella gara). */
    RACE_WINS,

    /** Miglior punteggio singolo ottenuto in una gara. */
    BEST_SINGLE_RACE,
}

val TieBreaker.italianLabel: String
    get() = when (this) {
        TieBreaker.TOTAL_POINTS -> "Punti totali"
        TieBreaker.RACE_WINS -> "Numero di gare vinte"
        TieBreaker.BEST_SINGLE_RACE -> "Miglior punteggio in gara"
    }
