package com.fantaformula1.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.AuthState
import com.fantaformula1.app.data.repository.UserException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AuthEvent {
    data object SignedIn : AuthEvent
    data object SignedUp : AuthEvent
    data object SignedOut : AuthEvent
    data class Failure(val message: String) : AuthEvent
}

data class AuthFormState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val mode: AuthMode = AuthMode.SIGN_IN,
    val emailError: String? = null,
    val passwordError: String? = null,
    val nameError: String? = null,
    val submitting: Boolean = false,
)

enum class AuthMode { SIGN_IN, SIGN_UP }

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Checking)

    private val _form = MutableStateFlow(AuthFormState())
    val form: StateFlow<AuthFormState> = _form

    private val _events = MutableSharedFlow<AuthEvent>()
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun onEmailChange(value: String) {
        _form.value = _form.value.copy(email = value, emailError = null)
    }

    fun onPasswordChange(value: String) {
        _form.value = _form.value.copy(password = value, passwordError = null)
    }

    fun onDisplayNameChange(value: String) {
        _form.value = _form.value.copy(displayName = value, nameError = null)
    }

    fun toggleMode() {
        _form.value = _form.value.copy(
            mode = if (_form.value.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN,
            emailError = null,
            passwordError = null,
            nameError = null,
        )
    }

    fun isConfigured(): Boolean = authRepository.isConfigured()

    fun submit() {
        val current = _form.value
        if (!validate(current)) return

        viewModelScope.launch {
            _form.value = _form.value.copy(submitting = true)
            try {
                when (current.mode) {
                    AuthMode.SIGN_IN -> {
                        authRepository.signIn(current.email, current.password)
                        _events.emit(AuthEvent.SignedIn)
                    }
                    AuthMode.SIGN_UP -> {
                        authRepository.signUp(current.email, current.password, current.displayName)
                        _events.emit(AuthEvent.SignedUp)
                    }
                }
            } catch (e: UserException) {
                _events.emit(AuthEvent.Failure(e.message))
            } catch (e: Exception) {
                _events.emit(
                    AuthEvent.Failure(e.message ?: "Si è verificato un errore durante l'accesso"),
                )
            } finally {
                _form.value = _form.value.copy(submitting = false)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { authRepository.signOut() }
            _events.emit(AuthEvent.SignedOut)
        }
    }

    private fun validate(state: AuthFormState): Boolean {
        val emailError = when {
            state.email.isBlank() -> EMAIL_VUOTO
            !EMAIL_REGEX.matches(state.email.trim()) -> EMAIL_FORMATO
            else -> null
        }
        val passwordError = if (state.password.length < MIN_PASSWORD) PASSWORD_CORTA else null
        val nameError = when {
            state.mode == AuthMode.SIGN_UP && state.displayName.trim().length < MIN_NAME -> NAME_CORTO
            else -> null
        }
        _form.value = state.copy(
            emailError = emailError,
            passwordError = passwordError,
            nameError = nameError,
        )
        return emailError == null && passwordError == null && nameError == null
    }

    companion object {
        const val MIN_PASSWORD = 6
        const val MIN_NAME = 2

        const val EMAIL_VUOTO = "Inserisci la tua email"
        const val EMAIL_FORMATO = "Inserisci un indirizzo email valido"
        const val PASSWORD_CORTA = "La password deve essere di almeno 6 caratteri"
        const val NAME_CORTO = "Inserisci un nome di almeno 2 caratteri"

        private val EMAIL_REGEX =
            Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
