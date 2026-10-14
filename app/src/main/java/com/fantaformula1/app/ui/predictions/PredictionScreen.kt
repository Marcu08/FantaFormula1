package com.fantaformula1.app.ui.predictions

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.PredictionValidation
import com.fantaformula1.app.core.domain.italianDescription
import com.fantaformula1.app.core.domain.italianLabel
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
            raceId = raceId,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val savedMessage = stringResource(R.string.pronostico_salvato)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                PredictionEvent.Saved -> snackbarHostState.showSnackbar(savedMessage)
                is PredictionEvent.Failure -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // B10: ricalcola la deadline ogni minuto mentre la schermata è aperta,
    // così il FAB si disabilita e i pronostici altrui appaiono esattamente
    // allo scadere del tempo.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.checkDeadline()
        }
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { DeadlineBanner(state) }

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
                        PredictionCard(
                            type = type,
                            draft = draft,
                            points = state.rules.pointsFor(type),
                            enabled = state.canSubmit,
                            isSprintRace = state.race?.isSprint == true,
                            viewModel = viewModel,
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
            }
        }
    }
}

@Composable
private fun DeadlineBanner(state: PredictionUiState) {
    val race = state.race ?: return
    // Stessa identita' visiva della card nel calendario: la gara e' riconoscibile.
    val visual = RaceIdentity.visualFor(race.name, race.country, race.round)
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
                            stringResource(
                                R.string.pronostico_scadenza,
                                formatLocalDateTimeInput(it),
                            )
                        } ?: stringResource(R.string.pronostico_sempre_aperta)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** Fasce preimpostate per DRIVER_POSITION_RANGE: banda realistica di posizioni. */
private fun rangePresets(): List<Triple<Int, Int, String>> = listOf(
    Triple(1, 3, "Podio (1-3)"),
    Triple(4, 8, "Punti (4-8)"),
    Triple(9, 14, "Metà (9-14)"),
    Triple(15, 22, "Coda (15-22)"),
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PredictionCard(
    type: PredictionType,
    draft: DraftEntry,
    points: Double,
    enabled: Boolean,
    isSprintRace: Boolean,
    viewModel: PredictionViewModel,
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(required) { slot ->
                        DriverDropdown(
                            label = if (required == 1) {
                                stringResource(R.string.pronostico_pilota)
                            } else {
                                stringResource(R.string.pronostico_slot_pilota, slot + 1)
                            },
                            selectedDriverId = draft.driverIds.getOrNull(slot),
                            enabled = enabled,
                            onSelect = { viewModel.setDriverAt(type, slot, it) },
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
                    // Fasce preimpostate: selezione rapida di bande realistiche
                    // invece di digitare min/max a mano.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rangePresets().forEach { preset ->
                            AssistChip(
                                onClick = {
                                    if (enabled) {
                                        viewModel.setRangePreset(
                                            type,
                                            preset.first,
                                            preset.second,
                                        )
                                    }
                                },
                                label = { Text(preset.third) },
                                enabled = enabled,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FantaNumberField(
                            value = draft.rangeMin.toIntOrNull(),
                            onValueChange = { viewModel.setRangeMin(type, it?.toString().orEmpty()) },
                            label = stringResource(R.string.campo_da),
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        )
                        FantaNumberField(
                            value = draft.rangeMax.toIntOrNull(),
                            onValueChange = { viewModel.setRangeMax(type, it?.toString().orEmpty()) },
                            label = stringResource(R.string.campo_a),
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                PredictionType.DRIVER_DNF -> Row(
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

                PredictionType.DRIVER_POINTS_OVER_UNDER,
                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                PredictionType.PIT_STOPS_OVER_UNDER,
                -> {
                    FantaTextField(
                        value = draft.threshold,
                        onValueChange = { viewModel.setThreshold(type, it) },
                        label = stringResource(R.string.pronostico_soglia),
                        keyboardType = KeyboardType.Decimal,
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(6.dp))
                    OverUnderRow(
                        over = draft.over,
                        enabled = enabled,
                        onChange = { if (enabled) viewModel.setOver(type, it) },
                    )
                }

                else -> Unit
            }
        }
    }
}

/**
 * Menu a tendina per la scelta di un pilota in uno slot del pronostico.
 * Sostituisce la griglia di chip: piu' compatto e con la semantica di
 * spostamento gestita da [PredictionViewModel.setDriverAt].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriverDropdown(
    label: String,
    selectedDriverId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedDriverId?.let { DriversCatalog.nameOf(it) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.pronostico_seleziona_pilota)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            enabled = enabled,
            // Avatar del pilota selezionato nel campo chiuso.
            leadingIcon = {
                selectedDriverId?.let { DriverAvatar(it, modifier = Modifier.size(24.dp)) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DriversCatalog.all.forEach { driver ->
                DropdownMenuItem(
                    text = { Text(driver.name) },
                    onClick = {
                        expanded = false
                        onSelect(driver.id)
                    },
                    // Avatar caricatura del pilota in ogni voce del menu.
                    leadingIcon = {
                        DriverAvatar(driver.id, modifier = Modifier.size(30.dp))
                    },
                )
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
        -> "${type.italianLabel}: " +
            (if (entry.over == true) "> " else "< ") + (entry.threshold ?: "")
        else -> "${type.italianLabel}: $drivers"
    }
}
