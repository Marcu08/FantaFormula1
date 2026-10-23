package com.fantaformula1.app.ui.leagues

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.LeagueStandings
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.SeasonStandingsRow
import com.fantaformula1.app.core.domain.SeasonStandingsSnapshot
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.PastSeason
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.SeasonHistoryRepository
import com.fantaformula1.app.data.repository.UserException
import com.fantaformula1.app.data.util.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** F2 — stato della scheda "Stagioni passate". */
data class SeasonHistoryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val pastSeasons: List<PastSeason> = emptyList(),
    val isAdmin: Boolean = false,
    /** true se la stagione in corso ha almeno una gara: allora si puo' archiviare. */
    val hasRaces: Boolean = false,
    val archiving: Boolean = false,
    /** null = nessun esito da mostrare, true = archiviazione riuscita, false = fallita. */
    val archivedEvent: Boolean? = null,
)

/**
 * F2 — Storico stagioni: elenco degli snapshot e chiusura della stagione
 * corrente (congelamento della classifica finale + nuove gare da zero).
 */
class SeasonHistoryViewModel(
    private val leagueRepository: LeagueRepository,
    private val raceRepository: RaceRepository,
    private val leagueDataRepository: LeagueDataRepository,
    private val seasonHistoryRepository: SeasonHistoryRepository,
    private val leagueId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeasonHistoryUiState())
    val uiState: StateFlow<SeasonHistoryUiState> = _uiState

    /** true mentre un caricamento e' in corso (evita refresh concorrenti). */
    private var loadInFlight = false

    init {
        load()
    }

    /**
     * Carica storico, ruolo dell'utente e presenza di gare nella stagione
     * corrente. `loading` resta a true solo al primo caricamento: il refresh
     * post-archiviazione non deve svuotare la scheda. Se un caricamento e'
     * gia' in corso la chiamata viene ignorata (niente doppi refresh).
     */
    fun load() {
        if (loadInFlight) return
        loadInFlight = true
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(loading = it.pastSeasons.isEmpty(), error = null) }
                val races = raceRepository.races(leagueId)
                val pastSeasons = seasonHistoryRepository.pastSeasons(leagueId)
                val isAdmin = leagueRepository.isAdmin(leagueId)
                _uiState.update {
                    it.copy(
                        loading = false,
                        pastSeasons = pastSeasons,
                        isAdmin = isAdmin,
                        hasRaces = races.isNotEmpty(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = throwable.message ?: "Si è verificato un errore",
                    )
                }
            } finally {
                loadInFlight = false
            }
        }
    }

    /**
     * Congela la classifica finale della stagione corrente: ricalcola la
     * classifica con la stessa logica della schermata Classifica
     * ([LeagueStandings.build]) e la salva nello snapshot, poi disattiva la
     * stagione (le nuove gare partiranno da zero).
     */
    fun archive() {
        val state = _uiState.value
        if (state.archiving || !state.isAdmin || !state.hasRaces) return
        viewModelScope.launch {
            _uiState.update { it.copy(archiving = true, error = null, archivedEvent = null) }
            try {
                val season = seasonHistoryRepository.currentSeason(leagueId)
                    ?: throw UserException("Nessuna stagione attiva da archiviare")
                val league = leagueRepository.league(leagueId)
                val rules = league?.rules ?: Rules()
                val memberNames = leagueRepository.members(leagueId)
                    .associate { it.userId to it.displayName }
                val races = raceRepository.races(leagueId)
                val data = leagueDataRepository.raceData(leagueId)

                val results = data.races
                    .mapNotNull { datum -> datum.result?.let { datum.raceId to it } }
                    .toMap()
                val predictionsByUser = data.races
                    .flatMap { datum ->
                        datum.predictions.map { (userId, prediction) -> userId to prediction }
                    }
                    .groupBy({ it.first }, { it.second })

                val snapshot = LeagueStandings.build(
                    rules = rules,
                    memberNames = memberNames,
                    races = races,
                    results = results,
                    predictionsByUser = predictionsByUser,
                )
                val rows = snapshot.rows.mapIndexed { index, row ->
                    SeasonStandingsRow(
                        position = index + 1,
                        userId = row.userId,
                        displayName = row.displayName,
                        totalPoints = row.totalPoints,
                        raceWins = row.raceWins,
                        bestRacePoints = row.bestRacePoints,
                    )
                }
                val standings = AppJson.encodeToJsonElement(
                    SeasonStandingsSnapshot.serializer(),
                    SeasonStandingsSnapshot(rows),
                )
                seasonHistoryRepository.archiveSeason(
                    leagueId = leagueId,
                    seasonId = season.seasonId,
                    seasonName = season.name,
                    standings = standings,
                )
                _uiState.update { it.copy(archiving = false, archivedEvent = true) }
                load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        archiving = false,
                        archivedEvent = false,
                        error = throwable.message ?: "Si è verificato un errore",
                    )
                }
            }
        }
    }

    /** Segnala che l'esito dell'archiviazione e' stato mostrato (snackbar). */
    fun acknowledgeArchivedEvent() {
        _uiState.update { it.copy(archivedEvent = null) }
    }
}
