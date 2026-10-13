package com.fantaformula1.app.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fantaformula1.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_supabase_config",
)

/**
 * Configurazione Supabase.
 *
 * I valori di default arrivano da `local.properties`/`BuildConfig` (nessun
 * rebuild per cambiarli: si puo' anche solo sostituire il file e ricompilare),
 * ma l'utente puo' sovrascriverli a runtime dalla schermata Configurazione.
 * Se mancano URL o anon key l'app mostra uno stato "configurazione mancante".
 */
data class SupabaseConfig(
    val url: String,
    val anonKey: String,
) {
    val isConfigured: Boolean
        get() = url.isNotBlank() &&
            anonKey.isNotBlank() &&
            url.startsWith("https://")
}

class SupabaseConfigStore(private val context: Context) {

    val config: Flow<SupabaseConfig> = context.configDataStore.data.map { prefs ->
        SupabaseConfig(
            url = prefs[KEY_URL] ?: BuildConfig.DEFAULT_SUPABASE_URL,
            anonKey = prefs[KEY_ANON] ?: BuildConfig.DEFAULT_SUPABASE_ANON_KEY,
        )
    }

    /** true se l'utente ha salvato valori espliciti (non stiamo usando i default). */
    val hasOverride: Flow<Boolean> = context.configDataStore.data.map { prefs ->
        !prefs[KEY_URL].isNullOrBlank() || !prefs[KEY_ANON].isNullOrBlank()
    }

    suspend fun current(): SupabaseConfig = config.first()

    suspend fun save(url: String, anonKey: String) {
        context.configDataStore.edit { prefs ->
            val trimmedUrl = url.trim()
            val trimmedKey = anonKey.trim()
            // Stringhe vuote = nessun override: tornano ai default di BuildConfig.
            if (trimmedUrl.isBlank()) {
                prefs.remove(KEY_URL)
            } else {
                prefs[KEY_URL] = trimmedUrl
            }
            if (trimmedKey.isBlank()) {
                prefs.remove(KEY_ANON)
            } else {
                prefs[KEY_ANON] = trimmedKey
            }
        }
    }

    suspend fun clearOverrides() {
        context.configDataStore.edit { prefs ->
            prefs.remove(KEY_URL)
            prefs.remove(KEY_ANON)
        }
    }

    companion object {
        private val KEY_URL = stringPreferencesKey("supabase_url")
        private val KEY_ANON = stringPreferencesKey("supabase_anon_key")
    }
}
