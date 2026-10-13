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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.data.util.formatLocalDateTimeInput
import com.fantaformula1.app.ui.components.EmptyState
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.RaceIdentity
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
            leagueId = leagueId,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Stato di sincronizzazione F1 condiviso con il worker in background.
    val syncState by container.f1SyncStateHolder.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<Race?>(null) }

    val savedMessage = stringResource(R.string.gara_salvata)
    val deletedMessage = stringResource(R.string.gara_elimina_ok)

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
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // Le gare gia' completate escono dalla lista principale: restano
            // raggiungibili nella sezione richiudibile in fondo (risultati).
            val upcoming = state.races.filter {
                it.effectiveStatus() != RaceStatus.COMPLETED
            }
            val completed = state.races.filter {
                it.effectiveStatus() == RaceStatus.COMPLETED
            }
            var showCompleted by remember { mutableStateOf(false) }

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
                    if (upcoming.isEmpty() && completed.isNotEmpty()) {
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
                            onOpenResult = { onOpenResult(race.id, race.leagueId) },
                            onOpenPrediction = { onOpenPrediction(race.id) },
                            onEdit = { viewModel.openEditForm(race) },
                            onDelete = { confirmDelete = race },
                        )
                    }
                    if (completed.isNotEmpty()) {
                        item(key = "completed_toggle") {
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
                                            completed.size,
                                        )
                                    },
                                )
                            }
                        }
                        if (showCompleted) {
                            items(completed, key = { it.id }) { race ->
                                RaceCard(
                                    race = race,
                                    isAdmin = state.isAdmin,
                                    onOpenResult = { onOpenResult(race.id, race.leagueId) },
                                    onOpenPrediction = { onOpenPrediction(race.id) },
                                    onEdit = { viewModel.openEditForm(race) },
                                    onDelete = { confirmDelete = race },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.formOpen) {
        RaceFormDialog(state = state, viewModel = viewModel)
    }

    confirmDelete?.let { race ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.gara_elimina)) },
            text = { Text(stringResource(R.string.gara_elimina_conferma, race.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        scope.launch { viewModel.deleteRace(race) }
                    },
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
                    Spacer(Modifier.weight(1f))
                    race.deadline?.let { deadline ->
                        Text(
                            text = stringResource(
                                R.string.gara_deadline,
                                formatLocalDateTimeInput(deadline),
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
                    TextButton(onClick = onOpenPrediction) {
                        Text(stringResource(R.string.lega_pronostici))
                    }
                    TextButton(onClick = onOpenResult) {
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
    Text(
        text = when (status) {
            RaceStatus.SCHEDULED -> stringResource(R.string.stato_programmata)
            RaceStatus.OPEN -> stringResource(R.string.stato_aperta)
            RaceStatus.CLOSED -> stringResource(R.string.stato_chiusa)
            RaceStatus.COMPLETED -> stringResource(R.string.stato_completata)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surface,
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
                FantaTextField(
                    value = state.form.deadline,
                    onValueChange = viewModel::onDeadlineChange,
                    label = stringResource(R.string.gara_deadline_label),
                    hint = stringResource(R.string.gara_deadline_hint),
                    error = state.formError?.takeIf {
                        it.contains("deadline", ignoreCase = true)
                    },
                    enabled = !state.submitting,
                )
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
            TextButton(
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
