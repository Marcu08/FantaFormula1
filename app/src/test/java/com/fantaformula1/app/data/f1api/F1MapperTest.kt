package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.data.f1api.model.F1Circuit
import com.fantaformula1.app.data.f1api.model.F1Driver
import com.fantaformula1.app.data.f1api.model.F1FastestLap
import com.fantaformula1.app.data.f1api.model.F1Location
import com.fantaformula1.app.data.f1api.model.F1PitStop
import com.fantaformula1.app.data.f1api.model.F1QualifyingResult
import com.fantaformula1.app.data.f1api.model.F1Race
import com.fantaformula1.app.data.f1api.model.F1Result
import com.fantaformula1.app.data.f1api.model.F1Session
import com.fantaformula1.app.data.f1api.model.F1SprintResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gli id API usati nei fixture sono quelli REALI di Jolpica (verificati su
 * `f1/2026/drivers.json`): cognome ("norris", "hamilton", "sainz"), con le
 * eccezioni nome+cognome ("max_verstappen", "arvid_lindblad"). I test con
 * id inventati stile "lando_norris" non avrebbero scoperto il bug N1.
 */
class F1MapperTest {

    private fun mapOk(
        results: List<F1Result>,
        qualifying: List<F1QualifyingResult> = emptyList(),
        sprint: List<F1SprintResult> = emptyList(),
    ) = (F1Mapper.toRaceResultData(results, qualifying, sprint) as RaceMapping.Success).data

    @Test
    fun `toRaceCreate estrae round nome e deadline qualifica`() {
        val race = F1Race(
            season = "2026",
            round = "5",
            raceName = "Italian Grand Prix",
            circuit = F1Circuit(
                circuitId = "monza",
                circuitName = "Autodromo Nazionale Monza",
                location = F1Location(country = "Italy"),
            ),
            date = "2026-09-06",
            time = "14:00:00Z",
            qualifying = F1Session("2026-09-05", "15:00:00Z"),
        )

        val create = F1Mapper.toRaceCreate(race)

        assertEquals(5, create.round)
        assertEquals("Italian Grand Prix", create.name)
        assertEquals("Italy", create.country)
        assertFalse(create.isSprint)
        assertEquals("2026-09-05T15:00:00Z", create.deadline.toString())
    }

    @Test
    fun `toRaceCreate riconosce gara sprint`() {
        val race = F1Race(
            season = "2026",
            round = "3",
            raceName = "Sprint Race",
            circuit = null,
            date = "2026-03-22",
            time = "14:00:00Z",
            sprint = F1Session("2026-03-21", "12:00:00Z"),
        )

        assertTrue(F1Mapper.hasSprint(race))
    }

    @Test
    fun `toRaceCreate weekend sprint usa la sprint qualifying come deadline`() {
        // N4: la sprint qualifying (venerdi) e' PRIMA della qualifica: la
        // deadline deve chiudere prima che si sappia l'esito dello sprint.
        val race = F1Race(
            season = "2026",
            round = "3",
            raceName = "Sprint Weekend",
            circuit = null,
            date = "2026-03-22",
            time = "14:00:00Z",
            sprintQualifying = F1Session("2026-03-20", "16:30:00Z"),
            sprint = F1Session("2026-03-21", "12:00:00Z"),
            qualifying = F1Session("2026-03-21", "16:00:00Z"),
        )

        val create = F1Mapper.toRaceCreate(race)

        assertTrue(create.isSprint)
        assertEquals("2026-03-20T16:30:00Z", create.deadline.toString())
    }

    @Test
    fun `toRaceCreate weekend sprint senza sprint qualifying usa lo sprint`() {
        val race = F1Race(
            season = "2026",
            round = "4",
            raceName = "Sprint Weekend 2",
            circuit = null,
            date = "2026-04-05",
            time = "14:00:00Z",
            sprint = F1Session("2026-04-04", "12:00:00Z"),
            qualifying = F1Session("2026-04-04", "16:00:00Z"),
        )

        assertEquals("2026-04-04T12:00:00Z", F1Mapper.toRaceCreate(race).deadline.toString())
    }

