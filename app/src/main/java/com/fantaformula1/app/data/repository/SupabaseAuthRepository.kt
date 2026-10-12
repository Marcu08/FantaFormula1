package com.fantaformula1.app.data.repository

import com.fantaformula1.app.data.supabase.SupabaseHolder
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.rpc
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Autenticazione email + password su Supabase Auth.
 *
 * Dopo la registrazione il trigger `on_auth_user_created` crea la riga in
 * `profiles`; qui la allineiamo con il nome scelto dall'utente.
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
        } catch (throwable: Throwable) {
            throw UserException(mapAuthError(throwable))
        }
        refresh()
    }

    override suspend fun signUp(email: String, password: String, displayName: String) {
        val client = requireClient()
        try {
            client.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
            }
        } catch (throwable: Throwable) {
            throw UserException(mapAuthError(throwable))
        }

        val user = client.auth.currentUserOrNull()
        if (user != null) {
            // Il trigger ha gia' creato il profilo con l'email come nome:
            // lo sostituiamo con il nome effettivo scelto dall'utente.
            runCatching {
                client.from("profiles").update(
                    mapOf("display_name" to displayName.trim()),
                ) { filter { eq("id", user.id) } }
            }
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

            raw.contains("unable to resolve host") || raw.contains("connection") ||
                raw.contains("network") || raw.contains("timeout") ->
                "Connessione assente: controlla la rete e la configurazione"

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

