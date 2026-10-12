package com.fantaformula1.app.ui.standings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.LeagueStandings
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.ScoringEngine
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.RaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Punteggi di una singola gara, per giocatore. */
data class RacePlayerScore(
    val userId: String,
    val displayName: String,
    val points: Double,
)

enum class StandingsTab { OVERALL, PER_RACE }

data class StandingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val league: League? = null,
    val rows: List<ScoringEngine.StandingsRow> = emptyList(),
    val races: List<Race> = emptyList(),
    val resultsByRace: Map<String, RaceResultData> = emptyMap(),
    /** raceId -> punteggi dei giocatori, ordinati dal più alto. */
    val scoresByRace: Map<String, List<RacePlayerScore>> = emptyMap(),
    val tab: StandingsTab = StandingsTab.OVERALL,
    val selectedRaceId: String? = null,
)

class StandingsViewModel(
    private val leagueDataRepository: LeagueDataRepository,
    private val leagueRepository: LeagueRepository,
    private val raceRepository: RaceRepository,
    private val leagueId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StandingsUiState())
    val uiState: StateFlow<StandingsUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = it.rows.isEmpty(), error = null) }
            runCatching {
                val league = leagueRepository.league(leagueId)
                val members = leagueRepository.members(leagueId)
                val races = raceRepository.races(leagueId)
                val data = leagueDataRepository.raceData(leagueId)
                StandingInputs(
                    league = league,
                    memberNames = members.associate { it.userId to it.displayName },
                    races = races,
                    data = data,
                )
            }.onSuccess { inputs ->
                val rules = inputs.league?.rules ?: Rules()
                val results = inputs.data
                    .mapNotNull { datum -> datum.result?.let { datum.raceId to it } }
                    .toMap()

                val predictionsByUser = inputs.data
                    .flatMap { datum ->
                        datum.predictions.map { (userId, prediction) -> userId to prediction }
                    }
                    .groupBy({ it.first }, { it.second })

                val snapshot = LeagueStandings.build(
                    rules = rules,
                    memberNames = inputs.memberNames,
                    races = inputs.races,
                    results = results,
                    predictionsByUser = predictionsByUser,
                )

                val scoresByRace = snapshot.scoresByRace.mapValues { (_, scores) ->
                    scores.map { score ->
                        RacePlayerScore(
                            userId = score.userId,
                            displayName = inputs.memberNames[score.userId] ?: "Giocatore",
                            points = score.points,
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        league = inputs.league,
                        rows = snapshot.rows,
                        races = inputs.races,
                        resultsByRace = results,
                        scoresByRace = scoresByRace,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = throwable.message ?: "Si è verificato un errore",
                    )
                }
            }
        }
    }

    fun selectTab(tab: StandingsTab) {
        _uiState.update { it.copy(tab = tab, selectedRaceId = null) }
    }

    fun selectRace(raceId: String?) {
        _uiState.update { it.copy(selectedRaceId = raceId) }
    }
}

/** Dati grezzi calcolati in un colpo solo, poi passati al motore. */
private data class StandingInputs(
    val league: League?,
    val memberNames: Map<String, String>,
    val races: List<Race>,
    val data: List<com.fantaformula1.app.data.repository.LeagueRaceData>,
)
