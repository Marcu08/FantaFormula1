package com.fantaformula1.app.data.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.data.util.AppJson
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.pendingWritesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_pending_writes",
)

/**
 * F3 — pronostico inviato offline e accodato per il ritentativo: il worker
 * periodico [PendingWritesWorker] lo riprovera' appena torna la rete.
 */
@Serializable
data class PendingPredictionWrite(
    val raceId: String,
    val prediction: RacePrediction,
    val queuedAtEpochMs: Long,
)

/** Parte JSON pura della coda (F3): testabile su JVM senza Context. */
object PendingWritesJson {

    fun encode(writes: List<PendingPredictionWrite>): String =
        AppJson.encodeToString(writes)

    fun decode(text: String): List<PendingPredictionWrite>? = try {
        AppJson.decodeFromString<List<PendingPredictionWrite>>(text)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/**
 * F3 — coda locale dei pronostici che la rete non ha fatto arrivare.
 * Una sola voce per gara: l'ultimo invio sostituisce il precedente.
 */
class PendingWritesStore(private val context: Context) {

    /** Accoda (o sostituisce) il pronostico della gara. */
    suspend fun enqueue(write: PendingPredictionWrite) {
        edit { prefs ->
            val current = decodeAll(prefs[KEY_WRITES])
            val withoutSameRace = current.filterNot { it.raceId == write.raceId }
            prefs[KEY_WRITES] = PendingWritesJson.encode(withoutSameRace + write)
        }
    }

    /** Tutte le scritture in attesa, in ordine di accodamento. */
    suspend fun all(): List<PendingPredictionWrite> = decodeAll(currentText())

    /** Rimuove la scrittura della gara (inviata, scaduta o non piu' valida). */
    suspend fun remove(raceId: String) {
        edit { prefs ->
            val current = decodeAll(prefs[KEY_WRITES]).filterNot { it.raceId == raceId }
            if (current.isEmpty()) {
                prefs.remove(KEY_WRITES)
            } else {
                prefs[KEY_WRITES] = PendingWritesJson.encode(current)
            }
        }
    }

    suspend fun clear() {
        edit { prefs -> prefs.remove(KEY_WRITES) }
    }

    private suspend fun currentText(): String? {
        val prefs = try {
            context.pendingWritesDataStore.data.first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return prefs?.get(KEY_WRITES)
    }

    private fun decodeAll(text: String?): List<PendingPredictionWrite> =
        text?.let(PendingWritesJson::decode).orEmpty()

    private suspend fun edit(block: suspend (MutablePreferences) -> Unit) {
        try {
            context.pendingWritesDataStore.edit(block)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Best-effort: la coda non deve mai far crashare il salvataggio.
        }
    }

    private companion object {
        val KEY_WRITES = stringPreferencesKey("pending_prediction_writes")
    }
}

/**
 * Messaggi (in tutta la catena di cause, lowercased) che indicano un errore
 * di rete: ktor/Supabase li avvolge spesso in eccezioni generiche, quindi si
 * controlla sia il tipo sia il testo. Cicli nelle cause non bloccano il
 * traversing (guard sui nodi gia' visitati).
 */
private val OFFLINE_ERROR_MARKERS = listOf(
    "unable to resolve host",
    "connection reset",
    "broken pipe",
    "timeout",
    "failed to connect",
    "network is unreachable",
    "software caused connection abort",
)

/**
 * F3 — true se l'errore (o una sue causa) e' un problema di rete: la
 * scrittura va accodata e riprovata, non mostrata come errore definitivo.
 * Funzione pura, testata su JVM.
 */
internal fun isOfflineError(throwable: Throwable): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = throwable
    while (current != null && seen.add(current)) {
        if (current is IOException) return true
        val message = current.message?.lowercase().orEmpty()
        if (OFFLINE_ERROR_MARKERS.any { message.contains(it) }) return true
        current = current.cause
    }
    return false
}
