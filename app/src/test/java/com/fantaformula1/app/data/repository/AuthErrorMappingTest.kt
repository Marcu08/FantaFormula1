package com.fantaformula1.app.data.repository

import io.github.jan.supabase.auth.exception.AuthErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * N11: il messaggio dipende dal [AuthErrorCode] strutturato, non dallo status
 * HTTP. Un 401 con codice `bad_jwt`/`no_authorization` (anon key sbagliata) e'
 * una configurazione invalida, NON "email o password non corretti".
 */
class AuthErrorMappingTest {

    @Test
    fun `credenziali errate usano il codice invalid credentials`() {
        assertEquals(
            "Email o password non corretti",
            mapAuthErrorMessage(AuthErrorCode.InvalidCredentials, "Invalid login credentials"),
        )
    }

    @Test
    fun `chiave anon errata non viene scambiata per credenziali`() {
        // Un 401 generico non basta piu': qui conta il codice BadJwt.
        val message = mapAuthErrorMessage(AuthErrorCode.BadJwt, "Invalid API key")

        assertEquals("Configurazione Supabase non valida: controlla URL e chiave", message)
        assertFalse(message.contains("password"))
    }

    @Test
    fun `no authorization e' configurazione non credenziali`() {
        val message = mapAuthErrorMessage(AuthErrorCode.NoAuthorization, "No authorization header")

        assertEquals("Configurazione Supabase non valida: controlla URL e chiave", message)
        assertFalse(message.contains("password"))
    }

    @Test
    fun `email gia' registrata sia con email exists che user already exists`() {
        assertEquals(
            "Esiste gia' un account con questa email",
            mapAuthErrorMessage(AuthErrorCode.EmailExists, "A user with this email address has already been registered"),
        )
        assertEquals(
            "Esiste gia' un account con questa email",
            mapAuthErrorMessage(AuthErrorCode.UserAlreadyExists, "User already registered"),
        )
    }

    @Test
    fun `password debole`() {
        assertEquals(
            "La password deve essere di almeno 6 caratteri",
            mapAuthErrorMessage(AuthErrorCode.WeakPassword, "Password should be at least 6 characters"),
        )
    }

    @Test
    fun `email non confermata`() {
        assertEquals(
            "Email non confermata: controlla la tua casella",
            mapAuthErrorMessage(AuthErrorCode.EmailNotConfirmed, "Email not confirmed"),
        )
    }

    @Test
    fun `rate limit sia request che email send`() {
        assertEquals(
            "Troppi tentativi: riprova tra qualche istante",
            mapAuthErrorMessage(AuthErrorCode.OverRequestRateLimit, "Over request rate limit"),
        )
        assertEquals(
            "Troppi tentativi: riprova tra qualche istante",
            mapAuthErrorMessage(AuthErrorCode.OverEmailSendRateLimit, "Over email send rate limit"),
        )
    }

    @Test
    fun `problemi di rete hanno la priorita' su qualunque codice`() {
        // Anche con un codice "innocente", se il dispositivo non arriva a
        // Supabase il messaggio parla di rete (B26).
        assertEquals(
            "Connessione assente: controlla la rete e la configurazione",
            mapAuthErrorMessage(AuthErrorCode.InvalidCredentials, "Unable to resolve host supabase.co"),
        )
    }

    @Test
    fun `errore sconosciuto usa il messaggio grezzo`() {
        assertEquals(
            "Something went wrong",
            mapAuthErrorMessage(null, "Something went wrong"),
        )
    }

    @Test
    fun `messaggio assente o vuoto cade sul generico`() {
        assertEquals(
            "Si è verificato un errore durante l'accesso",
            mapAuthErrorMessage(null, null),
        )
        assertEquals(
            "Si è verificato un errore durante l'accesso",
            mapAuthErrorMessage(null, "   "),
        )
    }

    @Test
    fun `url non valido riconosciuto dal messaggio anche senza codice`() {
        assertEquals(
            "Configurazione Supabase non valida: controlla URL e chiave",
            mapAuthErrorMessage(null, "Invalid url provided"),
        )
    }
}
