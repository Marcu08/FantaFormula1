package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U1: la spiegazione dei punti (explain) e il punteggio (score) derivano dalla
 * stessa funzione e quindi non possono divergere: il totale di uno pronostico
 * e' sempre la somma delle righe spiegate.
 */
class ScoringEngineExplainTest {

    private val rules = Rules.default().copy(
        enabledTypes = PredictionType.entries.toSet(),
    )

    private fun driver(
        id: String,
        position: Int? = null,
        grid: Int? = null,
        fastestLap: Boolean = false,
        dnf: Boolean = false,
        pointsScored: Double? = null,
        pitStops: Int = 0,
    ) = DriverRaceResult(
        driverId = id,
        driverName = id,
        grid = grid,
        position = position,
        fastestLap = fastestLap,
        dnf = dnf,
        pointsScored = pointsScored,
        pitStops = pitStops,
    )

    private fun sampleResult() = RaceResultData(
        raceId = "r1",
        drivers = listOf(
            driver("VER", position = 1, grid = 2, fastestLap = true, pointsScored = 25.0, pitStops = 2),
            driver("RUS", position = 2, grid = 1, pointsScored = 18.0, pitStops = 1),
            driver("LEC", position = 3, grid = 3, pointsScored = 15.0, pitStops = 2),
            driver("HAM", position = 4, grid = 4, pointsScored = 12.0, pitStops = 1),
        ),
        safetyCars = 2,
        redFlags = 0,
        laps = 58,
    )

    @Test
    fun `vincitore indovinato - spiegazione con nome e spunta`() {
        val entry = PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER"))
        val line = ScoringEngine.explain(entry, sampleResult(), rules)
        assertEquals(PredictionType.WINNER, line.type)
        assertEquals(25.0, line.points, 0.001)
        assertTrue(line.detail.contains("Max Verstappen"))
        assertTrue(line.detail.contains("✓"))
    }

    @Test
    fun `vincitore sbagliato - zero punti e crocetta`() {
        val entry = PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("LEC"))
        val line = ScoringEngine.explain(entry, sampleResult(), rules)
        assertEquals(0.0, line.points, 0.001)
        assertTrue(line.detail.contains("✗"))
        assertTrue(line.detail.contains("Verstappen"))
    }

    @Test
    fun `podio - dettaglio per posizione occupata`() {
        val entry = PredictionEntry(
            type = PredictionType.PODIUM,
            driverIds = listOf("VER", "RUS", "HAM"),
        )
        val line = ScoringEngine.explain(entry, sampleResult(), rules)
        assertTrue(line.detail.contains("Max Verstappen 1º ✓"))
        assertTrue(line.detail.contains("George Russell 2º ✓"))
        assertTrue(line.detail.contains("Lewis Hamilton"))
        assertTrue(line.detail.contains("✗"))
    }

    @Test
    fun `posizione esatta - mostra atteso ed effettivo`() {
        val entry = PredictionEntry(
            type = PredictionType.DRIVER_EXACT_POSITION,
            driverIds = listOf("HAM"),
            position = 4,
        )
        val line = ScoringEngine.explain(entry, sampleResult(), rules)
        assertTrue(line.detail.contains("4º"))
        assertTrue(line.detail.contains("atteso 4º"))
        assertTrue(line.detail.contains("✓"))
    }

    @Test
    fun `tipo disattivato - zero punti con etichetta`() {
        val limited = rules.copy(enabledTypes = setOf(PredictionType.WINNER))
        val entry = PredictionEntry(type = PredictionType.POLE, driverIds = listOf("VER"))
        val line = ScoringEngine.explain(entry, sampleResult(), limited)
        assertEquals(0.0, line.points, 0.001)
        assertTrue(line.detail.contains("disattivato"))
    }

    @Test
    fun `risultato senza dati - etichetta dati non disponibili`() {
        val empty = RaceResultData(raceId = "r0", drivers = emptyList())
        val entry = PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER"))
        val line = ScoringEngine.explain(entry, empty, rules)
        assertEquals(0.0, line.points, 0.001)
        assertTrue(line.detail.contains(ScoringEngine.NO_DATA_LABEL))
    }

    @Test
    fun `pilota non classificato - spiega il vero motivo e non i dati non disponibili`() {
        // PER ritirato (position null): lo score da' 0 DEFINITIVO, quindi
        // anche la spiegazione deve dire "non classificato": dire "dati
        // della gara non disponibili" faceva credere a un punteggio provvisorio.
        val withDnf = sampleResult().copy(
            drivers = sampleResult().drivers + driver("PER", dnf = true),
        )

        val exact = PredictionEntry(
            type = PredictionType.DRIVER_EXACT_POSITION,
            driverIds = listOf("PER"),
            position = 5,
        )
        val exactLine = ScoringEngine.explain(exact, withDnf, rules)
        assertEquals(0.0, exactLine.points, 0.001)
        assertTrue(exactLine.detail.contains("non classificato"))
        assertFalse(exactLine.detail.contains(ScoringEngine.NO_DATA_LABEL))

        val range = PredictionEntry(
            type = PredictionType.DRIVER_POSITION_RANGE,
            driverIds = listOf("PER"),
            rangeMin = 4,
            rangeMax = 6,
        )
        val rangeLine = ScoringEngine.explain(range, withDnf, rules)
        assertEquals(0.0, rangeLine.points, 0.001)
        assertTrue(rangeLine.detail.contains("non classificato"))
        assertFalse(rangeLine.detail.contains(ScoringEngine.NO_DATA_LABEL))

        val h2h = PredictionEntry(
            type = PredictionType.HEAD_TO_HEAD,
            driverIds = listOf("VER", "PER"),
        )
        val h2hLine = ScoringEngine.explain(h2h, withDnf, rules)
        assertEquals(0.0, h2hLine.points, 0.001)
        assertTrue(h2hLine.detail.contains("non classificato"))
        assertFalse(h2hLine.detail.contains(ScoringEngine.NO_DATA_LABEL))
    }

    @Test
    fun `somma delle righe spiegate coincide con score del pronostico`() {
        val prediction = RacePrediction(
            raceId = "r1",
            entries = listOf(
                PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER")),
                PredictionEntry(type = PredictionType.POLE, driverIds = listOf("RUS")),
                PredictionEntry(type = PredictionType.FASTEST_LAP, driverIds = listOf("LEC")),
                PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("HAM"), booleanValue = false),
            ),
        )
        val lines = ScoringEngine.explain(prediction, sampleResult(), rules)
        assertEquals(prediction.entries.size, lines.size)
        val total = lines.sumOf { it.points }
        assertEquals(ScoringEngine.score(prediction, sampleResult(), rules), total, 0.001)
    }

    @Test
    fun `over under - soglia e confronto nel dettaglio`() {
        val entry = PredictionEntry(
            type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
            rangeMin = 2,
            rangeMax = 3,
            threshold = 1.5,
            over = true,
        )
        val line = ScoringEngine.explain(entry, sampleResult(), rules)
        assertTrue(line.detail.contains("2"))
        assertTrue(line.points > 0.0)
        assertTrue(line.detail.contains("✓"))
    }
}
