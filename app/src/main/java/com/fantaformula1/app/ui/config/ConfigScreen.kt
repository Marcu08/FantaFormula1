package com.fantaformula1.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.data.notification.DeadlineReminderScheduler
import com.fantaformula1.app.data.notification.NotificationPrefs
import com.fantaformula1.app.ui.components.FantaTextField
import com.fantaformula1.app.ui.components.LoadingState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onConfigured: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ConfigViewModel = viewModel { ConfigViewModel(container) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val savedMessage = stringResource(R.string.config_salvata)
    val resetMessage = stringResource(R.string.config_reset_done)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ConfigEvent.Saved -> {
                    snackbarHostState.showSnackbar(savedMessage)
                    onConfigured()
                }
                ConfigEvent.Reset -> snackbarHostState.showSnackbar(resetMessage)
                is ConfigEvent.Failure -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.config_titolo)) },
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
        if (state.loading) {
            LoadingState(Modifier.padding(innerPadding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(R.string.config_sottotitolo),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = if (state.hasOverride) {
                    stringResource(R.string.config_override_attivo)
                } else {
                    stringResource(R.string.config_valori_default)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )

            FantaTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChange,
                label = stringResource(R.string.config_url),
                hint = stringResource(R.string.config_url_hint),
                error = state.urlError,
                keyboardType = KeyboardType.Uri,
                enabled = !state.isSaving,
            )

            FantaTextField(
                value = state.anonKey,
                onValueChange = viewModel::onKeyChange,
                label = stringResource(R.string.config_anon_key),
                hint = stringResource(R.string.config_anon_hint),
                error = state.keyError,
                isPassword = true,
                keyboardType = KeyboardType.Uri,
                enabled = !state.isSaving,
            )

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving && !state.isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.config_salva))
                }
            }

            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.isSaving && !state.isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(0.dp))
                } else {
                    Text(stringResource(R.string.config_test_in_corso))
                }
            }

            state.testResult?.let { result ->
                val ok = result == "ok"
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (ok) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = if (ok) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(
                            if (ok) R.string.config_test_ok else R.string.config_test_ko,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ok) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }

            // F1: promemoria scadenza (24h e 1h) e avviso risultati, con
            // richiesta del permesso di notifica su Android 13+.
            NotificationsSection(snackbarHostState = snackbarHostState)

            if (state.hasOverride) {
                OutlinedButton(
                    onClick = viewModel::resetToDefaults,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.config_reset))
                }
            }
        }
    }
}

/**
 * F1: interruttore delle notifiche locali. Attivandole su Android 13+ viene
 * richiesto il permesso POST_NOTIFICATIONS in contesto; se l'utente lo nega,
 * lo stato resta spente (e i promemoria schedulati vengono cancellati).
 */
@Composable
private fun NotificationsSection(snackbarHostState: SnackbarHostState) {
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
            scope.launch { snackbarHostState.showSnackbar(permissionDenied) }
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
        Spacer(Modifier.size(12.dp))
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
