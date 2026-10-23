package com.fantaformula1.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.ui.components.EmptyState
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.RaceIdentity
import com.fantaformula1.app.ui.components.CircuitOutline
import com.fantaformula1.app.ui.components.CircuitTracks
import com.fantaformula1.app.ui.components.SectionTitle
import com.fantaformula1.app.ui.components.SyncBar
import com.fantaformula1.app.ui.components.raceHeaderBrush
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    container: AppContainer,
    leagueId: String,
    onBack: () -> Unit,
    onOpenResult: (raceId: String, leagueId: String) -> Unit,
    onOpenPrediction: (raceId: String) -> Unit,
    onSyncResults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = viewModel {
        CalendarViewModel(
            raceRepository = container.raceRepository,
            leagueRepository = container.leagueRepository,
            authRepository = container.authRepository,
            predictionRepository = container.predictionRepository,
            leagueId = leagueId,
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Stato di sincronizzazione F1 condiviso con il worker in background.
    val syncState by container.f1SyncStateHolder.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<Race?>(null) }

    val savedMessage = stringResource(R.string.gara_salvata)
    val deletedMessage = stringResource(R.string.gara_elimina_ok)

    // N5: senza ruolo admin la sincronizzazione periodica non resta attiva.
    LaunchedEffect(leagueId, state.isAdmin) {
        if (!state.loading && !state.isAdmin) {
            com.fantaformula1.app.data.sync.F1SyncScheduler.cancel(context, leagueId)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                CalendarEvent.Saved -> snackbarHostState.showSnackbar(savedMessage)
                CalendarEvent.Deleted -> snackbarHostState.showSnackbar(deletedMessage)
                is CalendarEvent.Failure -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendario_titolo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.azione_indietro),
                        )
                    }
                },
                actions = {
                    // N5: la sincronizzazione e' riservata agli admin.
                    if (state.isAdmin) {
                        IconButton(
                            onClick = onSyncResults,
                            // Durante la sincronizzazione il pulsante e' disattivo:
                            // e' anche il retry manuale dopo un fallimento.
                            enabled = !syncState.isSyncing,
                        ) {
                            if (syncState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = "Sincronizza risultati F1",
                                )
                            }
                        }
                    }
                    IconButton(onClick = viewModel::load) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.azione_aggiorna),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.isAdmin) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openCreateForm,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.gara_nuova)) },
                )
            }
        },
    ) { innerPadding ->
        val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        var refreshing by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // Dal calendario si vedono solo le gare a venire: le gare passate
            // (deadline superata o gia' completate) restano nella sezione
            // richiudibile in fondo, per consultare i risultati.
            val upcoming = state.races.filterNot { it.isPastRace() }
            val past = state.races.filter { it.isPastRace() }
            var showCompleted by remember { mutableStateOf(false) }

            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                state = pullRefreshState,
                onRefresh = {
                    refreshing = true
                    viewModel.load()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
            // Il caricamento iniziale ha il suo stato dedicato; dopo il
            // primo caricamento il refresh resta gestito dal gesto.
            LaunchedEffect(state.loading) {
                if (!state.loading) refreshing = false
            }
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::load,
                )
                state.races.isEmpty() -> EmptyState(
                    title = stringResource(R.string.calendario_vuoto),
                    subtitle = if (state.isAdmin) {
                        stringResource(R.string.calendario_vuoto_admin)
                    } else {
                        stringResource(R.string.calendario_vuoto_sub)
                    },
                    icon = Icons.Outlined.Flag,
                    actionLabel = if (state.isAdmin) stringResource(R.string.gara_nuova) else null,
                    onAction = if (state.isAdmin) viewModel::openCreateForm else null,
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
                    if (syncState.isSyncing || syncState.lastResultsSync != null) {
                        item(key = "sync") {
                            SyncBar(
                                isSyncing = syncState.isSyncing,
                                lastSync = syncState.lastResultsSync,
                            )
                        }
                    }
                    // N7: una gara finita da piu' di 12 ore senza risultato e'
                    // un segnale che la sync non sta funzionando (es. nessun
                    // admin con la app aperta): avviso in evidenza.
                    val staleResults = past.count { race ->
                        val deadline = race.deadline
                        deadline != null &&
                            race.status != RaceStatus.COMPLETED &&
                            java.time.Duration.between(deadline, java.time.Instant.now())
                                .compareTo(java.time.Duration.ofHours(12)) > 0
                    }
                    if (staleResults > 0) {
                        item(key = "stale_results") {
                            StaleResultsBanner(count = staleResults)
                        }
                    }
                    if (upcoming.isEmpty() && past.isNotEmpty()) {
                        item(key = "all_done") {
                            Text(
                                text = stringResource(R.string.calendario_tutte_completate),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(upcoming, key = { it.id }) { race ->
                        RaceCard(
                            race = race,
                            isAdmin = state.isAdmin,
                            predictionSaved = race.id in state.myPredictionRaceIds,
                            onOpenResult = { onOpenResult(race.id, race.leagueId) },
                            onOpenPrediction = { onOpenPrediction(race.id) },
                            onEdit = { viewModel.openEditForm(race) },
                            onDelete = { confirmDelete = race },
                        )
                    }
                    if (past.isNotEmpty()) {
                        item(key = "past_toggle") {
                            TextButton(
                                onClick = { showCompleted = !showCompleted },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = if (showCompleted) {
                                        Icons.Outlined.KeyboardArrowUp
                                    } else {
                                        Icons.Outlined.KeyboardArrowDown
                                    },
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (showCompleted) {
                                        stringResource(R.string.calendario_completate_nascondi)
                                    } else {
                                        stringResource(
                                            R.string.calendario_completate_mostra,
                                            past.size,
                                        )
                                    },
                                )
                            }
                        }
                        if (showCompleted) {
                            items(past, key = { it.id }) { race ->
                                RaceCard(
                                    race = race,
                                    isAdmin = state.isAdmin,
                                    predictionSaved = race.id in state.myPredictionRaceIds,
                                    onOpenResult = { onOpenResult(race.id, race.leagueId) },
                                    onOpenPrediction = { onOpenPrediction(race.id) },
                                    onEdit = { viewModel.openEditForm(race) },
                                    onDelete = { confirmDelete = race },
                                )
                            }
                        }
                    }
                } // LazyColumn
            } // when
        } // PullToRefreshBox
    } // Box
} // Scaffold content

    if (state.formOpen) {
        RaceFormDialog(state = state, viewModel = viewModel)
    }

    confirmDelete?.let { race ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.gara_elimina)) },
            text = { Text(stringResource(R.string.gara_elimina_conferma, race.name)) },
