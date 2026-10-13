package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.data.f1api.model.F1Circuit
import com.fantaformula1.app.data.f1api.model.F1Driver
import com.fantaformula1.app.data.f1api.model.F1FastestLap
import com.fantaformula1.app.data.f1api.model.F1Location
import com.fantaformula1.app.data.f1api.model.F1QualifyingResult
import com.fantaformula1.app.data.f1api.model.F1Race
import com.fantaformula1.app.data.f1api.model.F1Result
import com.fantaformula1.app.data.f1api.model.F1Session
import com.fantaformula1.app.data.f1api.model.F1SprintResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class F1MapperTest {

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
    fun `toRaceResultData mappa vincitore e giro veloce`() {
        val results = listOf(
            F1Result(
                position = "1",
                points = "25",
                grid = "1",
                status = "Finished",
                driver = F1Driver(driverId = "max_verstappen"),
                fastestLap = com.fantaformula1.app.data.f1api.model.F1FastestLap(rank = "1"),
            ),
            F1Result(
                position = "2",
                points = "18",
                grid = "3",
                status = "Finished",
                driver = F1Driver(driverId = "lando_norris"),
            ),
        )

        val data = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

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
            F1Result(
                position = "18",
                points = "0",
                status = "Engine",
                driver = F1Driver(driverId = "pierre_gasly"),
            ),
        )

        val data = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        val driver = data.drivers.first { it.driverId == "GAS" }
        assertTrue(driver.dnf)
        assertNull(driver.position)
        assertEquals("Engine", driver.dnfReason)
    }

    @Test
    fun `toRaceResultData include posizioni sprint`() {
        val results = listOf(
            F1Result(
                position = "1",
                status = "Finished",
                driver = F1Driver(driverId = "max_verstappen"),
            ),
            F1Result(
                position = "2",
                status = "Finished",
                driver = F1Driver(driverId = "lando_norris"),
            ),
        )
        // Ordine sprint invertito rispetto alla gara: lo sprint inverte P1 e P2.
        val sprint = listOf(
            F1SprintResult(position = "1", driver = F1Driver(driverId = "lando_norris")),
            F1SprintResult(position = "2", driver = F1Driver(driverId = "max_verstappen")),
        )

        val data = F1Mapper.toRaceResultData(results, emptyList(), sprint)

        assertEquals(2, data.driver("VER")?.sprintPosition)
        assertEquals(1, data.driver("NOR")?.sprintPosition)
    }

    @Test
    fun `toRaceResultData usa qualifica per griglia`() {
        val results = listOf(
            F1Result(
                position = "1",
                status = "Finished",
                driver = F1Driver(driverId = "max_verstappen"),
                grid = "2",
            ),
        )
        val qualifying = listOf(
            F1QualifyingResult(position = "1", driver = F1Driver(driverId = "max_verstappen")),
        )

        val data = F1Mapper.toRaceResultData(results, qualifying, emptyList())

        assertEquals(1, data.drivers.first().grid)
    }

    @Test
    fun `toRaceResultData rinormalizza posizioni dopo pilota sconosciuto`() {
        // "michael_schumacher" non e' nel catalogo 2026: le posizioni dei
        // piloti noti restano contigue da 1 (invariante B12 del motore).
        val results = listOf(
            result("max_verstappen", position = "1"),
            result("michael_schumacher", position = "2"),
            result("lando_norris", position = "3"),
            result("george_russell", position = "4"),
        )

        val data = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        assertEquals(1, data.positionOf("VER"))
        assertEquals(2, data.positionOf("NOR"))
        assertEquals(3, data.positionOf("RUS"))
        assertNull(data.driver("michael_schumacher"))
    }

    @Test
    fun `toRaceResultData usa positionText per classificare`() {
        // Ergast: un pilota classificato ha positionText numerico anche con
        // stato "Accident"; un ritirato ha positionText "R".
        val results = listOf(
            result("max_verstappen", position = "1", positionText = "1", status = "Accident"),
            result("pierre_gasly", position = "2", positionText = "R", status = "Collision"),
        )

        val data = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        assertEquals(1, data.positionOf("VER"))
        val gasly = data.drivers.first { it.driverId == "GAS" }
        assertTrue(gasly.dnf)
        assertNull(gasly.position)
        assertEquals("Collision", gasly.dnfReason)
    }

    @Test
    fun `toRaceResultData non mescola griglia qualifica e gara`() {
        // Con la qualifica presente la griglia viene solo da li': chi non ha
        // girato in qualifica (es. partito dai box) non eredita la griglia di
        // gara, per non creare duplicati.
        val results = listOf(
            result("max_verstappen", position = "1", grid = "1"),
            result("lando_norris", position = "2", grid = "2"),
        )
        val qualifying = listOf(
            F1QualifyingResult(position = "1", driver = F1Driver(driverId = "max_verstappen")),
        )

        val data = F1Mapper.toRaceResultData(results, qualifying, emptyList())

        assertEquals(1, data.driver("VER")?.grid)
        assertNull(data.driver("NOR")?.grid)
    }

    @Test
    fun `toRaceResultData scarta griglia zero partenza dai box`() {
        val results = listOf(
            result("max_verstappen", position = "1", grid = "0"),
        )

        val data = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        assertNull(data.driver("VER")?.grid)
    }

    @Test
    fun `risultato mappato passa la validazione del form`() {
        // Invariante chiave: l'esito costruito dall'API deve essere accettato
        // dallo stesso validatore usato dall'admin, altrimenti la gara viene
        // scartata in sync.
        val results = listOf(
            result("max_verstappen", position = "1", positionText = "1", points = "25", grid = "1"),
            result("lando_norris", position = "2", positionText = "2", points = "18", grid = "3"),
            result("carlos_sainz", position = "3", positionText = "R", status = "Engine"),
        )
        val qualifying = listOf(
            F1QualifyingResult(position = "1", driver = F1Driver(driverId = "max_verstappen")),
            F1QualifyingResult(position = "2", driver = F1Driver(driverId = "michael_schumacher")),
            F1QualifyingResult(position = "3", driver = F1Driver(driverId = "lando_norris")),
        )
        val sprint = listOf(
            F1SprintResult(position = "1", driver = F1Driver(driverId = "lando_norris")),
            F1SprintResult(position = "2", driver = F1Driver(driverId = "max_verstappen")),
        )

        val data = F1Mapper.toRaceResultData(results, qualifying, sprint)

        val errors = RaceResultValidation.validate(data, isSprintRace = true)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `giro veloce su pilota sconosciuto non assegnato ad altri`() {
        val results = listOf(
            result("max_verstappen", position = "1"),
            result(
                "michael_schumacher",
                position = "2",
                fastestLapRank = "1",
            ),
        )

        val data = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        assertFalse(data.drivers.first { it.driverId == "VER" }.fastestLap)
    }

    @Test
    fun `griglia massimo corrisponde a MAX_DRIVERS`() {
        // La validazione accetta griglie fino a MAX_DRIVERS (22 nel 2026).
        assertEquals(22, DriversCatalog.MAX_DRIVERS)
    }

    private fun result(
        apiDriverId: String,
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
        driver = F1Driver(driverId = apiDriverId),
        fastestLap = fastestLapRank?.let { F1FastestLap(rank = it) },
    )
}
