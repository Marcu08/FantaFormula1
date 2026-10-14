package com.fantaformula1.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.R
import com.fantaformula1.app.core.domain.Driver
import com.fantaformula1.app.core.domain.DriversCatalog

/**
 * Filtra il catalogo per nome, cognome, sigla e scuderia. Funzione pura,
 * testata in [com.fantaformula1.app.ui.components.DriverPickerFilterTest].
 * Query vuota e filtro null = nessun vincolo.
 */
internal fun filterDrivers(query: String, team: String?): List<Driver> {
    val q = query.trim()
    return DriversCatalog.all.filter { driver ->
        val teamOk = team == null || driver.team == team
        val matchesQuery = q.isBlank() ||
            driver.name.contains(q, ignoreCase = true) ||
            driver.id.contains(q, ignoreCase = true)
        teamOk && matchesQuery
    }
}

private fun teamFilterMatches(teamFilter: String?, driver: Driver): Boolean =
    teamFilter == null || driver.team == teamFilter

/**
 * Selettore grafico dei piloti in un bottom sheet: ricerca testuale, filtro
 * per scuderia e griglia responsive di [DriverCard] (colonne adattive: su
 * schermi stretti passa automaticamente a una colonna). Nessuna rete: il
 * catalogo e' locale. La chiusura dopo una scelta valida e' gestita dal
 * chiamante tramite [onDriverSelected].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DriverPickerBottomSheet(
    selectedDriverId: String?,
    onDismiss: () -> Unit,
    onDriverSelected: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var teamFilter by remember { mutableStateOf<String?>(null) }

    val teams = remember { DriversCatalog.all.map { it.team }.distinct().sorted() }
    val filtered = filterDrivers(query, teamFilter)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sheet_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.sheet_cancella_ricerca),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = teamFilter == null,
                    onClick = { teamFilter = null },
                    label = { Text(stringResource(R.string.sheet_filter_all)) },
                )
                teams.forEach { team ->
                    FilterChip(
                        selected = teamFilter == team,
                        onClick = { teamFilter = team },
                        label = { Text(team) },
                    )
                }
            }

            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp, max = 460.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { driver ->
                        DriverCard(
                            driverId = driver.id,
                            selected = driver.id == selectedDriverId,
                            onClick = { onDriverSelected(driver.id) },
                        )
                    }
                }
            }
        }
    }
}