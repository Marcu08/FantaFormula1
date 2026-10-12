package com.fantaformula1.app.core.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il jsonb di `race_results.result` e' la sorgente di verita' persistita:
 * questi test ne blindano il formato (chiavi) e il round-trip.
 */
class RaceResultRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun fullResult() = RaceResultData(
        raceId = "race-1",
        drivers = listOf(
            DriverRaceResult(
                driverId = "VER",
                driverName = "Max Verstappen",
                grid = 1,
                position = 1,
                sprintPosition = 2,
                fastestLap = true,
                dnf = false,
                dnfReason = null,
                penaltyPoints = 5,
                pitStops = 2,
                pointsScored = 25.0,
            ),
            DriverRaceResult(
                driverId = "HAM",
                driverName = "Lewis Hamilton",
                grid = 3,
                position = null,
                sprintPosition = null,
                fastestLap = false,
                dnf = true,
                dnfReason = "Cambio del motore",
                penaltyPoints = 0,
                pitStops = 1,
                pointsScored = 0.0,
            ),
        ),
        safetyCars = 2,
        redFlags = 1,
        laps = 57,
        weather = "Piovoso",
        notes = "Gara interrotta ripresa dopo bandiera rossa",
    )

    @Test
    fun `round trip preserva tutti i campi`() {
        val original = fullResult()
        val encoded = json.encodeToString(RaceResultData.serializer(), original)
        val decoded = json.decodeFromString(RaceResultData.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `le chiavi del jsonb sono stabili`() {
        val element = json.encodeToJsonElement(RaceResultData.serializer(), fullResult())
        val root = element.jsonObject

        assertEquals(setOf(
            "raceId", "drivers", "safetyCars", "redFlags", "laps", "weather", "notes",
        ), root.keys)

        val drivers = root["drivers"] as kotlinx.serialization.json.JsonArray
        val driver = drivers[0].jsonObject
        assertEquals(setOf(
            "driverId", "driverName", "grid", "position", "sprintPosition",
            "fastestLap", "dnf", "dnfReason", "penaltyPoints", "pitStops", "pointsScored",
        ), driver.keys)

        assertEquals("VER", driver["driverId"]!!.jsonPrimitive.content)
        assertEquals(1L, driver["position"]!!.jsonPrimitive.long)
        assertTrue(driver["fastestLap"]!!.jsonPrimitive.boolean)
        assertEquals(25.0, driver["pointsScored"]!!.jsonPrimitive.content.toDouble(), 0.0001)
    }

    @Test
    fun `default minimi producono json decodificabile`() {
        val minimal = RaceResultData(raceId = "r", drivers = listOf(DriverRaceResult(driverId = "LEC")))
        val encoded = json.encodeToString(RaceResultData.serializer(), minimal)
        val decoded = json.decodeFromString(RaceResultData.serializer(), encoded)
        assertEquals(minimal, decoded)
        assertNull(decoded.winner())
        assertEquals(0, decoded.laps)
    }

    @Test
    fun `classifiedOrder ignora i DNF e ordina per posizione`() {
        val result = fullResult()
        val order = result.classifiedOrder()
        assertEquals(listOf("VER"), order.map { it.driverId })
        assertEquals(1, result.classificationRank("VER"))
        // Hamilton e' DNF: finisce dopo i classificati
        assertEquals(2, result.classificationRank("HAM"))
        assertEquals(0.0, result.driver("HAM")!!.pointsScored!!, 0.0001)
    }

    @Test
    fun `sprintOrder e fastestLap usano i campi giusti`() {
        val result = fullResult()
        assertEquals(listOf("VER"), result.sprintOrder().map { it.driverId })
        assertEquals("VER", result.sprintWinner()?.driverId)
        assertEquals("VER", result.fastestLapDriver()?.driverId)
        assertEquals("VER", result.poleDriver()?.driverId)
        assertEquals(3, result.totalPitStops)
        assertFalse(result.classifiedOrder().isEmpty())
    }
}
