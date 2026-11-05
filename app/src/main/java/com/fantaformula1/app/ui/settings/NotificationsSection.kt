package com.fantaformula1.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fantaformula1.app.R
import com.fantaformula1.app.data.notification.DeadlineReminderScheduler
import com.fantaformula1.app.data.notification.NotificationPrefs
import com.fantaformula1.app.ui.components.FantaSnackbarKind
import com.fantaformula1.app.ui.components.showFantaSnackbar
import kotlinx.coroutines.launch

/**
 * F1: interruttore delle notifiche locali. Attivandole su Android 13+ viene
 * richiesto il permesso POST_NOTIFICATIONS in contesto; se l'utente lo nega,
 * lo stato resta spento (e i promemoria schedulati vengono cancellati).
 *
 * Estratto dalla vecchia ConfigScreen per essere riusato dalla schermata
 * Impostazioni senza duplicare la logica del permesso.
 */
@Composable
fun NotificationsSection(snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }
    val prefs = remember { NotificationPrefs(context) }
    val permissionDenied = stringResource(R.string.config_notifiche_permesso_negato)

    LaunchedEffect(Unit) {
        enabled = prefs.enabled()
    }

    fun applyEnabled(value: Boolean) {
        scope.launch {
            prefs.setEnabled(value)
            if (!value) DeadlineReminderScheduler.cancelAll(context)
            enabled = value
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            applyEnabled(true)
        } else {
            applyEnabled(false)
            scope.launch {
                snackbarHostState.showFantaSnackbar(permissionDenied, FantaSnackbarKind.ERROR)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.config_notifiche),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.config_notifiche_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { want ->
                val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                if (want && needsPermission) {
                    // Il salvataggio avviene nel callback: con il permesso
                    // negato le notifiche restano spente.
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    applyEnabled(want)
                }
            },
        )
    }
}
