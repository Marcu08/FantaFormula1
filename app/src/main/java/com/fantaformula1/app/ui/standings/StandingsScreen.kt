package com.fantaformula1.app.ui.standings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.ui.components.EmptyState
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.OfflineBanner
import com.fantaformula1.app.ui.components.PointsSparkline
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandingsScreen(
    container: AppContainer,
    leagueId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: StandingsViewModel = viewModel {
        StandingsViewModel(
            leagueDataRepository = container.leagueDataRepository,
            leagueRepository = container.leagueRepository,
            raceRepository = container.raceRepository,
            authRepository = container.authRepository,
            supabaseHolder = container.supabaseHolder,
            leagueId = leagueId,
            offlineCache = container.offlineCacheStore,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Al rientro nella schermata (dopo un refresh dei risultati o un
    // ritorno dall'altra schermata) la classifica va riletta: il
    // ViewModel sopravvive nel backstack con lo stato vecchio.
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.classifica_titolo)) },
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
    ) { innerPadding ->
        val pullRefreshState =
            androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        var refreshing by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // F3: avviso "Offline" quando la classifica arriva dalla cache.
            if (state.offline) {
                OfflineBanner(
                    lastUpdatedEpochMs = state.lastUpdatedEpochMs,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
            // U6: anche la classifica si aggiorna con il gesto di trascinamento.
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
                    else -> Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.tab == StandingsTab.OVERALL,
                            onClick = { viewModel.selectTab(StandingsTab.OVERALL) },
                            label = { Text(stringResource(R.string.classifica_tab_classifica)) },
                        )
                        FilterChip(
                            selected = state.tab == StandingsTab.PER_RACE,
                            onClick = { viewModel.selectTab(StandingsTab.PER_RACE) },
                            label = { Text(stringResource(R.string.classifica_tab_gare)) },
                        )
                    }

                    if (state.unreadablePredictions > 0 || state.unreadableResults > 0) {
                        Text(
                            text = stringResource(
                                R.string.classifica_dati_parziali,
                                state.unreadablePredictions,
                                state.unreadableResults,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    when (state.tab) {
                        StandingsTab.OVERALL -> OverallList(state, viewModel)
                        StandingsTab.PER_RACE -> PerRaceList(state, viewModel)
                    }
                }
            }
            } // PullToRefreshBox
            } // Box contenuto
        } // Column
    }
}

@Composable
private fun OverallList(state: StandingsUiState, viewModel: StandingsViewModel) {
    if (state.rows.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.classifica_vuota),
            subtitle = stringResource(R.string.classifica_vuota_sub),
            icon = Icons.Outlined.EmojiEvents,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.rows, key = { _, row -> row.userId }) { index, row ->
            // Colori medaglia per il podio, neutri per il resto.
            val medal = when (index) {
                0 -> Color(0xFFE0A100) // oro
                1 -> Color(0xFF8E99A8) // argento
                2 -> Color(0xFFC07037) // bronzo
                else -> null
            }
            // U8: la mia riga e' evidenziata in modo non solo cromatico.
            val isMe = row.userId == state.myUserId
            // U8: tap sulla riga -> dettaglio punti gara per gara.
            val expanded = row.userId == state.expandedUserId
            // Ultima gara con risultato: per mostrare i punti guadagnati.
            val lastRaceId = state.races
                .filter { state.resultsByRace.containsKey(it.id) }
                .maxByOrNull { it.round }?.id
            val lastRacePoints = lastRaceId
                ?.let { raceId -> state.scoresByRace[raceId]?.find { it.userId == row.userId }?.points }
            // Trend per gara (punti effettivi, ordine di round): scala
            // sul miglior punteggio globale per confronto visivo fra giocatori.
            val racePoints = state.races
                .filter { state.resultsByRace.containsKey(it.id) }
                .mapNotNull { race -> state.scoresByRace[race.id]?.find { it.userId == row.userId }?.points }
            val pointsScale = state.rows.maxOfOrNull { it.bestRacePoints }?.coerceAtLeast(1.0) ?: 1.0

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleRow(row.userId) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isMe) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        medal?.copy(alpha = 0.18f)
                            ?: MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(40.dp),
                    ) {
                        Text(
                            text = "${index + 1}°",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = medal ?: MaterialTheme.colorScheme.primary,
                        )
                        // U8: variazione di posizione dopo l'ultima gara.
                        RankDeltaBadge(delta = state.rankDeltas[row.userId])
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(
                                R.string.classifica_dettaglio,
                                row.raceWins,
                                row.racesPredicted,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        lastRacePoints?.takeIf { it > 0.0 }?.let { points ->
                            Text(
                                text = stringResource(
                                    R.string.classifica_ultima_gara,
                                    formatPoints(points),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (racePoints.size >= 2) {
                            // Trend solo con almeno due gare concluse.
                            PointsSparkline(
                                points = racePoints,
                                maxScale = pointsScale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .height(12.dp),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatPoints(row.totalPoints),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (isMe) {
                            Text(
                                text = stringResource(R.string.classifica_tu),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (index > 0) {
                            val gap = state.rows[index - 1].totalPoints - row.totalPoints
                            if (gap > 0.0) {
                                Text(
                                    text = stringResource(
                                        R.string.classifica_distanza,
                                        formatPoints(gap),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // U8: dettaglio punti per gara al tap sulla riga.
                if (expanded) {
                    val completedRaces = state.races
                        .filter { state.resultsByRace.containsKey(it.id) }
                        .sortedBy { it.round }
                    if (completedRaces.isEmpty()) {
                        Text(
                            text = stringResource(R.string.classifica_nessun_pronostico),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    } else {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            completedRaces.forEach { race ->
                                val points = state.scoresByRace[race.id]
                                    ?.find { it.userId == row.userId }?.points
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.gara_round_name,
                                            race.round,
                                            race.name,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = points?.let { formatPoints(it) } ?: "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
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

/**
 * U8: freccia di variazione di posizione rispetto all'ultima gara conclusa.
 * Il significato e' sempre esplicito anche per chi non distingue i colori
 * (frecce + numero di posti).
 */
@Composable
private fun RankDeltaBadge(delta: Int?) {
    if (delta == null) return
    Text(
        text = if (delta > 0) "▲ $delta" else "▼ ${-delta}",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (delta > 0) Color(0xFF009150) else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun PerRaceList(state: StandingsUiState, viewModel: StandingsViewModel) {
    if (state.races.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.classifica_nessuna_gara),
            subtitle = stringResource(R.string.calendario_vuoto_sub),
            icon = Icons.Outlined.EmojiEvents,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.races, key = { it.id }) { race ->
            val expanded = race.id == state.selectedRaceId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.selectRace(if (expanded) null else race.id)
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.gara_round_name, race.round, race.name),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.resultsByRace.containsKey(race.id)) {
                            Text(
                                text = stringResource(R.string.stato_completata),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.classifica_senza_risultato),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (expanded) {
                        Spacer(Modifier.padding(top = 6.dp))
                        val scores = state.scoresByRace[race.id].orEmpty()
                        if (scores.isEmpty()) {
                            Text(
                                text = stringResource(R.string.classifica_nessun_pronostico),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            scores.forEach { score ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                ) {
                                    Text(
                                        text = score.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = formatPoints(score.points),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
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

private fun formatPoints(points: Double): String =
    String.format(Locale.ITALY, "%.1f", points)
