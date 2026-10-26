package com.fantaformula1.app.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.DriverRaceResult
import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
import com.fantaformula1.app.data.openf1.OpenF1Client
import com.fantaformula1.app.data.openf1.OpenF1SafetyCarParser
import com.fantaformula1.app.data.openf1.OpenF1SessionMatcher
import com.fantaformula1.app.data.openf1.OpenF1Source
import com.fantaformula1.app.data.openf1.openF1FailureMessage
import com.fantaformula1.app.data.sync.F1SyncStateHolder
import com.fantaformula1.app.data.sync.SyncResult
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ResultEvent {
    data object Saved : ResultEvent
    data class Failure(val message: String) : ResultEvent
    /** Messaggio informativo (snackbar neutra, non un errore). */
    data class Notice(val message: String) : ResultEvent
}

/** Riga del form risultati: tutto in stringhe, conversione al salvataggio. */
data class DriverFormState(
    val driverId: String,
    val driverName: String,
    val team: String,
    val grid: String = "",
    val position: String = "",
    val sprintPosition: String = "",
    val fastestLap: Boolean = false,
    val dnf: Boolean = false,
    val dnfReason: String = "",
    val penaltyPoints: String = "",
    val pitStops: String = "",
    /** Punti campionato effettivi del pilota (opzionale, per gli over/under). */
    val pointsScored: String = "",
) {
    /** True se il pilota ha almeno un dato compilato. */
    val hasData: Boolean
        get() = grid.isNotBlank() || position.isNotBlank() || sprintPosition.isNotBlank() ||
            fastestLap || dnf || dnfReason.isNotBlank() ||
            penaltyPoints.isNotBlank() || pitStops.isNotBlank() || pointsScored.isNotBlank()

    fun toDomain(): DriverRaceResult = DriverRaceResult(
        driverId = driverId,
        driverName = driverName,
        grid = grid.toIntOrNull(),
        position = if (dnf) null else position.toIntOrNull(),
        sprintPosition = sprintPosition.toIntOrNull(),
        fastestLap = fastestLap,
        dnf = dnf,
        dnfReason = dnfReason.trim().ifBlank { null },
        penaltyPoints = penaltyPoints.toIntOrNull() ?: 0,
        pitStops = pitStops.toIntOrNull() ?: 0,
        pointsScored = pointsScored.toDoubleOrNull(),
    )

    companion object {
        fun fromDomain(driver: DriverRaceResult): DriverFormState = DriverFormState(
            driverId = driver.driverId,
            driverName = driver.driverName.ifBlank { DriversCatalog.nameOf(driver.driverId) },
            team = DriversCatalog.byId(driver.driverId)?.team.orEmpty(),
            grid = driver.grid?.toString().orEmpty(),
            position = driver.position?.toString().orEmpty(),
            sprintPosition = driver.sprintPosition?.toString().orEmpty(),
            fastestLap = driver.fastestLap,
            dnf = driver.dnf,
            dnfReason = driver.dnfReason.orEmpty(),
            penaltyPoints = driver.penaltyPoints.takeIf { it > 0 }?.toString().orEmpty(),
            pitStops = driver.pitStops.takeIf { it > 0 }?.toString().orEmpty(),
            pointsScored = driver.pointsScored?.toString().orEmpty(),
        )

        fun catalog(): List<DriverFormState> = DriversCatalog.all.map {
            DriverFormState(driverId = it.id, driverName = it.name, team = it.team)
        }
    }
}

data class ResultUiState(
    val loading: Boolean = true,
    val race: Race? = null,
    val isAdmin: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val drivers: List<DriverFormState> = emptyList(),
    val safetyCars: String = "",
    val redFlags: String = "",
    val laps: String = "",
    val weather: String = "",
    val notes: String = "",
    val fieldErrors: List<String> = emptyList(),
    val saved: Boolean = false,
    /**
     * N3: campi che l'API non fornisce e che l'admin deve confermare a mano.
     * Finche' restano null gli over/under collegati valgono 0 punti.
     */
    val safetyCarsUnknown: Boolean = false,
    val pitStopsUnknown: Boolean = false,
    /** Importazione assistita SC/VSC da OpenF1 (mai salvata da sola). */
    val scImport: ScImportState = ScImportState.Idle,
    /** Sincronizzazione Jolpica dei risultati piloti in corso. */
    val resultsSyncing: Boolean = false,
    /**
     * True se l'admin ha gia' modificato qualcosa in questa sessione: la
     * sincronizzazione non ricarica il form per non perdere i suoi dati.
     */
    val edited: Boolean = false,
    /** Conferma aperta: rigenera da Jolpica il risultato gia' salvato. */
    val confirmRefresh: Boolean = false,
)

