package com.fantaformula1.app.data.openf1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del messaggio d'errore dell'import SC/VSC: l'utente deve capire la
 * causa senza leggere il trace di ktor (il 401 di OpenF1 durante le sessioni
 * live e' la situazione normale, non un bug dell'app).
 */
class OpenF1FailureMessageTest {

    @Test
    fun `401 spiega il blocco durante le sessioni live`() {
        val message = openF1FailureMessage(401, "Client request GET ... 401 Unauthorized")
        assertTrue(message.contains("sessioni", ignoreCase = true))
        assertTrue(message.contains("live", ignoreCase = true))
        // Nessun residuo di trace ktor nel messaggio utente.
        assertTrue(message.contains("ktor").not())
        assertTrue(message.contains("NoTransformation").not())
    }

    @Test
    fun `403 (chiave scaduta) usa lo stesso messaggio del 401`() {
        assertEquals(
            openF1FailureMessage(401, null),
            openF1FailureMessage(403, null),
        )
    }

    @Test
    fun `429 invita a riprovare piu tardi`() {
        val message = openF1FailureMessage(429, null)
        assertTrue(message.contains("Troppe richieste", ignoreCase = true))
        assertTrue(message.contains("riprova", ignoreCase = true))
    }

    @Test
    fun `senza status il messaggio grezzo viene passato com'e`() {
        assertEquals("boom", openF1FailureMessage(null, "boom"))
        assertEquals(
            "Impossibile importare da OpenF1",
            openF1FailureMessage(null, null),
        )
    }

    @Test
    fun `status sconosciuto degrada sul messaggio grezzo`() {
        assertEquals("boom", openF1FailureMessage(500, "boom"))
        assertEquals(
            "Impossibile importare da OpenF1",
            openF1FailureMessage(503, null),
        )
    }
}
