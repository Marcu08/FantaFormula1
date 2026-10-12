package com.fantaformula1.app.data.supabase

import com.fantaformula1.app.data.config.SupabaseConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
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

    /** Il client attivo, oppure null se la configurazione non e' ancora valida. */
    fun get(): SupabaseClient? = client

    fun currentConfigOrNull(): SupabaseConfig? = currentConfig

    /**
     * Applica la configurazione. Restituisce true se il client e' stato
     * (ri)creato, false se la configurazione non e' utilizzabile.
     */
    suspend fun configure(config: SupabaseConfig): Boolean {
        if (!config.isConfigured) {
            // Configurazione non valida: si lascia lo stato precedente invariato
            // solo se non c'era nulla di meglio; altrimenti si invalida.
            if (client != null && currentConfig != null) return true
            client = null
            currentConfig = null
            return false
        }
        if (config == currentConfig && client != null) return true

        // Chiude il client precedente per non lasciare socket aperti.
        runCatching { client?.close() }

        client = createSupabaseClient(
            supabaseUrl = config.url.trim().trimEnd('/'),
            supabaseKey = config.anonKey.trim(),
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            httpEngine = Android.create()
        }
        currentConfig = config
        return true
    }

    suspend fun reset() {
        runCatching { client?.close() }
        client = null
        currentConfig = null
    }
}
