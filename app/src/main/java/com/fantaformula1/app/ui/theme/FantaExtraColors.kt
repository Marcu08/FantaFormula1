package com.fantaformula1.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colori semantici che non fanno parte della palette Material 3 ma servono
 * alla UI FantaFormula 1 (medaglie, stati gara, header tematici, overlay
 * sulle immagini pilota).
 *
 * Ogni voce di testo e' accoppiata a un contenitore dedicato: i contrasti
 * sono stati verificati a WCAG 2.1 AA (>= 4.5:1) sia in tema chiaro sia scuro.
 */
@Immutable
data class FantaExtraColors(
    val medalGold: Color,
    val medalGoldContainer: Color,
    val medalSilver: Color,
    val medalSilverContainer: Color,
    val medalBronze: Color,
    val medalBronzeContainer: Color,
    /** Accento testuale della classifica (punti, posizione, stati). */
    val standingsAccent: Color,
    /** Variazione positiva di posizione in classifica. */
    val rankUp: Color,
    val raceScheduled: Color,
    val raceScheduledContainer: Color,
    val raceOpen: Color,
    val raceOpenContainer: Color,
    val raceClosed: Color,
    val raceClosedContainer: Color,
    val raceCompleted: Color,
    val raceCompletedContainer: Color,
    /** Testo sull'header tematico della gara (sopra lo scrim scuro). */
    val onRaceHeader: Color,
    val onRaceHeaderMuted: Color,
    /** Velo scuro che rende leggibile il testo su tutti i gradienti gara. */
    val raceHeaderScrim: Color,
    /** Fondo del badge con il numero del round nell'header gara. */
    val raceHeaderBadgeBackground: Color,
    /** Testo e velo sulle immagini dei piloti (DriverCard). */
    val onPhoto: Color,
    val photoScrim: Color,
)

internal val LightExtraColors = FantaExtraColors(
    medalGold = Color(0xFF6B4E00),
    medalGoldContainer = Color(0xFFFFF1C2),
    medalSilver = Color(0xFF3F4652),
    medalSilverContainer = Color(0xFFE6E9EE),
    medalBronze = Color(0xFF6B3410),
    medalBronzeContainer = Color(0xFFF7E0CE),
    standingsAccent = Color(0xFFA80400),
    rankUp = Color(0xFF006B3E),
    raceScheduled = Color(0xFF164E9F),
    raceScheduledContainer = Color(0xFFDCE6FA),
    raceOpen = Color(0xFF006B3E),
    raceOpenContainer = Color(0xFFD7F0E3),
    raceClosed = Color(0xFF7A4D00),
    raceClosedContainer = Color(0xFFF8E7C6),
    raceCompleted = Color(0xFFA31515),
    raceCompletedContainer = Color(0xFFF7DADA),
    onRaceHeader = Color.White,
    onRaceHeaderMuted = Color(0xFFE6E6E6),
    raceHeaderScrim = Color.Black.copy(alpha = 0.40f),
    raceHeaderBadgeBackground = Color.Black.copy(alpha = 0.28f),
    onPhoto = Color.White,
    photoScrim = Color.Black.copy(alpha = 0.55f),
)

internal val DarkExtraColors = FantaExtraColors(
    medalGold = Color(0xFFFFD666),
    medalGoldContainer = Color(0xFF342500),
    medalSilver = Color(0xFFD5DCE6),
    medalSilverContainer = Color(0xFF24282F),
    medalBronze = Color(0xFFF0B98D),
    medalBronzeContainer = Color(0xFF332014),
    standingsAccent = Color(0xFFFFB4AB),
    rankUp = Color(0xFF6EE7B7),
    raceScheduled = Color(0xFF9CC3FF),
    raceScheduledContainer = Color(0xFF1B2C4A),
    raceOpen = Color(0xFF6EE7B7),
    raceOpenContainer = Color(0xFF10352A),
    raceClosed = Color(0xFFFFD166),
    raceClosedContainer = Color(0xFF3A2A00),
    raceCompleted = Color(0xFFFF9B9B),
    raceCompletedContainer = Color(0xFF3D1A1A),
    onRaceHeader = Color.White,
    onRaceHeaderMuted = Color(0xFFE6E6E6),
    raceHeaderScrim = Color.Black.copy(alpha = 0.40f),
    raceHeaderBadgeBackground = Color.Black.copy(alpha = 0.28f),
    onPhoto = Color.White,
    photoScrim = Color.Black.copy(alpha = 0.55f),
)
