package com.fantaformula1.app.ui.predictions

import com.fantaformula1.app.core.domain.PredictionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Le modifiche locali non ancora inviate non devono sparire al resume. */
class DraftMergeTest {

    @Test
    fun `la bozza locale ha la precedenza su quella del server`() {
        val server = listOf(
            DraftEntry.of(PredictionType.WINNER).copy(driverIds = listOf("VER")),
            DraftEntry.of(PredictionType.POLE).copy(driverIds = listOf("NOR")),
        )
        val local = listOf(
            DraftEntry.of(PredictionType.WINNER).copy(driverIds = listOf("LEC")),
        )

        val merged = mergeDrafts(server, local)

        assertEquals(listOf("LEC"), merged.first().driverIds)
        assertEquals(listOf("NOR"), merged.last().driverIds)
    }

    @Test
    fun `senza bozza locale restano i valori del server`() {
        val server = listOf(
            DraftEntry.of(PredictionType.WINNER).copy(driverIds = listOf("VER")),
        )
        assertEquals(server, mergeDrafts(server, null))
    }

    @Test
    fun `bozze locali di tipi non visibili vengono ignorate`() {
        val server = listOf(DraftEntry.of(PredictionType.WINNER))
        val local = listOf(
            DraftEntry.of(PredictionType.WINNER).copy(driverIds = listOf("VER")),
            DraftEntry.of(PredictionType.SPRINT_WINNER).copy(driverIds = listOf("NOR")),
        )
        assertEquals(1, mergeDrafts(server, local).size)
    }
}
