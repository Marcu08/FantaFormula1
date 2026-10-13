package com.fantaformula1.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Mini grafico a barre dei punteggi per gara di un giocatore (trend).
 * Grafico puramente descrittivo: disegna solo i punteggi effettivi nelle
 * gare con risultato, nell'ordine dei round. Scala esplicita passata dal
 * chiamante per confronto visivo fra giocatori.
 */
@Composable
fun PointsSparkline(
    points: List<Double>,
    maxScale: Double,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    zeroColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
) {
    Canvas(modifier = modifier) {
        val scale = maxScale.toFloat()
        if (points.isEmpty() || scale <= 0f) return@Canvas
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (points.size - 1)) / points.size)
            .coerceAtLeast(1f)
        val radius = CornerRadius(1.5f, 1.5f)
        points.forEachIndexed { index, value ->
            val x = index * (barWidth + gap)
            if (value <= 0.0) {
                // Gara con zero punti: barra al minimo, per non falsare la
                // posizione nello spazio.
                drawRoundRect(
                    color = zeroColor,
                    topLeft = Offset(x, size.height - 2f),
                    size = Size(barWidth, 2f),
                    cornerRadius = radius,
                )
            } else {
                val height = ((value.toFloat() / scale) * size.height)
                    .coerceIn(2f, size.height)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = radius,
                )
            }
        }
    }
}