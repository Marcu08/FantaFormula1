package com.fantaformula1.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SportsMotorsports
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fantaformula1.app.R
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.UserException
import com.fantaformula1.app.ui.components.FantaTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository: AuthRepository = container.authRepository
    val viewModel: AuthViewModel = viewModel { AuthViewModel(repository) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val signedInMessage = stringResource(R.string.auth_accesso_ok)
    val signedUpMessage = stringResource(R.string.auth_registrazione_ok)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AuthEvent.SignedIn -> {
                    snackbarHostState.showSnackbar(signedInMessage)
                    onAuthenticated()
                }
                AuthEvent.SignedUp -> {
                    snackbarHostState.showSnackbar(signedUpMessage)
                    onAuthenticated()
                }
                AuthEvent.SignedOut -> Unit
                is AuthEvent.Failure -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auth_titolo)) },
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SportsMotorsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = stringResource(R.string.auth_sottotitolo),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FantaTextField(
                value = form.email,
                onValueChange = viewModel::onEmailChange,
                label = stringResource(R.string.auth_email),
                error = form.emailError,
                keyboardType = KeyboardType.Email,
                enabled = !form.submitting,
            )

            FantaTextField(
                value = form.password,
                onValueChange = viewModel::onPasswordChange,
                label = stringResource(R.string.auth_password),
                error = form.passwordError,
                isPassword = true,
                keyboardType = KeyboardType.Password,
                enabled = !form.submitting,
            )

            if (form.mode == AuthMode.SIGN_UP) {
                FantaTextField(
                    value = form.displayName,
                    onValueChange = viewModel::onDisplayNameChange,
                    label = stringResource(R.string.auth_nome),
                    hint = stringResource(R.string.auth_nome_hint),
                    error = form.nameError,
                    enabled = !form.submitting,
                )
            }

            Button(
                onClick = viewModel::submit,
                enabled = !form.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (form.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        stringResource(
                            if (form.mode == AuthMode.SIGN_IN) {
                                R.string.auth_accedi
                            } else {
                                R.string.auth_registrati
                            },
                        ),
                    )
                }
            }

            OutlinedButton(
                onClick = viewModel::toggleMode,
                enabled = !form.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (form.mode == AuthMode.SIGN_IN) {
                            R.string.auth_non_hai_account
                        } else {
                            R.string.auth_hai_account
                        },
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Usato dai test: verifica che il messaggio di errore arrivi tradotto. */
internal fun Throwable.toUserMessage(): String =
    (this as? UserException)?.message
        ?: this.message
        ?: "Si è verificato un errore"
