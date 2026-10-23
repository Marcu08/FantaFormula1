package com.fantaformula1.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_sync",
)

/** Timestamp dell'ultima sincronizzazione riuscita, per tipo. */
data class F1SyncTimestamps(
    val calendar: Instant? = null,
    val results: Instant? = null,
)

/**
 * Persistenza dell'orario dell'ultima sincronizzazione riuscita (calendario e
 * risultati): mostrato nella UI come "Ultimo aggiornamento". Segue la stessa
 * convenzione DataStore di [com.fantaformula1.app.data.config.SupabaseConfigStore].
 */
class F1SyncPrefs(private val context: Context) {

    val timestamps: Flow<F1SyncTimestamps> = context.syncDataStore.data.map { prefs ->
        F1SyncTimestamps(
            calendar = prefs[KEY_CALENDAR]?.let(::parseInstant),
            results = prefs[KEY_RESULTS]?.let(::parseInstant),
        )
    }

    suspend fun current(): F1SyncTimestamps = timestamps.first()

    /** Registra l'ora corrente come ultima sincronizzazione del calendario. */
    suspend fun markCalendarSynced(time: Instant = Instant.now()) {
        context.syncDataStore.edit { prefs -> prefs[KEY_CALENDAR] = time.toString() }
    }

    /** Registra l'ora corrente come ultima sincronizzazione dei risultati. */
    suspend fun markResultsSynced(time: Instant = Instant.now()) {
        context.syncDataStore.edit { prefs -> prefs[KEY_RESULTS] = time.toString() }
    }

    /**
     * Leghe per cui e' stata pianificata la sincronizzazione periodica su
     * QUESTO dispositivo: serve a cancellare i worker al logout o quando
     * l'utente non e' piu' admin (N5).
     */
    suspend fun markLeagueScheduled(leagueId: String) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_SCHEDULED_LEAGUES] = prefs[KEY_SCHEDULED_LEAGUES].orEmpty() + leagueId
        }
    }

    suspend fun clearLeagueScheduled(leagueId: String) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_SCHEDULED_LEAGUES] = prefs[KEY_SCHEDULED_LEAGUES].orEmpty() - leagueId
        }
    }

    suspend fun scheduledLeagues(): Set<String> = context.syncDataStore.data
        .map { prefs -> prefs[KEY_SCHEDULED_LEAGUES].orEmpty() }
        .first()

    suspend fun clearAllScheduled() {
        context.syncDataStore.edit { prefs -> prefs.remove(KEY_SCHEDULED_LEAGUES) }
    }

    private fun parseInstant(text: String): Instant? =
        runCatching { Instant.parse(text) }.getOrNull()

    companion object {
        private val KEY_CALENDAR = stringPreferencesKey("last_calendar_sync")
        private val KEY_RESULTS = stringPreferencesKey("last_results_sync")
        private val KEY_SCHEDULED_LEAGUES = stringSetPreferencesKey("scheduled_leagues")
    }
}