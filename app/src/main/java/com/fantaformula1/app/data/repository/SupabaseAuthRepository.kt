package com.fantaformula1.app.data.repository

import com.fantaformula1.app.data.supabase.SupabaseHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


/**
 * Registrazione e accesso email + password su Supabase Auth.
 *
 * Il nome scelto dall'utente viene passato ai metadata (`display_name`) in
 * fase di registrazione: e' il trigger `on_auth_user_created` sul database a
 * creare la riga in `profiles`. Questo client non scrive in `profiles`.
 */
class SupabaseAuthRepository(
    private val holder: SupabaseHolder,
) : AuthRepository {

    private val _state = MutableStateFlow<AuthState>(AuthState.Checking)

    override val state: Flow<AuthState> = _state

    override fun isConfigured(): Boolean = holder.get() != null

    override fun currentUserId(): String? =
        (holder.get()?.auth?.currentUserOrNull()?.id) ?: (_state.value as? AuthState.SignedIn)?.userId

    override suspend fun refresh() {
        val client = holder.get() ?: run {
            _state.value = AuthState.SignedOut
            return
        }
        // B8: attende che il plugin Auth abbia ripristinato la sessione
        // salvata, altrimenti currentUserOrNull() puo' dare null subito dopo
        // l'avvio anche se l'utente era loggato.
        try {
            client.auth.awaitInitialization()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Se l'inizializzazione fallisce proviamo comunque a leggere lo
            // stato attuale: meglio SignedOut/SignedIn basato sui dati che
            // abbiamo piuttosto che lasciare Checking all'infinito.
        }
        val user = client.auth.currentUserOrNull()
        _state.value = if (user != null) {
            AuthState.SignedIn(
                userId = user.id,
                email = user.email,
                displayName = user.userMetadata
                    ?.get("display_name")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            )
        } else {
            AuthState.SignedOut
        }
    }

    /** Job del collector sulla sessione: annullato al cambio client (N12). */
    private var sessionJob: Job? = null

    /**
     * Osserva i cambi di sessione (login, logout, scadenza del refresh token)
     * e aggiorna lo stato in tempo reale (B8). Va avviato da [AppContainer]
     * dopo aver configurato il client.
     *
     * N12: idempotente — una seconda chiamata annulla il vecchio collector
     * invece di lasciarne due. Inoltre segue [SupabaseHolder.clientState]:
     * quando la configurazione cambia il collector sul vecchio client viene
     * annullato e ne parte uno nuovo sul client nuovo.
     */
    override fun startSessionObserver(scope: CoroutineScope) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            var clientJob: Job? = null
            holder.clientState.collect { client: SupabaseClient? ->
                clientJob?.cancel()
                clientJob = if (client == null) {
                    _state.value = AuthState.SignedOut
                    null
                } else {
                    launch {
                        client.auth.sessionStatus.collect { status ->
                            when (status) {
                                is SessionStatus.Authenticated -> refresh()
                                is SessionStatus.NotAuthenticated -> _state.value = AuthState.SignedOut
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }


    override suspend fun signIn(email: String, password: String) {
        val client = requireClient()
        try {
            client.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw UserException(mapAuthErrorMessage(throwable))
        }
        refresh()
    }

    override suspend fun signUp(email: String, password: String, displayName: String) {
        val client = requireClient()
        val name = displayName.trim()
        try {
            client.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
                // B7: il nome va nei metadata dell'utente (campo `data` della
                // config). Supabase lo salva in `user_metadata` e il trigger
                // `on_auth_user_created` lo usa per la riga in `profiles`.
                this.data = buildJsonObject { put("display_name", name) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw UserException(mapAuthErrorMessage(throwable))
        }

        // B7: se dopo la registrazione non c'e' sessione, significa che su
        // Supabase e' attiva la conferma email. L'utente deve cliccare il link
        // prima di poter entrare.
        if (client.auth.currentSessionOrNull() == null) {
            throw UserException("Controlla la tua email per confermare l'account")
        }
        refresh()
    }


    override suspend fun signOut() {
        val client = holder.get() ?: run {
            _state.value = AuthState.SignedOut
            return
        }
        runCatching { client.auth.signOut() }
        _state.value = AuthState.SignedOut
    }

    private fun requireClient() = holder.get()
        ?: throw UserException("Configurazione Supabase mancante: apri le impostazioni e inserisci URL e chiave.")
}

