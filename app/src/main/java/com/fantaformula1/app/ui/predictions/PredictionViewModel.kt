package com.fantaformula1.app.ui.predictions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.DriverSeasonStats
import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.PredictionValidation
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.isSprintRelated
import com.fantaformula1.app.core.domain.italianLabel
import com.fantaformula1.app.core.domain.withFrozenQuota
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.PredictionRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
import com.fantaformula1.app.data.repository.LeagueDataSnapshot
import com.fantaformula1.app.data.sync.SeasonResultsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface PredictionEvent {
    data object Saved : PredictionEvent
    data class Failure(val message: String) : PredictionEvent
}

/** Bozza di un pronostico per tipo: campi numerici come stringhe. */
@kotlinx.serialization.Serializable
data class DraftEntry(
    val type: PredictionType,
    val driverIds: List<String> = emptyList(),
    val position: String = "",
    val rangeMin: String = "",
    val rangeMax: String = "",
    val threshold: String = "",
    val over: Boolean? = null,
    val booleanValue: Boolean? = null,
) {
    fun toDomain(): PredictionEntry = PredictionEntry(
        type = type,
        driverIds = driverIds,
        position = position.toIntOrNull(),
        rangeMin = rangeMin.toIntOrNull(),
        rangeMax = rangeMax.toIntOrNull(),
        threshold = threshold.toDoubleOrNull(),
        over = over,
        booleanValue = booleanValue,
    )

    val complete: Boolean
        get() = when (type) {
            PredictionType.DRIVER_EXACT_POSITION -> driverIds.size == 1 && position.isNotBlank()
            PredictionType.DRIVER_POSITION_RANGE ->
                driverIds.size == 1 && rangeMin.isNotBlank() && rangeMax.isNotBlank()
            PredictionType.DRIVER_DNF -> driverIds.size == 1 && booleanValue != null
            PredictionType.DRIVER_POINTS_OVER_UNDER ->
                driverIds.size == 1 && threshold.isNotBlank() && over != null
            PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
            PredictionType.PIT_STOPS_OVER_UNDER,
            // Opzioni/fasce: rangeMin/rangeMax definiscono l'opzione scelta.
            -> rangeMin.isNotBlank() && rangeMax.isNotBlank()
            PredictionType.PODIUM -> driverIds.size == 3
            PredictionType.HEAD_TO_HEAD -> driverIds.size == 2
            else -> driverIds.size == 1
        }

    companion object {
        fun of(type: PredictionType): DraftEntry = DraftEntry(type = type)

        fun from(entry: PredictionEntry): DraftEntry = DraftEntry(
            type = entry.type,
            driverIds = entry.driverIds,
            position = entry.position?.toString().orEmpty(),
            rangeMin = entry.rangeMin?.toString().orEmpty(),
            rangeMax = entry.rangeMax?.toString().orEmpty(),
            threshold = entry.threshold?.toString().orEmpty(),
            over = entry.over,
            booleanValue = entry.booleanValue,
        )
    }
}

data class OtherPrediction(
    val userId: String,
    val displayName: String,
    val prediction: RacePrediction,
)

data class PredictionUiState(
    val loading: Boolean = true,
    val race: Race? = null,
    val league: League? = null,
    val rules: Rules = Rules(),
    val mine: RacePrediction? = null,
    val drafts: List<DraftEntry> = emptyList(),
    val others: List<OtherPrediction> = emptyList(),
    /** Statistiche reali di stagione (quote/soglie; vuota = solo prior). */
    val seasonStats: DriverSeasonStats = DriverSeasonStats.empty,
    val canSubmit: Boolean = false,
    val deadlinePassed: Boolean = false,
    val canRevealOthers: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errors: List<String> = emptyList(),
    val error: String? = null,
    /** Avviso non bloccante su cio' che resta da compilare (R6). */
    val warning: String? = null,
    /** Risultato della gara, se gia' disponibile (per la spiegazione U1). */
    val result: com.fantaformula1.app.core.domain.RaceResultData? = null,
    /** U5: true se alla riapertura è stata ripristinata una bozza locale. */
    val draftRestored: Boolean = false,
) {
    /** Tipi di pronostico da mostrare nel form. */
    val visibleTypes: List<PredictionType>
        get() = rules.enabledTypes
            .filter { !it.isSprintRelated || race?.isSprint == true }
            .sortedBy { it.ordinal }
}

