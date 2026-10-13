package com.fantaformula1.app.core.domain

import com.fantaformula1.app.data.util.AppJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test di validazione delle regole e di serializzazione jsonb (round-trip). */
class RulesAndSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- Validazione ----------------------------------------------------------

    @Test
    fun `le regole di default sono valide`() {
        assertEquals(emptyList<String>(), Rules.default().validate())
    }

    @Test
    fun `i preset sono validi`() {
        assertEquals(emptyList<String>(), Rules.presetF1Official().validate())
        assertEquals(emptyList<String>(), Rules.presetArcade().validate())
    }

    @Test
    fun `nome troppo corto produce errore`() {
        val errors = Rules.default().copy(name = "A").validate()
        assertTrue(errors.any { it.contains("nome", ignoreCase = true) })
    }

    @Test
    fun `nessun tipo attivo produce errore`() {
        val errors = Rules.default().copy(enabledTypes = emptySet()).validate()
        assertTrue(errors.any { it.contains("almeno un tipo", ignoreCase = true) })
    }

    @Test
    fun `punti negativi producono errore`() {
        val broken = Rules.default().copy(
            points = Rules.default().points + (PredictionType.WINNER to -5.0),
        )
        assertTrue(broken.validate().any { it.contains("Punti non validi", ignoreCase = true) })
    }

    @Test
    fun `punti oltre il massimo producono errore`() {
        val broken = Rules.default().copy(
            points = Rules.default().points + (PredictionType.WINNER to 999.0),
        )
        assertTrue(broken.validate().isNotEmpty())
    }

    @Test
    fun `moltiplicatore oltre il massimo produce errore`() {
        val broken = Rules.default().copy(sprintPointsMultiplier = 99.0)
        assertTrue(broken.validate().any { it.contains("sprint", ignoreCase = true) })
    }

    @Test
    fun `podio non decrescente produce errore`() {
        val broken = Rules.default().copy(podiumPoints = listOf(10.0, 20.0, 5.0))
        assertTrue(broken.validate().any { it.contains("decrescenti", ignoreCase = true) })
    }

    @Test
    fun `podio con meno di tre valori produce errore`() {
        val broken = Rules.default().copy(podiumPoints = listOf(25.0, 18.0))
        assertTrue(broken.validate().isNotEmpty())
    }

    // -- Serializzazione ------------------------------------------------------

    @Test
    fun `regole round-trip through json preserva i valori`() {
        val original = Rules.presetArcade()
        val encoded = json.encodeToString(Rules.serializer(), original)
        val decoded = json.decodeFromString(Rules.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `json vuoto su lega creata senza regole produce i default`() {
        // Le leghe possono essere create con rules = '{}'::jsonb: il client
        // deve degradare sui default invece di lanciare eccezione.
        val fromEmpty = json.decodeFromString(Rules.serializer(), "{}")
        assertEquals(Rules.default().copy(name = fromEmpty.name), fromEmpty)
        assertEquals(Rules.default().points, fromEmpty.points)
        assertEquals(PredictionType.entries.toSet(), fromEmpty.enabledTypes)
        assertEquals(emptyList<String>(), fromEmpty.validate())
    }

    @Test
    fun `json con campi ignoti non rompe il parsing`() {
        val raw = """{"version":1,"name":"Legacy","campoSconosciuto":true}"""
        val decoded = json.decodeFromString(Rules.serializer(), raw)
        assertEquals("Legacy", decoded.name)
    }

    @Test
    fun `json con enum sconosciuti degrada sui default (B22)`() {
        // AppJson usa coerceInputValues: un enum sconosciuto (es. aggiunto in
        // una versione nuova del server) cade sul default invece di far
        // fallire l'intera decodifica della lega.
        val raw = """
            {"version":1,"name":"Legacy","tieBreaker":"SPRINT_OFF",
             "points":{"WINNER":12.0}}
        """.trimIndent()
        val decoded = AppJson.decodeFromString(Rules.serializer(), raw)
        assertEquals("Legacy", decoded.name)
        assertEquals(TieBreaker.RACE_WINS, decoded.tieBreaker)
        assertEquals(12.0, decoded.points[PredictionType.WINNER]!!, 0.001)
    }

    @Test
    fun `dnfMultiplier deprecato resta nel json per compatibilita' (B11)`() {
        val encoded = AppJson.encodeToString(Rules.serializer(), Rules.default())
        assertTrue(encoded.contains("dnfMultiplier"))
        val decoded = AppJson.decodeFromString(Rules.serializer(), encoded)
        assertEquals(Rules.default(), decoded)
    }

    // -- B3: punti effettivi --------------------------------------------------

    @Test
    fun `effectivePoints usa pointsScored se presente (B3)`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "VER", position = 1, pointsScored = 30.0),
            ),
        )
        assertEquals(30.0, result.effectivePoints(result.drivers.first()), 0.001)
    }

    @Test
    fun `effectivePoints senza pointsScored usa la tabella F1 (B3)`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "VER", position = 1),
                DriverRaceResult(driverId = "LEC", position = 2),
                DriverRaceResult(driverId = "NOR", position = 10),
                DriverRaceResult(driverId = "ALO", position = 11),
                DriverRaceResult(driverId = "PER", position = null, dnf = true),
            ),
        )
        assertEquals(25.0, result.effectivePoints(result.drivers[0]), 0.001)
        assertEquals(18.0, result.effectivePoints(result.drivers[1]), 0.001)
        assertEquals(1.0, result.effectivePoints(result.drivers[2]), 0.001)
        assertEquals(0.0, result.effectivePoints(result.drivers[3]), 0.001)
        assertEquals(0.0, result.effectivePoints(result.drivers[4]), 0.001)
    }

    @Test
    fun `risultato gara round-trip through json`() {
        val original = RaceResultData(
            raceId = "r1",
            drivers = listOf(
                DriverRaceResult(
                    driverId = "VER",
                    driverName = "Max Verstappen",
                    grid = 1,
                    position = 1,
                    sprintPosition = 1,
                    fastestLap = true,
                    dnf = false,
                    dnfReason = null,
                    penaltyPoints = 5,
                    pitStops = 2,
                    pointsScored = 26.0,
                ),
                DriverRaceResult(driverId = "PER", dnf = true, dnfReason = "Cambio del motore"),
            ),
            safetyCars = 2,
            redFlags = 1,
            laps = 58,
            weather = "Asciutto",
            notes = "Gara sotto pioggia nel primo stint",
        )
        val encoded = json.encodeToString(RaceResultData.serializer(), original)
        val decoded = json.decodeFromString(RaceResultData.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(2, decoded.totalPitStops)
    }

    @Test
    fun `pronostico round-trip through json`() {
        val original = RacePrediction(
            raceId = "r1",
            submittedAtEpochMs = 1_700_000_000_000L,
            entries = listOf(
                PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER")),
                PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("VER", "RUS", "LEC")),
                PredictionEntry(
                    type = PredictionType.DRIVER_POSITION_RANGE,
                    driverIds = listOf("HAM"),
                    rangeMin = 3,
                    rangeMax = 5,
                ),
                PredictionEntry(
                    type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                    threshold = 1.5,
                    over = true,
                ),
                PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"), booleanValue = true),
            ),
        )
        val encoded = json.encodeToString(RacePrediction.serializer(), original)
        val decoded = json.decodeFromString(RacePrediction.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // -- Utility dominio ------------------------------------------------------

    @Test
    fun `classificationRank mette i ritirati dopo i classificati`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "A", position = 1, grid = 2),
                DriverRaceResult(driverId = "B", position = null, grid = 1, dnf = true),
                DriverRaceResult(driverId = "C", position = 2, grid = 3),
            ),
        )
        assertEquals(1, result.classificationRank("A"))
        assertEquals(2, result.classificationRank("C"))
        assertEquals(3, result.classificationRank("B"))
    }

    @Test
    fun `fastestLapDriver e poleDriver restituiscono il pilota giusto`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "A", grid = 2, fastestLap = true),
                DriverRaceResult(driverId = "B", grid = 1),
            ),
        )
        assertEquals("A", result.fastestLapDriver()?.driverId)
        assertEquals("B", result.poleDriver()?.driverId)
    }

    @Test
    fun `enableTypes e points coerenti nel preset arcade`() {
        val arcade = Rules.presetArcade()
        arcade.enabledTypes.forEach {
            assertTrue("manca il valore per $it", arcade.points.containsKey(it))
        }
        assertFalse(arcade.points.keys.any { it !in arcade.enabledTypes })
    }
}
