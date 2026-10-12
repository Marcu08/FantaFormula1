package com.fantaformula1.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTO lato database. I nomi dei campi sono in snake_case perché coincidono
 * con le colonne Postgres: così la serializzazione funziona senza strategie
 * di naming.
 */

@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class LeagueDto(
    val id: String,
    val name: String,
    @SerialName("invite_code") val inviteCode: String,
    val rules: JsonElement? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class LeagueMemberDto(
    @SerialName("league_id") val leagueId: String,
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: String? = null,
)

@Serializable
data class LeagueMemberWithProfileDto(
    @SerialName("league_id") val leagueId: String,
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: String? = null,
    val profiles: ProfileDto? = null,
)

@Serializable
data class SeasonDto(
    val id: String,
    @SerialName("league_id") val leagueId: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class RaceDto(
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
data class RaceResultRowDto(
    val id: String,
    @SerialName("race_id") val raceId: String,
    @SerialName("league_id") val leagueId: String,
    val result: JsonElement,
    @SerialName("entered_at") val enteredAt: String? = null,
)

@Serializable
data class PredictionRowDto(
    val id: String,
    @SerialName("race_id") val raceId: String,
    @SerialName("league_id") val leagueId: String,
    @SerialName("user_id") val userId: String,
    val content: JsonElement,
    @SerialName("submitted_at") val submittedAt: String? = null,
)
