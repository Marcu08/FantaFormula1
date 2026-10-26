package com.fantaformula1.app.ui.predictions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.DriverPointsEstimates
import com.fantaformula1.app.core.domain.DriverSeasonStats
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.PredictionValidation
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.ScoringEngine
import com.fantaformula1.app.core.domain.italianDescription
import com.fantaformula1.app.core.domain.OverUnderQuotes
import com.fantaformula1.app.core.domain.italianLabel
import com.fantaformula1.app.core.util.Formats
import com.fantaformula1.app.ui.components.DriverAvatar
import com.fantaformula1.app.ui.components.DriverImages
import com.fantaformula1.app.ui.components.DriverPickerBottomSheet
import com.fantaformula1.app.ui.components.DriverTeamColors
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.FantaNumberField
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.OfflineBanner
import com.fantaformula1.app.ui.components.RaceIdentity
import com.fantaformula1.app.ui.components.SectionTitle
import com.fantaformula1.app.ui.components.raceHeaderBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionScreen(
    container: AppContainer,
    raceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PredictionViewModel = viewModel {
        PredictionViewModel(
            raceRepository = container.raceRepository,
            leagueRepository = container.leagueRepository,
            predictionRepository = container.predictionRepository,
            leagueDataRepository = container.leagueDataRepository,
            seasonResultsStore = container.seasonResultsStore,
            raceId = raceId,
            draftStore = container.predictionDraftStore,
            offlineCache = container.offlineCacheStore,
            pendingWritesStore = container.pendingWritesStore,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // U3: "ora corrente" aggiornata ogni minuto per il countdown alla deadline.
    var nowTick by remember { mutableStateOf(java.time.Instant.now()) }

    val savedMessage = stringResource(R.string.pronostico_salvato)

    val draftRestoredMessage = stringResource(R.string.pronostico_bozza_ripristinata)

    // F3: messaggio quando il salvataggio e' andato in coda offline.
    val queuedMessage = stringResource(R.string.pronostico_in_coda)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                PredictionEvent.Saved -> snackbarHostState.showSnackbar(savedMessage)
                PredictionEvent.Queued -> snackbarHostState.showSnackbar(queuedMessage)
                is PredictionEvent.Failure -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // U5: avviso quando una bozza locale è stata ripristinata.
    LaunchedEffect(state.draftRestored) {
        if (state.draftRestored) {
            snackbarHostState.showSnackbar(draftRestoredMessage)
        }
    }

    // B10: ricalcola la deadline ogni minuto mentre la schermata è aperta,
    // così il FAB si disabilita e i pronostici altrui appaiono esattamente
    // allo scadere del tempo.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowTick = java.time.Instant.now()
            viewModel.checkDeadline()
        }
    }

    // Al rientro nella schermata (dopo i risultati, dopo un refresh della
    // sync, o dopo aver riaperto l'app) i punteggi spiegati vanno riletti:
    // il ViewModel sopravvive nel backstack e senza questo resta con lo
    // stato vecchio. load() e' idempotente e ri-merge la bozza locale.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.race?.name ?: stringResource(R.string.pronostici_titolo),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.azione_indietro),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canSubmit) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::save,
                    icon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                    text = { Text(stringResource(R.string.azione_invia)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // F3: avviso "Offline" quando gara e pronostico arrivano dalla
            // cache perche' la rete non ha risposto.
            if (state.offline) {
                OfflineBanner(
                    lastUpdatedEpochMs = state.lastUpdatedEpochMs,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::load,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        DeadlineBanner(
                            state = state,
                            now = nowTick,
                            incomplete = state.drafts.any { !it.complete },
                        )
                    }

                    // U1: spiegazione dei punti del pronostico salvato, quando
                    // il risultato della gara e' gia' disponibile.
                    val minePrediction = state.mine
                    val raceResult = state.result
                    if (minePrediction != null && raceResult != null) {
                        val lines = ScoringEngine.explain(
                            prediction = minePrediction,
                            result = raceResult,
                            rules = state.rules,
                        )
                        item(key = "score_explain") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    SectionTitle(stringResource(R.string.pronostico_punti_dettaglio))
                                    lines.forEach { line ->
                                        Text(
                                            text = "${line.type.italianLabel}: " +
                                                "${Formats.points(line.points)} " +
                                                "(${line.detail})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.pronostico_totale,
                                            Formats.points(lines.sumOf { it.points }),
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    // R6: avviso non bloccante sui tipi non ancora compilati.
                    state.warning?.let { warning ->
                        item {
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }

                    if (state.errors.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.errors.forEach { error ->
                                    Text(
                                        text = "• $error",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    items(state.visibleTypes, key = { it.name }) { type ->
                        val draft = state.drafts.firstOrNull { it.type == type }
                            ?: DraftEntry.of(type)
                        // Regola podio/vincitore: il 1º slot del podio e' il
                        // vincitore scelto nel pronostico Vincitore (se attivo).
                        val winnerDraft = state.drafts.firstOrNull {
                            it.type == PredictionType.WINNER
                        }
                        PredictionCard(
                            type = type,
                            draft = draft,
                            points = state.rules.pointsFor(type),
                            enabled = state.canSubmit,
                            isSprintRace = state.race?.isSprint == true,
                            rules = state.rules,
                            seasonStats = state.seasonStats,
                            viewModel = viewModel,
                            lockedWinnerEnabled = state.rules.enabledTypes.contains(
                                PredictionType.WINNER,
                            ),
                            winnerDriverId = winnerDraft?.driverIds?.firstOrNull(),
                        )
                    }

                    if (state.canRevealOthers) {
                        item { SectionTitle(stringResource(R.string.pronostici_altri)) }
                        if (state.others.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.pronostici_altri_vuoto),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(state.others, key = { it.userId }) { other ->
                                OtherPredictionCard(other, state.visibleTypes)
                            }
                        }
                    }
                }
            } // when
            } // Box contenuto
        } // Column
    }
}

@Composable
private fun DeadlineBanner(
    state: PredictionUiState,
    now: java.time.Instant,
    incomplete: Boolean,
) {
    val race = state.race ?: return
    // Stessa identita' visiva della card nel calendario: la gara e' riconoscibile.
    val visual = RaceIdentity.visualFor(race.name, race.country, race.round)
    // U3: countdown aggiornato ogni minuto dalla schermata.
    val remaining = race.deadline?.let { java.time.Duration.between(now, it) }
    val open = !state.deadlinePassed
    val remainingText = remaining?.takeIf { open && !it.isNegative && !it.isZero }
        ?.let { Formats.countdown(it) }
    // U3: sotto le due ore un pronostico incompleto va evidenziato.
    val urgent = open && incomplete && remaining != null &&
        !remaining.isNegative && !remaining.isZero &&
        remaining <= java.time.Duration.ofHours(2)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.deadlinePassed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(raceHeaderBrush(visual))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = visual.flag,
                    style = MaterialTheme.typography.headlineSmall,
                    // U11: l'emoji della bandiera non e' autoesplicativa.
                    modifier = Modifier.semantics {
                        contentDescription = "Bandiera ${visual.flag}"
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.gara_round_name, race.round, race.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = if (state.deadlinePassed) {
                        stringResource(R.string.pronostico_chiuso)
                    } else {
                        race.deadline?.let {
                            // U12: data/ora nel locale e fuso del dispositivo.
                            stringResource(
                                R.string.pronostico_scadenza,
                                Formats.deadline(it),
                            )
                        } ?: stringResource(R.string.pronostico_sempre_aperta)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remainingText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pronostico_chiude_tra, remainingText),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (urgent) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (urgent) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pronostico_scadenza_ravvicinata),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.saved) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pronostico_invato),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Etichetta banda: numero semplice per safety car ("0".."5", "6+"), intervallo per pit stop ("46-55"). */
private fun bandLabel(band: Pair<Int, Int>): String = when {
    band.first == band.second -> "${band.first}"
    band.second >= 99 -> "${band.first}+"
    else -> "${band.first}-${band.second}"
}

/** Fascia testa a testa per indice: 0=ALTA (i 7 piu' forti), 1=MEDIA, 2=BASSA. */
private fun tierLabel(tier: Int): String = when (tier) {
    0 -> "ALTA"
    1 -> "MEDIA"
    else -> "BASSA"
}

/**
 * 2.5 -> "2.5", 1.0 -> "1", 0.5454... -> "0.55": MASSIMO 2 cifre decimali.
 * Le quote a valore atteso escono da una divisione (0.5/p) con cifre
 * periodiche: senza arrotondamento la UI mostrava 0.5454545454545455.
 */
private fun formatEstimate(value: Double): String {
    val rounded = kotlin.math.round(value * 100.0) / 100.0
    return if (rounded == rounded.toInt().toDouble()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PredictionCard(
    type: PredictionType,
    draft: DraftEntry,
    points: Double,
    enabled: Boolean,
    isSprintRace: Boolean,
    rules: Rules,
    seasonStats: DriverSeasonStats,
    viewModel: PredictionViewModel,
    lockedWinnerEnabled: Boolean = false,
    winnerDriverId: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(type.italianLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = type.italianDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.pronostico_punti, points),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (PredictionValidation.requiredDrivers(type) > 0) {
                val required = PredictionValidation.requiredDrivers(type)
                val pickerSlot = remember { mutableStateOf(0) }
                val pickerOpen = remember { mutableStateOf(false) }
                // Podio: il 1º slot e' il vincitore (non modificabile qui).
                val podiumLocked = type == PredictionType.PODIUM && lockedWinnerEnabled
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(required) { slot ->
                        val slotLocked = podiumLocked && slot == 0
                        DriverSlotTile(
                            label = if (required == 1) {
                                stringResource(R.string.pronostico_pilota)
                            } else {
                                stringResource(R.string.pronostico_slot_pilota, slot + 1)
                            },
                            selectedDriverId = if (slotLocked) {
                                winnerDriverId ?: draft.driverIds.getOrNull(slot)
                            } else {
                                draft.driverIds.getOrNull(slot)
                            },
                            enabled = enabled,
                            locked = slotLocked,
                            onClick = if (slotLocked) {
                                null
                            } else {
                                {
                                    pickerSlot.value = slot
                                    pickerOpen.value = true
                                }
                            },
                        )
                    }
                }
                if (podiumLocked) {
                    // Avviso: se il vincitore non e' ancora stato inserito si
                    // spiega che il 1º posto arriva da li', altrimenti si
                    // informa che lo slot e' bloccato sul vincitore scelto.
                    Text(
                        text = if (winnerDriverId == null) {
                            stringResource(R.string.podio_slot_bloccato_avviso)
                        } else {
                            stringResource(R.string.podio_slot_bloccato)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (winnerDriverId == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (pickerOpen.value) {
                    DriverPickerBottomSheet(
                        selectedDriverId = draft.driverIds.getOrNull(pickerSlot.value),
                        // Testa a testa: il picker mostra le 3 fasce di forza
                        // (ALTA/MEDIA/BASSA) cosi' la scelta e' consapevole.
                        groupByTier = type == PredictionType.HEAD_TO_HEAD,
                        tierOfDriver = { seasonStats.tierOf(it) },
                        onDismiss = { pickerOpen.value = false },
                        onDriverSelected = { driverId ->
                            viewModel.setDriverAt(type, pickerSlot.value, driverId)
                            pickerOpen.value = false
                        },
                    )
                }
                if (type == PredictionType.HEAD_TO_HEAD) {
                    // Quota e fasce della coppia scelta: chiaro PRIMA di salvare
                    // quanto rende la sfida (pari 1.2x, favorito poco, sorpresa
                    // tanto). Fasce REALI (0009): senza gare completate valgono
                    // quelle del prior pre-stagione.
                    val firstId = draft.driverIds.getOrNull(0)
                    val secondId = draft.driverIds.getOrNull(1)
                    if (firstId != null && secondId != null) {
                        val tierA = seasonStats.tierOf(firstId)
                        val tierB = seasonStats.tierOf(secondId)
                        val factor = OverUnderQuotes.headToHeadFactor(tierA, tierB)
                        Text(
                            text = "Fasce: ${tierLabel(tierA)} vs ${tierLabel(tierB)} — " +
                                "quota x${formatEstimate(factor)} → " +
                                "${formatEstimate(points * factor)} punti se vince",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                 Spacer(Modifier.height(8.dp))
             }

            when (type) {
                PredictionType.DRIVER_EXACT_POSITION -> FantaNumberField(
                    value = draft.position.toIntOrNull(),
                    onValueChange = { viewModel.setPosition(type, it?.toString().orEmpty()) },
                    label = stringResource(R.string.campo_posizione),
                    enabled = enabled,
                )

                PredictionType.DRIVER_POSITION_RANGE -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Fasce OBBLIGATORIE: le quattro bande fisse sono l'unico
                    // modo di selezionare (una banda libera 1-22 vincerebbe
                    // sempre, in test della scimmia). I campi manuali non
                    // esistono piu':`PredictionValidation` filtra lato server
                    // e client.
                    val selectedBand = PredictionValidation.RANGE_BANDS.firstOrNull {
                        it.first == draft.rangeMin.toIntOrNull() &&
                            it.second == draft.rangeMax.toIntOrNull()
                    }
                    if (selectedBand == null) {
                        val legacyMin = draft.rangeMin.toIntOrNull()
                        val legacyMax = draft.rangeMax.toIntOrNull()
                        if (legacyMin != null && legacyMax != null) {
                            // Fascia salvata prima del cambio regole: leggibile
                            // ma non selezionabile (non valida al risalvataggio).
                            Text(
                                text = stringResource(
                                    R.string.pronostico_fascia_legacy,
                                    legacyMin,
                                    legacyMax,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PredictionValidation.RANGE_BANDS.forEach { band ->
                            AssistChip(
                                onClick = {
                                    if (enabled) {
                                        viewModel.setRangePreset(
                                            type,
                                            band.first,
                                            band.second,
                                        )
                                    }
                                },
                                label = { Text("${band.first}-${band.second}") },
                                enabled = enabled,
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (selectedBand == band) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                ),
                                border = if (selectedBand == band) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    AssistChipDefaults.assistChipBorder(
                                        enabled = enabled,
                                    )
                                },
                            )
                        }
                    }
                }

                PredictionType.DRIVER_DNF -> {
                    val driverId = draft.driverIds.firstOrNull()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.pronostico_ritira))
                        Switch(
                            checked = draft.booleanValue ?: false,
                            onCheckedChange = { if (enabled) viewModel.setBooleanValue(type, it) },
                            enabled = enabled,
                        )
                    }
                    if (driverId != null) {
                        // Quote a valore atteso (0009): "non ritira" non e' piu'
                        // gratis — paga in proporzione alla sua probabilita'.
                        val rate = seasonStats.dnfRate(driverId)
                        val (qSi, qNo) = OverUnderQuotes.dnfQuotas(rate)
                        val chosen = draft.booleanValue
                        Text(
                            text = stringResource(
                                R.string.pronostico_dnf_quote,
                                formatEstimate(qSi),
                                formatEstimate(points * qSi),
                                formatEstimate(qNo),
                                formatEstimate(points * qNo),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = when (chosen) {
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.secondary
                            },
                        )
                    }
                }

                PredictionType.DRIVER_POINTS_OVER_UNDER -> {
                    val driverId = draft.driverIds.firstOrNull()
                    if (driverId == null) {
                        Text(
                            text = stringResource(R.string.pronostico_scegli_prima_pilota),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Soglia dai DATI REALI (mediana empirica, 0009); sotto
                        // il minimo di 3 gare queda la stima pre-stagione.
                        val threshold = seasonStats.thresholdFor(driverId)
                        val n = seasonStats.racesRun(driverId)
                        val (qOver, qUnder) = OverUnderQuotes.pointsOverUnderQuotas(
                            seasonStats.countOverPoints(driverId, threshold),
                            n,
                        )
                        Text(
                            text = if (seasonStats.isEmpirical(driverId)) {
                                stringResource(
                                    R.string.pronostico_soglia_reale,
                                    n,
                                    DriversCatalog.nameOf(driverId),
                                    formatEstimate(threshold),
                                )
                            } else {
                                stringResource(
                                    R.string.pronostico_soglia_stimata,
                                    formatEstimate(threshold),
                                    DriversCatalog.nameOf(driverId),
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OverUnderRow(
                            over = draft.over,
                            enabled = enabled,
                            onChange = {
                                // Soglia = dati reali (o stima): si setta al
                                // primo giro, il giocatore sceglie sopra/sotto.
                                viewModel.setThreshold(type, formatEstimate(threshold))
                                viewModel.setOver(type, it)
                            },
                        )
                        Text(
                            text = stringResource(
                                R.string.pronostico_punti_quote,
                                formatEstimate(qOver),
                                formatEstimate(points * qOver),
                                formatEstimate(qUnder),
                                formatEstimate(points * qUnder),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                PredictionType.PIT_STOPS_OVER_UNDER,
                -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Opzioni a fascia (safety car = numero esatto di
                        // SC+VSC; pit stop = totale della griglia). Payout
                        // per rarita' mostrato sul chip selezionato.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val bands = when (type) {
                                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER ->
                                    OverUnderQuotes.safetyCountBands
                                else -> OverUnderQuotes.pitStopBands
                            }
                            bands.forEach { band ->
                                val bandLabel = bandLabel(band)
                                val selected = draft.rangeMin.toIntOrNull() == band.first &&
                                    draft.rangeMax.toIntOrNull() == band.second
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (enabled) viewModel.setBand(type, band.first, band.second)
                                    },
                                    label = {
                                        Text(
                                            text = if (selected) {
                                                val factor = if (type == PredictionType.SAFETY_CAR_COUNT_OVER_UNDER) {
                                                    OverUnderQuotes.safetyBandFactor(band.first, band.second)
                                                } else {
                                                    OverUnderQuotes.pitBandFactor(band.first, band.second)
                                                }
                                                "$bandLabel (x${formatEstimate(factor)})"
                                            } else {
                                                bandLabel
                                            },
                                        )
                                    },
                                    enabled = enabled,
                                )
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}

/**
 * Slot grafico di selezione pilota: carta compatta dell'avatar pilota se
 * compilato, segnaposto con azione se vuoto. Il tap apre il bottom sheet
 * [DriverPickerBottomSheet]; la semantica di spostamento resta in
 * [PredictionViewModel.setDriverAt].
 */
@Composable
private fun DriverSlotTile(
    label: String,
    selectedDriverId: String?,
    enabled: Boolean,
    locked: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (locked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(4.dp))
        val driver = selectedDriverId?.let { DriversCatalog.byId(it) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (enabled && onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (driver != null) {
                    DriverTeamColors.colorFor(driver.team).copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            border = if (driver != null) {
                BorderStroke(1.dp, DriverTeamColors.colorFor(driver.team))
            } else {
                null
            },
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (driver != null) {
                    // Caricatura in un quadretto tinto scuderia, uniforme per
                    // tutti gli slot; fallback = avatar casco (legacy TSU).
                    val imageRes = DriverImages.resIdOf(driver.id)
                    if (imageRes != null) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    color = DriverTeamColors.colorFor(driver.team).copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(imageRes),
                                contentDescription = null, // nome e scuderia testuali vicino
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp),
                            )
                        }
                    } else {
                        DriverAvatar(
                            driverId = driver.id,
                            size = 28.dp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = driver.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(driver.team)
                                driver.raceNumber?.let { append(" · N.$it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = if (locked) {
                            Icons.Outlined.Lock
                        } else {
                            Icons.Outlined.Edit
                        },
                        contentDescription = if (locked) {
                            stringResource(R.string.podio_slot_bloccato_icona)
                        } else {
                            stringResource(R.string.slot_cambia)
                        },
                        tint = if (locked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.pronostico_seleziona_pilota),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverUnderRow(
    over: Boolean?,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = over == true,
            onClick = { onChange(true) },
            label = { Text(stringResource(R.string.pronostico_sopra)) },
            enabled = enabled,
        )
        FilterChip(
            selected = over == false,
            onClick = { onChange(false) },
            label = { Text(stringResource(R.string.pronostico_sotto)) },
            enabled = enabled,
        )
    }
}

@Composable
private fun OtherPredictionCard(
    other: OtherPrediction,
    visibleTypes: List<PredictionType>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(other.displayName, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            visibleTypes.forEach { type ->
                val entry = other.prediction.entries.firstOrNull { it.type == type } ?: return@forEach
                Text(
                    text = summary(type, entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun summary(type: PredictionType, entry: com.fantaformula1.app.core.domain.PredictionEntry): String {
    val drivers = entry.driverIds.joinToString(", ") { DriversCatalog.nameOf(it) }
    return when (type) {
        PredictionType.DRIVER_EXACT_POSITION ->
            "${type.italianLabel}: $drivers ${entry.position ?: ""}".trim()
        PredictionType.DRIVER_POSITION_RANGE ->
            "${type.italianLabel}: $drivers ${entry.rangeMin}-${entry.rangeMax}"
        PredictionType.DRIVER_DNF ->
            "${type.italianLabel}: $drivers " +
                if (entry.booleanValue == true) "ritira" else "non ritira"
        PredictionType.DRIVER_POINTS_OVER_UNDER,
        PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
        PredictionType.PIT_STOPS_OVER_UNDER,
        -> {
            // Fasce (formato attuale): "2", "36-45" o "6+"; il vecchio
            // sopra/sotto resta per i pronostici storici.
            val min = entry.rangeMin
            val max = entry.rangeMax
            "${type.italianLabel}: " + if (min != null && max != null) {
                if (min == max) "$min" else if (max >= 99) "$min+" else "$min-$max"
            } else {
                (if (entry.over == true) "> " else "< ") + (entry.threshold ?: "")
            }
        }
        else -> "${type.italianLabel}: $drivers"
    }
}
