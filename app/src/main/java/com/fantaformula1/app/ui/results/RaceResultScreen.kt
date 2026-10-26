package com.fantaformula1.app.ui.results

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.data.openf1.OpenF1SafetyCarParser
import com.fantaformula1.app.data.util.formatLocalDateTimeInput
import com.fantaformula1.app.ui.components.DriverAvatar
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.FantaNumberField
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.RaceIdentity
import com.fantaformula1.app.ui.components.SectionTitle
import com.fantaformula1.app.ui.components.raceHeaderBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceResultScreen(
    container: AppContainer,
    raceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openF1Client = remember { com.fantaformula1.app.data.openf1.OpenF1Client() }
    val viewModel: ResultViewModel = viewModel {
        ResultViewModel(
            raceRepository = container.raceRepository,
            leagueRepository = container.leagueRepository,
            authRepository = container.authRepository,
            openF1 = openF1Client,
            f1Sync = container.f1SyncStateHolder,
            raceId = raceId,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    val savedMessage = stringResource(R.string.risultato_salvato)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ResultEvent.Saved -> snackbarHostState.showSnackbar(savedMessage)
                is ResultEvent.Failure -> snackbarHostState.showSnackbar(event.message)
                is ResultEvent.Notice -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Apre di default i piloti che hanno gia' dei dati salvati.
    LaunchedEffect(state.drivers) {
        if (expanded.isEmpty()) {
            expanded = state.drivers.filter { it.hasData }.map { it.driverId }.toSet()
        }
    }

    // Al rientro nella schermata (dopo un refresh o un ritorno da un'altra
    // schermata) i dati salvati vanno riletti — SOLO se l'admin non sta
    // compilando: mai sovrascrivere i campi non salvati.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadIfUnedited()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Conferma del refresh: rigenera da Jolpica un risultato gia' salvato.
    if (state.confirmRefresh) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRefreshConfirm,
            title = { Text(stringResource(R.string.refresh_title)) },
            text = { Text(stringResource(R.string.refresh_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRefreshResult) {
                    Text(stringResource(R.string.refresh_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRefreshConfirm) {
                    Text(stringResource(R.string.refresh_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.race?.name ?: stringResource(R.string.risultati_titolo),
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
            if (state.isAdmin) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::save,
                    icon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                    text = { Text(stringResource(R.string.azione_salva)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RaceHeader(state) }

                    // N3: dati non confermato dall'admin (l'API non li
                    // fornisce): avviso visibili a tutti.
                    if (state.saved &&
                        (state.safetyCarsUnknown || state.pitStopsUnknown)
                    ) {
                        item { PendingDataNotice(state) }
                    }

                    if (!state.isAdmin) {
                        item { ReadOnlyNotice() }
                    } else {
                        item { SectionTitle(stringResource(R.string.sezione_gara)) }
                        item { ResultsSyncRow(state, viewModel) }
                        item { OpenF1ImportButton(state, viewModel) }
                        item { RaceFields(state, viewModel) }

                        item { SectionTitle(stringResource(R.string.sezione_piloti)) }
                        item {
                            Text(
                                text = stringResource(R.string.piloti_istruzioni),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        items(state.drivers, key = { it.driverId }) { driver ->
                            DriverCard(
                                driver = driver,
                                isSprintRace = state.race?.isSprint == true,
                                expanded = driver.driverId in expanded,
                                onToggle = {
                                    expanded = if (driver.driverId in expanded) {
                                        expanded - driver.driverId
                                    } else {
                                        expanded + driver.driverId
                                    }
                                },
                                viewModel = viewModel,
                            )
                        }
                    }

                    if (state.fieldErrors.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                state.fieldErrors.forEach { error ->
                                    Text(
                                        text = "• $error",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RaceHeader(state: ResultUiState) {
    val race = state.race ?: return
    // Stessa identita' visiva della card nel calendario: la gara e' riconoscibile.
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
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.gara_round_name, race.round, race.name),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.padding(14.dp)) {
                val details = buildList {
                    race.country?.takeIf { it.isNotBlank() }?.let(::add)
                    if (race.isSprint) add(stringResource(R.string.gara_sprint))
                    race.deadline?.let {
                        add(stringResource(R.string.risultati_deadline, formatLocalDateTimeInput(it)))
                    }
                    if (state.saved) add(stringResource(R.string.risultato_salvato))
                }.joinToString(" · ")
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyNotice() {
    Text(
        text = stringResource(R.string.risultati_sola_lettura),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Sincronizzazione Jolpica dei risultati piloti: riempie il form con posizioni,
 * DNF, giro veloce e punti senza scriverne nessuno a mano. Solo per gli admin;
 * con la gara passata senza risultato e' l'azione da provare PRIMA di
 * inserire i dati a mano (l'auto-sync all'apertura la gia' eseguita).
 */
@Composable
private fun ResultsSyncRow(state: ResultUiState, viewModel: ResultViewModel) {
    if (!state.isAdmin) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = viewModel::syncResultsFromApi,
            enabled = !state.resultsSyncing,
        ) {
            if (state.resultsSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Outlined.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(
                    if (state.race?.status == RaceStatus.COMPLETED) {
                        R.string.results_refresh_button
                    } else {
                        R.string.results_sync_button
                    },
                ),
            )
        }
        Text(
            text = stringResource(R.string.results_sync_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OpenF1ImportButton(state: ResultUiState, viewModel: ResultViewModel) {
    // L'azione esiste solo per admin e solo se l'import non esta in corso;
    // il flusso di salvataggio resta quello abituale (nessun write aggiunto).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { viewModel.importSafetyCars() },
            enabled = state.isAdmin && state.scImport !is ScImportState.Loading,
        ) {
            if (state.scImport is ScImportState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.openf1_import_button))
        }
        Text(
            text = stringResource(R.string.openf1_import_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when (val scImport = state.scImport) {
        is ScImportState.Success -> SafetyCarImportDialog(awaiting = scImport.import, viewModel = viewModel)
        is ScImportState.Failed -> Text(
            text = stringResource(R.string.openf1_import_failed, scImport.reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Unit
    }
}

/**
 * Revisione delle proposizioni OpenF1: contegi separati SC/VSC, eventi
 * riconosciuti, avvisi e fiducia; campo TOTALE (SC+VSC) editabile — e' quel
 * valore che alla conferma riempie il form. Nessun salvataggio qui.
 */
@Composable
private fun SafetyCarImportDialog(
    awaiting: OpenF1SafetyCarParser.Import,
    viewModel: ResultViewModel,
) {
    var totalTextField by remember { mutableStateOf((awaiting.proposedSafetyCars + awaiting.proposedVirtualSafetyCars).toString()) }

    AlertDialog(
        onDismissRequest = viewModel::dismissSafetyCarImport,
        title = { Text(stringResource(R.string.openf1_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.openf1_sc_proposta, awaiting.proposedSafetyCars))
                    Text(stringResource(R.string.openf1_vsc_proposta, awaiting.proposedVirtualSafetyCars))
                }
                Text(
                    text = when (awaiting.confidence) {
                        OpenF1SafetyCarParser.Confidence.HIGH ->
                            stringResource(R.string.openf1_fiducia_alta)
                        OpenF1SafetyCarParser.Confidence.MEDIUM ->
                            stringResource(R.string.openf1_fiducia_media)
                        OpenF1SafetyCarParser.Confidence.LOW ->
                            stringResource(R.string.openf1_fiducia_bassa)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (awaiting.confidence) {
                        OpenF1SafetyCarParser.Confidence.HIGH -> MaterialTheme.colorScheme.primary
                        OpenF1SafetyCarParser.Confidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                if (awaiting.events.isNotEmpty()) {
                    Text(stringResource(R.string.openf1_eventi), style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = eventsLabel(awaiting.events),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (awaiting.warnings.isNotEmpty()) {
                    awaiting.warnings.take(4).forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = totalTextField,
                    onValueChange = { totalTextField = it.filter(Char::isDigit).take(2) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.openf1_totale_edit)) },
                )
                Text(
                    text = stringResource(R.string.openf1_niente_automatico),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.confirmSafetyCarImport(totalTextField.toIntOrNull() ?: 0)
                },
                enabled = totalTextField.isNotBlank() && totalTextField.toIntOrNull() != null,
            ) { Text(stringResource(R.string.openf1_conferma)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSafetyCarImport) {
                Text(stringResource(R.string.openf1_annulla))
            }
        },
    )
}

/** Elenco compatto degli eventi riconosciuti ("Inizio VSC @ giro 12"). */
@Composable
private fun eventsLabel(events: List<OpenF1SafetyCarParser.ScEvent>): String {
    val startLabel = stringResource(R.string.openf1_start)
    val endLabel = stringResource(R.string.openf1_fine)
    val giroLabel = stringResource(R.string.openf1_giro)
    return events.take(8).joinToString("\n") { event ->
        val kind = if (event.kind == OpenF1SafetyCarParser.Kind.VSC) "VSC" else "SC"
        val action = if (event.isStart) startLabel else endLabel
        val lap = event.lapNumber?.let { " @ $giroLabel $it" }.orEmpty()
        "$action $kind$lap"
    }
}

/**
 * N3: avviso "In attesa di conferma admin" per i dati che l'API non
 * fornisce (safety car, pit stop): finche' non li conferma l'admin i
 * pronostici collegati valgono 0 punti.
 */
/**
 * Dati non forniti da nessuna fonte (safety car, pit stop): per i MEMBRI
 * resta l'avviso "in attesa di conferma admin"; per l'ADMIN e' un'azione con
 * istruzioni precise: cosa completare e DOVE, dentro questa stessa schermata.
 */
@Composable
private fun PendingDataNotice(state: ResultUiState) {
    if (state.isAdmin) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.pending_admin_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (state.safetyCarsUnknown) {
                        Text(
                            text = stringResource(R.string.pending_admin_sc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (state.pitStopsUnknown) {
                        Text(
                            text = stringResource(R.string.pending_admin_pit),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        return
    }
    val pending = buildList {
        if (state.safetyCarsUnknown) add(stringResource(R.string.campo_safety_car))
        if (state.pitStopsUnknown) add(stringResource(R.string.campo_pit_stop))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Text(
            text = stringResource(
                R.string.risultato_in_attesa_conferma,
                pending.joinToString(", "),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun RaceFields(state: ResultUiState, viewModel: ResultViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FantaNumberField(
                    value = state.safetyCars.toIntOrNull(),
                    onValueChange = { viewModel.onSafetyCarsChange(it?.toString().orEmpty()) },
                    label = stringResource(R.string.campo_safety_car),
                    modifier = Modifier.weight(1f),
                    // N3: finche' il dato manca il campo spiega cosa scrivere.
                    hint = if (state.saved && state.safetyCarsUnknown) {
                        stringResource(R.string.campo_safety_car_hint)
                    } else {
                        ""
                    },
                )
                FantaNumberField(
                    value = state.redFlags.toIntOrNull(),
                    onValueChange = { viewModel.onRedFlagsChange(it?.toString().orEmpty()) },
                    label = stringResource(R.string.campo_bandiere_rosse),
                    modifier = Modifier.weight(1f),
                )
                FantaNumberField(
                    value = state.laps.toIntOrNull(),
                    onValueChange = { viewModel.onLapsChange(it?.toString().orEmpty()) },
                    label = stringResource(R.string.campo_giri),
                    modifier = Modifier.weight(1f),
                )
            }
            // N3: i campi che nessuna fonte fornisce, con l'azione esatta.
            if (state.saved && state.safetyCarsUnknown) {
                Text(
                    text = stringResource(R.string.campo_safety_car_todo),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.saved && state.pitStopsUnknown) {
                Text(
                    text = stringResource(R.string.campo_pit_stop_todo),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FantaTextField(
                value = state.weather,
                onValueChange = viewModel::onWeatherChange,
                label = stringResource(R.string.campo_meteo),
                maxLength = 40,
            )
            FantaTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = stringResource(R.string.campo_note),
                singleLine = false,
                maxLength = 200,
            )
        }
    }
}

@Composable
private fun DriverCard(
    driver: DriverFormState,
    isSprintRace: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    viewModel: ResultViewModel,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar caricatura del pilota: casco colore squadra + iniziali.
                DriverAvatar(driver.driverId, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(driver.driverName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = driver.team.ifBlank { driver.driverId },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (driver.hasData) {
                    Text(
                        text = summaryOf(driver),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FantaNumberField(
                            value = driver.grid.toIntOrNull(),
                            onValueChange = { viewModel.onGridChange(driver.driverId, it?.toString().orEmpty()) },
                            label = stringResource(R.string.campo_griglia),
                            hint = "1-${RaceResultValidation.MAX_FIELD}",
                            modifier = Modifier.weight(1f),
                        )
                        FantaNumberField(
                            value = driver.position.toIntOrNull(),
                            onValueChange = { viewModel.onPositionChange(driver.driverId, it?.toString().orEmpty()) },
                            label = stringResource(R.string.campo_posizione),
                            enabled = !driver.dnf,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (isSprintRace) {
                        FantaNumberField(
                            value = driver.sprintPosition.toIntOrNull(),
                            onValueChange = {
                                viewModel.onSprintPositionChange(driver.driverId, it?.toString().orEmpty())
                            },
                            label = stringResource(R.string.campo_posizione_sprint),
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.campo_giro_veloce))
                        Checkbox(
                            checked = driver.fastestLap,
                            onCheckedChange = {
                                viewModel.onFastestLapChange(driver.driverId, it)
                            },
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.campo_dnf))
                        Switch(
                            checked = driver.dnf,
                            onCheckedChange = { viewModel.onDnfChange(driver.driverId, it) },
                        )
                    }

                    if (driver.dnf) {
                        FantaTextField(
                            value = driver.dnfReason,
                            onValueChange = { viewModel.onDnfReasonChange(driver.driverId, it) },
                            label = stringResource(R.string.campo_motivo_dnf),
                            maxLength = 60,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FantaNumberField(
                            value = driver.penaltyPoints.toIntOrNull(),
                            onValueChange = {
                                viewModel.onPenaltyChange(driver.driverId, it?.toString().orEmpty())
                            },
                            label = stringResource(R.string.campo_penalita),
                            modifier = Modifier.weight(1f),
                        )
                        FantaNumberField(
                            value = driver.pitStops.toIntOrNull(),
                            onValueChange = {
                                viewModel.onPitStopsChange(driver.driverId, it?.toString().orEmpty())
                            },
                            label = stringResource(R.string.campo_pit_stop),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FantaTextField(
                        value = driver.pointsScored,
                        onValueChange = { viewModel.onPointsChange(driver.driverId, it) },
                        label = stringResource(R.string.campo_punti),
                        hint = stringResource(R.string.campo_punti_hint),
                        keyboardType = KeyboardType.Decimal,
                        maxLength = 6,
                    )
                }
            }
        }
    }
}

private fun summaryOf(driver: DriverFormState): String = buildList {
    driver.position.takeIf { it.isNotBlank() }?.let { add("#$it") }
    driver.sprintPosition.takeIf { it.isNotBlank() }?.let { add("SP $it") }
    if (driver.fastestLap) add("GV")
    if (driver.dnf) add("DNF")
}.joinToString(" ")
