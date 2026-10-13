package com.fantaformula1.app.ui.leagues

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.util.formatLocalDateTimeInput
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.RaceIdentity
import com.fantaformula1.app.ui.components.SyncBar
import com.fantaformula1.app.ui.components.raceHeaderBrush

/**
 * Dettaglio di una lega: dashboard della prossima gara, anagrafica e accesso
 * alle sezioni (membri, calendario, classifica, regole).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeagueScreen(
    container: AppContainer,
    leagueId: String,
    onBack: () -> Unit,
    onOpenMembers: (String) -> Unit,
    onOpenCalendar: (String) -> Unit,
    onOpenStandings: (String) -> Unit,
    onOpenRules: (String) -> Unit,
    onOpenPrediction: (String) -> Unit,
    onSyncCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LeaguesViewModel = viewModel {
        LeaguesViewModel(container.leagueRepository, container.authRepository)
    }
    val membersState by viewModel.membersState.collectAsStateWithLifecycle()
    // Stato di sincronizzazione F1 condiviso con il worker in background.
    val syncState by container.f1SyncStateHolder.state.collectAsStateWithLifecycle()
    var league by remember { mutableStateOf<League?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Dashboard: prossima gara pronosticabile + stato del mio pronostico.
    var nextRace by remember { mutableStateOf<Race?>(null) }
    var predictionSavedForNext by remember { mutableStateOf(false) }
    // Aggiornata ogni minuto per il countdown (stesso pattern B10 dei pronostici).
    var nowTick by remember { mutableStateOf(java.time.Instant.now()) }

    suspend fun loadLeague() {
        loading = true
        error = null
        runCatching { container.leagueRepository.league(leagueId) }
            .onSuccess { league = it }
            .onFailure { throwable ->
                error = throwable.message ?: "Si è verificato un errore"
            }
        loading = false
    }

    LaunchedEffect(leagueId) {
        loadLeague()
        viewModel.loadMembers(leagueId)
        runCatching {
            val races = container.raceRepository.races(leagueId)
            val mine = container.predictionRepository.myPredictions(leagueId)
            val upcoming = races.filterNot { it.isPastRace() }
            val next = upcoming.minByOrNull { it.deadline ?: java.time.Instant.MAX }
            next to mine
        }.onSuccess { (race, mine) ->
            nextRace = race
            predictionSavedForNext = race?.id != null && race.id in mine
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowTick = java.time.Instant.now()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(league?.name ?: stringResource(R.string.lega_titolo_generico)) },
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
                        onClick = onSyncCalendar,
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
                                contentDescription = "Sincronizza calendario F1",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when {
                loading -> LoadingState()
                error != null -> ErrorState(
                    message = error.orEmpty(),
                    onRetry = { viewModel.loadMembers(leagueId) },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (syncState.isSyncing || syncState.lastCalendarSync != null) {
                        item {
                            SyncBar(
                                isSyncing = syncState.isSyncing,
                                lastSync = syncState.lastCalendarSync,
                            )
                        }
                    }
                    nextRace?.let { race ->
                        item(key = "next_race") {
                            NextRaceCard(
                                race = race,
                                predictionSaved = predictionSavedForNext,
                                now = nowTick,
                                onOpenPrediction = onOpenPrediction,
                            )
                        }
                    }
                    item {
                        LeagueSummaryCard(
                            league = league,
                            memberCount = membersState.members.size,
                        )
                    }
                    item {
                        NavRow(
                            icon = Icons.Outlined.Groups,
                            label = stringResource(R.string.lega_membri),
                            onClick = { onOpenMembers(leagueId) },
                        )
                    }
                    item {
                        NavRow(
                            icon = Icons.Outlined.Flag,
                            label = stringResource(R.string.lega_gare),
                            onClick = { onOpenCalendar(leagueId) },
                        )
                    }
                    item {
                        NavRow(
                            icon = Icons.Outlined.EmojiEvents,
                            label = stringResource(R.string.lega_classifica),
                            onClick = { onOpenStandings(leagueId) },
                        )
                    }
                    item {
                        NavRow(
                            icon = Icons.Outlined.Settings,
                            label = stringResource(R.string.lega_regole),
                            onClick = { onOpenRules(leagueId) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dashboard della prossima gara pronosticabile: bandiera e gradiente come
 * nel calendario, countdown alla chiusura, stato del mio pronostico e
 * accesso diretto alla compilazione.
 */
@Composable
private fun NextRaceCard(
    race: Race,
    predictionSaved: Boolean,
    now: java.time.Instant,
    onOpenPrediction: (String) -> Unit,
) {
    val visual = RaceIdentity.visualFor(race.name, race.country, race.round)
    val open = race.isPredictionOpen(now)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dashboard_prossima),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Text(
                        text = race.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (predictionSaved) {
                        stringResource(R.string.dashboard_pronostico_salvato)
                    } else {
                        stringResource(R.string.dashboard_da_compilare)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (predictionSaved) MaterialTheme.colorScheme.primary else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                race.deadline?.let { deadline ->
                    val remaining = java.time.Duration.between(now, deadline)
                    Text(
                        text = buildString {
                            append(
                                stringResource(
                                    R.string.dashboard_chiusura,
                                    formatLocalDateTimeInput(deadline),
                                ),
                            )
                            if (open && !remaining.isNegative && !remaining.isZero) {
                                append(" · ")
                                append(stringResource(R.string.dashboard_tra, countdownText(remaining)))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Text(
                    text = stringResource(R.string.pronostico_sempre_aperta),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (open) {
                    FilledTonalButton(
                        onClick = { onOpenPrediction(race.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (predictionSaved) {
                                stringResource(R.string.dashboard_modifica)
                            } else {
                                stringResource(R.string.dashboard_compila)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Countdown compatto: "3g 4h", "5h 20m" o "42m". */
private fun countdownText(remaining: java.time.Duration): String {
    val days = remaining.toDays()
    val hours = remaining.toHours() % 24
    val minutes = remaining.toMinutes() % 60
    return when {
        days > 0 -> "${days}g ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

/**
 * Riga di navigazione dello schermo lega: icona in un riquadro tinto,
 * etichetta e freccia. Sostituisce i semplici TextButton con una target
 * piu' ampia e coerente con le card delle leghe.
 */
@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LeagueSummaryCard(league: League?, memberCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = league?.name.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.lega_codice_invito) +
                        ": " + (league?.inviteCode.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.membri_conta, memberCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
