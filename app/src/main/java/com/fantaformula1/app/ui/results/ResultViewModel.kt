package com.fantaformula1.app.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.DriverRaceResult
import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
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
)

class ResultViewModel(
    private val raceRepository: RaceRepository,
    private val leagueRepository: LeagueRepository,
    private val authRepository: AuthRepository,
    private val raceId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState

    private val _events = MutableSharedFlow<ResultEvent>()
    val events: SharedFlow<ResultEvent> = _events.asSharedFlow()

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
        _uiState.update { it.copy(safetyCars = value.filter(Char::isDigit).take(2)) }

    fun onRedFlagsChange(value: String) =
        _uiState.update { it.copy(redFlags = value.filter(Char::isDigit).take(2)) }

    fun onLapsChange(value: String) =
        _uiState.update { it.copy(laps = value.filter(Char::isDigit).take(3)) }

    fun onWeatherChange(value: String) =
        _uiState.update { it.copy(weather = value.take(40)) }

    fun onNotesChange(value: String) =
        _uiState.update { it.copy(notes = value.take(200)) }

    fun save() {
        val state = _uiState.value
        if (state.saving) return
        val race = state.race ?: return

        val usedDrivers = state.drivers.filter { it.hasData }.map { it.toDomain() }
        val result = RaceResultData(
            raceId = raceId,
            drivers = usedDrivers,
            safetyCars = state.safetyCars.toIntOrNull() ?: 0,
            redFlags = state.redFlags.toIntOrNull() ?: 0,
            laps = state.laps.toIntOrNull() ?: 0,
            weather = state.weather.trim().ifBlank { null },
            notes = state.notes.trim().ifBlank { null },
        )

        viewModelScope.launch {
            val errors = RaceResultValidation.validate(result, isSprintRace = race.isSprint)
            if (errors.isNotEmpty()) {
                _uiState.update { it.copy(fieldErrors = errors) }
                _events.emit(ResultEvent.Failure(errors.first()))
                return@launch
            }

            _uiState.update { it.copy(saving = true, fieldErrors = emptyList()) }
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
