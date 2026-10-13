package com.fantaformula1.app.data.repository

import com.fantaformula1.app.data.supabase.SupabaseHolder
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.ktor.http.HttpStatusCode
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
            throw UserException(mapAuthError(throwable))
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
            throw UserException(mapAuthError(throwable))
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

    /** Traduce le eccezioni Supabase in messaggi comprensibili in italiano. */
    private fun mapAuthError(throwable: Throwable): String {
        val status = extractStatus(throwable)
        val raw = throwable.message?.lowercase().orEmpty()

        // B26: la rete ha la priorita'. Se il dispositivo non arriva a Supabase
        // (URL errato, chiave errata, offline) non ha senso dire "password
        // sbagliata": prima i problemi di connessione, poi il resto.
        if (raw.contains("unable to resolve host") || raw.contains("connection") ||
            raw.contains("network") || raw.contains("timeout") ||
            raw.contains("no address associated") || raw.contains("unreachable")
        ) {
            return "Connessione assente: controlla la rete e la configurazione"
        }
        // URL/chiave di Supabase non validi: l'host non risolve o la richiesta
        // viene respinta a monte dell'Auth. Non e' un problema di credenziali.
        if (raw.contains("invalid url") || raw.contains("illegal character") ||
            raw.contains("failed to execute request") || status == HttpStatusCode.BadRequest
        ) {
            return "Configurazione Supabase non valida: controlla URL e chiave"
        }

        return when {
            status == HttpStatusCode.Conflict || raw.contains("already registered") ||
                raw.contains("already been registered") || raw.contains("email_exists") ->
                "Esiste gia' un account con questa email"

            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ||
                raw.contains("invalid login credentials") || raw.contains("invalid_credentials") ->
                "Email o password non corretti"

            raw.contains("password should be at least") || raw.contains("password_limit") ->
                "La password deve essere di almeno 6 caratteri"

            raw.contains("email not confirmed") || raw.contains("email_not_confirmed") ->
                "Email non confermata: controlla la tua casella"

            status == HttpStatusCode.TooManyRequests || raw.contains("rate limit") ->
                "Troppi tentativi: riprova tra qualche istante"

            else -> throwable.message?.takeIf { it.isNotBlank() }
                ?: "Si è verificato un errore durante l'accesso"
        }
    }

    private fun extractStatus(throwable: Throwable): HttpStatusCode? {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 6) {
            val field = current.javaClass.methods
                .firstOrNull { it.name == "getStatus" || it.name == "getStatusCode" }
            val value = runCatching { field?.invoke(current) }.getOrNull()
            if (value is HttpStatusCode) return value
            current = current.cause
            depth++
        }
        return null
    }
}

