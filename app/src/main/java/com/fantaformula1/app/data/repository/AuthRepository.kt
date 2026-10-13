package com.fantaformula1.app.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Stato di autenticazione dell'utente, indipendente dal SDK Supabase. */
sealed interface AuthState {
    /** Sta controllando la sessione salvata. */
    data object Checking : AuthState

    /** Nessuna sessione attiva. */
    data object SignedOut : AuthState

    /** Sessione attiva. */
    data class SignedIn(val userId: String, val email: String?, val displayName: String?) : AuthState
}

/** Errore utente comprensibile, gia' tradotto in italiano. */
data class UserException(override val message: String) : Exception(message)

/**
 * Interfaccia del repository auth: permette di testare i ViewModel su JVM
 * con un'implementazione fake, senza toccare la rete.
 */
interface AuthRepository {
    val state: Flow<AuthState>

    /** Controlla la sessione esistente e mette a disposizione lo stato. */
    suspend fun refresh()

    suspend fun signIn(email: String, password: String)

    suspend fun signUp(email: String, password: String, displayName: String)

    suspend fun signOut()

    /** Id dell'utente attivo, oppure null se non autenticato. */
    fun currentUserId(): String?

    /** true se la configurazione Supabase e' presente e valida. */
    fun isConfigured(): Boolean

    /**
     * Avvia l'osservazione della sessione (login/logout/scadenza).
     * L'implementazione reale la collega al flusso di Supabase Auth.
     */
    fun startSessionObserver(scope: CoroutineScope)
}
