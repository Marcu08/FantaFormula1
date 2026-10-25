package com.fantaformula1.app.data.f1api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del messaggio d'errore della sync Jolpica: l'utente deve capire cosa
 * fare senza leggere il trace di ktor (il caso 404 con body HTML arriva da
 * rete interferente, non dall'API che risponde quasi sempre 200 JSON).
 */
class F1FailureMessageTest {

    @Test
    fun `404 suggerisce di riprovare o controllare il round`() {
        val message = f1FailureMessage(404, "Client request ... 404 Not Found")
        assertTrue(message.contains("Jolpica"))
        assertTrue(message.contains("round", ignoreCase = true))
        assertFalse(message.contains("ktor"))
        assertFalse(message.contains("NoTransformation"))
    }

    @Test
    fun `429 invita ad aspettare per il rate-limit`() {
        val message = f1FailureMessage(429, null)
        assertTrue(message.contains("Troppe richieste"))
        assertTrue(message.contains("riprova", ignoreCase = true))
    }

    @Test
    fun `gli errori di server vanno a riprovare piu tardi`() {
        val message = f1FailureMessage(503, "raw")
        assertTrue(message.contains("indisponibile"))
        assertFalse(message.contains("raw"))
    }

    @Test
    fun `gli altri status passano il messaggio grezzo`() {
        assertEquals("raw", f1FailureMessage(403, "raw"))
        assertEquals("Errore dal server Jolpica (HTTP 418)", f1FailureMessage(418, null))
    }

    @Test
    fun `senza status il messaggio di sistema passa com'e o cade sul default`() {
        assertEquals("Unable to resolve host", f1FailureMessage(null, "Unable to resolve host"))
        assertEquals(
            "Impossibile contattare Jolpica: controlla la connessione",
            f1FailureMessage(null, null),
        )
    }
}
