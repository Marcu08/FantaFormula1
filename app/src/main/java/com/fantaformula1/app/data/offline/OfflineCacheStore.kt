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
 */
class OfflineCacheStore(private val context: Context) {

    // ---------------------------------------------------------------------
    // Leghe
    // ---------------------------------------------------------------------

    suspend fun saveLeagues(leagues: List<League>) {
        editBestEffort { prefs ->
            prefs[KEY_LEAGUES] = OfflineCacheJson.encodeLeagues(leagues)
            prefs[KEY_CACHED_AT_LEAGUES] = now()
        }
    }

    suspend fun leagues(): Cached<List<League>>? =
        readValue(KEY_LEAGUES, KEY_CACHED_AT_LEAGUES, OfflineCacheJson::decodeLeagues)

    // ---------------------------------------------------------------------
    // Gare per lega
    // ---------------------------------------------------------------------

    suspend fun saveRaces(leagueId: String, races: List<Race>) {
        editBestEffort { prefs ->
            prefs[racesKey(leagueId)] = OfflineCacheJson.encodeRaces(races)
            prefs[timestampKey(racesKey(leagueId))] = now()
        }
    }

    suspend fun races(leagueId: String): Cached<List<Race>>? =
        readValue(racesKey(leagueId), timestampKey(racesKey(leagueId)), OfflineCacheJson::decodeRaces)

    /**
     * Cerca una gara in tutte le gare cacheate (serve alla schermata
     * pronostici, che conosce il raceId ma non la lega quando la rete manca).
     */
    suspend fun race(raceId: String): Cached<Race>? {
        val prefs = currentPrefs() ?: return null
        for ((key, value) in prefs.asMap()) {
            if (!key.name.startsWith(RACES_PREFIX)) continue
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

    suspend fun savePredictions(leagueId: String, predictions: Map<String, RacePrediction>) {
        editBestEffort { prefs ->
            prefs[predictionsKey(leagueId)] = OfflineCacheJson.encodePredictions(predictions)
            prefs[timestampKey(predictionsKey(leagueId))] = now()
        }
    }

    suspend fun predictions(leagueId: String): Cached<Map<String, RacePrediction>>? =
        readValue(
            predictionsKey(leagueId),
            timestampKey(predictionsKey(leagueId)),
            OfflineCacheJson::decodePredictions,
        )

    // ---------------------------------------------------------------------
    // Classifica per lega
    // ---------------------------------------------------------------------

    suspend fun saveStandings(leagueId: String, snapshot: SeasonStandingsSnapshot) {
        editBestEffort { prefs ->
            prefs[standingsKey(leagueId)] = OfflineCacheJson.encodeStandings(snapshot)
            prefs[timestampKey(standingsKey(leagueId))] = now()
        }
    }

    suspend fun standings(leagueId: String): Cached<SeasonStandingsSnapshot>? =
        readValue(
            standingsKey(leagueId),
            timestampKey(standingsKey(leagueId)),
            OfflineCacheJson::decodeStandings,
        )

    // ---------------------------------------------------------------------

    /** Timestamp (epoch ms) dell'ultimo aggiornamento riuscito della categoria. */
    suspend fun lastUpdate(category: OfflineCacheCategory, leagueId: String? = null): Long? {
        val timestampPrefKey = when (category) {
            OfflineCacheCategory.LEAGUES -> KEY_CACHED_AT_LEAGUES
            OfflineCacheCategory.RACES -> timestampKey(racesKey(leagueId.orEmpty()))
            OfflineCacheCategory.PREDICTIONS -> timestampKey(predictionsKey(leagueId.orEmpty()))
            OfflineCacheCategory.STANDINGS -> timestampKey(standingsKey(leagueId.orEmpty()))
        }
        val prefs = currentPrefs() ?: return null
        return prefs[timestampPrefKey]?.toLongOrNull()
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

    private fun racesKey(leagueId: String) = stringPreferencesKey("${RACES_PREFIX}$leagueId")

    private fun predictionsKey(leagueId: String) =
        stringPreferencesKey("${PREDICTIONS_PREFIX}$leagueId")

    private fun standingsKey(leagueId: String) = stringPreferencesKey("${STANDINGS_PREFIX}$leagueId")

    private fun timestampKey(key: Preferences.Key<String>) =
        stringPreferencesKey("${CACHED_AT_PREFIX}${key.name}")

    companion object {
        private val KEY_LEAGUES = stringPreferencesKey("leagues")
        private val KEY_CACHED_AT_LEAGUES = stringPreferencesKey("${CACHED_AT_PREFIX}leagues")
        private const val RACES_PREFIX = "races_"
        private const val PREDICTIONS_PREFIX = "predictions_"
        private const val STANDINGS_PREFIX = "standings_"
        private const val CACHED_AT_PREFIX = "cached_at_"
    }
}
