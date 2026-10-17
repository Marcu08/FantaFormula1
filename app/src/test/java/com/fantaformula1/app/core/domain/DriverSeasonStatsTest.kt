package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test delle statistiche reali di stagione (blocco 0009): shrinkage K=3,
 * soglia = mediana empirica (0.5 = "va a punti?"), fasce testa a testa dai
 * dati reali, tasso di ritiro clampato, piloti assenti gestiti senza eccezioni.
 */
class DriverSeasonStatsTest {

    /** RaceResultData compatta per i test: (pilota, punti gara, ritirato). */
    private fun raceResult(
        raceId: String,
        vararg entries: Triple<String, Double, Boolean>,
    ): RaceResultData = RaceResultData(
        raceId = raceId,
        drivers = entries.map { (id, points, dnf) ->
            DriverRaceResult(
                driverId = id,
                position = if (dnf) null else 5,
                dnf = dnf,
                pointsScored = points,
            )
        },
    )

    @Test
    fun `zero gare restituisce esattamente il prior`() {
        val stats = DriverSeasonStats.fromResults(emptyList())
        assertEquals(0, stats.racesRun("VER"))
        assertEquals(DriverPointsEstimates.forDriver("VER"), stats.meanPointsShrunk("VER"), 0.0001)
        assertEquals(0.10, stats.dnfRate("VER"), 0.0001)
        // Prior basso (<4): la soglia e' 0.5, NON la media.
        assertEquals(0.5, stats.thresholdFor("BOT"), 0.0001) // prior 2.0
        // Prior alto: soglia = prior (x.5).
        assertEquals(14.0, stats.thresholdFor("VER"), 0.0001) // prior 14.0
        assertFalse(stats.isEmpirical("VER"))
    }

    @Test
    fun `shrinkage K=3 verso il prior`() {
        // Piloti con prior 2.0 (BOT): una gara da 25 punti.
        val stats = DriverSeasonStats.fromResults(
            listOf(raceResult("r1", Triple("BOT", 25.0, false))),
        )
        // (25*1 + 3*2.0) / (1 + 3) = 31/4 = 7.75 (NON 25!).
        assertEquals(7.75, stats.meanPointsShrunk("BOT"), 0.0001)
        assertEquals(listOf(25.0), stats.pointsList("BOT"))
    }

    @Test
    fun `con piu' gare lo shrink pesa meno`() {
        val stats = DriverSeasonStats.fromResults(
            listOf(
                raceResult("r1", Triple("BOT", 0.0, false)),
                raceResult("r2", Triple("BOT", 0.0, false)),
                raceResult("r3", Triple("BOT", 16.0, false)),
            ),
        )
        // (16 + 3*2)/(3 + 3) = 22/6.
        assertEquals(22.0 / 6.0, stats.meanPointsShrunk("BOT"), 0.0001)
    }

    @Test
    fun `pilota che raramente va a punti ha soglia 0_5`() {
        val stats = DriverSeasonStats.fromResults(
            listOf(
                raceResult("r1", Triple("BOT", 0.0, false)),
                raceResult("r2", Triple("BOT", 0.0, false)),
                raceResult("r3", Triple("BOT", 0.0, false)),
                raceResult("r4", Triple("BOT", 25.0, false)),
            ),
        )
        assertEquals(4, stats.racesRun("BOT"))
        // t=0.5: over = 1 <= 4/2 -> la soglia piu' piccola sattisfattibile e' 0.5.
        assertEquals(0.5, stats.thresholdFor("BOT"), 0.0001)
        assertTrue(stats.isEmpirical("BOT"))
    }

    @Test
    fun `soglia empirica la piu piccola con frequenza over sopra la meta`() {
        val stats = DriverSeasonStats.fromResults(
            listOf(
                raceResult("r1", Triple("RUS", 10.0, false)),
                raceResult("r2", Triple("RUS", 12.0, false)),
                raceResult("r3", Triple("RUS", 14.0, false)),
                raceResult("r4", Triple("RUS", 0.0, false)),
            ),
        )
        // [10,12,14,0]: t=0.5 -> over=3 > 2, no; per t catene 1.0..9.5 -> over=3, no;
        // t=10.0 -> over = 2 (12,14; il 10 e' strettamente <=) <= 2, ok.
        assertEquals(10.0, stats.thresholdFor("RUS"), 0.0001)
    }

