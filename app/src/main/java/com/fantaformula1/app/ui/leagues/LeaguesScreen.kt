package com.fantaformula1.app.ui.leagues

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.ui.components.EmptyState
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaguesScreen(
    container: AppContainer,
    onOpenLeague: (String) -> Unit,
    onOpenMembers: (String) -> Unit,
    onOpenConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LeaguesViewModel = viewModel {
        LeaguesViewModel(container.leagueRepository, container.authRepository)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }

    val createdMessage = stringResource(R.string.lega_creato)
    val joinedMessage = stringResource(R.string.lega_unito)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LeaguesEvent.LeagueCreated -> {
                    showCreate = false
                    snackbarHostState.showSnackbar(createdMessage)
                    onOpenLeague(event.leagueId)
                }
                is LeaguesEvent.LeagueJoined -> {
                    showJoin = false
                    snackbarHostState.showSnackbar(joinedMessage)
                    onOpenLeague(event.leagueId)
                }
                is LeaguesEvent.Failure -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.leghe_titolo)) },
                actions = {
                    IconButton(onClick = onOpenConfig) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.config_titolo),
                        )
                    }
                    TextButton(onClick = viewModel::signOut) {
                        Text(stringResource(R.string.auth_esci))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.lega_crea_titolo)) },
            )
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
                    onRetry = viewModel::refresh,
                )
                state.leagues.isEmpty() -> EmptyState(
                    title = stringResource(R.string.leghe_vuoto),
                    subtitle = stringResource(R.string.leghe_vuoto_sub),
                    icon = Icons.Outlined.Groups,
                    actionLabel = stringResource(R.string.lega_unisciti_titolo),
                    onAction = { showJoin = true },
                )
                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.leagues, key = { it.id }) { league ->
                            LeagueCard(
                                league = league,
                                onClick = { onOpenLeague(league.id) },
                                onMembers = { onOpenMembers(league.id) },
                            )
                        }
                        item {
                            TextButton(onClick = { showJoin = true }) {
                                Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.lega_unisciti_titolo))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateLeagueDialog(
            submitting = state.submitting,
            onConfirm = viewModel::createLeague,
            onDismiss = { showCreate = false },
        )
    }
    if (showJoin) {
        JoinLeagueDialog(
            submitting = state.submitting,
            onConfirm = viewModel::joinByCode,
            onDismiss = { showJoin = false },
        )
    }
}

@Composable
private fun LeagueCard(
    league: League,
    onClick: () -> Unit,
    onMembers: () -> Unit,
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(league.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = league.inviteCode,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onMembers) {
                Icon(
                    imageVector = Icons.Outlined.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.lega_membri))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreateLeagueDialog(
    submitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.lega_crea_titolo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.lega_crea_sottotitolo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FantaTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = stringResource(R.string.lega_nome),
                    hint = stringResource(R.string.lega_nome_hint),
                    error = error,
                    enabled = !submitting,
                    maxLength = 60,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().length < 2) {
                        error = "Inserisci un nome di almeno 2 caratteri"
                    } else {
                        onConfirm(name)
                    }
                },
                enabled = !submitting,
            ) { Text(stringResource(R.string.azione_crea)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.azione_annulla))
            }
        },
    )
}

@Composable
private fun JoinLeagueDialog(
    submitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.lega_unisciti_titolo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.lega_codice_invito),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
                FantaTextField(
                    value = code,
                    onValueChange = {
                        code = it.uppercase().take(6)
                        error = null
                    },
                    label = stringResource(R.string.lega_codice),
                    hint = stringResource(R.string.lega_codice_hint),
                    error = error,
                    enabled = !submitting,
                    maxLength = 6,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (code.trim().length != 6) {
                        error = "Il codice invito deve essere di 6 caratteri"
                    } else {
                        onConfirm(code)
                    }
                },
                enabled = !submitting,
            ) { Text(stringResource(R.string.azione_unisciti)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.azione_annulla))
            }
        },
    )
}
