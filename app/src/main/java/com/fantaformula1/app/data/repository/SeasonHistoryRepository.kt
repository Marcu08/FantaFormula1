package com.fantaformula1.app.data.repository

import com.fantaformula1.app.data.model.SeasonDto
import com.fantaformula1.app.data.supabase.SupabaseHolder
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Stagione chiusa e consultabile nello storico: nome, data di archiviazione
 * e (se disponibile) la classifica finale congelata nello snapshot.
 */
data class PastSeason(
    val seasonId: String,
    val name: String,
    val archivedAt: String?,
    val standingsJson: JsonElement?,
)

/** Stagione attuale della lega: serve a nominare correttamente lo snapshot. */
data class CurrentSeason(
    val seasonId: String,
    val name: String,
)

/**
 * Storico stagioni (F2): alla chiusura di una stagione la classifica finale
 * viene congelata in `season_snapshots` (riga immutabile) e le gare della
 * stagione escono dalle schermate correnti.
 */
interface SeasonHistoryRepository {

    /** Stagioni archiviate della lega, ordinate per data di archiviazione (piu' recente prima). */
    suspend fun pastSeasons(leagueId: String): List<PastSeason>

    /** Stagione attiva della lega (id + nome), oppure null se non ce n'e' una. */
    suspend fun currentSeason(leagueId: String): CurrentSeason?

    /**
     * Congela la classifica finale della stagione e la disattiva.
     * Gli errori (già archiviata, permessi RLS) arrivano come [UserException]
     * con messaggio in italiano.
     */
    suspend fun archiveSeason(
        leagueId: String,
        seasonId: String,
        seasonName: String,
        standings: JsonElement,
    )
}

/**
 * Riga di `season_snapshots`. I nomi dei campi sono in snake_case per
 * coincidere con le colonne (stesso criterio di [com.fantaformula1.app.data.model.SeasonDto]).
 *
 * `archived_at` ha default a null solo per la decodifica: l'insert lo omette
 * (il client usa encodeDefaults=false) e la colonna usa il default del server.
 */
@Serializable
internal data class SeasonSnapshotDto(
    @SerialName("season_id") val seasonId: String,
    @SerialName("league_id") val leagueId: String,
    @SerialName("season_name") val seasonName: String,
    val standings: JsonElement,
    @SerialName("archived_by") val archivedBy: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
)

/**
 * PATCH su `seasons` per disattivare la stagione dopo l'archiviazione.
 * Il campo NON ha default: con encodeDefaults=false un default a false
 * verrebbe omesso e la colonna resterebbe invariata (stesso problema di
 * B27 / RaceUpdateDto.deadline).
 */
@Serializable
internal data class SeasonUpdateDto(
    @SerialName("is_active") val isActive: Boolean,
)

/**
 * Unisce stagioni e snapshot per season_id: lo storico contiene gli snapshot
 * (fonte del nome congelato e della classifica) piu' le eventuali stagioni
 * disattivate senza snapshot. Ordinamento per `archived_at` decrescente, le
 * righe senza data finiscono in coda.
 *
 * Funzione pura (testata su JVM).
 */
internal fun mergePastSeasons(
    seasons: List<SeasonDto>,
    snapshots: List<SeasonSnapshotDto>,
): List<PastSeason> {
    val snapshotIds = snapshots.map { it.seasonId }.toSet()
    val fromSnapshots = snapshots.map { snapshot ->
        PastSeason(
            seasonId = snapshot.seasonId,
            name = snapshot.seasonName,
            archivedAt = snapshot.archivedAt,
            standingsJson = snapshot.standings,
        )
    }
    val inactiveWithoutSnapshot = seasons
        .filter { !it.isActive && it.id !in snapshotIds }
        .map { season ->
            PastSeason(
                seasonId = season.id,
                name = season.name,
                archivedAt = null,
                standingsJson = null,
            )
        }
    return (fromSnapshots + inactiveWithoutSnapshot)
        .sortedByDescending { it.archivedAt.orEmpty() }
}

/**
 * Mappa gli errori di scrittura dello storico stagioni in messaggi
 * comprensibili (stesso criterio di [mapRaceWriteError]).
 */
internal fun mapSeasonHistoryError(rawMessage: String?, action: String): String {
    val raw = rawMessage?.lowercase().orEmpty()
    return when {
        raw.contains("duplicate") || raw.contains("unique") || raw.contains("already exists") ->
            "La stagione è già stata archiviata"

        raw.contains("permission denied") || raw.contains("row-level security") ||
            raw.contains("solo un amministratore") ->
            "Solo un amministratore può $action"

        raw.contains("null value") || raw.contains("not-null") ->
            "Dati della stagione non validi"

        else -> rawMessage ?: "Impossibile $action"
    }
}

/**
 * Implementazione Supabase di [SeasonHistoryRepository].
 *
 * L'autorizzazione e' interamente nella RLS di 0012: leggono i membri della
 * lega, scrive solo l'amministratore (e solo per se stesso).
 */
class SupabaseSeasonHistoryRepository(
    private val holder: SupabaseHolder,
    private val authRepository: AuthRepository,
) : SeasonHistoryRepository {

    override suspend fun pastSeasons(leagueId: String): List<PastSeason> {
        val client = requireClient()
        val seasons = client.from("seasons").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<SeasonDto>()
        val snapshots = client.from("season_snapshots").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<SeasonSnapshotDto>()
        return mergePastSeasons(seasons, snapshots)
    }

    override suspend fun currentSeason(leagueId: String): CurrentSeason? {
        val client = requireClient()
        return client.from("seasons").select {
            filter {
                eq("league_id", leagueId)
                eq("is_active", true)
            }
        }.decodeList<SeasonDto>().firstOrNull { it.isActive }?.let { season ->
            CurrentSeason(seasonId = season.id, name = season.name)
        }
    }

    override suspend fun archiveSeason(
        leagueId: String,
        seasonId: String,
        seasonName: String,
        standings: JsonElement,
    ) {
        val client = requireClient()
        val archivedBy = authRepository.currentUserId()
            ?: throw UserException("Devi essere autenticato per archiviare la stagione")

        // 1) Congela la classifica finale (PK = season_id: un doppio tentativo
        //    viene rifiutato dalla RLS/vincolo con "La stagione e' gia' archiviata").
        try {
            client.from("season_snapshots").insert(
                SeasonSnapshotDto(
                    seasonId = seasonId,
                    leagueId = leagueId,
                    seasonName = seasonName,
                    standings = standings,
                    archivedBy = archivedBy,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw UserException(mapSeasonHistoryError(throwable.message, "archiviare la stagione"))
        }

        // 2) Disattiva la stagione: le sue gare escono dalle schermate correnti.
        try {
            client.from("seasons").update(SeasonUpdateDto(isActive = false)) {
                filter { eq("id", seasonId) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw UserException(mapSeasonHistoryError(throwable.message, "archiviare la stagione"))
        }
    }

    private fun requireClient() = holder.get()
        ?: throw UserException("Configurazione Supabase mancante: apri le impostazioni e inserisci URL e chiave.")
}
