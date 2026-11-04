package com.fantaformula1.app.ui.predictions

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.R
import com.fantaformula1.app.core.domain.PredictionType

/**
 * Sezioni logiche della schermata pronostici. L'ordine dichiarato e' l'ordine
 * in cui le sezioni compaiono quando sono presenti.
 */
enum class PredictionSection(@StringRes val labelRes: Int) {
    RACE_RESULT(R.string.sezione_risultato_gara),
    HEAD_TO_HEAD(R.string.sezione_testa_a_testa),
    RACE_EVENTS(R.string.sezione_eventi_gara),
    STATISTICS(R.string.sezione_statistiche),
    SPRINT(R.string.sezione_sprint),
}

/**
 * Sezione di ogni tipo di pronostico. Il when e' esaustivo senza else: un nuovo
 * PredictionType obbliga ad assegnargli una sezione in compilazione.
 */
val PredictionType.section: PredictionSection
    get() = when (this) {
        PredictionType.WINNER,
        PredictionType.PODIUM,
        PredictionType.FASTEST_LAP,
        PredictionType.POLE,
        PredictionType.DRIVER_EXACT_POSITION,
        PredictionType.DRIVER_POSITION_RANGE,
        -> PredictionSection.RACE_RESULT

        PredictionType.HEAD_TO_HEAD -> PredictionSection.HEAD_TO_HEAD

        PredictionType.DRIVER_DNF,
        PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
        PredictionType.PIT_STOPS_OVER_UNDER,
        -> PredictionSection.RACE_EVENTS

        PredictionType.DRIVER_POINTS_OVER_UNDER -> PredictionSection.STATISTICS

        PredictionType.SPRINT_WINNER -> PredictionSection.SPRINT
    }

/**
 * Raggruppa i tipi visibili mantenendo l'ordine delle sezioni e l'ordine
 * originale dei tipi al loro interno. Le sezioni vuote non compaiono.
 */
internal fun groupBySection(
    visibleTypes: List<PredictionType>,
): List<Pair<PredictionSection, List<PredictionType>>> =
    PredictionSection.entries.mapNotNull { section ->
        val types = visibleTypes.filter { it.section == section }
        if (types.isEmpty()) null else section to types
    }

private val CollapsedSectionsSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/** Set delle sezioni compresse, salvato anche durante rotazione/ricreazione. */
@Composable
internal fun rememberCollapsedPredictionSections(): MutableState<Set<String>> =
    rememberSaveable(stateSaver = CollapsedSectionsSaver) {
        mutableStateOf(emptySet())
    }

/**
 * Aggiunge alla LazyColumn le sezioni con header comprimibile. Con una sola
 * sezione l'header non viene mostrato e le card restano sempre visibili.
 */
internal fun LazyListScope.predictionSectionItems(
    visibleTypes: List<PredictionType>,
    drafts: List<DraftEntry>,
    collapsedSections: Set<String>,
    onToggleSection: (PredictionSection) -> Unit,
    itemContent: @Composable (PredictionType) -> Unit,
) {
    val sections = groupBySection(visibleTypes)
    val showHeaders = sections.size > 1
    sections.forEach { (section, types) ->
        val expanded = !showHeaders || section.name !in collapsedSections
        if (showHeaders) {
            item(key = "section_${section.name}") {
                PredictionSectionHeader(
                    section = section,
                    types = types,
                    drafts = drafts,
                    expanded = expanded,
                    onToggle = { onToggleSection(section) },
                )
            }
        }
        if (expanded) {
            items(types, key = { it.name }) { type ->
                itemContent(type)
            }
        }
    }
}

@Composable
private fun PredictionSectionHeader(
    section: PredictionSection,
    types: List<PredictionType>,
    drafts: List<DraftEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val completed = completedPredictionCount(drafts, types)
    val total = types.size
    val complete = total > 0 && completed == total
    val expansionState = stringResource(
        if (expanded) R.string.sezione_espansa else R.string.sezione_compressa,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = expansionState },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(section.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.pronostico_compilati,
                        total,
                        completed,
                        total,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (complete) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.sezione_completa),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowUp
                } else {
                    Icons.Outlined.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
