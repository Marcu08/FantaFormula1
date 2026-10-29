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
 *
 * [userId] e' l'utente che ha creato la scrittura. Le voci legacy (senza
 * userId, scritte da versioni precedenti) non appartengono a nessuno: non
 * vengono mai inviate sotto l'account successivo.
 */
@Serializable
data class PendingPredictionWrite(
    val raceId: String,
    val prediction: RacePrediction,
    val queuedAtEpochMs: Long,
    val userId: String? = null,
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
 * True solo quando l'utente corrente e' noto e coincide con l'autore.
 * Una voce legacy con `userId == null` non e' verificabile e quindi non
 * appartiene a nessun account.
 */
internal fun PendingPredictionWrite.belongsTo(userId: String?): Boolean =
    userId != null && userId.isNotBlank() && this.userId == userId

/** Filtro puro usato dallo store e testabile senza Android. */
internal fun pendingWritesForUser(
    writes: List<PendingPredictionWrite>,
    userId: String?,
): List<PendingPredictionWrite> = writes.filter { it.belongsTo(userId) }

/**
 * Sostituisce solo la voce della stessa gara dell'utente indicato. Le voci di
 * altri account restano intatte: un enqueue non deve cancellare il lavoro di
 * un altro utente sullo stesso dispositivo.
 */
internal fun upsertPendingWriteForUser(
    writes: List<PendingPredictionWrite>,
    replacement: PendingPredictionWrite,
    userId: String,
): List<PendingPredictionWrite> {
    val owned = replacement.copy(userId = userId)
    return writes.filterNot { it.raceId == owned.raceId && it.belongsTo(userId) } + owned
}

/**
 * F3 — coda locale dei pronostici che la rete non ha fatto arrivare.
 * Una sola voce per gara: l'ultimo invio sostituisce il precedente.
 *
 * La coda e' per utente: [currentUserId] viene letto al momento
 * dell'operazione, cosi' un cambio account non puo' riusare la coda del
 * precedente.
 */
class PendingWritesStore(
    private val context: Context,
    private val currentUserId: () -> String?,
) {

    /**
     * Accoda (o sostituisce) il pronostico della gara per l'utente corrente.
     * [expectedUserId] e' l'utente che ha avviato il salvataggio: se la
     * sessione e' cambiata nel frattempo, la coda non viene scritta.
     */
    suspend fun enqueue(
        write: PendingPredictionWrite,
        expectedUserId: String? = null,
    ): Boolean {
        val userId = currentUserId()?.trim().orEmpty()
        if (userId.isEmpty()) return false
        if (expectedUserId != null && expectedUserId != userId) return false
        return edit { prefs ->
            val current = decodeAll(prefs[KEY_WRITES])
            prefs[KEY_WRITES] = PendingWritesJson.encode(
                upsertPendingWriteForUser(current, write, userId),
            )
        }
    }

    /** Tutte le scritture dell'utente corrente, in ordine di accodamento. */
    suspend fun all(): List<PendingPredictionWrite> {
        val userId = currentUserId()?.trim().orEmpty()
        if (userId.isEmpty()) return emptyList()
        return pendingWritesForUser(decodeAll(currentText()), userId)
    }

    /**
     * Rimuove la scrittura della gara dell'utente corrente (inviata, scaduta
     * o non piu' valida). Le voci di altri account restano intatte.
     */
    suspend fun remove(raceId: String) {
        val userId = currentUserId()?.trim().orEmpty()
        if (userId.isEmpty()) return
        edit { prefs ->
            val current = decodeAll(prefs[KEY_WRITES]).filterNot {
                it.raceId == raceId && it.belongsTo(userId)
            }
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

    private suspend fun edit(block: suspend (MutablePreferences) -> Unit): Boolean {
        return try {
            context.pendingWritesDataStore.edit(block)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Best-effort: la coda non deve mai far crashare il salvataggio.
            false
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
 * F3 — true se l'errore (o una sua causa) e' un problema di rete: la
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
