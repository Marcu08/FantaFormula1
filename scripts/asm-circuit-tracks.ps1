$ErrorActionPreference = 'Stop'
$dst = 'app\src\main\java\com\fantaformula1\app\ui\components\CircuitTracks.kt'
$body = Get-Content $env:TEMP\opencode\circuits_kotlin.txt -Raw

$head = @'
package com.fantaformula1.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sagome dei tracciati F1 (linea centrale, dati aperti bacinger/f1-circuits,
 * CC-BY-SA), conversion coordinate normalizzate [0..1] con correzione
 * d'aspect equirettangolare (cos lat media). Generato da
 * scripts/gen-circuit-tracks.ps1 + scripts/asm-circuit-tracks.ps1.
 */
object CircuitTracks {

    val byKey: Map<String, FloatArray> = mapOf(
'@

$tailParts = @'

    )

    fun pointsFor(key: String): FloatArray? = byKey[key]

    /**
     * Chiave del tracciato per una gara: keyword sul nome prima (gare doppie
     * con stesso paese: Vegas/Miami/COTA/Imola/Catalunya/Interlagos), poi
     * fallback sul paese. Null = nessuna sagoma disponibile.
     */
    fun keyFor(raceName: String?, country: String?): String? {
        val nameKey = raceName?.trim()?.lowercase().orEmpty()
        if (nameKey.isNotBlank()) {
            byNameKeyword.entries.firstOrNull { nameKey.contains(it.key) }
                ?.let { return it.value }
        }
        val countryKey = country?.trim()?.lowercase().orEmpty()
        if (countryKey.isNotBlank()) {
            byCountry.entries.firstOrNull { countryKey.contains(it.key) }
                ?.let { return it.value }
        }
        return null
    }

    // Prima i nomi tipici, poi i fallback per paese.
    private val byNameKeyword: Map<String, String> = mapOf(
        "vegas" to "vegas",
        "las vegas" to "vegas",
        "miami" to "miami",
        "emilia" to "imola",
        "catal" to "catalunya",
        "paulo" to "brazil",
        "brazilian" to "brazil",
        "cota" to "usa",
        "united states" to "usa",
        "abu dhabi" to "abu_dhabi",
        "saudi" to "saudi",
        "spanish" to "spain",
    )

    private val byCountry: Map<String, String> = mapOf(
        "bahrain" to "bahrain",
        "saudi arabia" to "saudi",
        "australia" to "australia",
        "japan" to "japan",
        "china" to "china",
        "monaco" to "monaco",
        "canada" to "canada",
        "spain" to "spain",
        "austria" to "austria",
        "uk" to "uk",
        "great britain" to "uk",
        "hungary" to "hungary",
        "belgium" to "belgium",
        "netherlands" to "netherlands",
        "italy" to "italy",
        "azerbaijan" to "azerbaijan",
        "singapore" to "singapore",
        "usa" to "usa",
        "united states" to "usa",
        "mexico" to "mexico",
        "brazil" to "brazil",
        "qatar" to "qatar",
        "uae" to "abu_dhabi",
        "united arab emirates" to "abu_dhabi",
    )
}

/**
 * Sagoma del tracciato in un Canvas: i punti sono gia' normalizzati [0..1]
 * con aspect corretto, quindi si scala con min(boxW/trackW, boxH/trackH) e
 * si centra nel box. Stroke arrotondato, lisible su gradient di header.
 */
@Composable
fun CircuitOutline(
    trackKey: String?,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.9f),
    strokeWidth: Dp = 2.dp,
    pad: Dp = 4.dp,
) {
    val points = remember(trackKey) { trackKey?.let(CircuitTracks::pointsFor) }
    if (points == null || points.size < 4) return

    Canvas(modifier = modifier) {
        val padPx = pad.toPx()
        val boxW = size.width - padPx * 2
        val boxH = size.height - padPx * 2
        if (boxW <= 0f || boxH <= 0f) return@Canvas

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < points.size) {
            val x = points[i]
            val y = points[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }
        val trackW = (maxX - minX).coerceAtLeast(1e-6f)
        val trackH = (maxY - minY).coerceAtLeast(1e-6f)
        val scale = minOf(boxW / trackW, boxH / trackH)
        val offX = padPx + (boxW - trackW * scale) / 2f
        val offY = padPx + (boxH - trackH * scale) / 2f

        val path = Path()
        i = 0
        var started = false
        while (i + 1 < points.size) {
            val x = offX + (points[i] - minX) * scale
            val y = offY + (points[i + 1] - minY) * scale
            if (started) path.lineTo(x, y) else path.moveTo(x, y)
            started = true
            i += 2
        }
        path.close()
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
'@

Set-Content -Path $dst -Value ($head + "`n" + $body + "`n" + $tailParts) -Encoding UTF8
Write-Output ("done: " + (Get-Item $dst).Length + " bytes")