package com.fantaformula1.app.data.repository

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException

private const val MSG_RETE = "Connessione assente: controlla la rete e la configurazione"
private const val MSG_CONFIG = "Configurazione Supabase non valida: controlla URL e chiave"
private const val MSG_CREDENZIALI = "Email o password non corretti"
private const val MSG_GENERICO = "Si è verificato un errore durante l'accesso"

private fun isNetworkFailure(raw: String): Boolean =
    raw.contains("unable to resolve host") || raw.contains("connection") ||
        raw.contains("network") || raw.contains("timeout") ||
        raw.contains("no address associated") || raw.contains("unreachable")

/**
 * Traduce un'eccezione di Supabase Auth in un messaggio italiano (N11).
 *
 * La decisione si basa sul [AuthErrorCode] strutturato dell'eccezione (e non
 * sullo status HTTP): un 401 con codice `bad_jwt` o `no_authorization` e'
 * una configurazione errata (anon key sbagliata), NON credenziali errate.
 */
internal fun mapAuthErrorMessage(throwable: Throwable): String {
    // B26: la rete ha la priorita'. Se il dispositivo non arriva a Supabase
    // (URL errato, offline) non ha senso dire "password sbagliata".
    if (isNetworkFailure(throwable.message?.lowercase().orEmpty())) return MSG_RETE

    val authError = generateSequence(throwable) { it.cause }
        .take(6)
        .filterIsInstance<AuthRestException>()
        .firstOrNull()

    return mapAuthErrorMessage(
        errorCode = authError?.errorCode,
        rawMessage = authError?.errorDescription ?: throwable.message,
    )
}

/**
 * Parte pura del mapping, testabile su JVM senza costruire eccezioni Ktor.
 */
internal fun mapAuthErrorMessage(errorCode: AuthErrorCode?, rawMessage: String?): String {
    val raw = rawMessage?.lowercase().orEmpty()
    if (isNetworkFailure(raw)) return MSG_RETE

    when (errorCode) {
        AuthErrorCode.InvalidCredentials -> return MSG_CREDENZIALI
        AuthErrorCode.UserAlreadyExists,
        AuthErrorCode.EmailExists,
        -> return "Esiste gia' un account con questa email"

        AuthErrorCode.UserNotFound -> return "Account non trovato: controlla l'email"
        AuthErrorCode.WeakPassword -> return "La password deve essere di almeno 6 caratteri"
        AuthErrorCode.EmailNotConfirmed -> return "Email non confermata: controlla la tua casella"
        AuthErrorCode.OverRequestRateLimit,
        AuthErrorCode.OverEmailSendRateLimit,
        -> return "Troppi tentativi: riprova tra qualche istante"

        // Anon key sbagliata o header Authorization non valido: e' un problema
        // di configurazione del progetto, non delle credenziali dell'utente.
        AuthErrorCode.BadJwt,
        AuthErrorCode.NoAuthorization,
        -> return MSG_CONFIG

        else -> Unit
    }

    // Errore di Auth non codificato ma riconciliabile dal messaggio grezzo.
    if (raw.contains("invalid url") || raw.contains("illegal character") ||
        raw.contains("failed to execute request") || raw.contains("invalid api key")
    ) {
        return MSG_CONFIG
    }

    return rawMessage?.takeIf { it.isNotBlank() } ?: MSG_GENERICO
}
