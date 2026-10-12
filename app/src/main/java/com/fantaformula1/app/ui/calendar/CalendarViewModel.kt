package com.fantaformula1.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
import com.fantaformula1.app.data.util.parseLocalDateTimeInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CalendarEvent {
    data object Saved : CalendarEvent
    data object Deleted : CalendarEvent
    data class Failure(val message: String) : CalendarEvent
}

data class CalendarUiState(
    val loading: Boolean = true,
    val races: List<Race> = emptyList(),
    val isAdmin: Boolean = false,
    val error: String? = null,
    val submitting: Boolean = false,
    /** Form gara aperto (null = chiuso). */
    val editingRaceId: String? = null,
    val formOpen: Boolean = false,
    val form: RaceForm = RaceForm(),
    val formError: String? = null,
)

/** Dati grezzi del form gara: la conversione in ISO avviene nel ViewModel. */
data class RaceForm(
    val name: String = "",
    val round: String = "",
    val country: String = "",
    val isSprint: Boolean = false,
    val deadline: String = "",
)

class CalendarViewModel(
    private val raceRepository: RaceRepository,
    private val leagueRepository: LeagueRepository,
    private val authRepository: AuthRepository,
    private val leagueId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState

    private val _events = MutableSharedFlow<CalendarEvent>()
    val events: SharedFlow<CalendarEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = it.races.isEmpty(), error = null) }
            runCatching {
                val races = raceRepository.races(leagueId)
                val members = leagueRepository.members(leagueId)
                val me = authRepository.currentUserId()
                val admin = members.any { it.userId == me && it.isAdmin }
                races to admin
            }.onSuccess { (races, admin) ->
                _uiState.update {
                    it.copy(loading = false, races = races, isAdmin = admin)
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(loading = false, error = throwable.message ?: "Si è verificato un errore")
                }
            }
        }
    }

    fun openCreateForm() {
        val nextRound = (_uiState.value.races.maxOfOrNull { it.round } ?: 0) + 1
        _uiState.update {
            it.copy(
                editingRaceId = null,
                formOpen = true,
                form = RaceForm(round = nextRound.toString()),
                formError = null,
            )
        }
    }

    fun openEditForm(race: Race) {
        _uiState.update {
            it.copy(
                editingRaceId = race.id,
                formOpen = true,
                form = RaceForm(
                    name = race.name,
                    round = race.round.toString(),
                    country = race.country.orEmpty(),
                    isSprint = race.isSprint,
                    deadline = com.fantaformula1.app.data.util.formatLocalDateTimeInput(
                        race.deadline,
                    ),
                ),
                formError = null,
            )
        }
    }

    fun closeForm() {
        _uiState.update { it.copy(formOpen = false, formError = null) }
    }

    fun onNameChange(value: String) = updateForm { it.copy(name = value) }
    fun onRoundChange(value: String) = updateForm { it.copy(round = value.filter(Char::isDigit)) }
    fun onCountryChange(value: String) = updateForm { it.copy(country = value) }
    fun onSprintChange(value: Boolean) = updateForm { it.copy(isSprint = value) }
    fun onDeadlineChange(value: String) = updateForm { it.copy(deadline = value) }

    private fun updateForm(transform: (RaceForm) -> RaceForm) {
        _uiState.update { it.copy(form = transform(it.form), formError = null) }
    }

    fun submitForm() {
        val state = _uiState.value
        if (state.submitting) return
        val form = state.form

        val round = form.round.toIntOrNull()
        val validationError = when {
            form.name.trim().length < 2 -> "Il nome della gara deve essere lungo almeno 2 caratteri"
            round == null || round < 1 -> "Inserisci un numero di round valido (almeno 1)"
            form.deadline.isNotBlank() && parseLocalDateTimeInput(form.deadline) == null ->
                "La deadline deve essere nel formato 2026-03-15 14:00"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(formError = validationError) }
            return
        }

        val deadlineIso = parseLocalDateTimeInput(form.deadline)?.toString()
        val editingId = state.editingRaceId

        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true) }
            try {
                if (editingId == null) {
                    raceRepository.createRace(
                        leagueId = leagueId,
                        name = form.name,
                        round = round!!,
                        country = form.country,
                        isSprint = form.isSprint,
                        deadline = deadlineIso,
                    )
                } else {
                    raceRepository.updateRace(
                        raceId = editingId,
                        name = form.name,
                        round = round!!,
                        country = form.country,
                        isSprint = form.isSprint,
                        deadline = deadlineIso,
                    )
                }
                load()
                _uiState.update { it.copy(formOpen = false, editingRaceId = null, formError = null) }
                _events.emit(CalendarEvent.Saved)
            } catch (e: UserException) {
                _uiState.update { it.copy(formError = e.message) }
                _events.emit(CalendarEvent.Failure(e.message))
            } catch (e: Exception) {
                val message = e.message ?: "Impossibile salvare la gara"
                _uiState.update { it.copy(formError = message) }
                _events.emit(CalendarEvent.Failure(message))
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }

    fun deleteRace(race: Race) {
        viewModelScope.launch {
            try {
                raceRepository.deleteRace(race.id)
                load()
                _events.emit(CalendarEvent.Deleted)
            } catch (e: UserException) {
                _events.emit(CalendarEvent.Failure(e.message))
            } catch (e: Exception) {
                _events.emit(
                    CalendarEvent.Failure(e.message ?: "Impossibile eliminare la gara"),
                )
            }
        }
    }
}
