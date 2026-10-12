package com.fantaformula1.app.core.domain

/**
 * Validazione del form risultati compilato dall'admin.
 * Regole: posizioni e griglie nel range 1..MAX_FIELD, posizioni gara uniche,
 * almeno un pilota classificato, sprint solo se la gara ha lo sprint.
 */
object RaceResultValidation {

    const val MAX_FIELD = 20

    /** Errori (in italiano) da mostrare nel form; lista vuota = form valido. */
    fun validate(
        result: RaceResultData,
        isSprintRace: Boolean = false,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (result.drivers.isEmpty()) {
            errors += "Aggiungi almeno un pilota"
            return errors
        }

        val driverIds = result.drivers.map { it.driverId }
        if (driverIds.any { it.isBlank() }) {
            errors += "Ogni pilota deve avere un identificativo"
        }
        if (driverIds.toSet().size != driverIds.size) {
            errors += "Ci sono piloti duplicati"
        }

        result.drivers.forEach { driver ->
            val label = driver.driverName.ifBlank { driver.driverId }
            val grid = driver.grid
            if (grid != null && (grid < 1 || grid > MAX_FIELD)) {
                errors += "Griglia di $label non valida (1-$MAX_FIELD)"
            }
            val position = driver.position
            if (position != null && (position < 1 || position > MAX_FIELD)) {
                errors += "Posizione di $label non valida (1-$MAX_FIELD)"
            }
            val sprintPosition = driver.sprintPosition
            if (sprintPosition != null) {
                if (!isSprintRace) {
                    errors += "Posizione sprint di $label presente ma la gara non ha sprint"
                } else if (sprintPosition < 1 || sprintPosition > MAX_FIELD) {
                    errors += "Posizione sprint di $label non valida (1-$MAX_FIELD)"
                }
            }
            if (driver.penaltyPoints < 0) {
                errors += "Penalità di $label non valide"
            }
            if (driver.pitStops < 0) {
                errors += "Pit stop di $label non validi"
            }
            if (driver.dnf && driver.position != null) {
                errors += "$label è DNF ma ha una posizione: rimuovi una delle due"
            }
            if (!driver.dnf && !driver.dnfReason.isNullOrBlank()) {
                errors += "$label ha un motivo DNF ma non risulta DNF"
            }
        }

        errors += duplicatePositionErrors(
            values = result.drivers.map { it.driverName.ifBlank { it.driverId } to it.grid },
            message = { label -> "Griglia di $label ripetuta" },
        )
        errors += duplicatePositionErrors(
            values = result.drivers.map { it.driverName.ifBlank { it.driverId } to it.position },
            message = { label -> "Posizione di $label ripetuta" },
        )
        if (isSprintRace) {
            errors += duplicatePositionErrors(
                values = result.drivers
                    .map { it.driverName.ifBlank { it.driverId } to it.sprintPosition },
                message = { label -> "Posizione sprint di $label ripetuta" },
            )
        }

        if (result.classifiedOrder().isEmpty()) {
            errors += "Classifica almeno un pilota (serve almeno una posizione)"
        }

        val fastestLaps = result.drivers.count { it.fastestLap }
        if (fastestLaps > 1) {
            errors += "Il giro veloce può essere assegnato a un solo pilota"
        }

        if (result.laps < 0) errors += "Numero di giri non valido"
        if (result.safetyCars < 0) errors += "Safety car non valide"
        if (result.redFlags < 0) errors += "Bandiere rosse non valide"

        return errors
    }

    private fun duplicatePositionErrors(
        values: List<Pair<String, Int?>>,
        message: (String) -> String,
    ): List<String> {
        val seen = mutableSetOf<Int>()
        val duplicatedLabels = mutableListOf<String>()
        values.sortedBy { it.second ?: Int.MAX_VALUE }.forEach { (label, value) ->
            if (value != null && !seen.add(value)) {
                duplicatedLabels += label
            }
        }
        return duplicatedLabels.map(message).distinct()
    }
}
