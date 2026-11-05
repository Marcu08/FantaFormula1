package com.fantaformula1.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fantaformula1.app.ui.theme.fantaExtraColors

/** Categoria semantica di uno snackbar: informazione, successo o errore. */
enum class FantaSnackbarKind { INFO, SUCCESS, ERROR }

private data class FantaSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    val kind: FantaSnackbarKind = FantaSnackbarKind.INFO,
) : SnackbarVisuals

/**
 * Mostra uno snackbar con la semantica dell'app: neutro per le informazioni,
 * verde tenue per i successi, rosso/errore per i fallimenti.
 */
suspend fun SnackbarHostState.showFantaSnackbar(
    message: String,
    kind: FantaSnackbarKind = FantaSnackbarKind.INFO,
) {
    showSnackbar(
        FantaSnackbarVisuals(
            message = message,
            kind = kind,
        ),
    )
}

/**
 * Host unico degli snackbar: usa i colori del tema invece dell'inverseSurface
 * Material, che in dark mode risultava una superficie chiara estranea.
 */
@Composable
fun FantaSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        FantaSnackbar(data = data)
    }
}

@Composable
private fun FantaSnackbar(data: SnackbarData) {
    val kind = (data.visuals as? FantaSnackbarVisuals)?.kind ?: FantaSnackbarKind.INFO
    val extraColors = MaterialTheme.fantaExtraColors
    val containerColor = when (kind) {
        FantaSnackbarKind.ERROR -> MaterialTheme.colorScheme.errorContainer
        FantaSnackbarKind.SUCCESS -> extraColors.raceOpenContainer
        FantaSnackbarKind.INFO -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (kind) {
        FantaSnackbarKind.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        FantaSnackbarKind.SUCCESS -> extraColors.raceOpen
        FantaSnackbarKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Snackbar(
        snackbarData = data,
        containerColor = containerColor,
        contentColor = contentColor,
        actionColor = contentColor,
        actionContentColor = contentColor,
        dismissActionContentColor = contentColor,
    )
}
