package com.fantaformula1.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.core.domain.DriversCatalog

/**
 * Carta "collezionabile" di un pilota: header a gradiente col colore della
 * scuderia e sigla grande, avatar casco, nome e scuderia. Stati: normale,
 * selezionato (bordo + tinta + spunta, annuncio accessibile) e disabilitato.
 * Grafica solo Compose: nessuna immagine remota, nessun logo protetto.
 */
@Composable
fun DriverCard(
    driverId: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val driver = DriversCatalog.byId(driverId)
    val teamColor = DriverTeamColors.colorFor(driver?.team.orEmpty())

    Card(
        modifier = modifier
            .animateContentSize()
            .alpha(alpha = if (enabled) 1f else 0.45f)
            .semantics { this.selected = selected }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                teamColor.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 4.dp else 1.dp,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 40.dp else 52.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(teamColor, teamColor.copy(alpha = 0.55f)),
                    ),
                ),
        ) {
            Text(
                text = driverId.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 12.dp),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null, // lo stato "selezionato" e' annunciato dalla semantica
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(horizontal = 10.dp)
                        .size(24.dp),
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DriverAvatar(
                driverId = driverId,
                size = if (compact) 26.dp else 34.dp,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = driver?.name ?: driverId,
                    style = if (compact) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = driver?.team.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}