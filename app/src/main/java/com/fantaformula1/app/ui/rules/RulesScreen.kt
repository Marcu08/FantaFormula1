package com.fantaformula1.app.ui.rules

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.TieBreaker
import com.fantaformula1.app.core.domain.italianLabel
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.SectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    container: AppContainer,
    leagueId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RulesViewModel = viewModel {
        RulesViewModel(
            leagueRepository = container.leagueRepository,
            authRepository = container.authRepository,
            leagueId = leagueId,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val savedMessage = stringResource(R.string.regole_salvate)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RulesEvent.Saved -> snackbarHostState.showSnackbar(savedMessage)
                is RulesEvent.Failure -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.regole_titolo)) },
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
                    if (!state.isAdmin) {
                        item {
                            Text(
                                text = stringResource(R.string.regole_sola_lettura),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        FantaTextField(
                            value = state.rules.name,
                            onValueChange = viewModel::onNameChange,
                            label = stringResource(R.string.regole_nome),
                            enabled = state.isAdmin,
                            maxLength = 60,
                        )
                    }

                    if (state.isAdmin) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { viewModel.applyPreset(Rules.presetF1Official()) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.regole_preset_f1)) }
                                TextButton(
                                    onClick = { viewModel.applyPreset(Rules.presetArcade()) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.regole_preset_arcade)) }
                            }
                        }
                    }

                    item { SectionTitle(stringResource(R.string.regole_sezione_tipi)) }
                    items(PredictionType.entries.size) { index ->
                        val type = PredictionType.entries[index]
                        TypeRow(
                            type = type,
                            points = state.rules.pointsFor(type),
                            enabledType = state.rules.enabledTypes.contains(type),
                            editable = state.isAdmin,
                            onToggle = { viewModel.toggleType(type) },
                            onPointsChange = { viewModel.onPointsChange(type, it) },
                        )
                    }

                    item { SectionTitle(stringResource(R.string.regole_sezione_premi)) }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.regole_punti_podio),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    repeat(3) { index ->
                                        FantaTextField(
                                            value = format(state.rules.podiumPoints.getOrNull(index)),
                                            onValueChange = {
                                                viewModel.onPodiumPointChange(index, it)
                                            },
                                            label = stringResource(
                                                R.string.regole_podio_pos,
                                                index + 1,
                                            ),
                                            enabled = state.isAdmin,
                                            keyboardType = KeyboardType.Decimal,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { SectionTitle(stringResource(R.string.regole_sezione_moltiplicatori)) }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MultiplierRow(
                                    label = stringResource(R.string.regole_fattore_quasi),
                                    value = state.rules.exactPositionNearMissFactor,
                                    editable = state.isAdmin,
                                    onChange = viewModel::onNearMissFactorChange,
                                )
                                // B11: il moltiplicatore DNF e' deprecato e non ha
                                // piu' effetto: non viene mostrato nell'editor.
                                MultiplierRow(
                                    label = stringResource(R.string.regole_molt_penalita),
                                    value = state.rules.penaltyMultiplier,
                                    editable = state.isAdmin,
                                    onChange = viewModel::onPenaltyMultiplierChange,
                                )
                                MultiplierRow(
                                    label = stringResource(R.string.regole_molt_sprint),
                                    value = state.rules.sprintPointsMultiplier,
                                    editable = state.isAdmin,
                                    onChange = viewModel::onSprintMultiplierChange,
                                    // B30: mostra i punti sprint effettivi (base x
                                    // moltiplicatore) per evitare calcoli a occhio.
                                    secondaryText = stringResource(
                                        R.string.regole_punti_sprint_effettivi,
                                        sprintWinnerBase(state),
                                        format(state.rules.sprintPointsMultiplier),
                                        sprintWinnerEffective(state),
                                    ),
                                )
                            }
                        }
                    }

                    item { SectionTitle(stringResource(R.string.regole_sezione_spareggio)) }
                    item {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TieBreaker.entries.forEach { tieBreaker ->
                                FilterChip(
                                    selected = state.rules.tieBreaker == tieBreaker,
                                    onClick = {
                                        if (state.isAdmin) viewModel.onTieBreakerChange(tieBreaker)
                                    },
                                    label = { Text(tieBreaker.italianLabel) },
                                    enabled = state.isAdmin,
                                )
                            }
                        }
                    }

                    item { SectionTitle(stringResource(R.string.regole_sezione_opzioni)) }
                    item {
                        OptionRow(
                            label = stringResource(R.string.regole_reveal),
                            checked = state.rules.revealOthersAfterDeadline,
                            enabled = state.isAdmin,
                            onChange = viewModel::onRevealOthersChange,
                        )
                    }
                    item {
                        OptionRow(
                            label = stringResource(R.string.regole_allow_edit),
                            checked = state.rules.allowEditUntilDeadline,
                            enabled = state.isAdmin,
                            onChange = viewModel::onAllowEditChange,
                        )
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
                }
            }
        }
    }
}

@Composable
private fun TypeRow(
    type: PredictionType,
    points: Double,
    enabledType: Boolean,
    editable: Boolean,
    onToggle: () -> Unit,
    onPointsChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(type.italianLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.regole_punti_tipo, format(points)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FantaTextField(
                value = format(points),
                onValueChange = onPointsChange,
                label = stringResource(R.string.regole_punti_campo),
                enabled = editable,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.width(90.dp),
            )
            Switch(checked = enabledType, onCheckedChange = { onToggle() }, enabled = editable)
        }
    }
}

@Composable
private fun MultiplierRow(
    label: String,
    value: Double,
    editable: Boolean,
    onChange: (String) -> Unit,
    secondaryText: String = "",
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FantaTextField(
            value = format(value),
            onValueChange = onChange,
            label = label,
            enabled = editable,
            keyboardType = KeyboardType.Decimal,
        )
        if (secondaryText.isNotEmpty()) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Punti base del pronostico vincitore sprint (prima del moltiplicatore). */
private fun sprintWinnerBase(state: RulesUiState): String =
    format(state.rules.points[PredictionType.SPRINT_WINNER])

/** Punti sprint effettivi = base x moltiplicatore sprint (B30). */
private fun sprintWinnerEffective(state: RulesUiState): String {
    val base = state.rules.points[PredictionType.SPRINT_WINNER] ?: 0.0
    val effective = base * state.rules.sprintPointsMultiplier
    return format(effective)
}

@Composable
private fun OptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun format(value: Double?): String =
    if (value == null) "" else {
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }
