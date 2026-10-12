package com.fantaformula1.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = RossoScudetto,
    onPrimary = Color.White,
    primaryContainer = RossoScuro,
    onPrimaryContainer = Color.White,
    secondary = GialloBandiera,
    onSecondary = Color.Black,
    secondaryContainer = Pista,
    onSecondaryContainer = Neve,
    tertiary = VerdePista,
    onTertiary = Color.Black,
    background = Asfalto,
    onBackground = Neve,
    surface = AsfaltoElevato,
    onSurface = Neve,
    surfaceVariant = Pista,
    onSurfaceVariant = GrigioMuretto,
    outline = GrigioMuretto,
    error = Color(0xFFFF5A5F),
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    primary = RossoScuro,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBDA),
    onPrimaryContainer = Color(0xFF410000),
    secondary = Color(0xFF7A5900),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA6),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF006B3E),
    onTertiary = Color.White,
    background = Neve,
    onBackground = Asfalto,
    surface = Color.White,
    onSurface = Asfalto,
    surfaceVariant = Color(0xFFECECF0),
    onSurfaceVariant = Color(0xFF49494F),
    outline = Color(0xFF7A7A80),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

/** Tipografia con numeri leggermente più compatti, utile per tabelle e classifiche. */
private val FantaTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontWeight = FontWeight.Bold),
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun FantaFormulaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Il tema dinamico offuscherebbe la brand color F1: offerto solo come opzione esplicita.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FantaTypography,
        content = content,
    )
}
