package com.fantaformula1.app.data.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.SeasonStandingsSnapshot
import com.fantaformula1.app.data.model.RaceDto
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.toDomain
import com.fantaformula1.app.data.util.AppJson
import com.fantaformula1.app.data.util.toIsoInstantString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.offlineCacheDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_offline_cache",
)

/** Voce di cache con l'istante in cui e' stata salvata (epoch ms). */
data class Cached<T>(val value: T, val cachedAtEpochMs: Long)

/** Categorie di dati memorizzate nella cache offline (F3). */
enum class OfflineCacheCategory { LEAGUES, RACES, PREDICTIONS, STANDINGS }

/**
 * Riga minimale della cache "tuoi pronostici" (F3): raceId + pronostico.
 * I DTO Supabase interni restano nel loro modulo: qui serve solo cio' che
 * serve a ripristinare la schermata offline.
 */
@Serializable
data class CachedPrediction(
    val raceId: String,
    val prediction: RacePrediction,
)

/**
 * Parte JSON pura della cache offline (F3): testabile su JVM senza
 * `android.content.Context`. Le decodifiche rotte restituiscono null (mai
 * crash): la cache e' best-effort per definizione.
 */
object OfflineCacheJson {

    fun encodeLeagues(leagues: List<League>): String =
        AppJson.encodeToString(leagues)

    fun decodeLeagues(text: String): List<League>? = decodeOrNull {
        AppJson.decodeFromString<List<League>>(text)
    }

    /** Le gare del dominio (con `Instant`) passano dal DTO serializzabile. */
    fun encodeRaces(races: List<Race>): String =
        AppJson.encodeToString(races.map { it.toCacheDto() })

    fun decodeRaces(text: String): List<Race>? = decodeOrNull {
        AppJson.decodeFromString<List<RaceDto>>(text).map { it.toDomain() }
    }

    fun encodePredictions(predictions: Map<String, RacePrediction>): String =
        AppJson.encodeToString(
            predictions.map { (raceId, prediction) -> CachedPrediction(raceId, prediction) },
        )

    fun decodePredictions(text: String): Map<String, RacePrediction>? = decodeOrNull {
        AppJson.decodeFromString<List<CachedPrediction>>(text)
            // Come nei repository (B4): il raceId affidabile e' la chiave.
            .associate { it.raceId to it.prediction.copy(raceId = it.raceId) }
    }

    fun encodeStandings(snapshot: SeasonStandingsSnapshot): String =
        AppJson.encodeToString(snapshot)