class PredictionViewModel(
    private val raceRepository: RaceRepository,
    private val leagueRepository: LeagueRepository,
    private val predictionRepository: PredictionRepository,
    private val leagueDataRepository: LeagueDataRepository,
    private val seasonResultsStore: SeasonResultsStore,
    private val raceId: String,
    private val draftStore: com.fantaformula1.app.data.prediction.PredictionDraftStore? = null,
) : ViewModel() {

    companion object {
        const val NIENTE_COMPILATO = "Compila almeno un pronostico prima di inviare"
    }

    private val _uiState = MutableStateFlow(PredictionUiState())
    val uiState: StateFlow<PredictionUiState> = _uiState

    private val _events = MutableSharedFlow<PredictionEvent>()
    val events: SharedFlow<PredictionEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching {
                val race = raceRepository.race(raceId)
                    ?: throw UserException("Gara non trovata")
                val league = leagueRepository.league(race.leagueId)
                val mine = predictionRepository.myPrediction(raceId)
                val others = predictionRepository.othersPredictions(raceId)
                val names = leagueRepository.members(race.leagueId)
                    .associate { it.userId to it.displayName }

                // Risultati della LEGA con i loro round (2 query esistenti).
                val snapshot = runCatching {
                    leagueDataRepository.raceData(race.leagueId)
                }.getOrDefault(LeagueDataSnapshot(emptyList(), 0, 0))
                val roundsById = runCatching {
                    raceRepository.races(race.leagueId).associate { it.id to it.round }
                }.getOrDefault(emptyMap())
                val leagueResults = snapshot.races.mapNotNull { legaRaceData ->
                    legaRaceData.result?.let { result ->
                        DriverSeasonStats.StampedResult(
                            round = roundsById[legaRaceData.raceId],
                            result = result,
                        )
                    }
                }
                // U1: risultato della gara corrente, per spiegare i punti.
                val raceResult = snapshot.races
                    .firstOrNull { it.raceId == raceId }?.result

                // Statistiche IMMEDIATE: cache stagione (ZERO rete) + lega.
                // La schermata non aspetta mai la rete per le statistiche.
                val cachedReal = runCatching { seasonResultsStore.current() }
                    .getOrNull()?.results?.map {
                        DriverSeasonStats.StampedResult(round = it.round, result = it.result)
                    }.orEmpty()
                val stats = DriverSeasonStats.fromMergedResults(leagueResults, cachedReal)

                RaceLoad(race, league, mine, others.map { (id, p) ->
                    OtherPrediction(id, names[id] ?: "Giocatore", p)
                }, leagueResults, stats, raceResult)
            }.onSuccess { load ->
                val race = load.race
                val league = load.league
                val mine = load.mine
                val others = load.others
                val rules = league?.rules ?: Rules()
                // B9/B10: la decisione "gara aperta" viene da
                // Race.isPredictionOpen, che considera deadline E status.
                val open = race.isPredictionOpen()
                // U5: la bozza locale riempie i tipi che sul server
                // non risultano ancora compilati (l'invio e' l'ultimo passo).
                val serverDrafts = buildDrafts(rules, race, mine)
                val localDrafts = try {
                    draftStore?.load(raceId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val mergedDrafts = serverDrafts.map { draft ->
                    val local = localDrafts?.firstOrNull { it.type == draft.type }
                    if (draft.driverIds.isEmpty() && local != null) local else draft
                }
                val restored = mergedDrafts != serverDrafts
                _uiState.update {
                    it.copy(
                        loading = false,
                        race = race,
                        league = league,
                        rules = rules,
                        mine = mine,
                        // B14: le bozze seguono sempre i tipi attuali della
                        // lega e compatibili con la gara; i valori salvati
                        // riempiono solo i tipi ancora attivi. U5: la bozza
                        // locale ha la precedenza sui tipi vuoti.
                        drafts = mergedDrafts,
                        others = others,
                        seasonStats = load.stats,
                        canSubmit = open && (mine == null || rules.allowEditUntilDeadline),
                        deadlinePassed = !open,
                        // B10: se revealOthersAfterDeadline è false gli altri
                        // pronostici sono visibili subito, altrimenti solo dopo
                        // la deadline.
                        canRevealOthers = !rules.revealOthersAfterDeadline || !open,
                        saved = mine != null,
                        result = load.result,
                        draftRestored = restored,
                    )
                }
                // RINFRESCO IN BACKGROUND (incrementale, max 6h, timeout 10s
                // a richiesta): non blocca la schermata e nessun errore
                // visibile se fallisce. Quando arriva aggiorna le statistiche.
                viewModelScope.launch {
                    val refreshed = runCatching { seasonResultsStore.ensureFresh() }.getOrNull()
                    if (refreshed != null) {
                        val statsAfter = DriverSeasonStats.fromMergedResults(
                            load.leagueResults,
                            refreshed.results.map {
                                DriverSeasonStats.StampedResult(round = it.round, result = it.result)
                            },
                        )
                        _uiState.update { it.copy(seasonStats = statsAfter) }
                    }
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(loading = false, error = throwable.message ?: "Si è verificato un errore")
                }
            }
        }
    }

    /**
     * Seleziona un pilota nello slot indicato del pronostico (menu a tendina).
     * La logica e' in [setDriverInSelection], testata con unit test JVM.
     *
     * Regola podio/vincitore: il 1º slot del podio coincide con il vincitore
     * scelto nel pronostico Vincitore, quindi non e' modificabile da qui; e
     * scegliere un vincitore riempie/aggiorna in automatico il 1º slot del
     * podio. Il blocco vale solo quando il tipo Vincitore e' attivo.
     */
    fun setDriverAt(type: PredictionType, slot: Int, driverId: String) {
        val winnerEnabled = PredictionType.WINNER in _uiState.value.rules.enabledTypes
        if (type == PredictionType.PODIUM && winnerEnabled && slot == 0) {
            // Slot bloccato: il 1º posto arriva solo dal pronostico Vincitore.
            return
        }
        updateDraft(type) { draft ->
            draft.copy(driverIds = setDriverInSelection(draft.driverIds, slot, driverId))
        }
        if (type == PredictionType.WINNER && winnerEnabled) {
            // Sync automatico: il vincitore e' il 1º del podio, gli altri due
            // slot restano invariati (rimuovendo l'eventuale duplicato sotto).
            updateDraft(PredictionType.PODIUM) { draft ->
                val rest = draft.driverIds.drop(1).filter { it != driverId }
                if (draft.driverIds.firstOrNull() == driverId) {
                    draft
                } else {
                    draft.copy(driverIds = listOf(driverId) + rest)
                }
            }
        }
    }

    fun setPosition(type: PredictionType, value: String) =
        updateDraft(type) { it.copy(position = value.filter(Char::isDigit).take(2)) }

    fun setRangeMin(type: PredictionType, value: String) =
        updateDraft(type) { it.copy(rangeMin = value.filter(Char::isDigit).take(2)) }

    fun setRangeMax(type: PredictionType, value: String) =
        updateDraft(type) { it.copy(rangeMax = value.filter(Char::isDigit).take(2)) }

    /** Imposta una fascia preimpostata (chip) in un colpo solo. */
    fun setRangePreset(type: PredictionType, min: Int, max: Int) =
        updateDraft(type) { it.copy(rangeMin = min.toString(), rangeMax = max.toString()) }

    fun setThreshold(type: PredictionType, value: String) =
        updateDraft(type) {
            it.copy(threshold = value.filter { c -> c.isDigit() || c == '.' }.take(6))
        }

        fun setOver(type: PredictionType, value: Boolean) =
        updateDraft(type) { it.copy(over = value) }

    /**
     * Seleziona un'opzione/fascia (safety car a numero esatto, pit stop a
     * banda): scrive rangeMin/rangeMax e azzera i vecchi campi soglia/direzione
     * cosi' il JSON salvato contiene solo la forma nuova.
     */
    fun setBand(type: PredictionType, min: Int, max: Int) =
        updateDraft(type) {
            it.copy(
                rangeMin = min.toString(),
                rangeMax = max.toString(),
                threshold = "",
                over = null,
            )
        }

    fun setBooleanValue(type: PredictionType, value: Boolean) =
        updateDraft(type) { it.copy(booleanValue = value) }

    /**
     * Ricalcola lo stato legato alla deadline (B10): utile quando la schermata
     * è aperta mentre scade il tempo, o quando l'app torna in foreground.
     */
    fun checkDeadline() {
        val state = _uiState.value
        val race = state.race ?: return
        val open = race.isPredictionOpen()
        _uiState.update {
            it.copy(
                canSubmit = open && (it.mine == null || it.rules.allowEditUntilDeadline),
                deadlinePassed = !open,
                canRevealOthers = !it.rules.revealOthersAfterDeadline || !open,
            )
        }
    }

    private fun updateDraft(type: PredictionType, transform: (DraftEntry) -> DraftEntry) {
        _uiState.update { state ->
            state.copy(
                drafts = state.drafts.map { if (it.type == type) transform(it) else it },
                errors = emptyList(),
                warning = null,
            )
        }
        // U5: la bozza viene persistita localmente a ogni modifica, cosi'
        // chiudere l'app non perde il lavoro in corso.
        val snapshot = _uiState.value.drafts
        viewModelScope.launch {
            try {
                draftStore?.save(raceId, snapshot)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Bozza locale best-effort: un fallimento (disco pieno, ecc.)
                // non deve interrompere la compilazione.
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.saving || !state.canSubmit) return
        val race = state.race ?: return

        val entries = state.drafts
            .filter { it.complete }
            // Quota/Soglia congelati con le statistiche DEL MOMENTO della
            // scrittura: dopo il save, il ricalcolo non dipende piu' dai dati.
            .map { it.toDomain().withFrozenQuota(state.seasonStats) }

        val prediction = RacePrediction(raceId = raceId, entries = entries)

        if (entries.isEmpty()) {
            _uiState.update { it.copy(errors = listOf(NIENTE_COMPILATO), warning = null) }
            viewModelScope.launch { _events.emit(PredictionEvent.Failure(NIENTE_COMPILATO)) }
            return
        }

        // R6: parziali ammesse, ma l'utente deve sapere cosa resta vuoto
        // PRIMA di premere invia.
        val warning = missingTypesMessage(state.drafts)

        val errors = PredictionValidation.validate(
            prediction = prediction,
            rules = state.rules,
            isSprintRace = race.isSprint,
        )
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors, warning = warning) }
            viewModelScope.launch { _events.emit(PredictionEvent.Failure(errors.first())) }
            return
        }

        // Guard sincrona prima del launch: un doppio tocco non avvia due
        // salvataggi in parallelo (l'upsert e' idempotente, ma evitiamo
        // comunque richieste duplicate in volo).
        _uiState.update { it.copy(saving = true, errors = emptyList(), warning = warning) }
        viewModelScope.launch {
            try {
                predictionRepository.savePrediction(raceId, race.leagueId, prediction)
                // U5: bozza inviata -> rimossa dal locale (il server e' ora
                // la sorgente di verita').
                try {
                    draftStore?.clear(raceId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best-effort: una bozza residua viene sovrascritta al
                    // prossimo salvataggio.
                }
                _uiState.update { it.copy(saving = false, saved = true, mine = prediction) }
                _events.emit(PredictionEvent.Saved)
            } catch (e: UserException) {
                _uiState.update { it.copy(saving = false, errors = listOf(e.message)) }
                _events.emit(PredictionEvent.Failure(e.message))
            } catch (e: Exception) {
                val message = e.message ?: "Impossibile salvare il pronostico"
                _uiState.update { it.copy(saving = false, errors = listOf(message)) }
                _events.emit(PredictionEvent.Failure(message))
            }
        }
    }
}

