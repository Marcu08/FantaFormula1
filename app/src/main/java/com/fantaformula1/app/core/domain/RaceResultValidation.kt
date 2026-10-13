package com.fantaformula1.app.core.domain

/**
 * Validazione del form risultati compilato dall'admin.
 * Regole: posizioni e griglie nel range 1..MAX_DRIVERS, posizioni gara uniche
 * e contigue da 1 (serve il podio e non ci possono essere buchi), sprint solo
 * se la gara ha lo sprint, almeno un pilota classificato.
 */
object RaceResultValidation {

    /** Range massimo dei campi numerici = numero piloti in griglia. */
    const val MAX_FIELD = DriversCatalog.MAX_DRIVERS

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

        // Le posizioni classificate devono essere contigue da 1: se c'e' la
        // posizione 3 deve esserci anche la 2, e non si puo' vincere senza il
        // primo posto. Copre anche il caso "manca il P1".
        val classified = result.drivers.mapNotNull { it.position }.sorted()
        if (classified.isNotEmpty() && classified != (1..classified.size).toList()) {
            errors += "Le posizioni devono essere contigue da 1, senza buchi e con il primo posto"
        }
        // Stessa regola per lo sprint: 1..n contiguo.
        if (isSprintRace) {
            val sprint = result.drivers.mapNotNull { it.sprintPosition }.sorted()
            if (sprint.isNotEmpty() && sprint != (1..sprint.size).toList()) {
                errors += "Le posizioni sprint devono essere contigue da 1, senza buchi"
            }
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
