package com.fantaformula1.app.data.offline

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.RacePrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F3 — round-trip JSON della coda dei salvataggi offline, senza Android
 * Context: la serializzazione vive in [PendingWritesJson].
 */
class PendingWritesSerializationTest {

    @Test
    fun `write con pronostico realistico round trip`() {
        val write = PendingPredictionWrite(
            raceId = "race-1",
            prediction = RacePrediction(
                raceId = "race-1",
                entries = listOf(
                    PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("NOR")),
                    PredictionEntry(
                        type = PredictionType.HEAD_TO_HEAD,
                        driverIds = listOf("VER", "LEC"),
                        quota = 1.4,
                    ),
                    PredictionEntry(
                        type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                        rangeMin = 1,
                        rangeMax = 2,
                    ),
                ),
                submittedAtEpochMs = 1_773_123_456_789L,
            ),
            queuedAtEpochMs = 1_773_123_456_000L,
        )
        val decoded = PendingWritesJson.decode(PendingWritesJson.encode(listOf(write)))
        assertEquals(listOf(write), decoded)
    }

    @Test
    fun `lista multipla round trip mantiene l'ordine`() {
        val writes = listOf(
            PendingPredictionWrite("r1", RacePrediction(raceId = "r1"), queuedAtEpochMs = 1L),
            PendingPredictionWrite("r2", RacePrediction(raceId = "r2"), queuedAtEpochMs = 2L),
        )
        val decoded = PendingWritesJson.decode(PendingWritesJson.encode(writes))
        assertEquals(writes, decoded)
    }

    @Test
    fun `lista vuota round trip`() {
        assertEquals(
            emptyList<PendingPredictionWrite>(),
            PendingWritesJson.decode(PendingWritesJson.encode(emptyList())),
        )
    }

    @Test
    fun `json rotto degrada a null`() {
        assertNull(PendingWritesJson.decode("{non json"))
    }
}
