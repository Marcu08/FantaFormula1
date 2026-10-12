package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceResultValidationTest {

    private fun driver(
        id: String,
        grid: Int? = null,
        position: Int? = null,
        sprintPosition: Int? = null,
        fastestLap: Boolean = false,
        dnf: Boolean = false,
        dnfReason: String? = null,
        penaltyPoints: Int = 0,
        pitStops: Int = 0,
    ) = DriverRaceResult(
        driverId = id,
        driverName = id,
        grid = grid,
        position = position,
        sprintPosition = sprintPosition,
        fastestLap = fastestLap,
        dnf = dnf,
        dnfReason = dnfReason,
        penaltyPoints = penaltyPoints,
        pitStops = pitStops,
    )

    @Test
    fun `form valido senza errori`() {
        val result = RaceResultData(
            raceId = "r1",
            drivers = listOf(
                driver("A", grid = 1, position = 1, fastestLap = true),
                driver("B", grid = 2, position = 2),
                driver("C", grid = 3, position = null, dnf = true, dnfReason = "Motore"),
            ),
            laps = 58,
        )
        assertEquals(emptyList<String>(), RaceResultValidation.validate(result))
    }

    @Test
    fun `posizioni duplicate rilevate`() {
        val result = RaceResultData(
            drivers = listOf(
                driver("A", grid = 1, position = 1),
                driver("B", grid = 2, position = 1),
            ),
        )
        val errors = RaceResultValidation.validate(result)
        assertTrue(errors.any { it.contains("Posizione di B ripetuta") })
    }

    @Test
    fun `griglie duplicate rilevate`() {
        val result = RaceResultData(
            drivers = listOf(
                driver("A", grid = 5, position = 1),
                driver("B", grid = 5, position = 2),
            ),
        )
        val errors = RaceResultValidation.validate(result)
        assertTrue(errors.any { it.contains("Griglia di B ripetuta") })
    }

    @Test
    fun `posizione fuori range rilevata`() {
        val result = RaceResultData(
            drivers = listOf(
                driver("A", position = 25),
                driver("B", position = 2),
            ),
        )
        val errors = RaceResultValidation.validate(result)
        assertTrue(errors.any { it.contains("Posizione di A non valida") })

        val zero = RaceResultData(
            drivers = listOf(driver("A", position = 0), driver("B", position = 2)),
        )
        assertTrue(
            RaceResultValidation.validate(zero).any { it.contains("Posizione di A non valida") },
        )
    }

    @Test
    fun `pilota DNF con posizione e' un errore`() {
        val result = RaceResultData(
            drivers = listOf(
                driver("A", position = 1),
                driver("B", position = 2, dnf = true, dnfReason = "Motore"),
            ),
        )
        assertTrue(
            RaceResultValidation.validate(result).any { it.contains("B è DNF ma ha una posizione") },
        )
    }

    @Test
    fun `motivo DNF senza DNF e' un errore`() {
        val result = RaceResultData(
            drivers = listOf(
                driver("A", position = 1),
                driver("B", position = 2, dnfReason = "Motore"),
            ),
        )
        assertTrue(
            RaceResultValidation.validate(result).any { it.contains("motivo DNF") },
        )
    }

    @Test
    fun `piu' di un giro veloce e' un errore`() {
        val result = RaceResultData(
            drivers = listOf(
                driver("A", position = 1, fastestLap = true),
                driver("B", position = 2, fastestLap = true),
            ),
        )
        assertTrue(
            RaceResultValidation.validate(result).any { it.contains("giro veloce") },
        )
    }

    @Test
    fun `posizione sprint su gara senza sprint e' un errore`() {
        val result = RaceResultData(
            drivers = listOf(driver("A", position = 1, sprintPosition = 1)),
        )
        val errors = RaceResultValidation.validate(result, isSprintRace = false)
        assertTrue(errors.any { it.contains("la gara non ha sprint") })

        val sprintErrors = RaceResultValidation.validate(
            RaceResultData(
                drivers = listOf(driver("A", position = 1, sprintPosition = 1)),
            ),
            isSprintRace = true,
        )
        assertEquals(emptyList<String>(), sprintErrors)
    }

    @Test
    fun `almeno un pilota classificato e' obbligatorio`() {
        val result = RaceResultData(
            drivers = listOf(
                driver("A", dnf = true, dnfReason = "Motore"),
                driver("B", dnf = true, dnfReason = "Sospensioni"),
            ),
        )
        assertTrue(
            RaceResultValidation.validate(result).any { it.contains("almeno una posizione") },
        )
    }

    @Test
    fun `piloti duplicati e identificativo mancante rilevati`() {
        val result = RaceResultData(
            drivers = listOf(driver("A", position = 1), driver("A", position = 2)),
        )
        assertTrue(RaceResultValidation.validate(result).any { it.contains("duplicati") })

        val blank = RaceResultData(drivers = listOf(driver(" ", position = 1)))
        assertTrue(
            RaceResultValidation.validate(blank).any { it.contains("identificativo") },
        )
    }

    @Test
    fun `range di giri safety car e bandiere rosse non negativi`() {
        val result = RaceResultData(
            drivers = listOf(driver("A", position = 1)),
            laps = -1,
            safetyCars = -2,
            redFlags = -3,
        )
        val errors = RaceResultValidation.validate(result)
        assertTrue(errors.any { it.contains("giri") })
        assertTrue(errors.any { it.contains("Safety car") })
        assertTrue(errors.any { it.contains("Bandiere rosse") })
    }

    @Test
    fun `nessun driver significa errore immediato`() {
        val errors = RaceResultValidation.validate(RaceResultData())
        assertEquals(listOf("Aggiungi almeno un pilota"), errors)
    }
}
