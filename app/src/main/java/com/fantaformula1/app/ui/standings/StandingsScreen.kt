package com.fantaformula1.app.ui.standings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.ScoringEngine
import com.fantaformula1.app.ui.components.EmptyState
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.LoadingState
import com.fantaformula1.app.ui.components.OfflineBanner
import com.fantaformula1.app.ui.components.PointsSparkline
import com.fantaformula1.app.ui.theme.fantaExtraColors

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
                isRefreshing = state.refreshing,
                state = pullRefreshState,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading && state.rows.isEmpty() -> LoadingState()
                    state.error != null && state.rows.isEmpty() -> ErrorState(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::load,
                    )
                    else -> Column(Modifier.fillMaxSize()) {
                    StandingsHeader(state)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.tab == StandingsTab.OVERALL,
                            onClick = { viewModel.selectTab(StandingsTab.OVERALL) },
                            label = { Text(stringResource(R.string.classifica_tab_classifica)) },
                        )
                        FilterChip(
                            selected = state.tab == StandingsTab.LAST_RACE,
                            onClick = { viewModel.selectTab(StandingsTab.LAST_RACE) },
                            label = { Text(stringResource(R.string.classifica_tab_ultima_gara)) },
                        )
                        FilterChip(
                            selected = state.tab == StandingsTab.PER_RACE,
                            onClick = { viewModel.selectTab(StandingsTab.PER_RACE) },
                            label = { Text(stringResource(R.string.classifica_tab_gare)) },
                        )
                    }

                    if (state.error != null) {
                        Text(
                            text = state.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
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
                        StandingsTab.LAST_RACE -> LastRaceList(state)
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
private fun StandingsHeader(state: StandingsUiState) {
    val memberCount = if (state.memberCount > 0) state.memberCount else state.rows.size
    val leagueName = state.league?.name ?: stringResource(R.string.lega_titolo_generico)
    val lastRace = state.completedRaces.lastOrNull()
    val meIndex = state.rows.indexOfFirst { it.userId == state.myUserId }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = leagueName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.classifica_partecipanti,
                        memberCount,
                        memberCount,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lastRace != null) {
                Text(
                    text = stringResource(
                        R.string.classifica_header_ultima_gara,
                        stringResource(
                            R.string.gara_round_name,
                            lastRace.round,
                            lastRace.name,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.classifica_ultima_gara_vuota),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meIndex >= 0) {
                val gap = gapToLeader(state.rows, meIndex)
                val gapText = when {
                    meIndex == 0 -> stringResource(R.string.classifica_leader)
                    gap < 0.005 -> stringResource(R.string.classifica_pari_leader)
                    else -> stringResource(R.string.classifica_dal_leader, formatPoints(gap))
                }
                Text(
                    text = stringResource(
                        R.string.classifica_tua_posizione,
                        meIndex + 1,
                        gapText,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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
    val pointsScale = state.rows.maxOfOrNull { it.bestRacePoints }?.coerceAtLeast(1.0) ?: 1.0
    val lastRace = state.completedRaces.lastOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "podium") {
            Podium(rows = state.rows.take(3), myUserId = state.myUserId)
        }
        item(key = "complete_header") {
            Text(
                text = stringResource(R.string.classifica_classifica_completa),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        }
        itemsIndexed(state.rows, key = { _, row -> row.userId }) { index, row ->
            StandingsRowCard(
                index = index,
                row = row,
                isMe = row.userId == state.myUserId,
                expanded = row.userId == state.expandedUserId,
                gap = gapToLeader(state.rows, index),
                lastRace = lastRace,
                lastRacePoints = lastRace?.let { race ->
                    state.scoresByUserRace[row.userId]?.get(race.id)
                },
                racePoints = state.completedRaces.mapNotNull { race ->
                    state.scoresByUserRace[row.userId]?.get(race.id)
                },
                pointsScale = pointsScale,
                completedRaces = state.completedRaces,
                scoresByRace = state.scoresByUserRace[row.userId].orEmpty(),
                rankDelta = state.rankDeltas[row.userId],
                onToggle = { viewModel.toggleRow(row.userId) },
            )
        }
    }
}

@Composable
private fun Podium(
    rows: List<ScoringEngine.StandingsRow>,
    myUserId: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        repeat(3) { index ->
            val row = rows.getOrNull(index)
            if (row == null) {
                Spacer(Modifier.weight(1f))
            } else {
                PodiumCard(
                    rank = index + 1,
                    row = row,
                    isMe = row.userId == myUserId,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PodiumCard(
    rank: Int,
    row: ScoringEngine.StandingsRow,
    isMe: Boolean,
    modifier: Modifier = Modifier,
) {
    val extraColors = MaterialTheme.fantaExtraColors
    val (medalColor, medalContainer) = when (rank) {
        1 -> extraColors.medalGold to extraColors.medalGoldContainer
        2 -> extraColors.medalSilver to extraColors.medalSilverContainer
        else -> extraColors.medalBronze to extraColors.medalBronzeContainer
    }
    Card(
        modifier = modifier.heightIn(min = 124.dp),
        border = if (isMe) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(containerColor = medalContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "$rank°",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = medalColor,
                )
                Icon(
                    imageVector = Icons.Outlined.EmojiEvents,
                    contentDescription = null,
                    tint = medalColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialsFor(row.displayName),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = medalColor,
                )
            }
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatPoints(row.totalPoints),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = medalColor,
            )
        }
    }
}

@Composable
private fun StandingsRowCard(
    index: Int,
    row: ScoringEngine.StandingsRow,
    isMe: Boolean,
    expanded: Boolean,
    gap: Double,
    lastRace: Race?,
    lastRacePoints: Double?,
    racePoints: List<Double>,
    pointsScale: Double,
    completedRaces: List<Race>,
    scoresByRace: Map<String, Double>,
    rankDelta: Int?,
    onToggle: () -> Unit,
) {
    val extraColors = MaterialTheme.fantaExtraColors
    val medal = when (index) {
        0 -> extraColors.medalGold
        1 -> extraColors.medalSilver
        2 -> extraColors.medalBronze
        else -> null
    }
    val accentColor = when {
        isMe -> MaterialTheme.colorScheme.onPrimaryContainer
        medal != null -> medal
        else -> extraColors.standingsAccent
    }
    val secondaryTextColor = if (isMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val toggleLabel = stringResource(
        if (expanded) R.string.classifica_comprimi else R.string.classifica_espandi,
        row.displayName,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = toggleLabel, onClick = onToggle),
        border = if (isMe) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isMe) {
                // Tinta leggera: la riga "Tu" deve essere riconoscibile senza
                // dominare la lista con un rosso pieno.
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isMe) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(38.dp),
            ) {
                Text(
                    text = "${index + 1}°",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
                RankDeltaBadge(delta = rankDelta, onPrimaryContainer = isMe)
            }
            InitialsAvatar(
                name = row.displayName,
                isMe = isMe,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.classifica_dettaglio,
                        pluralStringResource(
                            R.plurals.classifica_vittorie,
                            row.raceWins,
                            row.raceWins,
                        ),
                        pluralStringResource(
                            R.plurals.classifica_gare_con_risultato,
                            row.racesPredicted,
                            row.racesPredicted,
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor,
                )
                if (lastRace != null && lastRacePoints != null && lastRacePoints > 0.0) {
                    Text(
                        text = stringResource(
                            R.string.classifica_ultima_gara,
                            formatPoints(lastRacePoints),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor,
                    )
                }
                if (racePoints.size >= 2) {
                    PointsSparkline(
                        points = racePoints,
                        maxScale = pointsScale,
                        barColor = if (isMe) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        zeroColor = if (isMe) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(10.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatPoints(row.totalPoints),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
                Text(
                    text = when {
                        index == 0 -> stringResource(R.string.classifica_leader)
                        gap < 0.005 -> stringResource(R.string.classifica_pari_leader)
                        else -> stringResource(R.string.classifica_dal_leader, formatPoints(gap))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == 0) accentColor else secondaryTextColor,
                )
                if (isMe) {
                    Text(
                        text = stringResource(R.string.classifica_tu),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                }
            }
        }
        if (expanded) {
            if (completedRaces.isEmpty()) {
                Text(
                    text = stringResource(R.string.classifica_nessun_risultato),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Due round per riga: il dettaglio resta compatto anche
                    // con una stagione completa, senza una lista lunghissima.
                    completedRaces.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            pair.forEach { race ->
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.gara_round_name,
                                            race.round,
                                            race.name,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = secondaryTextColor,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = formatPoints(scoresByRace[race.id] ?: 0.0),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor,
                                    )
                                }
                            }
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialsAvatar(
    name: String,
    isMe: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isMe) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsFor(name),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * U8: freccia di variazione di posizione rispetto all'ultima gara conclusa.
 * Il significato e' sempre esplicito anche per chi non distingue i colori
 * (frecce + numero di posti + descrizione per TalkBack).
 */
@Composable
private fun RankDeltaBadge(delta: Int?, onPrimaryContainer: Boolean = false) {
    if (delta == null || delta == 0) return
    val extraColors = MaterialTheme.fantaExtraColors
    val description = if (delta > 0) {
        pluralStringResource(R.plurals.classifica_movimento_su, delta, delta)
    } else {
        pluralStringResource(R.plurals.classifica_movimento_giu, -delta, -delta)
    }
    Text(
        text = if (delta > 0) "▲ $delta" else "▼ ${-delta}",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = when {
            // Sulla riga "Tu" il fondo e' primaryContainer: il rosso errore
            // scuro non raggiungerebbe 4.5:1.
            onPrimaryContainer -> MaterialTheme.colorScheme.onPrimaryContainer
            delta > 0 -> extraColors.rankUp
            else -> MaterialTheme.colorScheme.error
        },
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
        },
    )
}

@Composable
private fun LastRaceList(state: StandingsUiState) {
    if (state.rows.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.classifica_vuota),
            subtitle = stringResource(R.string.classifica_vuota_sub),
            icon = Icons.Outlined.EmojiEvents,
        )
        return
    }
    val race = state.completedRaces.lastOrNull()
    if (race == null) {
        EmptyState(
            title = stringResource(R.string.classifica_ultima_gara_vuota),
            subtitle = stringResource(R.string.classifica_ultima_gara_vuota_sub),
            icon = Icons.Outlined.EmojiEvents,
        )
        return
    }
    val ranking = lastRaceRanking(
        rows = state.rows,
        pointsByUser = state.scoresByUserRace.mapValues { (_, byRace) ->
            byRace[race.id] ?: 0.0
        },
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "last_race_header") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.classifica_ultima_gara_titolo),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.gara_round_name,
                            race.round,
                            race.name,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(ranking, key = { _, item -> item.row.userId }) { index, item ->
            val isMe = item.row.userId == state.myUserId
            val accentColor = if (isMe) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.fantaExtraColors.standingsAccent
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = if (isMe) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                } else {
                    null
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (isMe) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isMe) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}°",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.width(38.dp),
                    )
                    InitialsAvatar(
                        name = item.row.displayName,
                        isMe = isMe,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item.row.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatPoints(item.points),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                }
            }
        }
    }
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
        items(state.races.sortedBy { it.round }, key = { it.id }) { race ->
            val extraColors = MaterialTheme.fantaExtraColors
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
                                color = extraColors.standingsAccent,
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
                                        color = extraColors.standingsAccent,
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
    com.fantaformula1.app.core.util.Formats.points(points, signed = false)