    fun decodeStandings(text: String): SeasonStandingsSnapshot? = decodeOrNull {
        AppJson.decodeFromString<SeasonStandingsSnapshot>(text)
    }

    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/** Gara di dominio -> DTO serializzabile (deadline come stringa ISO). */
private fun Race.toCacheDto() = RaceDto(
    id = id,
    seasonId = seasonId,
    leagueId = leagueId,
    round = round,
    name = name,
    country = country,
    isSprint = isSprint,
    deadline = deadline?.toEpochMilli()?.toIsoInstantString(),
    status = status.stored,
)

/**
 * F3 — cache di sola lettura su DataStore (scelta "semplice": nessuna Room,
 * stesso pattern di `F1SyncPrefs`/`PredictionDraftStore`).
 *
 * Contiene, per categoria, l'ultimo dato caricato dalla rete:
 *  - leghe (`List<League>` con regole);
 *  - gare per lega (DTO `RaceDto`, mai `Race` diretto);
 *  - "tuoi pronostici" per lega;
 *  - classifica per lega (`SeasonStandingsSnapshot`).
 *
 * REGOLA CHIAVE: la cache viene SEMPRE aggiornata quando la rete funziona e
 * viene letta SOLO quando la rete fallisce ("stale-while-error").
 * Ogni voce ha il proprio timestamp `cached_at_*` (epoch ms). Una
 * decodifica corrotta degrada a null, mai a crash.
 *
 * Le chiavi includono l'utente autenticato: la cache di A non deve essere
 * mostrata a B sullo stesso dispositivo. Le chiavi legacy non namespace non
 * vengono lette; [clearAll] le elimina insieme a tutto il resto.
 */
class OfflineCacheStore(
    private val context: Context,
    private val currentUserId: () -> String?,
) {

    // ---------------------------------------------------------------------
    // Leghe
    // ---------------------------------------------------------------------

    suspend fun saveLeagues(
        leagues: List<League>,
        expectedUserId: String? = null,
    ) {
        val key = scopedKey("leagues", expectedUserId) ?: return
        editBestEffort { prefs ->
            prefs[key] = OfflineCacheJson.encodeLeagues(leagues)
            prefs[timestampKey(key)] = now()
        }
    }

    suspend fun leagues(expectedUserId: String? = null): Cached<List<League>>? =
        scopedKey("leagues", expectedUserId)?.let {
            readValue(it, timestampKey(it), OfflineCacheJson::decodeLeagues)
        }

    // ---------------------------------------------------------------------
    // Gare per lega
    // ---------------------------------------------------------------------

    suspend fun saveRaces(
        leagueId: String,
        races: List<Race>,
        expectedUserId: String? = null,
    ) {
        val key = scopedKey("${RACES_PREFIX}$leagueId", expectedUserId) ?: return
        editBestEffort { prefs ->
            prefs[key] = OfflineCacheJson.encodeRaces(races)
            prefs[timestampKey(key)] = now()
        }
    }

    suspend fun races(
        leagueId: String,
        expectedUserId: String? = null,
    ): Cached<List<Race>>? =
        scopedKey("${RACES_PREFIX}$leagueId", expectedUserId)?.let {
            readValue(it, timestampKey(it), OfflineCacheJson::decodeRaces)
        }

    /**
     * Cerca una gara in tutte le gare cacheate dell'utente corrente (serve
     * alla schermata pronostici, che conosce il raceId ma non la lega quando
     * la rete manca).
     */
    suspend fun race(
        raceId: String,
        expectedUserId: String? = null,
    ): Cached<Race>? {
        val prefix = scopedPrefix(RACES_PREFIX, expectedUserId) ?: return null
        val prefs = currentPrefs() ?: return null
        for ((key, value) in prefs.asMap()) {
            if (!key.name.startsWith(prefix)) continue
            val text = value as? String ?: continue
            val races = OfflineCacheJson.decodeRaces(text) ?: continue
            val match = races.firstOrNull { it.id == raceId } ?: continue
            val cachedAt = prefs[stringPreferencesKey("${CACHED_AT_PREFIX}${key.name}")]
                ?.toLongOrNull() ?: 0L
            return Cached(match, cachedAt)
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Tuoi pronostici per lega
    // ---------------------------------------------------------------------

    suspend fun savePredictions(
        leagueId: String,
        predictions: Map<String, RacePrediction>,
        expectedUserId: String? = null,
    ) {
        val key = scopedKey("${PREDICTIONS_PREFIX}$leagueId", expectedUserId) ?: return
        editBestEffort { prefs ->
            prefs[key] = OfflineCacheJson.encodePredictions(predictions)
            prefs[timestampKey(key)] = now()
        }
    }

    suspend fun predictions(
        leagueId: String,
        expectedUserId: String? = null,
    ): Cached<Map<String, RacePrediction>>? =
        scopedKey("${PREDICTIONS_PREFIX}$leagueId", expectedUserId)?.let {
            readValue(
                it,
                timestampKey(it),
                OfflineCacheJson::decodePredictions,
            )
        }

    // ---------------------------------------------------------------------
    // Classifica per lega
    // ---------------------------------------------------------------------

    suspend fun saveStandings(
        leagueId: String,
        snapshot: SeasonStandingsSnapshot,
        expectedUserId: String? = null,
    ) {
        val key = scopedKey("${STANDINGS_PREFIX}$leagueId", expectedUserId) ?: return
        editBestEffort { prefs ->
            prefs[key] = OfflineCacheJson.encodeStandings(snapshot)
            prefs[timestampKey(key)] = now()
        }
    }

    suspend fun standings(
        leagueId: String,
        expectedUserId: String? = null,
    ): Cached<SeasonStandingsSnapshot>? =
        scopedKey("${STANDINGS_PREFIX}$leagueId", expectedUserId)?.let {
            readValue(
                it,
                timestampKey(it),
                OfflineCacheJson::decodeStandings,
            )
        }

    // ---------------------------------------------------------------------

    /** Timestamp (epoch ms) dell'ultimo aggiornamento riuscito della categoria. */
    suspend fun lastUpdate(category: OfflineCacheCategory, leagueId: String? = null): Long? {
        val base = when (category) {
            OfflineCacheCategory.LEAGUES -> "leagues"
            OfflineCacheCategory.RACES -> "${RACES_PREFIX}${leagueId.orEmpty()}"
            OfflineCacheCategory.PREDICTIONS -> "${PREDICTIONS_PREFIX}${leagueId.orEmpty()}"
            OfflineCacheCategory.STANDINGS -> "${STANDINGS_PREFIX}${leagueId.orEmpty()}"
        }
        val key = scopedKey(base) ?: return null
        val prefs = currentPrefs() ?: return null
        return prefs[timestampKey(key)]?.toLongOrNull()
    }

    /**
     * Cancella l'intera cache locale. Usato al logout: elimina anche le
     * chiavi legacy non namespace, che non devono sopravvivere al cambio
     * account.
     */
    suspend fun clearAll() {
        editBestEffort { prefs -> prefs.clear() }
    }

    // ---------------------------------------------------------------------

    private suspend fun currentPrefs(): Preferences? = try {
        context.offlineCacheDataStore.data.first()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // DataStore corrotto o IO fallito: la cache decade a "assente".
        null
    }

    private suspend fun <T> readValue(
        key: Preferences.Key<String>,
        cachedAtKey: Preferences.Key<String>,
        decode: (String) -> T?,
    ): Cached<T>? {
        val prefs = currentPrefs() ?: return null
        val text = prefs[key] ?: return null
        val value = decode(text) ?: return null
        return Cached(value, prefs[cachedAtKey]?.toLongOrNull() ?: 0L)
    }

    private suspend fun editBestEffort(block: suspend (MutablePreferences) -> Unit) {
        try {
            context.offlineCacheDataStore.edit(block)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Salvataggio cache best-effort: non deve mai rompere il flusso.
        }
    }

    private fun now() = System.currentTimeMillis().toString()

    private fun scopedKey(
        base: String,
        expectedUserId: String? = null,
    ): Preferences.Key<String>? {
        val current = currentUserId()
        if (expectedUserId != null && current != expectedUserId) return null
        return scopedPreferenceName(base, current)?.let(::stringPreferencesKey)
    }

    private fun scopedPrefix(
        base: String,
        expectedUserId: String? = null,
    ): String? {
        val current = currentUserId()
        if (expectedUserId != null && current != expectedUserId) return null
        return current?.trim()?.takeIf { it.isNotEmpty() }?.let { "u_${it}_$base" }
    }

    private fun timestampKey(key: Preferences.Key<String>) =
        stringPreferencesKey("${CACHED_AT_PREFIX}${key.name}")

    companion object {
        private const val RACES_PREFIX = "races_"
        private const val PREDICTIONS_PREFIX = "predictions_"
        private const val STANDINGS_PREFIX = "standings_"
        private const val CACHED_AT_PREFIX = "cached_at_"
    }
}
