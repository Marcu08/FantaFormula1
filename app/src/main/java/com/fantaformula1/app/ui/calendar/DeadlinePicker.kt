package com.fantaformula1.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.R
import com.fantaformula1.app.core.util.Formats
import com.fantaformula1.app.data.util.parseLocalDateTimeInput
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * U2: selettori Material 3 per la deadline al posto del campo di testo ISO.
 *
 * Il ViewModel continua a ricevere/riempiere la stessa stringa "yyyy-MM-dd
 * HH:mm" (formato locale del dispositivo): i picker mostrano data e ora nel
 * fuso del telefono e la conversione in UTC avviene alla conferma, dove
 * passa da [parseLocalDateTimeInput].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlinePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Valore corrente interpretato nel fuso del dispositivo.
    val current: LocalDateTime = remember(value) {
        parseLocalDateTimeInput(value)
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()
            ?: LocalDateTime.now()
    }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { showDate = true },
            enabled = enabled,
            modifier = Modifier.weight(1.4f),
        ) {
            Text(
                text = stringResource(R.string.gara_scegli_data) + ": " +
                    Formats.dateTime(
                        current.atZone(ZoneId.systemDefault()).toInstant(),
                        Locale.getDefault(),
                    ),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        OutlinedButton(
            onClick = { showTime = true },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.gara_scegli_ora) + ": " +
                    String.format(Locale.getDefault(), "%02d:%02d", current.hour, current.minute),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }

    if (showDate) {
        // DatePicker M3: selectedDateMillis e' UTC mezzanotte della data
        // scelta, indipendente dal fuso del dispositivo.
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = current
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = dateState.selectedDateMillis
                        if (millis != null) {
                            val picked = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.of("UTC"))
                                .toLocalDate()
                            pendingDate = picked
                            // Conferma data -> apre subito l'ora, cosi'
                            // l'admin compila in un flusso solo.
                            showDate = false
                            showTime = true
                        } else {
                            showDate = false
                        }
                    },
                ) { Text(stringResource(R.string.azione_salva)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = pendingDate ?: current.toLocalDate()
                        val combined = LocalDateTime.of(
                            date,
                            LocalTime.of(timeState.hour, timeState.minute),
                        )
                        onValueChange(
                            String.format(
                                Locale.ROOT,
                                "%04d-%02d-%02d %02d:%02d",
                                combined.year,
                                combined.monthValue,
                                combined.dayOfMonth,
                                combined.hour,
                                combined.minute,
                            ),
                        )
                        pendingDate = null
                        showTime = false
                    },
                ) { Text(stringResource(R.string.azione_salva)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingDate = null
                        showTime = false
                    },
                ) { Text(stringResource(R.string.azione_annulla)) }
            },
            text = { TimePicker(state = timeState) },
        )
    }
}

/** Formattazione invariante per la stringa del form (testabile). */
internal fun formatFormDateTime(value: LocalDateTime): String =
    String.format(
        Locale.ROOT,
        "%04d-%02d-%02d %02d:%02d",
        value.year,
        value.monthValue,
        value.dayOfMonth,
        value.hour,
        value.minute,
    )

/** Inverse di [formatFormDateTime] senza zone: serve ai test JVM. */
internal fun parseFormDateTime(text: String): LocalDateTime? =
    parseLocalDateTimeInput(text)
        ?.atZone(ZoneId.systemDefault())
        ?.toLocalDateTime()
