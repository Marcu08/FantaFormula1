package com.fantaformula1.app.core.domain

/**
 * Tipi di pronostico supportati dal motore di punteggio.
 *
 * Il nome dell'enum e' usato come chiave nel jsonb salvato su Supabase:
 * non va rinominato senza una migrazione dei dati.
 */
@kotlinx.serialization.Serializable
enum class PredictionType {
    /** Vincitore della gara (P1). */
    WINNER,

    /** Podio: i primi 3 piloti nell'ordine esatto. */
    PODIUM,

    /** Pole position. */
    POLE,

    /** Pilota col giro veloce. */
    FASTEST_LAP,

    /** Posizione esatta di un pilota in classifica. */
    DRIVER_EXACT_POSITION,

    /** Un pilota finisce in un intervallo di posizioni (es. 4º-6º). */
    DRIVER_POSITION_RANGE,

    /** Sfida a due: A termina meglio di B. */
    HEAD_TO_HEAD,

    /** Un pilota ritira (DNF) o meno. */
    DRIVER_DNF,

    /** Punti di un pilota over/under una soglia. */
    DRIVER_POINTS_OVER_UNDER,

    /** Vincitore dello sprint. */
    SPRINT_WINNER,

    /** Numero di safety car over/under. */
    SAFETY_CAR_COUNT_OVER_UNDER,

    /** Numero totale di pit stop over/under. */
    PIT_STOPS_OVER_UNDER,
}

/** Etichette brevi in italiano, usate dall'UI (fuori dal core per restare senza dipendenze Android). */
val PredictionType.italianLabel: String
    get() = when (this) {
        PredictionType.WINNER -> "Vincitore"
        PredictionType.PODIUM -> "Podio"
        PredictionType.POLE -> "Pole position"
        PredictionType.FASTEST_LAP -> "Giro veloce"
        PredictionType.DRIVER_EXACT_POSITION -> "Posizione esatta"
        PredictionType.DRIVER_POSITION_RANGE -> "Fascia di posizione"
        PredictionType.HEAD_TO_HEAD -> "Testa a testa"
        PredictionType.DRIVER_DNF -> "Ritiro (DNF)"
        PredictionType.DRIVER_POINTS_OVER_UNDER -> "Punti pilota over/under"
        PredictionType.SPRINT_WINNER -> "Vincitore sprint"
        PredictionType.SAFETY_CAR_COUNT_OVER_UNDER -> "Safety car over/under"
        PredictionType.PIT_STOPS_OVER_UNDER -> "Pit stop over/under"
    }

/** Descrizione una riga, utile nell'editor regole. */
val PredictionType.italianDescription: String
    get() = when (this) {
        PredictionType.WINNER -> "Indovina il vincitore della gara"
        PredictionType.PODIUM -> "Indovina i primi 3 nell'ordine esatto"
        PredictionType.POLE -> "Indovina chi conquista la pole position"
        PredictionType.FASTEST_LAP -> "Indovina chi fa il giro veloce"
        PredictionType.DRIVER_EXACT_POSITION -> "Indovina la posizione esatta di un pilota"
        PredictionType.DRIVER_POSITION_RANGE -> "Indovina in quale fascia finisce un pilota"
        PredictionType.HEAD_TO_HEAD -> "Scegli chi dei due piloti finisce meglio"
        PredictionType.DRIVER_DNF -> "Indovina se un pilota ritira"
        PredictionType.DRIVER_POINTS_OVER_UNDER -> "Scommetti sui punti di un pilota"
        PredictionType.SPRINT_WINNER -> "Indovina il vincitore dello sprint"
        PredictionType.SAFETY_CAR_COUNT_OVER_UNDER -> "Scommetti sul numero di safety car"
        PredictionType.PIT_STOPS_OVER_UNDER -> "Scommetti sul numero di pit stop"
    }

/** Tipi che dipendono dallo sprint (usati per applicare il moltiplicatore sprint). */
val PredictionType.isSprintRelated: Boolean
    get() = this == PredictionType.SPRINT_WINNER
