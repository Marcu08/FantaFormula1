package com.fantaformula1.app.ui.leagues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.R
import com.fantaformula1.app.core.domain.SeasonStandingsSnapshot
import com.fantaformula1.app.core.util.Formats
import com.fantaformula1.app.data.repository.PastSeason
import com.fantaformula1.app.data.util.AppJson
import com.fantaformula1.app.data.util.parseInstantMillis
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.JsonElement

/**
 * F2 — Bottom sheet con le stagioni archiviate e (per gli admin) l'azione
 * per congelare la stagione corrente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastSeasonsSheet(
    state: SeasonHistoryUiState,
    onArchive: () -> Unit,
    onArchiveEventShown: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmArchive by remember { mutableStateOf(false) }
    val successMessage = stringResource(R.string.lega_archivia_fatto)
    val errorMessage = stringResource(R.string.lega_archivia_errore)

    // Esito dell'archiviazione: il messaggio resta visibile per qualche
    // secondo dentro la scheda e poi la scheda si chiude (successo).
    LaunchedEffect(state.archivedEvent) {
        when (state.archivedEvent) {
            true -> {
                snackbarHostState.showSnackbar(successMessage)
                onArchiveEventShown()
                onDismiss()
            }

            false -> {
                snackbarHostState.showSnackbar(state.error ?: errorMessage)
                onArchiveEventShown()
            }

            null -> Unit
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.lega_stagioni_passate),
                style = MaterialTheme.typography.titleLarge,
            )
            when {
                state.loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.error != null && state.pastSeasons.isEmpty() -> Text(
                    text = state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )

                state.pastSeasons.isEmpty() -> Text(
                    text = stringResource(R.string.lega_stagioni_vuote),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> state.pastSeasons.forEach { season ->
                    PastSeasonCard(season = season)
                }
            }

            // Solo l'admin puo' chiudere la stagione, e solo se la stagione
            // in corso ha delle gare da congelare.
            if (state.isAdmin && state.hasRaces) {
                FilledTonalButton(
                    onClick = { confirmArchive = true },
                    enabled = !state.archiving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.archiving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.lega_archivia_stagione))
                    }
                }
            }

            SnackbarHost(snackbarHostState)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(stringResource(R.string.lega_archivia_conferma_titolo)) },
            text = { Text(stringResource(R.string.lega_archivia_conferma_testo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmArchive = false
                        onArchive()
                    },
                ) { Text(stringResource(R.string.lega_archivia_stagione)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        )
    }
}

/** Card di una singola stagione archiviata: nome, data e classifica finale. */
@Composable
private fun PastSeasonCard(season: PastSeason) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = season.name,
                style = MaterialTheme.typography.titleMedium,
            )
            val archivedInstant = parseInstantMillis(season.archivedAt)
                ?.let(Instant::ofEpochMilli)
            if (archivedInstant != null) {
                Text(
                    text = stringResource(
                        R.string.lega_stagione_archiviata_il,
                        Formats.dateTime(archivedInstant),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.lega_classifica_finale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            val snapshot = decodeSnapshot(season.standingsJson)
            when {
                snapshot == null -> Text(
                    text = stringResource(R.string.lega_stagione_non_leggibile),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                snapshot.rows.isEmpty() -> Text(
                    text = stringResource(R.string.classifica_vuota),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> snapshot.rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${row.position}º",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(34.dp),
                        )
                        Text(
                            text = row.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatPoints(row.totalPoints),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Decodifica lo snapshot congelato. Un JSON non leggibile degrada a null
 * (mostrato come "Classifica non leggibile") invece di far saltare la scheda.
 */
private fun decodeSnapshot(json: JsonElement?): SeasonStandingsSnapshot? {
    if (json == null) return null
    return try {
        AppJson.decodeFromJsonElement(SeasonStandingsSnapshot.serializer(), json)
    } catch (_: Throwable) {
        null
    }
}

/** Punti con un decimale, come nella schermata Classifica. */
private fun formatPoints(points: Double): String =
    String.format(Locale.ITALY, "%.1f", points)