/** Carico iniziale: dati schermata + risultati lega (per il rinfresco bg). */
private data class RaceLoad(
    val race: Race,
    val league: League?,
    val mine: RacePrediction?,
    val others: List<OtherPrediction>,
    val leagueResults: List<DriverSeasonStats.StampedResult>,
    val stats: DriverSeasonStats,
    val result: com.fantaformula1.app.core.domain.RaceResultData?,
)

/**
 * Logica pura della selezione a slot per i pronostici a tendina.
 *
 * La lista resta compatta e ordinata per slot: scegliere un pilota gia'
 * selezionato in un altro slot lo sposta qui con inserimento (gli altri
 * scalano), mentre un pilota nuovo sostituisce l'occupante dello slot.
 * I duplicati sono impossibili per costruzione (invariante richiesta da
 * PredictionValidation).
 */
internal fun setDriverInSelection(
    selection: List<String>,
    slot: Int,
    driverId: String,
): List<String> {
    val moving = driverId in selection
    val selected = selection.toMutableList()
    selected.remove(driverId)
    val at = slot.coerceAtMost(selected.size)
    if (moving || at == selected.size) {
        // Spostamento o slot oltre la dimensione: inserimento con shift.
        selected.add(at, driverId)
    } else {
        // Pilota nuovo che prende il posto dell'occupante dello slot.
        selected[at] = driverId
    }
    return selected
}