/**
 * Stato dell'importazione assistita: Idle -> Loading -> Success(result) o
 * Failed(motivo). L'esito e' SOLO proposta: ne salva ne' tocca i campi
 * direttamente — l'amministratore revisiona, puo' editare il totale e
 * convalida; la conferma riempie il campo esistente del form (SC+VSC sommate
 * perche' il JSONB del risultato conta una sola neutralizzazione).
 */
sealed interface ScImportState {
    data object Idle : ScImportState
    data object Loading : ScImportState
    data class Success(val import: com.fantaformula1.app.data.openf1.OpenF1SafetyCarParser.Import) : ScImportState
    data class Failed(val reason: String) : ScImportState
}

class ResultViewModel(
    private val raceRepository: RaceRepository,
    private val leagueRepository: LeagueRepository,
    private val authRepository: AuthRepository,
    private val openF1: OpenF1Source? = null,
    /** Sync Jolpica dei risultati piloti (stesso holder della sync periodica). */
    private val f1Sync: F1SyncStateHolder? = null,
    private val raceId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState

    private val _events = MutableSharedFlow<ResultEvent>()
    val events: SharedFlow<ResultEvent> = _events.asSharedFlow()

    /** Auto-sync Jolpica: una sola volta per istanza (apertura schermata). */
    private var autoSyncAttempted = false

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching {
                val race = raceRepository.race(raceId)
                    ?: throw UserException("Gara non trovata")
                val result = raceRepository.result(raceId)
                val members = leagueRepository.members(race.leagueId)
                val me = authRepository.currentUserId()
                val admin = members.any { it.userId == me && it.isAdmin }
                Triple(race, result, admin)
            }.onSuccess { (race, result, admin) ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        race = race,
                        isAdmin = admin,
                        // B13: anche con risultato esistente mostriamo tutto il
                        // catalogo, così l'admin può aggiungere piloti dopo il
                        // primo salvataggio.
                        drivers = if (result != null) {
                            mergeWithCatalog(result.drivers)
                        } else {
                            DriverFormState.catalog()
                        },
                        safetyCars = result?.safetyCars?.takeIf { v -> v > 0 }?.toString().orEmpty(),
                        redFlags = result?.redFlags?.takeIf { v -> v > 0 }?.toString().orEmpty(),
                        laps = result?.laps?.takeIf { v -> v > 0 }?.toString().orEmpty(),
                        weather = result?.weather.orEmpty(),
                        notes = result?.notes.orEmpty(),
                        saved = result != null,
                        safetyCarsUnknown = result != null && result.safetyCars == null,
                        pitStopsUnknown = result != null && result.confirmedTotalPitStops() == null,
                    )
                }
                // Auto-sync: aperta la schermata di una gara gia' disputata
                // ancora senza risultato, i dati piloti arrivano da soli da
                // Jolpica (una volta per apertura; la sync periodica fa il
                // resto in background). Solo admin, niente se ha gia' digitato.
                if (!autoSyncAttempted && admin && result == null && race.isDeadlinePassed()) {
                    autoSyncAttempted = true
                    syncResultsFromApi()
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

/**
 * Unisce i dati salvati con il catalogo completo: ogni pilota del catalogo
 * appare nel form, e quelli già inseriti conservano i valori (B13).
 */
private fun mergeWithCatalog(saved: List<DriverRaceResult>): List<DriverFormState> {
    val savedById = saved.associateBy { it.driverId }
    return DriversCatalog.all.map { catalog ->
        savedById[catalog.id]
            ?.let { DriverFormState.fromDomain(it.copy(driverName = catalog.name)) }
            ?: DriverFormState(driverId = catalog.id, driverName = catalog.name, team = catalog.team)
    }
}

    fun updateDriver(driverId: String, transform: (DriverFormState) -> DriverFormState) {
        _uiState.update { state ->
            state.copy(
                drivers = state.drivers.map { if (it.driverId == driverId) transform(it) else it },
                edited = true,
            )
        }
    }

    fun onGridChange(driverId: String, value: String) =
        updateDriver(driverId) { it.copy(grid = value.filter(Char::isDigit).take(2)) }

    fun onPositionChange(driverId: String, value: String) =
        updateDriver(driverId) { it.copy(position = value.filter(Char::isDigit).take(2)) }

    fun onSprintPositionChange(driverId: String, value: String) =
        updateDriver(driverId) { it.copy(sprintPosition = value.filter(Char::isDigit).take(2)) }

    fun onFastestLapChange(driverId: String, value: Boolean) {
        // il giro veloce e' unico: accendendolo su un pilota si spegne sugli altri
        _uiState.update { state ->
            state.copy(
                edited = true,
                drivers = state.drivers.map {
                    when {
                        it.driverId != driverId -> if (value) it.copy(fastestLap = false) else it
                        else -> it.copy(fastestLap = value)
                    }
                },
            )
        }
    }

    fun onDnfChange(driverId: String, value: Boolean) =
        updateDriver(driverId) { it.copy(dnf = value, position = if (value) "" else it.position) }

    fun onDnfReasonChange(driverId: String, value: String) =
        updateDriver(driverId) { it.copy(dnfReason = value.take(60)) }

    fun onPenaltyChange(driverId: String, value: String) =
        updateDriver(driverId) { it.copy(penaltyPoints = value.filter(Char::isDigit).take(2)) }

    fun onPitStopsChange(driverId: String, value: String) =
        updateDriver(driverId) { it.copy(pitStops = value.filter(Char::isDigit).take(2)) }

    fun onPointsChange(driverId: String, value: String) =
        updateDriver(driverId) { it.copy(pointsScored = value.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(6)) }

    fun onSafetyCarsChange(value: String) =
        _uiState.update { it.copy(safetyCars = value.filter(Char::isDigit).take(2), edited = true) }

    fun onRedFlagsChange(value: String) =
        _uiState.update { it.copy(redFlags = value.filter(Char::isDigit).take(2), edited = true) }

    fun onLapsChange(value: String) =
        _uiState.update { it.copy(laps = value.filter(Char::isDigit).take(3), edited = true) }

    fun onWeatherChange(value: String) =
        _uiState.update { it.copy(weather = value.take(40), edited = true) }

    fun onNotesChange(value: String) =
        _uiState.update { it.copy(notes = value.take(200), edited = true) }

    /**
     * Sincronizzazione Jolpica dei risultati piloti (pulsante nella
     * schermata + auto-sync all'apertura): stesso percorso della sync
     * periodica, salva tutte le gare passate della lega che l'API conosce.
     * Il form viene ricaricato SOLO se l'admin non ha gia' digitato qualcosa:
     * mai sovrascrivere i dati inseriti a mano.
     */
    fun syncResultsFromApi() {
        val sync = f1Sync ?: return
        val state = _uiState.value
        val race = state.race ?: return
        if (!state.isAdmin || state.resultsSyncing) return
        // Gara GIA' completata: la sync non la rigenera (sovrascriverebbe
        // le correzioni dell'admin senza chiederlo). Si passa per la
        // conferma del refresh, che dichiara cosa aggiorna e cosa conserva.
        if (race.status == RaceStatus.COMPLETED) {
            _uiState.update { it.copy(confirmRefresh = true) }
            return
        }
        val leagueId = race.leagueId
        _uiState.update { it.copy(resultsSyncing = true) }
        viewModelScope.launch {
            try {
                when (val result = sync.syncResults(leagueId)) {
                    null -> _events.emit(
                        ResultEvent.Notice("Sincronizzazione già in corso: riprova tra un attimo"),
                    )
                    is SyncResult.Failure -> _events.emit(ResultEvent.Failure(result.message))
                    is SyncResult.Success -> {
                        val edited = _uiState.value.edited
                        if (result.completed > 0 && !edited) load()
                        val gare = if (result.completed == 1) "gara aggiornata" else "gare aggiornate"
                        val race = _uiState.value.race
                        val message = when {
                            result.completed > 0 && edited ->
                                "Risultati sincronizzati da Jolpica: " +
                                    "${result.completed} $gare. I dati già inseriti sono stati conservati"
                            result.completed > 0 ->
                                "Risultati sincronizzati da Jolpica: ${result.completed} $gare"
                            // Perche' completed == 0: la gara attuale non e'
                            // stata nemmeno interrogata, oppure Jolpica non ha
                            // nulla per quel round. Il round nel messaggio fa
                            // da controllo diretto col calendario reale.
                            race == null -> "Jolpica non ha ancora il risultato di questa gara"
                            // Gara gia' salvata: il messaggio rilegge lo stato
                            // REALE appena scritto, per dire cosa manca davvero
                            // (safety car e pit stop hanno fonti diverse).
                            race.status == RaceStatus.COMPLETED ->
                                completedResultMessage(race)
                            race.deadline == null ->
                                "La gara non ha una deadline: la sincronizzazione " +
                                    "salta le gare senza deadline (impostala dal calendario)"
                            !race.isDeadlinePassed() ->
                                "La gara non è ancora disputata: Jolpica pubblica " +
                                    "i risultati solo dopo la gara"
                            else ->
                                "Jolpica non ha risultati per il round ${race.round} " +
                                    "(${race.name}): riprova più tardi o controlla " +
                                    "il numero del round"
                        }
                        _events.emit(ResultEvent.Notice(message))
                    }
                }
            } catch (cancellation: CancellationException) {
                // Regola 6: il cancel del viewModelScope non va mangiato.
                throw cancellation
            } catch (e: Exception) {
                _events.emit(
                    ResultEvent.Failure(e.message ?: "Impossibile sincronizzare i risultati"),
                )
            } finally {
                _uiState.update { it.copy(resultsSyncing = false) }
            }
        }
    }

    /** Conferma del refresh: rigenera il risultato salvato da Jolpica. */
    fun confirmRefreshResult() {
        val sync = f1Sync ?: return
        val race = _uiState.value.race ?: return
        if (_uiState.value.resultsSyncing) return
        _uiState.update { it.copy(confirmRefresh = false, resultsSyncing = true) }
        viewModelScope.launch {
            try {
                when (val result = sync.refreshResult(race)) {
                    null -> _events.emit(
                        ResultEvent.Notice("Sincronizzazione già in corso: riprova tra un attimo"),
                    )
                    is SyncResult.Failure -> _events.emit(ResultEvent.Failure(result.message))
                    is SyncResult.Success -> {
                        val edited = _uiState.value.edited
                        if (!edited) load()
                        _events.emit(
                            ResultEvent.Notice(
                                if (edited) {
                                    "Risultato aggiornato da Jolpica (i tuoi campi " +
                                        "inseriti sono stati conservati nel form)"
                                } else {
                                    "Risultato aggiornato da Jolpica: posizioni, giri e " +
                                        "pit stop rigenerati — safety car, meteo e note " +
                                        "conservati"
                                },
                            ),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                // Regola 6: il cancel del viewModelScope non va mangiato.
                throw cancellation
            } catch (e: Exception) {
                _events.emit(ResultEvent.Failure(e.message ?: "Impossibile aggiornare il risultato"))
            } finally {
                _uiState.update { it.copy(resultsSyncing = false) }
            }
        }
    }

    /** Chiude il dialogo di conferma del refresh senza fare nulla. */
    fun dismissRefreshConfirm() {
        _uiState.update { it.copy(confirmRefresh = false) }
    }

    /**
     * Stato REALE di una gara gia' completata, riletto dopo la sync: il
     * messaggio deve dire cosa manca davvero (e non un generico "riprova"),
     * perche' safety car e pit stop hanno fonti completamente diverse.
     */
    private suspend fun completedResultMessage(race: Race): String {
        val saved = try {
            raceRepository.result(race.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val pitDone = saved?.confirmedTotalPitStops() != null
        val safetyDone = saved?.safetyCars != null
        return when {
            pitDone && safetyDone ->
                "Gara completa: pit stop e safety car confermati ✓"
            pitDone ->
                "Pit stop confermati ✓ — manca la safety car: scrivila qui " +
                    "sotto oppure usa \"Importa SC/VSC (OpenF1)\""
            safetyDone ->
                "Safety car confermata ✓ — i pit stop non sono ancora arrivati " +
                    "da Jolpica: riprova più tardi"
            else ->
                "Mancano pit stop e safety car: i pit stop arrivano da Jolpica " +
                    "con la sincronizzazione, la safety car va inserita qui " +
                    "sotto (o da OpenF1)"
        }
    }

    /**
     * Importazione ASSISTITA delle neutralizzazioni da OpenF1: cerca la
     * sessione Race della stagione (niente "latest"), scarica i messaggi
     * FIA e propone SC/VSC. NON tocca accredita i campi: solo dati con
     * review dell'amministratore. Fallita = messaggio, il manual resta.
     */
    fun importSafetyCars() {
        val race = _uiState.value.race ?: return
        val client = openF1 ?: return
        if (_uiState.value.scImport is ScImportState.Loading) return
        _uiState.update { it.copy(scImport = ScImportState.Loading) }
        viewModelScope.launch {
            try {
                val sessions = client.sessions(OpenF1Client.SEASON)
                val match = OpenF1SessionMatcher.match(
                    raceSeason = OpenF1Client.SEASON,
                    raceCountry = race.country,
                    raceName = race.name,
                    deadline = race.deadline,
                    sessions = sessions,
                )
                val imported = when (match) {
                    is OpenF1SessionMatcher.Match.Failed ->
                        throw UserException(match.reason)
                    is OpenF1SessionMatcher.Match.Ok -> {
                        val events = client.raceControl(match.sessionKey)
                        OpenF1SafetyCarParser.parse(events)
                    }
                }
                _uiState.update { it.copy(scImport = ScImportState.Success(imported)) }
            } catch (cancellation: CancellationException) {
                // Regola 6: il cancel del viewModelScope non va mangiato.
                throw cancellation
            } catch (e: Exception) {
                // 401 = sessione live OpenF1 (caso piu' comune): il messaggio
                // spiega la causa invece di mostrare il trace di ktor.
                val status = (e as? ResponseException)?.response?.status?.value
                val message = openF1FailureMessage(status, e.message)
                _uiState.update { it.copy(scImport = ScImportState.Failed(message)) }
                _events.emit(ResultEvent.Failure(message))
            }
        }
    }

    /**
     * Conferma dell'amministratore: riempie il campo GIÀ esistente del form
     * (SC+VSC, il JSONB li conta sommati). Nessun salvataggio automatico:
     * con persistere scatta come prima con [save].
     */
    fun confirmSafetyCarImport(total: Int) {
        _uiState.update {
            it.copy(
                safetyCars = total.coerceIn(0, 99).toString(),
                scImport = ScImportState.Idle,
            )
        }
    }

    fun dismissSafetyCarImport() =
        _uiState.update { it.copy(scImport = ScImportState.Idle) }

    fun save() {
        val state = _uiState.value
        if (state.saving) return
        val race = state.race ?: return

        val usedDrivers = state.drivers.filter { it.hasData }.map { it.toDomain() }
        val result = RaceResultData(
            raceId = raceId,
            drivers = usedDrivers,
            // Campo vuoto = dato non confermato (null, N3): mai uno zero
            // inventato che falserebbe i pronostici over/under.
            safetyCars = state.safetyCars.toIntOrNull(),
            redFlags = state.redFlags.toIntOrNull() ?: 0,
            laps = state.laps.toIntOrNull() ?: 0,
            weather = state.weather.trim().ifBlank { null },
            notes = state.notes.trim().ifBlank { null },
        )

        val errors = RaceResultValidation.validate(result, isSprintRace = race.isSprint)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(fieldErrors = errors) }
            viewModelScope.launch { _events.emit(ResultEvent.Failure(errors.first())) }
            return
        }

        // Guard sincrona prima del launch: un doppio tocco non puo' avviare
        // due salvataggi in parallelo (la RPC e' idempotente, ma evitiamo
        // comunque scritture duplicate in volo).
        _uiState.update { it.copy(saving = true, fieldErrors = emptyList()) }
        viewModelScope.launch {
            try {
                raceRepository.saveResult(raceId, race.leagueId, result)
                _uiState.update { it.copy(saving = false, saved = true) }
                _events.emit(ResultEvent.Saved)
            } catch (e: UserException) {
                _uiState.update { it.copy(saving = false, fieldErrors = listOf(e.message)) }
                _events.emit(ResultEvent.Failure(e.message))
            } catch (e: Exception) {
                val message = e.message ?: "Impossibile salvare il risultato"
                _uiState.update { it.copy(saving = false, fieldErrors = listOf(message)) }
                _events.emit(ResultEvent.Failure(message))
            }
        }
    }
}
