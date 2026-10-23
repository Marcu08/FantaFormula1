package com.fantaformula1.app.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.data.config.SupabaseConfig
import com.fantaformula1.app.data.config.isValidSupabaseUrl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Eventi della schermata configurazione: l'UI li traduce in testi da strings.xml. */
sealed interface ConfigEvent {
    data object Saved : ConfigEvent
    data object Reset : ConfigEvent
    data class Failure(val message: String) : ConfigEvent
}

/** Stato della schermata di configurazione Supabase. */
data class ConfigUiState(
    val url: String = "",
    val anonKey: String = "",
    val isConfigured: Boolean = false,
    val hasOverride: Boolean = false,
    val urlError: String? = null,
    val keyError: String? = null,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    /** null = nessuna prova eseguita, "ok" = riuscita, "ko" = fallita. */
    val testResult: String? = null,
    val loading: Boolean = true,
)

class ConfigViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState

    private val _events = MutableSharedFlow<ConfigEvent>()
    val events: SharedFlow<ConfigEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val config = container.configStore.current()
            val override = container.configStore.hasOverride.first()
            _uiState.value = _uiState.value.copy(
                url = config.url,
                anonKey = config.anonKey,
                isConfigured = config.isConfigured,
                hasOverride = override,
                loading = false,
            )
        }
    }

    fun onUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(url = value, urlError = null, testResult = null)
    }

    fun onKeyChange(value: String) {
        _uiState.value = _uiState.value.copy(anonKey = value, keyError = null, testResult = null)
    }

    /** Validazione puramente locale: nessuna chiamata di rete. */
    fun validate(): Boolean {
        val state = _uiState.value
        val urlError = when {
            state.url.isBlank() -> URL_VUOTO
            !isValidSupabaseUrl(state.url) -> URL_FORMATO
            else -> null
        }
        val keyError = if (state.anonKey.isBlank()) CHIAVE_VUOTA else null
        _uiState.value = state.copy(urlError = urlError, keyError = keyError)
        return urlError == null && keyError == null
    }

    fun save() {
        if (!validate()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val config = SupabaseConfig(
                url = _uiState.value.url.trim(),
                anonKey = _uiState.value.anonKey.trim(),
            )
            val applied = runCatching {
                container.configStore.save(config.url, config.anonKey)
                container.supabaseHolder.configure(config)
                container.authRepository.refresh()
                true
            }.getOrElse { false }

            _uiState.value = _uiState.value.copy(
                isSaving = false,
                isConfigured = applied,
                hasOverride = true,
            )
            if (applied) {
                _events.emit(ConfigEvent.Saved)
            } else {
                _events.emit(ConfigEvent.Failure(SALVATAGGIO_FALLITO))
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            runCatching {
                container.configStore.clearOverrides()
                val config = container.configStore.current()
                container.supabaseHolder.configure(config)
                container.authRepository.refresh()
                _uiState.value = _uiState.value.copy(
                    url = config.url,
                    anonKey = config.anonKey,
                    isConfigured = config.isConfigured,
                    hasOverride = false,
                    urlError = null,
                    keyError = null,
                    testResult = null,
                )
            }
            _events.emit(ConfigEvent.Reset)
        }
    }

    /**
     * Verifica reale della connessione con i valori SCRITTI NEL FORM, senza
     * salvarli e senza toccare il client globale (N10): usa un client
     * temporaneo che viene chiuso a fine test, quindi un test fallito non
     * lascia l'app su una configurazione sbagliata.
     */
    fun testConnection() {
        if (!validate()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null)
            val config = SupabaseConfig(
                url = _uiState.value.url.trim(),
                anonKey = _uiState.value.anonKey.trim(),
            )
            val ok = container.supabaseHolder.testConnection(config)

            _uiState.value = _uiState.value.copy(
                isTesting = false,
                testResult = if (ok) "ok" else "ko",
            )
        }
    }

    companion object {
        /** Messaggi di errore in italiano, usati anche dai test. */
        const val URL_VUOTO = "Inserisci l'URL del progetto"
        const val URL_FORMATO =
            "Inserisci un URL valido, es. https://abcdefgh.supabase.co"
        const val CHIAVE_VUOTA = "La chiave anon non può essere vuota"
        const val SALVATAGGIO_FALLITO = "Impossibile applicare la configurazione"
    }
}
