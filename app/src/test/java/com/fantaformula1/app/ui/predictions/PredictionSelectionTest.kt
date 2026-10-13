package com.fantaformula1.app.ui.predictions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Selezione a slot per i pronostici a tendina: la lista resta compatta,
 * ordinata per slot e senza duplicati (invariante di PredictionValidation).
 */
class PredictionSelectionTest {

    @Test
    fun `selezione su lista vuota`() {
        assertEquals(listOf("VER"), setDriverInSelection(emptyList(), 0, "VER"))
    }

    @Test
    fun `slot successivi si riempiono in ordine`() {
        var selection = emptyList<String>()
        selection = setDriverInSelection(selection, 0, "VER")
        selection = setDriverInSelection(selection, 1, "NOR")
        selection = setDriverInSelection(selection, 2, "LEC")

        assertEquals(listOf("VER", "NOR", "LEC"), selection)
    }

    @Test
    fun `scegliere uno slot avanti con lista parziale compatta la lista`() {
        // Lista vuota, si sceglie direttamente lo slot 3 (podio): il pilota
        // occupa la prima posizione libera.
        assertEquals(listOf("VER"), setDriverInSelection(emptyList(), 2, "VER"))
        assertEquals(listOf("VER", "NOR"), setDriverInSelection(listOf("VER"), 2, "NOR"))
    }

    @Test
    fun `sostituzione di uno slot esistente`() {
        assertEquals(
            listOf("LEC", "NOR"),
            setDriverInSelection(listOf("VER", "NOR"), 0, "LEC"),
        )
    }

    @Test
    fun `riprendere un pilota gia' selezionato lo sposta`() {
        // VER e' in slot 1; sceglierlo nello slot 3 lo sposta e gli altri
        // scalano, senza duplicati.
        assertEquals(
            listOf("NOR", "LEC", "VER"),
            setDriverInSelection(listOf("VER", "NOR", "LEC"), 2, "VER"),
        )
    }

    @Test
    fun `nessun duplicato possibile`() {
        val selection = listOf("VER", "NOR", "LEC")

        // Ogni slot scelto con un pilota gia' presente produce una lista
        // della stessa dimensione senza ripetizioni.
        selection.forEachIndexed { slot, driver ->
            val result = setDriverInSelection(selection, slot, driver)
            assertEquals("slot=$slot driver=$driver result=$result", selection.size, result.size)
            assertEquals(result.size, result.toSet().size)
        }
    }
}