package com.fantaformula1.app.data.supabase

import com.fantaformula1.app.data.config.SupabaseConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.android.Android

/**
 * Detiene l'unico [SupabaseClient] attivo.
 *
 * Il client viene ricreato quando l'utente cambia URL/anon key dalla schermata
 * Configurazione: in quel caso vengono anche chiuse le sessioni precedenti.
 */
class SupabaseHolder {

    @Volatile
    private var client: SupabaseClient? = null

    @Volatile
    private var currentConfig: SupabaseConfig? = null

    /**
     * Client corrente come flusso: [startSessionObserver] lo usa per
     * agganciarsi al client attivo e per annullare il collector vecchio
     * quando la configurazione cambia (N12).
     */
    private val _clientState = MutableStateFlow<SupabaseClient?>(null)

    /** Il client attivo (null se la configurazione non e' valida). */
    val clientState: StateFlow<SupabaseClient?> = _clientState.asStateFlow()

    /** Il client attivo, oppure null se la configurazione non e' ancora valida. */
    fun get(): SupabaseClient? = client

    fun currentConfigOrNull(): SupabaseConfig? = currentConfig

    /**
     * Attende che il plugin Auth abbia finito di ripristinare la sessione
     * salvata. Va chiamato prima di leggere l'utente corrente: subito dopo la
     * creazione del client `currentUserOrNull()` puo' dare null perche' il
     * restore non e' ancora terminato.
     */
    suspend fun awaitAuthInitialization() {
        runCatching { client?.auth?.awaitInitialization() }
    }

    /**
     * Applica la configurazione. Restituisce true se il client e' stato
     * (ri)creato, false se la configurazione non e' utilizzabile.
     *
     * B24: una configurazione non valida restituisce SEMPRE false e azzera lo
     * stato. Prima poteva restituire true lasciando il vecchio client attivo:
     * il salvataggio sembrava riuscito ma la nuova (errata) configurazione non
     * veniva applicata.
     */
    suspend fun configure(config: SupabaseConfig): Boolean {
        if (!config.isConfigured) {
            runCatching { client?.close() }
            client = null
            currentConfig = null
            _clientState.value = null
            return false
        }
        if (config == currentConfig && client != null) return true

        // Chiude il client precedente per non lasciare socket aperti.
        runCatching { client?.close() }

        val newClient = createSupabaseClient(
            supabaseUrl = config.url.trim().trimEnd('/'),
            supabaseKey = config.anonKey.trim(),
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            httpEngine = Android.create()
        }
        client = newClient
        currentConfig = config
        _clientState.value = newClient
        return true
    }

    /**
     * Verifica reale della connessione con un client TEMPORANEO (N10).
     *
     * Prova a leggere `leagues` (tabella protetta da RLS, visibile agli
     * autenticati) senza toccare il client globale: un test fallito non
     * lascia l'app su una configurazione sbagliata. Il client temporaneo
     * viene sempre chiuso a fine operazione.
     */
    suspend fun testConnection(config: SupabaseConfig): Boolean {
        if (!config.isConfigured) return false
        val testClient = try {
            createSupabaseClient(
                supabaseUrl = config.url.trim().trimEnd('/'),
                supabaseKey = config.anonKey.trim(),
            ) {
                install(Postgrest)
                httpEngine = Android.create()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return false
        }
        return try {
            testClient.from("leagues").select()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { testClient.close() }
        }
    }

    suspend fun reset() {
        runCatching { client?.close() }
        client = null
        currentConfig = null
        _clientState.value = null
    }
}