    @Test
    fun `soglia al tetto quando il pilota pianta sempre punti alti`() {
        val stats = DriverSeasonStats.fromResults(
            listOf(
                raceResult("r1", Triple("VER", 25.0, false)),
                raceResult("r2", Triple("VER", 25.0, false)),
                raceResult("r3", Triple("VER", 25.0, false)),
                raceResult("r4", Triple("VER", 25.0, false)),
            ),
        )
        // 25 > 25 e' falso: a t=25.0 over=0 <= 2 -> soglia 25.0.
        assertEquals(25.0, stats.thresholdFor("VER"), 0.0001)
    }

    @Test
    fun `dnfRate con smoothing su ritiri contati bene`() {
        val stats = DriverSeasonStats.fromResults(
            listOf(
                raceResult("r1", Triple("SED", 0.0, true)),
                raceResult("r2", Triple("SED", 0.0, true)),
                raceResult("r3", Triple("SED", 4.0, false)),
            ),
        )
        assertEquals(2, stats.dnfs("SED"))
        // (2 + 3*0.10)/(3 + 3) = 2.3/6.
        assertEquals(2.3 / 6.0, stats.dnfRate("SED"), 0.0001)
    }

    @Test
    fun `dnfRate clampata tra 0_03 e 0_50`() {
        val races = List(10) { i ->
            raceResult(
                "r$i",
                Triple("GIR", 10.0, false), // mai ritirato
                Triple("VIS", 0.0, true), // sempre ritirato
            )
        }
        val stats = DriverSeasonStats.fromResults(races)
        // GIR: (0 + 0.3)/13 = 0.023 -> clamp a 0.03.
        assertEquals(0.03, stats.dnfRate("GIR"), 0.0001)
        // VIS: (10 + 0.3)/13 = 0.79 -> clamp a 0.50.
        assertEquals(0.50, stats.dnfRate("VIS"), 0.0001)
        // Suff issetto della quota: il "no" (VIS) paga meno del "si".
        val (qSi, qNo) = OverUnderQuotes.dnfQuotas(stats.dnfRate("VIS"))
        assertTrue(qNo <= qSi)
    }

    @Test
    fun `fasce dai dati reali con pari media ordine per id e determinismo`() {
        // AAA e BBB in entrambe le gare da 20 punti: media shrinkata (40+6)/5=9.2,
        // sopra ANT (8.0) e HAD (7.0) -> risch indici 6 e 7 (tetto della fascia alta).
        // CCC 4+"4" punti: media (8+6)/5=2.8, dietro i 3.0 catalogo, in fascia bassa.
        val c1 = raceResult("r1", Triple("AAA", 20.0, false), Triple("BBB", 20.0, false), Triple("CCC", 4.0, false))
        val c2 = raceResult("r2", Triple("AAA", 20.0, false), Triple("BBB", 20.0, false), Triple("CCC", 4.0, false))
        val stats = DriverSeasonStats.fromResults(listOf(c1, c2))
        // AAA e BBB hanno identica media shrinkata: AAA prima di BBB (id).
        assertEquals("AAA tier", 0, stats.tierOf("AAA"))
        assertEquals("BBB tier", 1, stats.tierOf("BBB"))
        assertEquals("CCC tier", 2, stats.tierOf("CCC"))
        // Ricostituendo le stesse statistiche: le fasce non cambiano.
        val stats2 = DriverSeasonStats.fromResults(listOf(c1, c2))
        assertEquals(stats.tierOf("AAA"), stats2.tierOf("AAA"))
        assertEquals(stats.tierOf("BBB"), stats2.tierOf("BBB"))
        assertEquals(stats.tierOf("CCC"), stats2.tierOf("CCC"))
    }

    @Test
    fun `sprint non conteggiato due volte`() {
        // Lo sprint vive come sprintPosition nella STESSA RaceResultData: la
        // statistica conta LA GARA una volta sola (punti gara).
        val result = RaceResultData(
            raceId = "r1",
            drivers = listOf(
                DriverRaceResult(
                    driverId = "VER",
                    position = 1,
                    sprintPosition = 3,
                    pointsScored = 25.0,
                ),
            ),
        )
        val stats = DriverSeasonStats.fromResults(listOf(result))
        assertEquals(1, stats.racesRun("VER"))
        assertEquals(listOf(25.0), stats.pointsList("VER"))
    }

    @Test
    fun `pilota sostituto fuori catalogo senza eccezioni`() {
        val stats = DriverSeasonStats.fromResults(
            listOf(
                raceResult("r1", Triple("FAKE_SUB", 6.0, false)),
                raceResult("r2", Triple("FAKE_SUB", 0.0, true)),
            ),
        )
        assertEquals(2, stats.racesRun("FAKE_SUB"))
        assertEquals(1, stats.dnfs("FAKE_SUB"))
        // n=2 (< 3): soglia = prior di default (2.0 < 4) -> 0.5.
        assertEquals(0.5, stats.thresholdFor("FAKE_SUB"), 0.0001)
        // [6, 0]: mean shrink = (6 + 3*2)/5 = 12/5.
        assertEquals(12.0 / 5.0, stats.meanPointsShrunk("FAKE_SUB"), 0.0001)
        assertFalse(stats.isEmpirical("FAKE_SUB"))
    }

