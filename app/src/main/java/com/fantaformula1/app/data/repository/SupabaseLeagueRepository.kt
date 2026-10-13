package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.data.model.LeagueDto
import com.fantaformula1.app.data.model.LeagueMemberDto
import com.fantaformula1.app.data.model.ProfileDto
import com.fantaformula1.app.data.supabase.SupabaseHolder
import com.fantaformula1.app.data.util.AppJson
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Converte la riga del database in dominio (B22).
 *
 * Le regole malformate degradano sui default invece di far sparire l'intera
 * lega: la lega con regole di riserva resta visibile e l'admin puo' correggerle.
 */
internal fun LeagueDto.toDomainOrNull(): League? = runCatching {
    val rules = rules?.let { element ->
        runCatching { AppJson.decodeFromJsonElement(Rules.serializer(), element) }
            .getOrNull() ?: Rules()
    } ?: Rules()
    League(
        id = id,
        name = name,
        inviteCode = inviteCode,
        rules = rules,
        createdBy = createdBy,
    )
}.getOrNull()

/**
 * Implementazione Supabase di [LeagueRepository].
 *
 * Creazione e unione passano per funzioni SECURITY DEFINER (`create_league`,
 * `join_league_by_code`): sono atomiche e permettono la lettura del codice
 * invito anche a chi non e' ancora membro.
 */
class SupabaseLeagueRepository(
    private val holder: SupabaseHolder,
    private val authRepository: AuthRepository,
) : LeagueRepository {

    override suspend fun myLeagues(): List<League> {
        val client = requireClient()
        val rows = client.from("leagues").select()
            .decodeList<LeagueDto>()
        return rows.mapNotNull { it.toDomainOrNull() }
    }

    override suspend fun createLeague(name: String, rules: Rules): League {
        val client = requireClient()
        val trimmed = name.trim()
        if (trimmed.length < 2) throw UserException("Il nome della lega deve essere lungo almeno 2 caratteri")

        val leagueId = callUuidFunction(
            client = client,
            function = "create_league",
            parameters = buildJsonObject {
                put("p_name", trimmed)
                put("p_rules", encodeRules(rules))
            },
            emptyFallback = "create_league",
        )
        return league(leagueId) ?: throw UserException("Lega creata ma non trovata: ricarica la lista")
    }

    override suspend fun joinByCode(code: String): String {
        val client = requireClient()
        val normalized = code.trim().uppercase()
        if (normalized.length != 6) throw UserException("Il codice invito deve essere di 6 caratteri")

        return callUuidFunction(
            client = client,
            function = "join_league_by_code",
            parameters = buildJsonObject { put("p_code", normalized) },
            emptyFallback = "join_league_by_code",
        )
    }

    override suspend fun league(leagueId: String): League? {
        val client = requireClient()
        val row = client.from("leagues").select {
            filter { eq("id", leagueId) }
        }.decodeSingleOrNull<LeagueDto>()
        return row?.toDomainOrNull()
    }

    override suspend fun updateRules(leagueId: String, rules: Rules) {
        val client = requireClient()
        val errors = rules.validate()
        if (errors.isNotEmpty()) throw UserException(errors.first())
        client.from("leagues").update(
            mapOf("rules" to encodeRules(rules)),
        ) { filter { eq("id", leagueId) } }
    }

    override suspend fun members(leagueId: String): List<LeagueMember> {
        val client = requireClient()
        val memberRows = client.from("league_members").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<LeagueMemberDto>()

        if (memberRows.isEmpty()) return emptyList()

        // I nomi pubblici si recuperano a parte: evita di dover dipendere dalla
        // sintassi dei join annidati di PostgREST.
        val userIds = memberRows.map { it.userId }.distinct()
        val profiles = client.from("profiles").select {
            filter { isIn("id", userIds) }
        }.decodeList<ProfileDto>().associateBy { it.id }

        return memberRows
            .sortedBy { it.joinedAt ?: "" }
            .map { row ->
                LeagueMember(
                    leagueId = row.leagueId,
                    userId = row.userId,
                    role = if (row.role == "admin") MemberRole.ADMIN else MemberRole.PLAYER,
                    displayName = profiles[row.userId]?.displayName ?: "Giocatore",
                    joinedAt = row.joinedAt,
                )
            }
    }

    override suspend fun leaveLeague(leagueId: String) {
        val client = requireClient()
        val userId = authRepository.currentUserId()
            ?: throw UserException("Devi essere autenticato per abbandonare una lega")
        client.from("league_members").delete {
            filter {
                eq("league_id", leagueId)
                eq("user_id", userId)
            }
        }
    }

    override suspend fun isAdmin(leagueId: String): Boolean =
        members(leagueId).any { it.userId == authRepository.currentUserId() && it.isAdmin }

    // ---------------------------------------------------------------------

    private fun requireClient() = holder.get()
        ?: throw UserException("Configurazione Supabase mancante: apri le impostazioni e inserisci URL e chiave.")

    private fun encodeRules(rules: Rules): JsonElement =
        AppJson.encodeToJsonElement(Rules.serializer(), rules)

    /**
     * Chiama una funzione Postgres che restituisce un uuid e ne restituisce il
     * valore. La risposta di PostREST per uno scalare e' un JSON string:
     * si deserializza a mano per non dipendere dal tipo di decoder.
     */
    private suspend fun callUuidFunction(
        client: io.github.jan.supabase.SupabaseClient,
        function: String,
        parameters: JsonElement,
        emptyFallback: String,
    ): String {
        val raw = try {
            client.postgrest.rpc(function, parameters).data
        } catch (throwable: Throwable) {
            throw UserException(mapFunctionError(throwable, emptyFallback))
        }
        val value = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
        if (value.isNullOrBlank() || value == "null") {
            throw UserException(mapFunctionError(null, emptyFallback))
        }
        return value
    }

    private fun mapFunctionError(throwable: Throwable?, function: String): String {
        val raw = throwable?.message?.lowercase().orEmpty()
        return when {
            raw.contains("codice invito non valido") -> "Codice invito non valido"
            raw.contains("non valido") -> "Codice invito non valido"
            raw.contains("autenticato") -> "Devi essere autenticato per continuare"
            raw.contains("permission denied") || raw.contains("row-level security") ->
                "Non hai i permessi per questa operazione"
            raw.contains("duplicate") || raw.contains("unique") || raw.contains("already") ->
                "Sei gia' membro di questa lega"
            function == "join_league_by_code" ->
                "Codice invito non valido o lega non trovata"
            function == "create_league" ->
                "Impossibile creare la lega: riprova"
            else -> throwable?.message ?: "Si è verificato un errore"
        }
    }
}
