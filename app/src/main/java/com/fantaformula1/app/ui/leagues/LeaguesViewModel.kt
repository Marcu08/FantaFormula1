package com.fantaformula1.app.ui.leagues

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.LeagueMember
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.MemberRole
import com.fantaformula1.app.data.repository.UserException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LeaguesEvent {
    data class LeagueCreated(val leagueId: String) : LeaguesEvent
    data class LeagueJoined(val leagueId: String) : LeaguesEvent
    data class Failure(val message: String) : LeaguesEvent
}

data class LeaguesUiState(
    val loading: Boolean = true,
    val leagues: List<League> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val submitting: Boolean = false,
)

data class MembersUiState(
    val loading: Boolean = true,
    val members: List<LeagueMember> = emptyList(),
    val isAdmin: Boolean = false,
    val league: League? = null,
    val error: String? = null,
    /** Id dell'utente corrente: distingue la propria riga dalle altre (R3). */
    val myUserId: String? = null,
)

class LeaguesViewModel(
    private val leagueRepository: LeagueRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LeaguesUiState())
    val uiState: StateFlow<LeaguesUiState> = _uiState

    private val _membersState = MutableStateFlow(MembersUiState())
    val membersState: StateFlow<MembersUiState> = _membersState

    private val _events = MutableSharedFlow<LeaguesEvent>()
    val events: SharedFlow<LeaguesEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = it.leagues.isEmpty(), error = null) }
            runCatching { leagueRepository.myLeagues() }
                .onSuccess { leagues ->
                    _uiState.update { it.copy(loading = false, refreshing = false, leagues = leagues) }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = throwable.message ?: "Si è verificato un errore",
                        )
                    }
                }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(refreshing = true) }
        load()
    }

    fun createLeague(name: String) {
        if (_uiState.value.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true) }
            try {
                val league = leagueRepository.createLeague(name, Rules.default())
                load()
                _events.emit(LeaguesEvent.LeagueCreated(league.id))
            } catch (e: UserException) {
                _events.emit(LeaguesEvent.Failure(e.message))
            } catch (e: Exception) {
                _events.emit(LeaguesEvent.Failure(e.message ?: "Impossibile creare la lega"))
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }

    fun joinByCode(code: String) {
        if (_uiState.value.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true) }
            try {
                val leagueId = leagueRepository.joinByCode(code)
                load()
                _events.emit(LeaguesEvent.LeagueJoined(leagueId))
            } catch (e: UserException) {
                _events.emit(LeaguesEvent.Failure(e.message))
            } catch (e: Exception) {
                _events.emit(LeaguesEvent.Failure(e.message ?: "Impossibile unirsi alla lega"))
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }

    fun loadMembers(leagueId: String) {
        viewModelScope.launch {
            _membersState.update { it.copy(loading = true, error = null) }
            runCatching {
                val league = leagueRepository.league(leagueId)
                val members = leagueRepository.members(leagueId)
                league to members
            }.onSuccess { (league, members) ->
                val me = authRepository.currentUserId()
                _membersState.update {
                    it.copy(
                        loading = false,
                        members = members,
                        league = league,
                        myUserId = me,
                        isAdmin = members.any { member -> member.userId == me && member.isAdmin },
                    )
                }
            }.onFailure { throwable ->
                _membersState.update {
                    it.copy(
                        loading = false,
                        error = throwable.message ?: "Si è verificato un errore",
                    )
                }
            }
        }
    }

    fun leaveLeague(leagueId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { leagueRepository.leaveLeague(leagueId) }
                .onSuccess {
                    load()
                    onDone()
                }
                .onFailure { throwable ->
                    _events.emit(
                        LeaguesEvent.Failure(throwable.message ?: "Impossibile abbandonare la lega"),
                    )
                }
        }
    }

    /** Cambia il ruolo di un membro (solo admin, R3) e ricarica la lista. */
    fun setMemberRole(leagueId: String, userId: String, role: MemberRole, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { leagueRepository.setMemberRole(leagueId, userId, role) }
                .onSuccess {
                    loadMembers(leagueId)
                    onDone()
                }
                .onFailure { throwable ->
                    _events.emit(
                        LeaguesEvent.Failure(throwable.message ?: "Impossibile cambiare il ruolo"),
                    )
                }
        }
    }

    /** Rimuove un membro dalla lega (solo admin, R3) e ricarica la lista. */
    fun removeMember(leagueId: String, userId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { leagueRepository.removeMember(leagueId, userId) }
                .onSuccess {
                    loadMembers(leagueId)
                    onDone()
                }
                .onFailure { throwable ->
                    _events.emit(
                        LeaguesEvent.Failure(throwable.message ?: "Impossibile rimuovere il membro"),
                    )
                }
        }
    }

    /** Esce dall'account corrente: l'UI torna alla schermata di accesso. */
    fun signOut() {
        viewModelScope.launch {
            runCatching { authRepository.signOut() }
        }
    }
}
