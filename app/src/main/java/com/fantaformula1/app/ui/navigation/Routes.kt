package com.fantaformula1.app.ui.navigation

/** Rotte di navigazione dell'app. */
object Routes {
    const val CONFIG = "config"
    const val AUTH = "auth"
    const val HOME = "home"

    const val LEAGUE = "league/{leagueId}"
    fun league(leagueId: String) = "league/$leagueId"

    const val LEAGUE_MEMBERS = "league/{leagueId}/members"
    fun leagueMembers(leagueId: String) = "league/$leagueId/members"

    const val LEAGUE_RULES = "league/{leagueId}/rules"
    fun leagueRules(leagueId: String) = "league/$leagueId/rules"

    const val LEAGUE_STANDINGS = "league/{leagueId}/standings"
    fun leagueStandings(leagueId: String) = "league/$leagueId/standings"

    const val RACE = "race/{raceId}"
    fun race(raceId: String) = "race/$raceId"

    const val RACE_RESULTS = "race/{raceId}/results"
    fun raceResults(raceId: String) = "race/$raceId/results"

    const val RACE_PREDICTION = "race/{raceId}/prediction"
    fun racePrediction(raceId: String) = "race/$raceId/prediction"
}
