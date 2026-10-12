package com.fantaformula1.app.ui.results

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.data.util.formatLocalDateTimeInput
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.FantaNumberField
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.SectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceResultScreen(
    container: AppContainer,
    raceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ResultViewModel = viewModel {
        ResultViewModel(
            raceRepository = container.raceRepository,
            leagueRepository = container.leagueRepository,
            authRepository = container.authRepository,
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
            }
        }
    }

    // Apre di default i piloti che hanno gia' dei dati salvati.
    LaunchedEffect(state.drivers) {
        if (expanded.isEmpty()) {
            expanded = state.drivers.filter { it.hasData }.map { it.driverId }.toSet()
        }
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

                    if (!state.isAdmin) {
                        item { ReadOnlyNotice() }
                    } else {
                        item { SectionTitle(stringResource(R.string.sezione_gara)) }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.gara_round_name, race.round, race.name),
                style = MaterialTheme.typography.titleLarge,
            )
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

@Composable
private fun ReadOnlyNotice() {
    Text(
        text = stringResource(R.string.risultati_sola_lettura),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
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
