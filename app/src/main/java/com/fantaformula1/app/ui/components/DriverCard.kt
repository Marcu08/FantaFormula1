package com.fantaformula1.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.core.domain.DriversCatalog

/**
 * Carta "collezionabile" di un pilota: caricatura trasparente in cima su
 * sfondo tinto scuderia (ContentScale.Fit: mai tagliata/deformata), fascia
 * inferiore con nome e scuderia. Fallback = avatar casco se la caricatura
 * manca. Stati: normale, selezionato (bordo + tinta + spunta, annuncio
 * accessibile) e disabilitato. Titolo grafica solo Compose/risorse locali.
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
    val headerHeight = if (compact) 64.dp else 96.dp

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
        // Fascia superiore: caricatura su fondo tinto scuderia.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            teamColor.copy(alpha = 0.45f),
                            teamColor.copy(alpha = 0.12f),
                        ),
                    ),
                ),
        ) {
            val imageRes = DriverImages.resIdOf(driverId)
            if (imageRes != null) {
                Image(
                    painter = painterResource(imageRes),
                    contentDescription = null, // il nome visibile nella fascia inferiore
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Fallback grafico esistente: casco colore squadra + iniziali.
                DriverAvatar(
                    driverId = driverId,
                    size = if (compact) 34.dp else 46.dp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Sigla identificativa, sempre leggibile su qualunque sfondo.
            Text(
                text = driverId.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )

            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null, // lo stato "selezionato" e' annunciato dalla semantica
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp),
                )
            }
        }

        // Fascia inferiore: nome, scuderia e numero di gara.
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
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
            driver?.raceNumber?.let { number ->
                Text(
                    text = "N.$number",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = teamColor.takeIf { c -> c != Color(0xFF6B6B76) }
                        ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}