    @Test
    fun `toRaceCreate weekend normale usa la qualifica`() {
        val race = F1Race(
            season = "2026",
            round = "5",
            raceName = "Normal Weekend",
            circuit = null,
            date = "2026-09-06",
            time = "14:00:00Z",
            qualifying = F1Session("2026-09-05", "15:00:00Z"),
        )

        assertFalse(race.let(F1Mapper::hasSprint))
        assertEquals("2026-09-05T15:00:00Z", F1Mapper.toRaceCreate(race).deadline.toString())
    }

    @Test
    fun `toRaceResultData mappa vincitore e giro veloce con id reali`() {
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", points = "25", grid = "1", fastestLapRank = "1"),
            result("norris", code = "NOR", position = "2", points = "18", grid = "3"),
        )

        val data = mapOk(results)

        assertEquals(2, data.drivers.size)
        val winner = data.drivers.first { it.driverId == "VER" }
        assertEquals(1, winner.position)
        assertTrue(winner.fastestLap)
        assertEquals(25.0, winner.pointsScored)

        val second = data.drivers.first { it.driverId == "NOR" }
        assertEquals(2, second.position)
        assertFalse(second.fastestLap)
    }

    @Test
    fun `toRaceResultData segna DNF per status non classificato`() {
        val results = listOf(
            result(
                "gasly",
                code = "GAS",
                position = "18",
                positionText = null,
                points = "0",
                status = "Engine",
            ),
        )

        val data = mapOk(results)

        val driver = data.drivers.first { it.driverId == "GAS" }
        assertTrue(driver.dnf)
        assertNull(driver.position)
        assertEquals("Engine", driver.dnfReason)
    }

    @Test
    fun `toRaceResultData include posizioni sprint`() {
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", status = "Finished"),
            result("norris", code = "NOR", position = "2", status = "Finished"),
        )
        // Ordine sprint invertito rispetto alla gara: lo sprint inverte P1 e P2.
        val sprint = listOf(
            F1SprintResult(position = "1", driver = F1Driver(driverId = "norris", code = "NOR")),
            F1SprintResult(position = "2", driver = F1Driver(driverId = "max_verstappen", code = "VER")),
        )

        val data = mapOk(results, sprint = sprint)

        assertEquals(2, data.driver("VER")?.sprintPosition)
        assertEquals(1, data.driver("NOR")?.sprintPosition)
    }

    @Test
    fun `toRaceResultData usa qualifica per griglia`() {
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", status = "Finished", grid = "2"),
        )
        val qualifying = listOf(
            F1QualifyingResult(
                position = "1",
                driver = F1Driver(driverId = "max_verstappen", code = "VER"),
            ),
        )

        val data = mapOk(results, qualifying)

        assertEquals(1, data.drivers.first().grid)
    }

    @Test
    fun `pilota sconosciuto blocca il risultato intero`() {
        // N2: una riserva fuori catalogo non deve spostare le posizioni di
        // tutti i piloti dietro di lei: il risultato non viene mappato.
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1"),
            result("michael_schumacher", code = null, position = "2"),
            result("norris", code = "NOR", position = "3"),
        )

        val mapping = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        assertTrue(mapping is RaceMapping.UnknownDriver)
        assertEquals("michael_schumacher", (mapping as RaceMapping.UnknownDriver).driverId)
    }

    @Test
    fun `pilota sconosciuto anche solo in qualifica blocca il risultato`() {
        val results = listOf(result("norris", code = "NOR", position = "1"))
        val qualifying = listOf(
            F1QualifyingResult(
                position = "1",
                driver = F1Driver(driverId = "reserve_x", code = null),
            ),
        )

        val mapping = F1Mapper.toRaceResultData(results, qualifying, emptyList())

        assertTrue(mapping is RaceMapping.UnknownDriver)
    }

    @Test
    fun `toRaceResultData usa positionText per classificare`() {
        // Ergast: un pilota classificato ha positionText numerico anche con
        // stato "Accident"; un ritirato ha positionText "R".
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", positionText = "1", status = "Accident"),
            result("gasly", code = "GAS", position = "2", positionText = "R", status = "Collision"),
        )

        val data = mapOk(results)

        assertEquals(1, data.positionOf("VER"))
        val gasly = data.drivers.first { it.driverId == "GAS" }
        assertTrue(gasly.dnf)
        assertNull(gasly.position)
        assertEquals("Collision", gasly.dnfReason)
    }

    @Test
    fun `toRaceResultData tratta la squalifica come non classificato`() {
        // Ergast: positionText "D" = disqualified. Il pilota non ha posizione:
        // per il motore di punteggio si comporta come un DNF.
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", positionText = "1"),
            result("gasly", code = "GAS", position = "2", positionText = "D", status = "Disqualified"),
        )

        val data = mapOk(results)

        assertEquals(1, data.positionOf("VER"))
        val gasly = data.drivers.first { it.driverId == "GAS" }
        assertTrue(gasly.dnf)
        assertNull(gasly.position)
        assertEquals("Disqualified", gasly.dnfReason)
        assertEquals(emptyList<String>(), RaceResultValidation.validate(data))
    }

    @Test
    fun `toRaceResultData non mescola griglia qualifica e gara`() {
        // Con la qualifica presente la griglia viene solo da li': chi non ha
        // girato in qualifica (es. partito dai box) non eredita la griglia di
        // gara, per non creare duplicati.
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", grid = "1"),
            result("norris", code = "NOR", position = "2", grid = "2"),
        )
        val qualifying = listOf(
            F1QualifyingResult(
                position = "1",
                driver = F1Driver(driverId = "max_verstappen", code = "VER"),
            ),
        )

        val data = mapOk(results, qualifying)

        assertEquals(1, data.driver("VER")?.grid)
        assertNull(data.driver("NOR")?.grid)
    }

    @Test
    fun `toRaceResultData scarta griglia zero partenza dai box`() {
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", grid = "0"),
        )

        val data = mapOk(results)

        assertNull(data.driver("VER")?.grid)
    }

    @Test
    fun `risultato mappato passa la validazione del form`() {
        // Invariante chiave: l'esito costruito dall'API deve essere accettato
        // dallo stesso validatore usato dall'admin, altrimenti la gara viene
        // scartata in sync.
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1", positionText = "1", points = "25", grid = "1"),
            result("norris", code = "NOR", position = "2", positionText = "2", points = "18", grid = "3"),
            result("sainz", code = "SAI", position = "3", positionText = "R", status = "Engine"),
        )
        val qualifying = listOf(
            F1QualifyingResult(position = "1", driver = F1Driver(driverId = "max_verstappen", code = "VER")),
            F1QualifyingResult(position = "2", driver = F1Driver(driverId = "norris", code = "NOR")),
        )
        val sprint = listOf(
            F1SprintResult(position = "1", driver = F1Driver(driverId = "norris", code = "NOR")),
            F1SprintResult(position = "2", driver = F1Driver(driverId = "max_verstappen", code = "VER")),
        )

        val data = mapOk(results, qualifying, sprint)

        val errors = RaceResultValidation.validate(data, isSprintRace = true)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `giro veloce su pilota sconosciuto non assegnato ad altri`() {
        val results = listOf(
            result("max_verstappen", code = "VER", position = "1"),
            result("michael_schumacher", code = null, position = "2", fastestLapRank = "1"),
        )

        val mapping = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        // Con la regola N2 il risultato intero viene bloccato: nessun rischio
        // che il giro veloce venga assegnato al pilota sbagliato.
        assertTrue(mapping is RaceMapping.UnknownDriver)
    }

    @Test
    fun `griglia massimo corrisponde a MAX_DRIVERS`() {
        // La validazione accetta griglie fino a MAX_DRIVERS (22 nel 2026).
        assertEquals(22, DriversCatalog.MAX_DRIVERS)
    }

    @Test
    fun `isPast riconosce le gare gia' disputate`() {
        val today = LocalDate.of(2026, 10, 13)
        val yesterday = F1Race(
            season = "2026",
            round = "18",
            raceName = "Past Grand Prix",
            date = "2026-10-12",
        )
        val todayRace = F1Race(
            season = "2026",
            round = "19",
            raceName = "Today Grand Prix",
            date = "2026-10-13",
        )
        val future = F1Race(
            season = "2026",
            round = "20",
            raceName = "Future Grand Prix",
            date = "2026-10-20",
        )

        // La gara di oggi non e' "fatta": e' ancora pronosticabile.
        assertTrue(F1Mapper.isPast(yesterday, today))
        assertFalse(F1Mapper.isPast(todayRace, today))
        assertFalse(F1Mapper.isPast(future, today))
    }

    @Test
    fun `isPast con data assente o non valida non blocca la gara`() {
        val today = LocalDate.of(2026, 10, 13)
        val noDate = F1Race(
            season = "2026",
            round = "5",
            raceName = "No Date Grand Prix",
            date = null,
        )
        val badDate = F1Race(
            season = "2026",
            round = "6",
            raceName = "Bad Date Grand Prix",
            date = "13/10/2026",
        )

        assertFalse(F1Mapper.isPast(noDate, today))
        assertFalse(F1Mapper.isPast(badDate, today))
    }

    @Test
    fun `pitStopsByDriver conta le fermate per pilota`() {
        val pitStops = listOf(
            F1PitStop(driverId = "max_verstappen", lap = "12"),
            F1PitStop(driverId = "max_verstappen", lap = "34"),
            F1PitStop(driverId = "norris", lap = "15"),
            F1PitStop(driverId = "gasly", lap = "18"),
            F1PitStop(driverId = "gasly", lap = "30"),
            F1PitStop(driverId = "gasly", lap = "44"),
        )

        val counts = F1Mapper.pitStopsByDriver(pitStops)

        assertEquals(2, counts["VER"])
        assertEquals(1, counts["NOR"])
        assertEquals(3, counts["GAS"])
    }

    @Test
    fun `pitStopsByDriver scarta i piloti non in catalogo e i conteggi assurdi`() {
        val pitStops = listOf(
            F1PitStop(driverId = "michael_schumacher", lap = "10"),
            F1PitStop(driverId = "max_verstappen", lap = "12"),
            F1PitStop(driverId = "max_verstappen", lap = "13"),
            F1PitStop(driverId = "max_verstappen", lap = "14"),
            F1PitStop(driverId = "max_verstappen", lap = "15"),
            F1PitStop(driverId = "max_verstappen", lap = "16"),
            F1PitStop(driverId = "max_verstappen", lap = "17"),
            F1PitStop(driverId = "max_verstappen", lap = "18"),
            F1PitStop(driverId = "max_verstappen", lap = "19"),
            F1PitStop(driverId = "max_verstappen", lap = "20"), // 9 fermate: oltre il massimo
        )

        val counts = F1Mapper.pitStopsByDriver(pitStops)

        assertNull(counts["SCH"] ?: counts["michael_schumacher"])
        // 9 fermate supera il massimo: il conteggio viene scartato (dati malformati).
        assertFalse(counts.containsKey("VER"))
    }

    @Test
    fun `pitStopsByDriver su lista vuota restituisce mappa vuota`() {
        assertEquals(emptyMap<String, Int>(), F1Mapper.pitStopsByDriver(emptyList()))
    }

    @Test
    fun `raceLaps restituisce il massimo dei giri completati`() {
        val withLaps = listOf(
            F1Result(position = "1", laps = "57", driver = F1Driver(driverId = "max_verstappen")),
            F1Result(position = "2", laps = "57", driver = F1Driver(driverId = "norris")),
            F1Result(position = "18", laps = "12", driver = F1Driver(driverId = "gasly")),
        )

        assertEquals(57, F1Mapper.raceLaps(withLaps))
    }

    @Test
    fun `raceLaps senza dati restituisce null`() {
        val results = listOf(
            F1Result(position = "1", driver = F1Driver(driverId = "max_verstappen")),
        )
        assertNull(F1Mapper.raceLaps(results))
        assertNull(F1Mapper.raceLaps(emptyList()))
    }

    @Test
    fun `risultato sincronizzato non inventa safety car`() {
        // N3: Jolpica non fornisce le safety car: il campo resta null
        // ("in attesa di conferma admin"), mai uno zero falso.
        val data = mapOk(listOf(result("norris", code = "NOR", position = "1")))
        assertNull(data.safetyCars)
        assertNull(data.totalPitStops)
    }

    private fun result(
        apiDriverId: String,
        code: String?,
        position: String,
        positionText: String? = position,
        status: String = "Finished",
        grid: String? = null,
        points: String? = null,
        fastestLapRank: String? = null,
    ): F1Result = F1Result(
        position = position,
        positionText = positionText,
        points = points,
        grid = grid,
        status = status,
        driver = F1Driver(driverId = apiDriverId, code = code),
        fastestLap = fastestLapRank?.let { F1FastestLap(rank = it) },
    )
}
