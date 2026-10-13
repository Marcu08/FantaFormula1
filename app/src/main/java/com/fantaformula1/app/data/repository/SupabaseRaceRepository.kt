package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.data.model.RaceDto
import com.fantaformula1.app.data.model.RaceResultRowDto
import com.fantaformula1.app.data.model.SeasonDto
import com.fantaformula1.app.data.supabase.SupabaseHolder
import com.fantaformula1.app.data.util.AppJson
import com.fantaformula1.app.data.util.nowIsoInstantString
import com.fantaformula1.app.data.util.parseInstantMillis
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

// Json condiviso con coerceInputValues (B22): il nome locale evita di
// riscrivere tutti i punti d'uso.
private val resultJson = AppJson

internal fun RaceDto.toDomain(): Race = Race(
    id = id,
    seasonId = seasonId,
    leagueId = leagueId,
    round = round,
    name = name,
    country = country,
    isSprint = isSprint,
    deadline = parseInstantMillis(deadline)?.let(Instant::ofEpochMilli),
    status = RaceStatus.fromStored(status),
)

@Serializable
private data class RaceInsertDto(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("league_id") val leagueId: String,
    val round: Int,
    val name: String,
    val country: String? = null,
    @SerialName("is_sprint") val isSprint: Boolean = false,
    val deadline: String? = null,
    val status: String = "scheduled",
)

@Serializable
private data class RaceUpdateDto(
    val round: Int,
    val name: String,
    val country: String? = null,
    @SerialName("is_sprint") val isSprint: Boolean = false,
    val deadline: String? = null,
)

@Serializable
private data class RaceStatusDto(val status: String)

@Serializable
private data class RaceResultInsertDto(
    val id: String,
    @SerialName("race_id") val raceId: String,
    @SerialName("league_id") val leagueId: String,
    val result: JsonElement,
    @SerialName("entered_by") val enteredBy: String? = null,
)

@Serializable
private data class RaceResultUpdateDto(
    val result: JsonElement,
    @SerialName("entered_by") val enteredBy: String? = null,
    @SerialName("entered_at") val enteredAt: String,
)

/**
 * Implementazione Supabase di [RaceRepository].
 *
 * L'autorizzazione e' interamente nella RLS: gare e risultati possono essere
 * scritti solo dagli admin della lega, letto da tutti i membri.
 */
