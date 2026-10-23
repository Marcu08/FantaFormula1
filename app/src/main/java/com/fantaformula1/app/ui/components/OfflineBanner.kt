package com.fantaformula1.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.R
import com.fantaformula1.app.core.util.Formats
import java.time.Instant

/**
 * F3 — banner "Offline": mostrato quando i dati provengono dalla cache
 * locale perche' la rete non ha risposto. Lo stile riusa il pattern dei
 * banner di avviso esistenti (containerColor error, vedi StaleResultsBanner).
 */
@Composable
fun OfflineBanner(
    lastUpdatedEpochMs: Long?,
    modifier: Modifier = Modifier,
) {
    val text = if (lastUpdatedEpochMs != null) {
        stringResource(
            R.string.offline_banner,
            Formats.dateTime(Instant.ofEpochMilli(lastUpdatedEpochMs)),
        )
    } else {
        stringResource(R.string.offline_banner_senza_data)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