confirmButton = {
                        Button(
                            onClick = {
                                confirmDelete = null
                                scope.launch { viewModel.deleteRace(race) }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text(stringResource(R.string.azione_elimina)) }
                    },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        )
    }
}

@Composable
private fun RaceCard(
    race: Race,
    isAdmin: Boolean,
    predictionSaved: Boolean,
    onOpenResult: () -> Unit,
    onOpenPrediction: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Ogni gara ha la sua identita' visiva: bandiera e gradiente tematico.
    val visual = RaceIdentity.visualFor(race.name, race.country, race.round)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(raceHeaderBrush(visual))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = race.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    race.country?.takeIf { it.isNotBlank() }?.let { country ->
                        Text(
                            text = country,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                // Sagoma del tracciato (dati aperti), bianca sul gradiente.
                CircuitOutline(
                    trackKey = CircuitTracks.keyFor(race.name, race.country),
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = race.round.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(status = race.effectiveStatus())
                    Spacer(Modifier.width(8.dp))
                    if (race.isSprint) SprintBadge()
                    if (predictionSaved) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "Pronostico salvato",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    race.deadline?.let { deadline ->
                        Text(
                            text = stringResource(
                                R.string.gara_deadline,
                                // U12: locale e fuso del dispositivo.
                                com.fantaformula1.app.core.util.Formats.deadline(deadline),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Azione primaria tonale, secondaria in outline.
                    FilledTonalButton(
                        onClick = onOpenPrediction,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.lega_pronostici))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onOpenResult,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.gara_risultati))
                    }
                    if (isAdmin) {
                        IconButton(onClick = onEdit) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.gara_modifica),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.gara_elimina),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * N7: avviso per gare finite da piu' di 12 ore senza risultato salvato —
 * segnala che la sincronizzazione non sta arrivando (serve un admin attivo).
 */
@Composable
private fun StaleResultsBanner(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.risultati_in_attesa, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SprintBadge() {
    Text(
        text = stringResource(R.string.gara_sprint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusChip(status: RaceStatus) {
    // Colore semantico per stato: leggibile su tema chiaro e scuro
    // (testo saturo su fondo tinto al 16%).
    val (textColor, bgColor) = when (status) {
        RaceStatus.SCHEDULED -> Color(0xFF2F6FD3) to Color(0xFF2F6FD3).copy(alpha = 0.16f)
        RaceStatus.OPEN -> Color(0xFF009150) to Color(0xFF009150).copy(alpha = 0.16f)
        RaceStatus.CLOSED -> Color(0xFFB07C00) to Color(0xFFB07C00).copy(alpha = 0.16f)
        RaceStatus.COMPLETED -> Color(0xFFD93A34) to Color(0xFFD93A34).copy(alpha = 0.16f)
    }
    Text(
        text = when (status) {
            RaceStatus.SCHEDULED -> stringResource(R.string.stato_programmata)
            RaceStatus.OPEN -> stringResource(R.string.stato_aperta)
            RaceStatus.CLOSED -> stringResource(R.string.stato_chiusa)
            RaceStatus.COMPLETED -> stringResource(R.string.stato_completata)
        },
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier = Modifier
            .background(
                color = bgColor,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaceFormDialog(
    state: CalendarUiState,
    viewModel: CalendarViewModel,
) {
    AlertDialog(
        onDismissRequest = {
            if (!state.submitting) viewModel.closeForm()
        },
        title = {
            Text(
                stringResource(
                    if (state.editingRaceId == null) R.string.gara_nuova else R.string.gara_modifica,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FantaTextField(
                    value = state.form.name,
                    onValueChange = viewModel::onNameChange,
                    label = stringResource(R.string.gara_nome),
                    hint = stringResource(R.string.gara_nome_hint),
                    enabled = !state.submitting,
                    maxLength = 60,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FantaTextField(
                        value = state.form.round,
                        onValueChange = viewModel::onRoundChange,
                        label = stringResource(R.string.gara_round),
                        enabled = !state.submitting,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    FantaTextField(
                        value = state.form.country,
                        onValueChange = viewModel::onCountryChange,
                        label = stringResource(R.string.gara_paese),
                        enabled = !state.submitting,
                        modifier = Modifier.weight(1f),
                    )
                }
                // U2: selettori data/ora Material 3 al posto del campo testo.
                Text(
                    text = stringResource(R.string.gara_deadline_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DeadlinePickerField(
                    value = state.form.deadline,
                    onValueChange = viewModel::onDeadlineChange,
                    enabled = !state.submitting,
                )
                state.formError?.takeIf {
                    it.contains("deadline", ignoreCase = true)
                }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.gara_sprint_label))
                    Switch(
                        checked = state.form.isSprint,
                        onCheckedChange = viewModel::onSprintChange,
                        enabled = !state.submitting,
                    )
                }
                state.formError?.takeIf {
                    !it.contains("deadline", ignoreCase = true)
                }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::submitForm,
                enabled = !state.submitting,
            ) { Text(stringResource(R.string.azione_salva)) }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::closeForm,
                enabled = !state.submitting,
            ) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}
