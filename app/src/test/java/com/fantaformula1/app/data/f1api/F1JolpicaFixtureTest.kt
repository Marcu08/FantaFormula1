package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.data.f1api.model.F1Driver
import com.fantaformula1.app.data.f1api.model.F1DriversResponse
import com.fantaformula1.app.data.f1api.model.F1Response
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test su fixture REALI scaricate da Jolpica (N1):
 * - `jolpica_drivers_2026.json` = `f1/2026/drivers.json` (ridotto): tutti i
 *   piloti con codice devono mappare sugli ID dell'app;
 * - `jolpica_results_2026_r1.json` = `f1/2026/1/results.json`: la gara reale
 *   va mappata per intero (22 piloti) e passa la validazione del form.
 */
class F1JolpicaFixtureTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Fixture mancante: $name"
        }.bufferedReader().readText()

    @Test
    fun `ogni pilota 2026 con codice viene mappato`() {
        val decoded: F1DriversResponse = json.decodeFromString(fixture("jolpica_drivers_2026.json"))
        val drivers = decoded.mrData.driverTable.drivers
        assertTrue(drivers.isNotEmpty())

        drivers.filter { !it.code.isNullOrBlank() }.forEach { driver ->
            val appId = F1DriverMapping.toAppId(driver)
            assertEquals(
                "Il pilota ${driver.driverId} (code=${driver.code}) non viene mappato",
                driver.code,
                appId,
            )
        }
        // I riservi senza codice non sono mappabili: con la regola N2
        // bloccano il salvataggio invece di falsare le posizioni.
        assertNull(F1DriverMapping.toAppId(F1Driver(driverId = "paul_aron", code = "")))
    }

    @Test
    fun `la gara reale del 2026 viene mappata per intero`() {
        val response = json.decodeFromString(
            F1Response.serializer(),
            fixture("jolpica_results_2026_r1.json"),
        )
        val results = response.mrData.raceTable.races.first().results.orEmpty()
        assertTrue(results.isNotEmpty())

        val mapping = F1Mapper.toRaceResultData(results, emptyList(), emptyList())
        assertTrue(
            "Risultato reale bloccato: $mapping",
            mapping is RaceMapping.Success,
        )
        val data = (mapping as RaceMapping.Success).data

        // La gara reale 2026 (GP d'Australia) ha 22 piloti titolari.
        assertEquals(22, data.drivers.size)
        assertEquals("RUS", data.winner()?.driverId)
        assertEquals(1, data.positionOf("RUS"))
        assertEquals(10, data.positionOf("GAS"))
        // "Did not start" (positionText "W"): non classificati come da regole
        // Ergast, quindi nessuna posizione.
        assertNull(data.positionOf("PIA"))
        assertNull(data.positionOf("HUL"))
        assertTrue(data.driver("PIA")!!.dnf)
        assertTrue(data.driver("HUL")!!.dnf)
        assertEquals(emptyList<String>(), RaceResultValidation.validate(data))
        // N3: Jolpica non fornisce safety car: mai uno zero inventato.
        assertNull(data.safetyCars)
    }

    @Test
    fun `tutti i codici della gara reale esistono nel catalogo`() {
        val response = json.decodeFromString(
            F1Response.serializer(),
            fixture("jolpica_results_2026_r1.json"),
        )
        val results = response.mrData.raceTable.races.first().results.orEmpty()
        results.forEach { result ->
            val code = result.driver.code
            assertTrue(
                "Codice FIA mancante o fuori catalogo per ${result.driver.driverId}",
                code != null && DriversCatalog.byId(code) != null,
            )
        }
    }
}
