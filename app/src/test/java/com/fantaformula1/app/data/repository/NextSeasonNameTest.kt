package com.fantaformula1.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F2 — nome della stagione creata dopo l'archiviazione: l'anno corrente non
 * deve collidere con i nomi gia' usati nella lega ("2026 (2)", "2026 (3)", ...).
 */
class NextSeasonNameTest {

    @Test
    fun `nessuna stagione rimane l'anno`() {
        assertEquals("2026", nextSeasonName(emptyList(), "2026"))
    }

    @Test
    fun `anno non ancora usato rimane l'anno`() {
        assertEquals("2026", nextSeasonName(listOf("2024", "2025"), "2026"))
    }

    @Test
    fun `anno gia' usato diventa suffisso (2)`() {
        assertEquals("2026 (2)", nextSeasonName(listOf("2026"), "2026"))
    }

    @Test
    fun `anno e primo suffisso gia' usati diventa suffisso (3)`() {
        assertEquals("2026 (3)", nextSeasonName(listOf("2026", "2026 (2)"), "2026"))
    }

    @Test
    fun `prende il primo suffisso libero anche con buchi`() {
        assertEquals(
            "2026 (3)",
            nextSeasonName(listOf("2026", "2026 (2)", "2026 (4)"), "2026"),
        )
    }

    @Test
    fun `piu' ripetizioni in fila`() {
        val existing = listOf("2026", "2026 (2)", "2026 (3)", "2026 (4)")
        assertEquals("2026 (5)", nextSeasonName(existing, "2026"))
    }

    @Test
    fun `nomi non correlati non influiscono`() {
        assertEquals("2027", nextSeasonName(listOf("2026", "2026 (2)", "Stagione"), "2027"))
    }
}