class SupabaseRaceRepository(
    private val holder: SupabaseHolder,
    private val authRepository: AuthRepository,
) : RaceRepository {

    override suspend fun races(leagueId: String): List<Race> {
        val client = requireClient()
        val rows = client.from("races").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<RaceDto>()
        return rows.map { it.toDomain() }.sortedBy { it.round }
    }

    override suspend fun race(raceId: String): Race? {
        val client = requireClient()
        return client.from("races").select {
            filter { eq("id", raceId) }
        }.decodeSingleOrNull<RaceDto>()?.toDomain()
    }

    override suspend fun createRace(
        leagueId: String,
        name: String,
        round: Int,
        country: String?,
        isSprint: Boolean,
        deadline: String?,
    ): Race {
        val client = requireClient()
        val trimmed = name.trim()
        if (trimmed.length < 2) throw UserException("Il nome della gara deve essere lungo almeno 2 caratteri")
        if (round < 1) throw UserException("Il numero del round deve essere almeno 1")

        val seasonId = ensureSeason(client, leagueId)
        val id = UUID.randomUUID().toString()
        val dto = RaceInsertDto(
            id = id,
            seasonId = seasonId,
            leagueId = leagueId,
            round = round,
            name = trimmed,
            country = country?.trim()?.ifBlank { null },
            isSprint = isSprint,
            deadline = deadline?.trim()?.ifBlank { null },
        )
        try {
            client.from("races").insert(dto)
        } catch (throwable: Throwable) {
            throw UserException(mapWriteError(throwable, "creare la gara"))
        }
        return Race(
            id = id,
            seasonId = seasonId,
            leagueId = leagueId,
            round = round,
            name = trimmed,
            country = country?.trim()?.ifBlank { null },
            isSprint = isSprint,
            deadline = parseInstantMillis(deadline)?.let(Instant::ofEpochMilli),
            status = RaceStatus.SCHEDULED,
        )
    }

    override suspend fun updateRace(
        raceId: String,
        name: String,
        round: Int,
        country: String?,
        isSprint: Boolean,
        deadline: String?,
    ) {
        val client = requireClient()
        val trimmed = name.trim()
        if (trimmed.length < 2) throw UserException("Il nome della gara deve essere lungo almeno 2 caratteri")
        if (round < 1) throw UserException("Il numero del round deve essere almeno 1")
        try {
            client.from("races").update(
                RaceUpdateDto(
                    round = round,
                    name = trimmed,
                    country = country?.trim()?.ifBlank { null },
                    isSprint = isSprint,
                    deadline = deadline?.trim()?.ifBlank { null },
                ),
            ) { filter { eq("id", raceId) } }
        } catch (throwable: Throwable) {
            throw UserException(mapWriteError(throwable, "aggiornare la gara"))
        }
    }

    override suspend fun updateStatus(raceId: String, status: RaceStatus) {
        val client = requireClient()
        try {
            client.from("races").update(RaceStatusDto(status.stored)) {
                filter { eq("id", raceId) }
            }
        } catch (throwable: Throwable) {
            throw UserException(mapWriteError(throwable, "aggiornare lo stato della gara"))
        }
    }

    override suspend fun deleteRace(raceId: String) {
        val client = requireClient()
        try {
            client.from("races").delete { filter { eq("id", raceId) } }
        } catch (throwable: Throwable) {
            throw UserException(mapWriteError(throwable, "eliminare la gara"))
        }
    }

    override suspend fun result(raceId: String): RaceResultData? {
        val client = requireClient()
        val row = client.from("race_results").select {
            filter { eq("race_id", raceId) }
        }.decodeSingleOrNull<RaceResultRowDto>() ?: return null
        return runCatching {
            resultJson.decodeFromJsonElement(RaceResultData.serializer(), row.result)
        }.getOrNull()
    }

    override suspend fun saveResult(
        raceId: String,
        leagueId: String,
        result: RaceResultData,
    ) {
        val client = requireClient()
        val errors = RaceResultValidation.validate(result)
        if (errors.isNotEmpty()) throw UserException(errors.first())

        val normalized = result.copy(raceId = raceId)
        val encoded = resultJson.encodeToJsonElement(RaceResultData.serializer(), normalized)
        val enteredBy = authRepository.currentUserId()

        try {
            val updated = client.from("race_results").update(
                RaceResultUpdateDto(
                    result = encoded,
                    enteredBy = enteredBy,
                    enteredAt = nowIsoInstantString(),
                ),
            ) { filter { eq("race_id", raceId) } }.decodeList<RaceResultRowDto>()

            if (updated.isEmpty()) {
                client.from("race_results").insert(
                    RaceResultInsertDto(
                        id = UUID.randomUUID().toString(),
                        raceId = raceId,
                        leagueId = leagueId,
                        result = encoded,
                        enteredBy = enteredBy,
                    ),
                )
            }
            updateStatus(raceId, RaceStatus.COMPLETED)
        } catch (throwable: Throwable) {
            throw UserException(mapWriteError(throwable, "salvare il risultato"))
        }
    }

    // ---------------------------------------------------------------------

    /**
     * Ritorna la stagione attiva della lega, creandola se non esiste.
     * E' un dettaglio di implementazione dello schema: ogni gara deve
     * appartenere a una stagione.
     */
    private suspend fun ensureSeason(client: SupabaseClient, leagueId: String): String {
        val existing = client.from("seasons").select {
            filter {
                eq("league_id", leagueId)
                eq("is_active", true)
            }
        }.decodeList<SeasonDto>().firstOrNull { it.isActive }
        if (existing != null) return existing.id

        val name = java.time.Year.now().toString()
        val raw = try {
            client.postgrest.rpc(
                "create_default_season",
                buildJsonObject {
                    put("p_league", leagueId)
                    put("p_name", name)
                },
            ).data
        } catch (throwable: Throwable) {
            throw UserException(mapWriteError(throwable, "creare la stagione"))
        }
        val seasonId = runCatching {
            Json.parseToJsonElement(raw).jsonPrimitive.content
        }.getOrNull()
        if (seasonId.isNullOrBlank() || seasonId == "null") {
            throw UserException("Impossibile creare la stagione della lega")
        }
        return seasonId
    }

    private fun requireClient() = holder.get()
        ?: throw UserException("Configurazione Supabase mancante: apri le impostazioni e inserisci URL e chiave.")

    private fun mapWriteError(throwable: Throwable, action: String): String {
        val raw = throwable.message?.lowercase().orEmpty()
        return when {
            raw.contains("permission denied") || raw.contains("row-level security") ->
                "Solo un amministratore può $action"
            raw.contains("duplicate") || raw.contains("unique") || raw.contains("already exists") ->
                "Esiste già una gara con lo stesso numero di round"
            raw.contains("check constraint") || raw.contains("violates") ->
                "Dati della gara non validi"
            else -> throwable.message ?: "Impossibile $action"
        }
    }
}
