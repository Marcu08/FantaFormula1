package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.Rules

/** Ruolo di un membro nella lega. */
enum class MemberRole { ADMIN, PLAYER }

/** Lega con le sue regole decodificate. */
data class League(
    val id: String,
    val name: String,
    val inviteCode: String,
    val rules: Rules,
    val createdBy: String?,
)

/** Membro della lega con il nome pubblico del profilo. */
data class LeagueMember(
    val leagueId: String,
    val userId: String,
    val role: MemberRole,
    val displayName: String,
    val joinedAt: String?,
) {
    val isAdmin: Boolean get() = role == MemberRole.ADMIN
}

/** Operazioni sulle leghe. Interfaccia separata per poterla sostituire con un fake nei test. */
interface LeagueRepository {

    /** Leghe a cui appartiene l'utente corrente. */
    suspend fun myLeagues(): List<League>

    /** Crea una lega (l'utente corrente diventa automaticamente admin) e restituisce la lega. */
    suspend fun createLeague(name: String, rules: Rules = Rules.default()): League

    /** Si unisce a una lega esistente usando il codice invito. Restituisce l'id della lega. */
    suspend fun joinByCode(code: String): String

    /** Carica una lega per id, oppure null se non trovata o non accessibile. */
    suspend fun league(leagueId: String): League?

    /** Aggiorna le regole della lega (solo admin, lato server il RLS blocca gli altri). */
    suspend fun updateRules(leagueId: String, rules: Rules)

    /** Membri della lega con il relativo nome pubblico. */
    suspend fun members(leagueId: String): List<LeagueMember>

    /** Abbandona la lega corrente. */
    suspend fun leaveLeague(leagueId: String)

    /** true se l'utente corrente e' admin della lega. */
    suspend fun isAdmin(leagueId: String): Boolean
}
