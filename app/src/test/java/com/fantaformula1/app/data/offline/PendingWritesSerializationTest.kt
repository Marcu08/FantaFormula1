package com.fantaformula1.app.data.offline

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.RacePrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `una coda appartiene solo all'utente che l'ha creata`() {
        val a = PendingPredictionWrite("r1", RacePrediction("r1"), 1L, userId = "user-a")
        val b = PendingPredictionWrite("r2", RacePrediction("r2"), 2L, userId = "user-b")

        assertEquals(listOf(a), pendingWritesForUser(listOf(a, b), "user-a"))
        assertEquals(listOf(b), pendingWritesForUser(listOf(a, b), "user-b"))
        assertTrue(pendingWritesForUser(listOf(a), "user-a").isNotEmpty())
        assertFalse(pendingWritesForUser(listOf(a), "user-b").isNotEmpty())
    }

    @Test
    fun `una voce legacy senza utente non appartiene a nessuno`() {
        // JSON scritto da una versione precedente: userId assente -> null.
        val legacy = PendingWritesJson.decode(
            """[{"raceId":"r1","prediction":{"raceId":"r1","entries":[]},"queuedAtEpochMs":1}]""",
        )!!.single()

        assertNull(legacy.userId)
        assertFalse(legacy.belongsTo("user-a"))
        assertTrue(pendingWritesForUser(listOf(legacy), "user-a").isEmpty())
    }

    @Test
    fun `enqueue non cancella la coda di un altro utente per la stessa gara`() {
        val oldA = PendingPredictionWrite("r1", RacePrediction("r1"), 1L, userId = "user-a")
        val oldB = PendingPredictionWrite("r1", RacePrediction("r1"), 2L, userId = "user-b")
        val newA = PendingPredictionWrite("r1", RacePrediction("r1"), 3L)

        val result = upsertPendingWriteForUser(listOf(oldA, oldB), newA, "user-a")

        assertEquals(
            listOf(oldB, newA.copy(userId = "user-a")),
            result,
        )
        assertEquals(listOf(oldB), pendingWritesForUser(result, "user-b"))
        assertEquals(listOf(newA.copy(userId = "user-a")), pendingWritesForUser(result, "user-a"))
    }

    @Test
    fun `round trip conserva l'utente proprietario`() {
        val write = PendingPredictionWrite(
            "r1",
            RacePrediction("r1"),
            queuedAtEpochMs = 1L,
            userId = "user-a",
        )
        assertEquals(listOf(write), PendingWritesJson.decode(PendingWritesJson.encode(listOf(write))))
    }
}
