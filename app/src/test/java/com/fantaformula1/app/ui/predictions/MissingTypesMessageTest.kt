package com.fantaformula1.app.ui.predictions

import com.fantaformula1.app.core.domain.PredictionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R6: i pronostici parziali sono ammessi (B15), ma prima dell'invio va
 * mostrato "Non hai compilato: …" con i tipi mancanti.
 */
class MissingTypesMessageTest {

    @Test
    fun `tutti completi non genera avviso`() {
        val drafts = listOf(
            DraftEntry.of(PredictionType.WINNER).copy(driverIds = listOf("ver")),
            DraftEntry.of(PredictionType.PODIUM).copy(driverIds = listOf("ver", "nor", "lec")),
        )

        assertNull(missingTypesMessage(drafts))
    }

    @Test
    fun `bozze parziali elencano i tipi mancanti`() {
        val drafts = listOf(
            DraftEntry.of(PredictionType.WINNER).copy(driverIds = listOf("ver")),
            DraftEntry.of(PredictionType.PODIUM),
            DraftEntry.of(PredictionType.HEAD_TO_HEAD),
        )

        assertEquals(
            "Non hai compilato: Podio, Testa a testa",
            missingTypesMessage(drafts),
        )
    }

    @Test
    fun `lista vuota non genera avviso`() {
        assertNull(missingTypesMessage(emptyList()))
    }
}
