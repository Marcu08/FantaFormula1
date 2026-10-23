package com.fantaformula1.app.data.notification

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.notificationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_notifications",
)

/**
 * Preferenza dell'utente per le notifiche locali (F1): promemoria scadenza
 * e avviso risultati disponibili. Di default attive; spegnerle cancella i
 * promemoria gia' schedulati (il worker comunque riverifica il flag prima di
 * notificare, per coprire anche i work gia' in coda).
 */
class NotificationPrefs(private val context: Context) {

    suspend fun enabled(): Boolean =
        context.notificationDataStore.data.first()[KEY_ENABLED] ?: true

    suspend fun setEnabled(value: Boolean) {
        context.notificationDataStore.edit { it[KEY_ENABLED] = value }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("notifications_enabled")
    }
}
