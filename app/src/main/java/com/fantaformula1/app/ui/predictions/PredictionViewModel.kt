package com.fantaformula1.app.ui.predictions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantaformula1.app.core.domain.DriverSeasonStats
import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.PredictionValidation
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceQuote
import com.fantaformula1.app.core.domain.QuoteKeys
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.isSprintRelated
import com.fantaformula1.app.core.domain.italianLabel
import com.fantaformula1.app.core.domain.usesServerQuote
import com.fantaformula1.app.core.domain.withPublishedQuote
import com.fantaformula1.app.data.offline.OfflineCacheStore
import com.fantaformula1.app.data.offline.PendingPredictionWrite
import com.fantaformula1.app.data.offline.PendingWritesStore
import com.fantaformula1.app.data.offline.isOfflineError
import com.fantaformula1.app.data.quotes.QuoteService
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.PredictionRepository
import com.fantaformula1.app.data.repository.QuotePublishOutcome
import com.fantaformula1.app.data.repository.RaceQuoteLoad
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
import com.fantaformula1.app.data.repository.LeagueDataSnapshot
import com.fantaformula1.app.data.repository.restrictToRaceIds
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
    /** F3: salvataggio messo in coda perche' la rete non era disponibile. */
    data object Queued : PredictionEvent
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

/**
 * Esito della risoluzione delle voci da salvare. Se [missingQuoteTypes] non
 * e' vuoto il salvataggio va bloccato: una voce a quota non puo' sparire
 * silenziosamente perche' il server non ha pubblicato quella scelta.
 */
internal data class ResolvedPredictionEntries(
    val entries: List<PredictionEntry>,
    val missingQuoteTypes: List<PredictionType>,
)

