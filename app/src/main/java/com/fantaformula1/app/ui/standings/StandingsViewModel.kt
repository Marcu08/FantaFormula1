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
    /** Pronostici/esiti non decodificabili, mostrati come avviso (B22). */
    val unreadablePredictions: Int = 0,
    val unreadableResults: Int = 0,
    /** U8: userId dell'utente corrente, per evidenziare la sua riga. */
    val myUserId: String? = null,
    /**
     * U8: variazione di posizione dopo l'ultima gara con risultato
     * (userId -> posti guadagnati, positivo = risalito). Mappa vuota con
     * meno di due gare completate.
     */
    val rankDeltas: Map<String, Int> = emptyMap(),
    /** U8: riga espansa nel tab classifica (userId), per il dettaglio per gara. */
    val expandedUserId: String? = null,
)

class StandingsViewModel(
    private val leagueDataRepository: LeagueDataRepository,
    private val leagueRepository: LeagueRepository,
    private val raceRepository: RaceRepository,
    private val authRepository: com.fantaformula1.app.data.repository.AuthRepository,
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
                val results = inputs.data.races
                    .mapNotNull { datum -> datum.result?.let { datum.raceId to it } }
                    .toMap()

                val predictionsByUser = inputs.data.races
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
                            // Come nelle righe generali: un ex-membro con
                            // pronostici niente nome noto.
                            displayName = inputs.memberNames[score.userId]
                                ?: ScoringEngine.EX_MEMBER_NAME,
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
                        unreadablePredictions = inputs.data.unreadablePredictions,
                        unreadableResults = inputs.data.unreadableResults,
                        myUserId = authRepository.currentUserId(),
                        // U8: variazione di posizione rispetto a prima
                        // dell'ultima gara con risultato.
                        rankDeltas = computeRankDeltas(
                            rules = rules,
                            memberNames = inputs.memberNames,
                            races = inputs.races,
                            results = results,
                            predictionsByUser = predictionsByUser,
                            currentRows = snapshot.rows,
                        ),
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

    /** U8: apre/chiude il dettaglio per gara della riga toccata. */
    fun toggleRow(userId: String) {
        _uiState.update {
            it.copy(expandedUserId = if (it.expandedUserId == userId) null else userId)
        }
    }
}

/** Dati grezzi calcolati in un colpo solo, poi passati al motore. */
private data class StandingInputs(
    val league: League?,
    val memberNames: Map<String, String>,
    val races: List<Race>,
    val data: com.fantaformula1.app.data.repository.LeagueDataSnapshot,
)

/**
 * U8: variazione di posizione di ogni giocatore dopo l'ultima gara con
 * risultato. Funzione pura (testata): ricalcola la classifica ESCLUDENDO
 * l'ultima gara conclusa e confronta le posizioni con quelle correnti.
 * Result: userId -> posti risaliti (positivo) o persi (negativo); i
 * giocatori non presenti in una delle due classifiche non hanno delta.
 */
internal fun computeRankDeltas(
    rules: Rules,
    memberNames: Map<String, String>,
    races: List<Race>,
    results: Map<String, RaceResultData>,
    predictionsByUser: Map<String, List<com.fantaformula1.app.core.domain.RacePrediction>>,
    currentRows: List<ScoringEngine.StandingsRow>,
): Map<String, Int> {
    val completedRaceIds = races.sortedBy { it.round }
        .map { it.id }
        .filter { results.containsKey(it) }
    // Servono almeno due gare concluse: con una sola non c'e' un "prima".
    if (completedRaceIds.size < 2) return emptyMap()
    val lastRaceId = completedRaceIds.last()
    val previousResults = results - lastRaceId
    val previousRows = ScoringEngine.computeStandings(
        predictions = predictionsByUser,
        results = previousResults,
        rules = rules,
        displayNames = memberNames,
    )
    val previousPosition = previousRows.mapIndexed { index, row -> row.userId to index }.toMap()
    val currentPosition = currentRows.mapIndexed { index, row -> row.userId to index }.toMap()
    return currentPosition.mapNotNull { (userId, index) ->
        val before = previousPosition[userId] ?: return@mapNotNull null
        val delta = before - index
        if (delta == 0) null else userId to delta
    }.toMap()
}
