package com.fantaformula1.app.data.sync

import com.fantaformula1.app.core.domain.Race
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Stato di sincronizzazione osservato dalla UI. */
data class F1SyncUiState(
    val isSyncing: Boolean = false,
    val lastCalendarSync: Instant? = null,
    val lastResultsSync: Instant? = null,
)

/**
 * Gestore dello stato di sincronizzazione F1: un unico punto per UI e
 * WorkManager che impedisce sincronizzazioni concorrenti, aggiorna
 * l'indicatore "Ultimo aggiornamento" e registra i timestamp su DataStore.
 *
 * Le funzioni restituiscono [SyncResult] della sincronizzazione appena
 * completata, oppure null se una sincronizzazione era gia' in corso (la
 * richiesta viene ignorata, non accodata).
 */
class F1SyncStateHolder(
    private val repository: F1SyncRepository,
    private val prefs: F1SyncPrefs,
) {

/**
     * Mutex di sincronizzazione: garantisce che una sola operazione (calendario
     * o risultati, manuale o da WorkManager) sia in corso. `tryLock` rende il
     * controllo atomico, senza gare di stato sul flag `isSyncing`.
     */
    private val syncMutex = Mutex()

    private val _state = MutableStateFlow(F1SyncUiState())
    val state: StateFlow<F1SyncUiState> = _state.asStateFlow()

    /**
     * Sincronizza il calendario. Null se una sincronizzazione e' gia' in corso.
     * Il timestamp viene registrato solo sul successo, cosi' l'indicatore
     * "Ultimo aggiornamento" resta sincrono con i dati effettivamente scaricati.
     */
    suspend fun syncCalendar(leagueId: String): SyncResult? {
        if (!syncMutex.tryLock()) return null
        try {
            _state.update { it.copy(isSyncing = true) }
            val result = repository.syncCalendar(leagueId)
            if (result is SyncResult.Success) {
                prefs.markCalendarSynced()
                _state.update { it.copy(lastCalendarSync = Instant.now()) }
            }
            return result
        } finally {
            _state.update { it.copy(isSyncing = false) }
            syncMutex.unlock()
        }
    }

    /**
     * Sincronizza i risultati. Null se una sincronizzazione e' gia' in corso.
     */
    suspend fun syncResults(leagueId: String): SyncResult? {
        if (!syncMutex.tryLock()) return null
        try {
            _state.update { it.copy(isSyncing = true) }
            val result = repository.syncResults(leagueId)
            if (result is SyncResult.Success) {
                prefs.markResultsSynced()
                _state.update { it.copy(lastResultsSync = Instant.now()) }
            }
            return result
        } finally {
            _state.update { it.copy(isSyncing = false) }
            syncMutex.unlock()
        }
    }

    /**
     * Rigenera il risultato di UNA gara da Jolpica (azione esplicita
     * dell'admin su una gara gia' completata). Null se una sincronizzazione
     * era gia' in corso.
     */
    suspend fun refreshResult(race: Race): SyncResult? {
        if (!syncMutex.tryLock()) return null
        try {
            _state.update { it.copy(isSyncing = true) }
            val result = repository.refreshRaceResult(race)
            if (result is SyncResult.Success) {
                prefs.markResultsSynced()
                _state.update { it.copy(lastResultsSync = Instant.now()) }
            }
            return result
        } finally {
            _state.update { it.copy(isSyncing = false) }
            syncMutex.unlock()
        }
    }

    /** Carica gli ultimi timestamp salvati: chiamato all'avvio dell'app. */
    suspend fun refresh() {
        val saved = prefs.current()
        _state.update {
            it.copy(
                lastCalendarSync = saved.calendar,
                lastResultsSync = saved.results,
            )
        }
    }
}