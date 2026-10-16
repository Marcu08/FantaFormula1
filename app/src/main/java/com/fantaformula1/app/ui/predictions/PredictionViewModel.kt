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
import com.fantaformula1.app.core.domain.withFrozenQuota
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.PredictionRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
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
    private val raceId: String,
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
                // Statistiche reali dalle gare completate (quota/soglie/fasce).
                // Fallisca = "nessuna statistica" (solo prior), non errore bloccante.
                val results = runCatching {
                    leagueDataRepository.raceData(race.leagueId)
                        .races
                        .mapNotNull { it.result }
                }.getOrDefault(emptyList())
                val stats = DriverSeasonStats.fromResults(results)
                Quint(race, league, mine, others.map { (id, p) ->
                    OtherPrediction(id, names[id] ?: "Giocatore", p)
                }, stats)
            }.onSuccess { (race, league, mine, others, stats) ->
                val rules = league?.rules ?: Rules()
                // B9/B10: la decisione "gara aperta" viene da
                // Race.isPredictionOpen, che considera deadline E status.
                val open = race.isPredictionOpen()
                _uiState.update {
                    it.copy(
                        loading = false,
                        race = race,
                        league = league,
                        rules = rules,
                        mine = mine,
                        // B14: le bozze seguono sempre i tipi attuali della
                        // lega e compatibili con la gara; i valori salvati
                        // riempiono solo i tipi ancora attivi.
                        drafts = buildDrafts(rules, race, mine),
                        others = others,
                        seasonStats = stats,
                        canSubmit = open && (mine == null || rules.allowEditUntilDeadline),
                        deadlinePassed = !open,
                        // B10: se revealOthersAfterDeadline è false gli altri
                        // pronostici sono visibili subito, altrimenti solo dopo
                        // la deadline.
                        canRevealOthers = !rules.revealOthersAfterDeadline || !open,
                        saved = mine != null,
                    )
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
     */
    fun setDriverAt(type: PredictionType, slot: Int, driverId: String) {
        updateDraft(type) { draft ->
            draft.copy(driverIds = setDriverInSelection(draft.driverIds, slot, driverId))
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
            )
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
            _uiState.update { it.copy(errors = listOf(NIENTE_COMPILATO)) }
            viewModelScope.launch { _events.emit(PredictionEvent.Failure(NIENTE_COMPILATO)) }
            return
        }

        val errors = PredictionValidation.validate(
            prediction = prediction,
            rules = state.rules,
            isSprintRace = race.isSprint,
        )
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            viewModelScope.launch { _events.emit(PredictionEvent.Failure(errors.first())) }
            return
        }

        // Guard sincrona prima del launch: un doppio tocco non avvia due
        // salvataggi in parallelo (l'upsert e' idempotente, ma evitiamo
        // comunque richieste duplicate in volo).
        _uiState.update { it.copy(saving = true, errors = emptyList()) }
        viewModelScope.launch {
            try {
                predictionRepository.savePrediction(raceId, race.leagueId, prediction)
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

/** Tupla comoda a 5 elementi per il caricamento iniziale. */
private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

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
