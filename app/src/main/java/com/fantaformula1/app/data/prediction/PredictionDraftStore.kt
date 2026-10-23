package com.fantaformula1.app.data.prediction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 * Chiave = raceId, valore = JSON della lista di [DraftEntry] (le stesse
 * bozze del ViewModel, gia' serializzabili).
 */
class PredictionDraftStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Bozza salvata localmente per la gara, oppure null. */
    suspend fun load(raceId: String): List<DraftEntry>? {
        val text = context.predictionDraftDataStore.data.first()[keyFor(raceId)]
            ?: return null
        return runCatching { json.decodeFromString<List<DraftEntry>>(text) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Salva (o sovrascrive) la bozza locale della gara. */
    suspend fun save(raceId: String, drafts: List<DraftEntry>) {
        context.predictionDraftDataStore.edit { prefs ->
            prefs[keyFor(raceId)] = json.encodeToString(drafts)
        }
    }

    /**
     * Rimuove la bozza: chiamata dopo un invio riuscito, cosi' la
     * prossima apertura parte dal pronostico salvato sul server.
     */
    suspend fun clear(raceId: String) {
        context.predictionDraftDataStore.edit { prefs ->
            prefs.remove(keyFor(raceId))
        }
    }

    private fun keyFor(raceId: String) = stringPreferencesKey("draft_$raceId")
}