internal fun resolveEntriesForSave(
    drafts: List<DraftEntry>,
    quotes: List<RaceQuote>,
    mine: RacePrediction?,
): ResolvedPredictionEntries {
    val entries = mutableListOf<PredictionEntry>()
    val missing = mutableListOf<PredictionType>()

    drafts.filter { it.complete }.forEach { draft ->
        val entry = draft.toDomain()
        if (!entry.type.usesServerQuote) {
            entries += entry
            return@forEach
        }

        val fromServer = entry.withPublishedQuote(quotes)
        if (fromServer != null) {
            entries += fromServer
            return@forEach
        }

        // Voce "vintage" gia' salvata con la stessa scelta: conservala invece
        // di perdere dati quando la migrazione 0013 non e' disponibile.
        val previous = mine?.entries
            ?.firstOrNull { it.type == entry.type }
            ?.takeIf { QuoteKeys.of(it) == QuoteKeys.of(entry) }
        if (previous != null) {
            entries += previous
        } else {
            missing += entry.type
        }
    }

    return ResolvedPredictionEntries(
        entries = entries,
        missingQuoteTypes = missing.distinct(),
    )
}

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
    /**
     * Incrementato solo quando un tentativo di salvataggio viene fermato da
     * un errore di validazione. La schermata lo usa per riaprire le sezioni.
     */
    val saveAttemptFailed: Int = 0,
    /** Risultato della gara, se gia' disponibile (per la spiegazione U1). */
    val result: com.fantaformula1.app.core.domain.RaceResultData? = null,
    /** U5: true se alla riapertura è stata ripristinata una bozza locale. */
    val draftRestored: Boolean = false,
    /** F3: true se i dati mostrati arrivano dalla cache (rete non disponibile). */
    val offline: Boolean = false,
    /** F3: quando e' stato aggiornato l'ultimo dato di rete mostrato. */
    val lastUpdatedEpochMs: Long? = null,
    /** 0013: quote pubblicate dal server per questa gara. */
    val quotes: List<RaceQuote> = emptyList(),
    /** 0013: esito lettura quote (determina il messaggio in UI). */
    val quotesLoad: RaceQuoteLoad = RaceQuoteLoad.NotPublished,
    /** 0013: l'utente corrente e' admin della lega (pubblicazione quote). */
    val isAdmin: Boolean = false,
    /** 0013: messaggio di un errore di pubblicazione quote (solo admin). */
    val quoteError: String? = null,
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
    private val authRepository: AuthRepository,
    private val leagueDataRepository: LeagueDataRepository,
    private val seasonResultsStore: SeasonResultsStore? = null,
    private val raceId: String,
    private val draftStore: com.fantaformula1.app.data.prediction.PredictionDraftStore? = null,
    /** F3: cache offline del "mio pronostico" (opzionale per i test). */
    private val offlineCache: OfflineCacheStore? = null,
    /** F3: coda salvataggi offline (opzionale per i test). */
    private val pendingWritesStore: PendingWritesStore? = null,
    /** 0013: lettura/pubblicazione delle quote lato server. */
    private val quoteService: QuoteService? = null,
) : ViewModel() {

    companion object {
        const val NIENTE_COMPILATO = "Compila almeno un pronostico prima di inviare"
    }

    private val _uiState = MutableStateFlow(PredictionUiState())
    val uiState: StateFlow<PredictionUiState> = _uiState

    private val _events = MutableSharedFlow<PredictionEvent>()
    val events: SharedFlow<PredictionEvent> = _events.asSharedFlow()

    /** 0013: la pubblicazione delle quote si tenta una volta per apertura. */
    private var quotePublishAttempted = false

    /**
     * Utente proprietario dei dati locali di questa schermata. Viene
     * catturato all'inizio di [load] e usato per cache, bozze e coda offline:
     * un cambio account durante una richiesta non deve scrivere dati di A
     * sotto la chiave di B.
     */
    private var localOwnerId: String? = null

    init {
        load()
    }

    fun load() {
        val owner = authRepository.currentUserId()
        localOwnerId = owner
        viewModelScope.launch {
            // Un ritorno sulla schermata non deve nascondere i dati gia'
            // caricati dietro lo spinner a schermo intero: lo spinner serve
            // solo al primo caricamento (o dopo un errore senza dati).
            val hasContent = _uiState.value.race != null
            _uiState.update { it.copy(loading = !hasContent, error = null) }
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
                // Le statistiche/quote della lega valgono solo per la stagione
                // corrente: le gare di stagioni archiviate non devono
                // contaminare medie, DNF e fasce.
                val seasonSnapshot = snapshot.restrictToRaceIds(roundsById.keys)
                val leagueResults = seasonSnapshot.races.mapNotNull { legaRaceData ->
                    legaRaceData.result?.let { result ->
                        DriverSeasonStats.StampedResult(
                            round = roundsById[legaRaceData.raceId],
                            result = result,
                        )
                    }
                }
                // U1: risultato della gara corrente, per spiegare i punti.
                val raceResult = seasonSnapshot.races
                    .firstOrNull { it.raceId == raceId }?.result

                // Statistiche IMMEDIATE: cache stagione (ZERO rete) + lega.
                // La schermata non aspetta mai la rete per le statistiche.
                val cachedReal = runCatching { seasonResultsStore?.current() }
                    .getOrNull()?.results?.map {
                        DriverSeasonStats.StampedResult(round = it.round, result = it.result)
                    }.orEmpty()
                val stats = DriverSeasonStats.fromMergedResults(leagueResults, cachedReal)

                // 0013: quote pubblicate dal server per QUESTA gara.
                val isAdmin = leagueRepository.isAdmin(race.leagueId)
                val quotesLoad = quoteService?.loadQuotes(raceId) ?: RaceQuoteLoad.NotPublished

                RaceLoad(
                    race, league, mine, others.map { (id, p) ->
                        OtherPrediction(id, names[id] ?: "Giocatore", p)
                    }, leagueResults, stats, raceResult, isAdmin, quotesLoad,
                )
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
                val localDrafts = if (owner == null) {
                    null
                } else {
                    try {
                        draftStore?.load(raceId, expectedUserId = owner)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val mergedDrafts = mergeDrafts(serverDrafts, localDrafts)
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
                        offline = false,
                        lastUpdatedEpochMs = null,
                        quotes = (load.quotesLoad as? RaceQuoteLoad.Published)?.quotes.orEmpty(),
                        quotesLoad = load.quotesLoad,
                        isAdmin = load.isAdmin,
                        quoteError = null,
                    )
                }
                // 0013 — pubblicazione PIGRA (solo admin, una volta per
                // apertura): la gara aperta senza quote le riceve adesso, con
                // le statistiche appena calcolate. I membri leggono e basta.
                val service = quoteService
                if (!quotePublishAttempted && service != null &&
                    load.isAdmin && race.isPredictionOpen() &&
                    load.quotesLoad == RaceQuoteLoad.NotPublished
                ) {
                    quotePublishAttempted = true
                    viewModelScope.launch {
                        try {
                            val outcome = service.publishForOpenedRace(race, load.stats)
                            val reloaded = service.loadQuotes(raceId)
                            _uiState.update {
                                it.copy(
                                    quotes = (reloaded as? RaceQuoteLoad.Published)
                                        ?.quotes.orEmpty(),
                                    quotesLoad = reloaded,
                                    quoteError = (outcome as? QuotePublishOutcome.Failed)?.message,
                                )
                            }
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (e: Exception) {
                            _uiState.update {
                                it.copy(
                                    quoteError = e.message
                                        ?: "Impossibile pubblicare le quote",
                                )
                            }
                        }
                    }
                }
                // F3: la rete funziona -> il pronostico viene SEMPRE
                // salvato in cache per il ripristino offline.
                mine?.let { saved -> rememberPrediction(race.leagueId, saved) }
                // RINFRESCO IN BACKGROUND (incrementale, max 6h, timeout 10s
                // a richiesta): non blocca la schermata e nessun errore
                // visibile se fallisce. Quando arriva aggiorna le statistiche.
                viewModelScope.launch {
                    val refreshed = runCatching { seasonResultsStore?.ensureFresh() }.getOrNull()
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
                // F3: rete fallita -> si prova a ripristinare gara, lega e
                // pronostico dalla cache; solo senza cache l'errore resta
                // bloccante come prima.
                if (!restoreFromCache()) {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = throwable.message ?: "Si è verificato un errore",
                        )
                    }
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
        val owner = localOwnerId
        if (owner == null) return
        viewModelScope.launch {
            try {
                draftStore?.save(
                    raceId,
                    snapshot,
                    expectedUserId = owner,
                )
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

        // 0013: quota e soglia arrivano SOLO dal server (`race_quotes`), mai
        // ricalcolate localmente. Una voce completa per cui il server non ha
        // pubblicato la scelta NON viene scartata in silenzio: si blocca il
        // salvataggio e si spiega il problema. Le voci vintage identiche
        // restano conservate per compatibilita'.
        val resolved = resolveEntriesForSave(
            drafts = state.drafts,
            quotes = state.quotes,
            mine = state.mine,
        )
        val entries = resolved.entries

        if (resolved.missingQuoteTypes.isNotEmpty()) {
            val message = "Quote non disponibili per: " +
                resolved.missingQuoteTypes.joinToString(", ") { it.italianLabel } +
                ". Riprova quando l'admin le ha pubblicate."
            _uiState.update {
                it.copy(
                    errors = listOf(message),
                    warning = null,
                    saveAttemptFailed = it.saveAttemptFailed + 1,
                )
            }
            viewModelScope.launch { _events.emit(PredictionEvent.Failure(message)) }
            return
        }

        val prediction = RacePrediction(raceId = raceId, entries = entries)

        if (entries.isEmpty()) {
            _uiState.update {
                it.copy(
                    errors = listOf(NIENTE_COMPILATO),
                    warning = null,
                    saveAttemptFailed = it.saveAttemptFailed + 1,
                )
            }
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
            _uiState.update {
                it.copy(
                    errors = errors,
                    warning = warning,
                    saveAttemptFailed = it.saveAttemptFailed + 1,
                )
            }
            viewModelScope.launch { _events.emit(PredictionEvent.Failure(errors.first())) }
            return
        }

        // Guard sincrona prima del launch: un doppio tocco non avvia due
        // salvataggi in parallelo (l'upsert e' idempotente, ma evitiamo
        // comunque richieste duplicate in volo).
        _uiState.update { it.copy(saving = true, errors = emptyList(), warning = warning) }
        viewModelScope.launch {
            try {
                predictionRepository.savePrediction(
                    raceId,
                    race.leagueId,
                    prediction,
                    expectedUserId = localOwnerId,
                )
                // U5: bozza inviata -> rimossa dal locale (il server e' ora
                // la sorgente di verita').
                try {
                    draftStore?.clear(raceId, expectedUserId = localOwnerId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best-effort: una bozza residua viene sovrascritta al
                    // prossimo salvataggio.
                }
                // F3: il pronostico appena salvato alimenta la cache offline.
                rememberPrediction(race.leagueId, prediction)
                _uiState.update { it.copy(saving = false, saved = true, mine = prediction) }
                _events.emit(PredictionEvent.Saved)
            } catch (e: UserException) {
                if (isOfflineError(e)) {
                    // F3: rete assente -> in coda, niente errore rosso.
                    queueOfflineSave(prediction)
                } else {
                    _uiState.update { it.copy(saving = false, errors = listOf(e.message)) }
                    _events.emit(PredictionEvent.Failure(e.message))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isOfflineError(e)) {
                    // F3: rete assente -> in coda, niente errore rosso.
                    queueOfflineSave(prediction)
                } else {
                    val message = e.message ?: "Impossibile salvare il pronostico"
                    _uiState.update { it.copy(saving = false, errors = listOf(message)) }
                    _events.emit(PredictionEvent.Failure(message))
                }
            }
        }
    }

    /**
     * F3 — "stale-while-error": se la rete non risponde, gara, lega e
     * pronostico proprio vengono ripristinati dalla cache locale e la
     * schermata mostra il banner Offline invece dell'errore bloccante.
     *
     * @return true se la cache ha potuto ripristinare la schermata.
     */
    private suspend fun restoreFromCache(): Boolean {
        val cache = offlineCache ?: return false
        val owner = localOwnerId ?: return false
        val cachedRace = cache.race(raceId, expectedUserId = owner) ?: return false
        val race = cachedRace.value
        val league = cache.leagues(expectedUserId = owner)
            ?.value?.firstOrNull { it.id == race.leagueId }
        val cachedPredictions = cache.predictions(race.leagueId, expectedUserId = owner)
        val mine = cachedPredictions?.value?.get(raceId)
        val rules = league?.rules ?: Rules()
        // B9/B10: la decisione "gara aperta" vale anche offline (sul clock).
        val open = race.isPredictionOpen()
        _uiState.update {
            it.copy(
                loading = false,
                race = race,
                league = league,
                rules = rules,
                mine = mine,
                drafts = buildDrafts(rules, race, mine),
                canSubmit = open && (mine == null || rules.allowEditUntilDeadline),
                deadlinePassed = !open,
                canRevealOthers = !rules.revealOthersAfterDeadline || !open,
                saved = mine != null,
                offline = true,
                lastUpdatedEpochMs = maxOf(
                    cachedRace.cachedAtEpochMs,
                    cachedPredictions?.cachedAtEpochMs ?: 0L,
                ),
            )
        }
        return true
    }

    /** F3: aggiorna la cache locale del "mio pronostico" (best-effort). */
    private suspend fun rememberPrediction(leagueId: String, prediction: RacePrediction) {
        val cache = offlineCache ?: return
        val owner = localOwnerId ?: return
        val existing = cache.predictions(leagueId, expectedUserId = owner)
            ?.value.orEmpty()
        cache.savePredictions(
            leagueId,
            existing + (raceId to prediction),
            expectedUserId = owner,
        )
    }

    /**
     * F3 — la rete non ha fatto arrivare il salvataggio: il pronostico va
     * nella coda ([PendingWritesStore]) e il worker periodico riprovera'.
     */
    private fun queueOfflineSave(prediction: RacePrediction) {
        val owner = localOwnerId ?: return
        viewModelScope.launch {
            val store = pendingWritesStore
            val queued = if (store == null) {
                false
            } else {
                try {
                    store.enqueue(
                        PendingPredictionWrite(
                            raceId = raceId,
                            prediction = prediction,
                            queuedAtEpochMs = Instant.now().toEpochMilli(),
                            userId = owner,
                        ),
                        expectedUserId = owner,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
            if (queued) {
                // La bozza resta locale: il worker riprovera' appena torna
                // la rete, e lo snackbar lo comunica senza errore rosso.
                _uiState.update { it.copy(saving = false) }
                _events.emit(PredictionEvent.Queued)
            } else {
                val message = "Impossibile salvare il pronostico"
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
    val isAdmin: Boolean,
    val quotesLoad: RaceQuoteLoad,
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

/**
 * Conteggio dei tipi visibili nella schermata che hanno una bozza completa.
 * Le bozze di tipi non visibili (es. sprint in una gara normale) non entrano
 * nel totale, e un'eventuale bozza duplicata non gonfia il conteggio.
 */
internal fun completedPredictionCount(
    drafts: List<DraftEntry>,
    visibleTypes: List<PredictionType>,
): Int {
    if (visibleTypes.isEmpty()) return 0
    val completeTypes = drafts.asSequence()
        .filter { it.complete }
        .map { it.type }
        .toSet()
    return visibleTypes.distinct().count { it in completeTypes }
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
/**
 * Unisce le bozze del server con quelle locali. La bozza locale rappresenta
 * lavoro non ancora inviato: ha la precedenza, altrimenti un ON_RESUME
 * cancellerebbe le modifiche fatte dopo l'ultimo salvataggio. Dopo un invio
 * riuscito la bozza locale viene eliminata, quindi non riemerge.
 */
internal fun mergeDrafts(
    serverDrafts: List<DraftEntry>,
    localDrafts: List<DraftEntry>?,
): List<DraftEntry> {
    val localByType = localDrafts.orEmpty().associateBy { it.type }
    return serverDrafts.map { localByType[it.type] ?: it }
}

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
