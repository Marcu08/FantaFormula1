package com.fantaformula1.app.core.domain

/**
 * Validazione di un pronostico compilato dal giocatore.
 * Regole: un solo pronostico per tipo attivo, numero corretto di piloti,
 * campi numerici nei range, nessun pilota ripetuto nello stesso pronostico,
 * tipi legati allo sprint solo su gare con sprint.
 */
object PredictionValidation {

    const val MAX_POSITION = 20

    /** Quanti piloti richiede ogni tipo. */
    fun requiredDrivers(type: PredictionType): Int = when (type) {
        PredictionType.WINNER,
        PredictionType.POLE,
        PredictionType.FASTEST_LAP,
        PredictionType.SPRINT_WINNER,
        PredictionType.DRIVER_EXACT_POSITION,
        PredictionType.DRIVER_POSITION_RANGE,
        PredictionType.DRIVER_DNF,
        PredictionType.DRIVER_POINTS_OVER_UNDER,
        -> 1
        PredictionType.HEAD_TO_HEAD -> 2
        PredictionType.PODIUM -> 3
        PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
        PredictionType.PIT_STOPS_OVER_UNDER,
        -> 0
    }

    /** Errori (in italiano) del singolo pronostico, rispetto alle regole della lega. */
    fun validate(
        prediction: RacePrediction,
        rules: Rules,
        isSprintRace: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val errors = mutableListOf<String>()

        val byType = prediction.entries.groupBy { it.type }
        byType.filterValues { it.size > 1 }.keys.forEach { type ->
            errors += "\"${type.italianLabel}\": solo un pronostico per tipo"
        }

        prediction.entries.forEach { entry ->
            errors += validateEntry(entry, isSprintRace)
        }

        rules.enabledTypes.forEach { type ->
            if (type == PredictionType.SPRINT_WINNER && !isSprintRace) return@forEach
            if (byType[type] == null) {
                errors += "Manca il pronostico \"${type.italianLabel}\""
            }
        }

        prediction.entries.forEach { entry ->
            if (!rules.enabledTypes.contains(entry.type)) {
                errors += "\"${entry.type.italianLabel}\" non è attivo per questa lega"
            }
        }

        val submittedAt = prediction.submittedAtEpochMs
        if (submittedAt != null && submittedAt > nowEpochMs) {
            errors += "Orario di invio non valido"
        }

        return errors.distinct()
    }

    private fun validateEntry(
        entry: PredictionEntry,
        isSprintRace: Boolean,
    ): List<String> {
        val errors = mutableListOf<String>()
        val label = entry.type.italianLabel

        if (entry.type == PredictionType.SPRINT_WINNER && !isSprintRace) {
            errors += "\"$label\": la gara non ha lo sprint"
            return errors
        }

        val needed = requiredDrivers(entry.type)
        if (entry.driverIds.size != needed) {
            errors += "\"$label\": servono $needed pilota/i"
        }
        if (entry.driverIds.any { it.isBlank() }) {
            errors += "\"$label\": pilota non valido"
        }
        if (entry.driverIds.toSet().size != entry.driverIds.size) {
            errors += "\"$label\": i piloti devono essere diversi tra loro"
        }

        when (entry.type) {
            PredictionType.DRIVER_EXACT_POSITION -> {
                val position = entry.position
                if (position == null || position < 1 || position > MAX_POSITION) {
                    errors += "\"$label\": posizione tra 1 e $MAX_POSITION"
                }
            }

            PredictionType.DRIVER_POSITION_RANGE -> {
                val min = entry.rangeMin
                val max = entry.rangeMax
                if (min == null || min < 1 || max == null || max > MAX_POSITION || min > max) {
                    errors += "\"$label\": fascia tra 1 e $MAX_POSITION e minore o uguale al massimo"
                }
            }

            PredictionType.DRIVER_DNF -> {
                if (entry.booleanValue == null) {
                    errors += "\"$label\": indica se il pilota ritira"
                }
            }

            PredictionType.DRIVER_POINTS_OVER_UNDER -> {
                val threshold = entry.threshold
                if (threshold == null || !threshold.isFinite() || threshold < 0) {
                    errors += "\"$label\": soglia di punti non valida"
                }
                if (entry.over == null) {
                    errors += "\"$label\": indica se sarà sopra o sotto la soglia"
                }
            }

            PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
            PredictionType.PIT_STOPS_OVER_UNDER,
            -> {
                val threshold = entry.threshold
                if (threshold == null || !threshold.isFinite() || threshold < 0) {
                    errors += "\"$label\": soglia non valida"
                }
                if (entry.over == null) {
                    errors += "\"$label\": indica se sarà sopra o sotto la soglia"
                }
            }

            else -> Unit
        }

        return errors
    }
}
