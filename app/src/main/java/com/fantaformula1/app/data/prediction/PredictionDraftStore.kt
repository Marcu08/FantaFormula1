package com.fantaformula1.app.data.prediction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fantaformula1.app.data.offline.scopedPreferenceName
import com.fantaformula1.app.ui.predictions.DraftEntry
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.predictionDraftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_prediction_drafts",
)

/**
 * U5: bozze dei pronostici salvate in locale mentre l'utente compila.
 *
 * L'obiettivo e' che chiudere l'app (o perdere la rete) non vada a scapito
 * del lavoro gia' fatto: la bozza viene ripristinata alla riapertura della
 * schermata, finche' il pronostico non viene inviato con successo.
 *
 * Le chiavi includono l'utente autenticato: una bozza lasciata da A non deve
 * riempire il form di B sullo stesso dispositivo. Senza utente le operazioni
 * sono no-op (nessuna chiave legacy condivisa).
 */
class PredictionDraftStore(
    private val context: Context,
    private val currentUserId: () -> String?,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Bozza salvata localmente per la gara dell'utente atteso, oppure null. */
    suspend fun load(
        raceId: String,
        expectedUserId: String? = null,
    ): List<DraftEntry>? {
        val key = keyFor(raceId, expectedUserId) ?: return null
        val text = context.predictionDraftDataStore.data.first()[key]
            ?: return null
        return runCatching { json.decodeFromString<List<DraftEntry>>(text) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Salva (o sovrascrive) la bozza locale della gara per l'utente atteso. */
    suspend fun save(
        raceId: String,
        drafts: List<DraftEntry>,
        expectedUserId: String? = null,
    ) {
        val key = keyFor(raceId, expectedUserId) ?: return
        context.predictionDraftDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(drafts)
        }
    }

    /**
     * Rimuove la bozza: chiamata dopo un invio riuscito, cosi' la
     * prossima apertura parte dal pronostico salvato sul server.
     */
    suspend fun clear(
        raceId: String,
        expectedUserId: String? = null,
    ) {
        val key = keyFor(raceId, expectedUserId) ?: return
        context.predictionDraftDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    /**
     * Cancella tutte le bozze locali. Usato al logout: oltre alle chiavi
     * per-utente, elimina anche eventuali bozze legacy non namespace.
     */
    suspend fun clearAll() {
        context.predictionDraftDataStore.edit { prefs -> prefs.clear() }
    }

    private fun keyFor(
        raceId: String,
        expectedUserId: String? = null,
    ): Preferences.Key<String>? {
        val current = currentUserId()
        if (expectedUserId != null && current != expectedUserId) return null
        return scopedPreferenceName("draft_$raceId", current)
            ?.let(::stringPreferencesKey)
    }
}
