package com.fantaformula1.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fantaformula1.app.core.domain.DriversCatalog

/**
 * Colori di squadra per gli avatar dei piloti (niente risorse da licenziare:
 * solo colori tematici riconoscibili). Match per sottostringa del nome
 * squadra del catalogo; fallback grigio per squadre/salvataggi sconosciuti.
 */
object DriverTeamColors {

    private val byTeamKnown: Map<String, Long> = mapOf(
        "red bull" to 0xFF1E2A5C,
        "racing bulls" to 0xFF2A6DB0,
        "mclaren" to 0xFFFF8000,
        "ferrari" to 0xFFDC0000,
        "mercedes" to 0xFF1FB8AD,
        "aston martin" to 0xFF0B6E5C,
        "alpine" to 0xFF0093CC,
        "williams" to 0xFF146FD6,
        "haas" to 0xFF8A8A93,
        "audi" to 0xFF27A68F,
        "cadillac" to 0xFFB08C3D,
    )

    /** Colore squadra: match parziale sul nome (case-insensitive). */
    fun colorFor(team: String): Color {
        val key = team.trim().lowercase()
        if (key.isNotBlank()) {
            byTeamKnown.entries.firstOrNull { key.contains(it.key) }?.let { return Color(it.value) }
        }
        return Color(0xFF6B6B76)
    }

    /** Secondo colore per bordo/accenno: versione schiarita. */
    fun accentFor(team: String): Color =
        colorFor(team).copy(alpha = 0.45f)
}

/**
 * Avatar "caricatura" del pilota senza risorse fotografiche: casco stilizzato
 * con visore chiaro, colore della squadra e iniziali del cognome. Usato nei
 * tendini dei pronostici e nel form dei risultati.
 */
@Composable
fun DriverAvatar(
    driverId: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val driver = DriversCatalog.byId(driverId)
    val teamColor = DriverTeamColors.colorFor(driver?.team.orEmpty())
    val accent = DriverTeamColors.accentFor(driver?.team.orEmpty())
    // Iniziali del cognome (Nome Cognome -> "CON", 3 lettere come i tag F1).
    val initials = driver?.name
        ?.split(' ')
        ?.lastOrNull()
        ?.filter { it.isLetter() }
        ?.take(3)
        ?.uppercase()
        ?.ifBlank { null }
        ?: driverId.take(3).uppercase()

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(teamColor),
    ) {
        // Banda visore del casco, in alto.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = size * 0.16f)
                .fillMaxWidth(0.62f)
                .height(size * 0.22f)
                .background(accent, RoundedCornerShape(50)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize()
                .padding(bottom = size * 0.10f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}