/**
 * R6: elenca i tipi visibili ancora non compilati, per l'avviso non bloccante
 * "Non hai compilato: …". Ritorna null se tutte le bozze sono complete.
 */
internal fun missingTypesMessage(drafts: List<DraftEntry>): String? {
    val missing = drafts.filter { !it.complete }
        .map { it.type.italianLabel }
    if (missing.isEmpty()) return null
    return "Non hai compilato: " + missing.joinToString(", ")
}

/** Tipi di pronostico da mostrare: attivi nella lega e compatibili con la gara. */
private fun visibleTypesFor(rules: Rules, race: Race): List<PredictionType> =
    rules.enabledTypes
        .filter { !it.isSprintRelated || race.isSprint }
        .sortedBy { it.ordinal }

/**
 * Costruisce le bozze a partire dai tipi attuali della lega, riempiendo con i
 * valori salvati solo per i tipi ancora attivi. Così se l'admin disattiva un
 * tipo, la bozza sparisce; se ne attiva uno nuovo, appare subito (B14).
 */
private fun buildDrafts(
    rules: Rules,
    race: Race,
    mine: RacePrediction?,
): List<DraftEntry> {
    val visibleTypes = visibleTypesFor(rules, race)
    val savedByType = mine?.entries?.associateBy { it.type } ?: emptyMap()
    return visibleTypes.map { type ->
        savedByType[type]?.let(DraftEntry::from) ?: DraftEntry.of(type)
    }
}
