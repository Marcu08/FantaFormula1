package com.fantaformula1.app.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.TieBreaker
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.UserException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RulesEvent {
    data object Saved : RulesEvent
    data class Failure(val message: String) : RulesEvent
}

data class RulesUiState(
    val loading: Boolean = true,
    val league: League? = null,
    val isAdmin: Boolean = false,
    val rules: Rules = Rules(),
    val saving: Boolean = false,
    val errors: List<String> = emptyList(),
    val error: String? = null,
)

class RulesViewModel(
    private val leagueRepository: LeagueRepository,
    private val authRepository: AuthRepository,
    private val leagueId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RulesUiState())
    val uiState: StateFlow<RulesUiState> = _uiState

    private val _events = MutableSharedFlow<RulesEvent>()
    val events: SharedFlow<RulesEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching {
                val league = leagueRepository.league(leagueId)
                val members = leagueRepository.members(leagueId)
                val me = authRepository.currentUserId()
                val admin = members.any { it.userId == me && it.isAdmin }
                league to admin
            }.onSuccess { (league, admin) ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        league = league,
                        isAdmin = admin,
                        rules = league?.rules ?: Rules(),
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

    fun applyPreset(preset: Rules) {
        _uiState.update { it.copy(rules = preset, errors = emptyList()) }
    }

    fun onNameChange(value: String) = update { it.copy(name = value.take(60)) }

    fun toggleType(type: PredictionType) = update { rules ->
        val enabled = if (rules.enabledTypes.contains(type)) {
            rules.enabledTypes - type
        } else {
            rules.enabledTypes + type
        }
        rules.copy(enabledTypes = enabled)
    }

    fun onPointsChange(type: PredictionType, value: String) = update { rules ->
        val parsed = value.filter { it.isDigit() || it == '.' }.take(6).toDoubleOrNull() ?: 0.0
        rules.copy(points = rules.points + (type to parsed))
    }

    fun onPodiumPointChange(index: Int, value: String) = update { rules ->
        val parsed = value.filter { it.isDigit() || it == '.' }.take(6).toDoubleOrNull() ?: 0.0
        val updated = rules.podiumPoints.toMutableList()
        while (updated.size <= index) updated.add(0.0)
        updated[index] = parsed
        rules.copy(podiumPoints = updated)
    }

    fun onPodiumNoOrderChange(value: String) = update { rules ->
        val parsed = value.filter { it.isDigit() || it == '.' }.take(6).toDoubleOrNull() ?: 0.0
        rules.copy(podiumNoOrderPoints = parsed)
    }

    fun onNearMissFactorChange(value: String) {
        val parsed = parseMultiplier(value) ?: return
        update { it.copy(exactPositionNearMissFactor = parsed) }
    }

    // onDnfMultiplierChange rimosso: il moltiplicatore DNF e' deprecato (B11).

    fun onPenaltyMultiplierChange(value: String) {
        val parsed = parseMultiplier(value) ?: return
        update { it.copy(penaltyMultiplier = parsed) }
    }

    fun onSprintMultiplierChange(value: String) {
        val parsed = parseMultiplier(value) ?: return
        update { it.copy(sprintPointsMultiplier = parsed) }
    }

    private fun parseMultiplier(raw: String): Double? =
        raw.filter { it.isDigit() || it == '.' }.take(5).toDoubleOrNull()

    fun onTieBreakerChange(tieBreaker: TieBreaker) =
        update { it.copy(tieBreaker = tieBreaker) }

    fun onRevealOthersChange(value: Boolean) = update { it.copy(revealOthersAfterDeadline = value) }

    fun onAllowEditChange(value: Boolean) = update { it.copy(allowEditUntilDeadline = value) }

    private fun update(transform: (Rules) -> Rules) {
        _uiState.update { it.copy(rules = transform(it.rules), errors = emptyList()) }
    }

    fun save() {
        val state = _uiState.value
        if (state.saving || !state.isAdmin) return

        viewModelScope.launch {
            val errors = state.rules.validate()
            if (errors.isNotEmpty()) {
                _uiState.update { it.copy(errors = errors) }
                _events.emit(RulesEvent.Failure(errors.first()))
                return@launch
            }

            _uiState.update { it.copy(saving = true, errors = emptyList()) }
            try {
                leagueRepository.updateRules(leagueId, state.rules)
                _uiState.update { it.copy(saving = false) }
                _events.emit(RulesEvent.Saved)
            } catch (e: UserException) {
                _uiState.update { it.copy(saving = false, errors = listOf(e.message)) }
                _events.emit(RulesEvent.Failure(e.message))
            } catch (e: Exception) {
                val message = e.message ?: "Impossibile salvare le regole"
                _uiState.update { it.copy(saving = false, errors = listOf(message)) }
                _events.emit(RulesEvent.Failure(message))
            }
        }
    }
}
