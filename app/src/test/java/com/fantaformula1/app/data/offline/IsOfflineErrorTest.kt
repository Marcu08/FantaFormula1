package com.fantaformula1.app.data.offline

import com.fantaformula1.app.data.repository.UserException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3 — riconoscimento degli errori di rete per la coda salvataggi. */
class IsOfflineErrorTest {

    @Test
    fun `IOException diretta e' offline`() {
        assertTrue(isOfflineError(IOException("broken pipe")))
        assertTrue(isOfflineError(SocketTimeoutException("timeout")))
    }

    @Test
    fun `IOException annidata e' offline`() {
        assertTrue(isOfflineError(RuntimeException("wrapped", IOException("reset"))))
        assertTrue(
            isOfflineError(
                IllegalStateException(
                    "outer",
                    RuntimeException("inner", IOException("Unable to resolve host")),
                ),
            ),
        )
    }

    @Test
    fun `solo il messaggio indica offline quando il tipo e' generico`() {
        assertTrue(isOfflineError(Exception("Unable to resolve host \"abc.supabase.co\"")))
        assertTrue(isOfflineError(Exception("Connection reset by peer")))
        assertTrue(isOfflineError(Exception("failed to connect to /10.0.0.1 (port 443)")))
        assertTrue(isOfflineError(Exception("Network is unreachable")))
        assertTrue(isOfflineError(Exception("Software caused connection abort")))
    }

    @Test
    fun `UserException col causa IOException e' offline (percorso repository)`() {
        // E' il percorso reale: SupabasePredictionRepository wrappa la causa.
        assertTrue(
            isOfflineError(
                UserException("Problema di connessione: controlla la rete e riprova", IOException()),
            ),
        )
    }

    @Test
    fun `errori di dominio non sono offline`() {
        assertFalse(isOfflineError(UserException("La deadline è passata: non puoi più modificare")))
        assertFalse(isOfflineError(IllegalStateException("chiave duplicata")))
        assertFalse(isOfflineError(NullPointerException()))
    }

    @Test
    fun `catena di cause ciclica non va in loop`() {
        val first = Exception("boom")
        val second = Exception("boom two")
        first.initCause(second)
        second.initCause(first)
        assertFalse(isOfflineError(first))
    }
}