    @Test
    fun `pilota assente da alcune gare non inventa gare`() {
        val stats = DriverSeasonStats.fromResults(
            listOf(
                raceResult("r1", Triple("VER", 25.0, false), Triple("BOR", 0.0, false)),
                raceResult("r2", Triple("VER", 18.0, false)), // BOR assente: spesso nelle sfide
            ),
        )
        assertEquals(2, stats.racesRun("VER"))
        assertEquals(1, stats.racesRun("BOR"))
        assertEquals(listOf(0.0), stats.pointsList("BOR"))
        assertEquals(listOf(25.0, 18.0), stats.pointsList("VER"))
    }

    // ---------------------------------------------------------------
    // Unione lega + stagione reale con dedup per round (0010+)
    // ---------------------------------------------------------------

    private fun stamped(round: Int?, result: RaceResultData) =
        DriverSeasonStats.StampedResult(round = round, result = result)

    @Test
    fun `stesso round lega e stagione reale conta una volta e vince la lega`() {
        val leagueVersion = raceResult("lega", Triple("VER", 25.0, false), Triple("BOR", 0.0, false))
        val realVersion = raceResult("reale", Triple("VER", 18.0, false), Triple("BOR", 12.0, false))
        val stats = DriverSeasonStats.fromMergedResults(
            league = listOf(stamped(4, leagueVersion)),
            realSeason = listOf(stamped(4, realVersion)),
        )
        // Stessa gara = stesso round: il round 4 conta UNA volta con i punti
        // della versione lega (l'admin e' autorita' sul suo calendario).
        assertEquals(1, stats.racesRun("VER"))
        assertEquals(listOf(25.0), stats.pointsList("VER"))
        assertEquals(listOf(0.0), stats.pointsList("BOR"))
    }

    @Test
    fun `round solo nella stagione reale sono contati (lega a meta stagione)`() {
        val realR1 = raceResult("s1", Triple("VER", 25.0, false))
        val realR2 = raceResult("s2", Triple("VER", 18.0, false))
        val realR3 = raceResult("s3", Triple("VER", 15.0, false))
        val leagueR4 = raceResult("l4", Triple("VER", 12.0, false))
        val stats = DriverSeasonStats.fromMergedResults(
            league = listOf(stamped(4, leagueR4)),
            realSeason = listOf(stamped(1, realR1), stamped(2, realR2), stamped(3, realR3)),
        )
        assertEquals(4, stats.racesRun("VER"))
        assertEquals(listOf(25.0, 18.0, 15.0, 12.0), stats.pointsList("VER"))
    }

    @Test
    fun `duplicati dentro la cache della stagione contano una volta sola`() {
        val realR2 = raceResult("s2a", Triple("VER", 18.0, false))
        val realR2b = raceResult("s2b", Triple("VER", 25.0, false)) // stessa gara, cache doppia
        val stats = DriverSeasonStats.fromMergedResults(
            league = emptyList(),
            realSeason = listOf(stamped(2, realR2), stamped(2, realR2b)),
        )
        assertEquals(1, stats.racesRun("VER"))
        // La prima in ordine di arrivo vince.
        assertEquals(listOf(18.0), stats.pointsList("VER"))
    }

    @Test
    fun `gare lega senza round entrano comunque nelle statistiche`() {
        val special = raceResult("spec", Triple("VER", 10.0, false))
        val realR1 = raceResult("s1", Triple("VER", 25.0, false))
        val stats = DriverSeasonStats.fromMergedResults(
            league = listOf(stamped(null, special)),
            realSeason = listOf(stamped(1, realR1)),
        )
        assertEquals(2, stats.racesRun("VER"))
        assertEquals(listOf(25.0, 10.0), stats.pointsList("VER"))
    }

    @Test
    fun ` stagione reale senza lega con sostituti gestiti`() {
        val realR1 = raceResult("s1", Triple("SOST", 6.0, false))
        val realR2 = raceResult("s2", Triple("SOST", 0.0, true))
        val stats = DriverSeasonStats.fromMergedResults(
            league = emptyList(),
            realSeason = listOf(stamped(1, realR1), stamped(2, realR2)),
        )
        assertEquals(2, stats.racesRun("SOST"))
        assertEquals(1, stats.dnfs("SOST"))
    }
}
