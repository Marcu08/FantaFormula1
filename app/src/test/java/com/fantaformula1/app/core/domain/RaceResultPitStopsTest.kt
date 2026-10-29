package com.fantaformula1.app.core.domain

import com.fantaformula1.app.data.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** H2/H3: zero, sconosciuto e dati parziali non devono essere confusi. */
class RaceResultPitStopsTest {

    private fun driver(id: String, pitStops: Int?) =
        DriverRaceResult(driverId = id, pitStops = pitStops)

    @Test
    fun `zero safety car sopravvive al round trip json`() {
        val result = RaceResultData(raceId = "r1", safetyCars = 0)
        val decoded = AppJson.decodeFromString(
            RaceResultData.serializer(),
            AppJson.encodeToString(RaceResultData.serializer(), result),
        )
        assertEquals(0, decoded.safetyCars)
    }

    @Test
    fun `null safety car resta sconosciuta`() {
        val decoded = AppJson.decodeFromString(
            RaceResultData.serializer(),
            """{"raceId":"r1","drivers":[],"safetyCars":null}""",
        )
        assertNull(decoded.safetyCars)
    }

    @Test
    fun `totale esplicito vince sui dati per pilota`() {
        val result = RaceResultData(
            raceId = "r1",
            totalPitStops = 40,
            drivers = listOf(driver("VER", 2)),
        )
        assertEquals(40, result.confirmedTotalPitStops())
    }

    @Test
    fun `dati per pilota parziali non producono un totale inventato`() {
        val result = RaceResultData(
            raceId = "r1",
            drivers = listOf(driver("VER", 2), driver("PER", null)),
        )
        assertNull(result.confirmedTotalPitStops())
    }

    @Test
    fun `dati completi con zeri espliciti producono la somma`() {
        val result = RaceResultData(
            raceId = "r1",
            drivers = listOf(driver("VER", 2), driver("PER", 0)),
        )
        assertEquals(2, result.confirmedTotalPitStops())
    }

    @Test
    fun `tutti zero senza totale restano sconosciuti`() {
        val result = RaceResultData(
            raceId = "r1",
            drivers = listOf(driver("VER", 0), driver("PER", 0)),
        )
        assertNull(result.confirmedTotalPitStops())
    }

    @Test
    fun `json legacy con pit stop numerici resta leggibile`() {
        val decoded = AppJson.decodeFromString(
            RaceResultData.serializer(),
            """{"raceId":"r1","drivers":[{"driverId":"VER","pitStops":2},{"driverId":"PER","pitStops":0}]}""",
        )
        assertEquals(2, decoded.confirmedTotalPitStops())
    }
}
