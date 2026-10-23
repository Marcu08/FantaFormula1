package com.fantaformula1.app.ui.calendar

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validazione del form gara (R4): la deadline e' obbligatoria, altrimenti la
 * gara resterebbe aperta per sempre (coerente col trigger lato DB di
 * 0011_fixes.sql).
 */
class RaceFormValidationTest {

    private fun form(
        name: String = "Gran Premio d'Italia",
        round: String = "5",
        deadline: String = "2026-05-10 14:00",
    ) = RaceForm(name = name, round = round, deadline = deadline)

    @Test
    fun `form completo e valido`() {
        assertNull(validateRaceForm(form()))
    }

    @Test
    fun `deadline mancante rifiutata`() {
        val error = validateRaceForm(form(deadline = ""))

        assertNotNull(error)
        assertTrue("atteso messaggio su deadline: $error", error!!.contains("deadline"))
    }

    @Test
    fun `deadline composta solo da spazi rifiutata`() {
        val error = validateRaceForm(form(deadline = "   "))

        assertNotNull(error)
        assertTrue(error!!.contains("deadline"))
    }

    @Test
    fun `deadline nel formato sbagliato rifiutata`() {
        val error = validateRaceForm(form(deadline = "10/05/2026"))

        assertNotNull(error)
        assertTrue(error!!.contains("2026-03-15 14:00"))
    }

    @Test
    fun `nome troppo corto rifiutato`() {
        val error = validateRaceForm(form(name = "A"))

        assertNotNull(error)
        assertTrue(error!!.contains("nome"))
    }

    @Test
    fun `round non numerico rifiutato`() {
        val error = validateRaceForm(form(round = "x"))

        assertNotNull(error)
        assertTrue(error!!.contains("round"))
    }

    @Test
    fun `round zero rifiutato`() {
        assertNotNull(validateRaceForm(form(round = "0")))
    }

    @Test
    fun `la validazione viene prima del salvataggio anche in modifica`() {
        // Anche il form di modifica passa per submitForm -> validateRaceForm:
        // svuotare la deadline di una gara esistente non deve essere possibile.
        val editing = RaceForm(
            name = "Gran Premio di Monaco",
            round = "8",
            country = "Monaco",
            deadline = "",
        )

        assertNotNull(validateRaceForm(editing))
    }
}
