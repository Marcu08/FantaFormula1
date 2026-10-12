package com.fantaformula1.app.ui.leagues

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.ui.components.ErrorState
import com.fantaformula1.app.ui.components.LoadingState

/**
 * Dettaglio di una lega: per ora anagrafica e accesso ai membri.
 * Le sezioni calendario, pronostici e classifica arrivano nelle fasi successive.
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
    modifier: Modifier = Modifier,
) {
    val viewModel: LeaguesViewModel = viewModel {
        LeaguesViewModel(container.leagueRepository, container.authRepository)
    }
    val membersState by viewModel.membersState.collectAsStateWithLifecycle()
    var league by remember { mutableStateOf<League?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

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
                    item {
                        LeagueSummaryCard(
                            league = league,
                            memberCount = membersState.members.size,
                        )
                    }
                    item {
                        TextButton(
                            onClick = { onOpenMembers(leagueId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Groups, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.lega_membri))
                        }
                    }
                    item {
                        TextButton(
                            onClick = { onOpenCalendar(leagueId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Flag, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.lega_gare))
                        }
                    }
                    item {
                        TextButton(
                            onClick = { onOpenStandings(leagueId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.EmojiEvents, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.lega_classifica))
                        }
                    }
                    item {
                        TextButton(
                            onClick = { onOpenRules(leagueId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.lega_regole))
                        }
                    }
                }
            }
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
