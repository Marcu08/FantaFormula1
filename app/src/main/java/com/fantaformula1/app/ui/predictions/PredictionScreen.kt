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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import com.fantaformula1.app.core.domain.DriverPointsEstimates
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.PredictionValidation
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.italianDescription
import com.fantaformula1.app.core.domain.italianLabel
import com.fantaformula1.app.data.util.formatLocalDateTimeInput
import com.fantaformula1.app.ui.components.DriverAvatar
import com.fantaformula1.app.ui.components.DriverImages
import com.fantaformula1.app.ui.components.DriverPickerBottomSheet
import com.fantaformula1.app.ui.components.DriverTeamColors
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
                            rules = state.rules,
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

/** 2.5 -> "2.5", 1.0 -> "1": formato compatto per soglie e quote. */
private fun formatEstimate(value: Double): String =
    if (value == value.toInt().toDouble()) {
        value.toInt().toString()
    } else {
        value.toString()
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
                val pickerSlot = remember { mutableStateOf(0) }
                val pickerOpen = remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(required) { slot ->
                        DriverSlotTile(
                            label = if (required == 1) {
                                stringResource(R.string.pronostico_pilota)
                            } else {
                                stringResource(R.string.pronostico_slot_pilota, slot + 1)
                            },
                            selectedDriverId = draft.driverIds.getOrNull(slot),
                            enabled = enabled,
                            onClick = {
                                pickerSlot.value = slot
                                pickerOpen.value = true
                            },
                        )
                    }
                }
                if (pickerOpen.value) {
                    DriverPickerBottomSheet(
                        selectedDriverId = draft.driverIds.getOrNull(pickerSlot.value),
                        onDismiss = { pickerOpen.value = false },
                        onDriverSelected = { driverId ->
                            viewModel.setDriverAt(type, pickerSlot.value, driverId)
                            pickerOpen.value = false
                        },
                    )
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

                PredictionType.DRIVER_POINTS_OVER_UNDER -> {
                    val driverId = draft.driverIds.firstOrNull()
                    val estimate = driverId?.let { DriverPointsEstimates.forDriver(it) }
                    if (driverId == null || estimate == null) {
                        Text(
                            text = stringResource(R.string.pronostico_scegli_prima_pilota),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.pronostico_soglia_stimata,
                                formatEstimate(estimate),
                                DriversCatalog.nameOf(driverId),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OverUnderRow(
                            over = draft.over,
                            enabled = enabled,
                            onChange = {
                                // Soglia fissa = stima del pilota: si setta in
                                // automatico al primo giro, il giocatore sceglie
                                // solo sopra/sotto.
                                viewModel.setThreshold(type, formatEstimate(estimate))
                                viewModel.setOver(type, it)
                            },
                        )
                    }
                }

                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                PredictionType.PIT_STOPS_OVER_UNDER,
                -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Soglie ammesse (quote calcistiche): niente valore libero.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val allowed = when (type) {
                                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER ->
                                    rules.allowedSafetyCarThresholds
                                else -> rules.allowedPitStopThresholds
                            }
                            allowed.forEach { t ->
                                FilterChip(
                                    selected = draft.threshold.toDoubleOrNull() == t,
                                    onClick = {
                                        if (enabled) viewModel.setThreshold(type, formatEstimate(t))
                                    },
                                    label = { Text(formatEstimate(t)) },
                                    enabled = enabled,
                                )
                            }
                        }
                        // Payout prevista: base x quota (si aggiorna a scelta).
                        val t = draft.threshold.toDoubleOrNull()
                        val over = draft.over
                        if (t != null && over != null && rules.pointsFor(type) > 0.0) {
                            val factor = rules.overUnderFactor(type, t, over)
                            Text(
                                text = stringResource(
                                    R.string.pronostico_quota,
                                    formatEstimate(factor),
                                    formatEstimate(points * factor),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        OverUnderRow(
                            over = draft.over,
                            enabled = enabled,
                            onChange = { if (enabled) viewModel.setOver(type, it) },
                        )
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
    onClick: () -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        val driver = selectedDriverId?.let { DriversCatalog.byId(it) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (enabled) {
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
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.slot_cambia),
                        tint = MaterialTheme.colorScheme.primary,
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
        -> "${type.italianLabel}: " +
            (if (entry.over == true) "> " else "< ") + (entry.threshold ?: "")
        else -> "${type.italianLabel}: $drivers"
    }
}
