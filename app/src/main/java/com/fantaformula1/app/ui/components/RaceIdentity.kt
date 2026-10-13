package com.fantaformula1.app.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Identita' visiva di una gara: bandiera del gran premio e gradiente
 * tematico. Ogni gara ha una coppia di colori propria, derivata dal nome
 * del gran premio (es. "Miami Grand Prix" -> rosa/teal), con fallback sul
 * paese e infine su una palette deterministica per round cosi' che anche
 * una gara sconosciuta abbia un aspetto unico.
 */
data class RaceVisual(
    /** Bandiera emoji del paese ospitante (o 🏁 per le gare sconosciute). */
    val flag: String,
    val startColor: Long,
    val endColor: Long,
)

object RaceIdentity {

    /** Corrispondenze per nome del gran premio (chiavi in minuscolo). */
    private val byName: Map<String, RaceVisual> = mapOf(
        "bahrain" to RaceVisual("🇧🇭", 0xFF8E1220, 0xFFCE1126),
        "saudi" to RaceVisual("🇸🇦", 0xFF02402F, 0xFF006C35),
        "australian" to RaceVisual("🇦🇺", 0xFF00592A, 0xFF00843D),
        "japanese" to RaceVisual("🇯🇵", 0xFF5C0016, 0xFFBC002D),
        "chinese" to RaceVisual("🇨🇳", 0xFF8A1B0B, 0xFFDE2910),
        "miami" to RaceVisual("🇺🇸", 0xFFC2186B, 0xFF00B5AD),
        "monaco" to RaceVisual("🇲🇨", 0xFF20124D, 0xFFE10600),
        "emilia" to RaceVisual("🇮🇹", 0xFF2E4A1F, 0xFF008C45),
        "italian" to RaceVisual("🇮🇹", 0xFF7A0A16, 0xFFCE2B37),
        "spanish" to RaceVisual("🇪🇸", 0xFF6E1200, 0xFFAD1519),
        "canadian" to RaceVisual("🇨🇦", 0xFF5E0B15, 0xFFD80621),
        "austrian" to RaceVisual("🇦🇹", 0xFF6B0F16, 0xFFED2939),
        "british" to RaceVisual("🇬🇧", 0xFF012169, 0xFF7A0C22),
        "hungarian" to RaceVisual("🇭🇺", 0xFF8C2F39, 0xFF477050),
        "belgian" to RaceVisual("🇧🇪", 0xFF2B2823, 0xFF9C8412),
        "dutch" to RaceVisual("🇳🇱", 0xFF8C3D00, 0xFFE8641B),
        "azerbaijan" to RaceVisual("🇦🇿", 0xFF005E77, 0xFF0092BC),
        "singapore" to RaceVisual("🇸🇬", 0xFF2B1B3F, 0xFFEF3340),
        "united states" to RaceVisual("🇺🇸", 0xFF1C1B5E, 0xFFB22234),
        "mexico" to RaceVisual("🇲🇽", 0xFF00513A, 0xFF8E1F2E),
        "paulo" to RaceVisual("🇧🇷", 0xFF02382F, 0xFF009739),
        "brazilian" to RaceVisual("🇧🇷", 0xFF02382F, 0xFF009739),
        "vegas" to RaceVisual("🇺🇸", 0xFF2D0A4E, 0xFF7A3FA0),
        "qatar" to RaceVisual("🇶🇦", 0xFF4E0A20, 0xFF8A1538),
        "abu dhabi" to RaceVisual("🇦🇪", 0xFF00401F, 0xFF6E1423),
        "vietnam" to RaceVisual("🇻🇳", 0xFF7A0A16, 0xFFDA251D),
        "france" to RaceVisual("🇫🇷", 0xFF00337F, 0xFFB3252B),
        "german" to RaceVisual("🇩🇪", 0xFF1B1B1B, 0xFF8C1F1F),
    )

    /** Corrispondenze per paese (fallback se il nome non e' riconosciuto). */
    private val byCountry: Map<String, RaceVisual> = mapOf(
        "bahrain" to byName.getValue("bahrain"),
        "saudi arabia" to byName.getValue("saudi"),
        "australia" to byName.getValue("australian"),
        "japan" to byName.getValue("japanese"),
        "china" to byName.getValue("chinese"),
        "usa" to byName.getValue("united states"),
        "united states" to byName.getValue("united states"),
        "monaco" to byName.getValue("monaco"),
        "spain" to byName.getValue("spanish"),
        "canada" to byName.getValue("canadian"),
        "austria" to byName.getValue("austrian"),
        "uk" to byName.getValue("british"),
        "great britain" to byName.getValue("british"),
        "hungary" to byName.getValue("hungarian"),
        "belgium" to byName.getValue("belgian"),
        "netherlands" to byName.getValue("dutch"),
        "italy" to byName.getValue("italian"),
        "azerbaijan" to byName.getValue("azerbaijan"),
        "singapore" to byName.getValue("singapore"),
        "mexico" to byName.getValue("mexico"),
        "brazil" to byName.getValue("brazilian"),
        "qatar" to byName.getValue("qatar"),
        "uae" to byName.getValue("abu dhabi"),
        "united arab emirates" to byName.getValue("abu dhabi"),
        "france" to byName.getValue("france"),
        "germany" to byName.getValue("german"),
    )

    /**
     * Palette deterministica per le gare non riconosciute: cicla sul round,
     * cosi' ogni gara ha comunque un gradiente stabile e distintivo.
     */
    private val fallback: List<RaceVisual> = listOf(
        RaceVisual("🏁", 0xFF1C1B5E, 0xFF5B4B8A),
        RaceVisual("🏁", 0xFF5C0016, 0xFFB3252B),
        RaceVisual("🏁", 0xFF02402F, 0xFF00754A),
        RaceVisual("🏁", 0xFF3E2723, 0xFF8D6E63),
        RaceVisual("🏁", 0xFF012169, 0xFF2874A6),
        RaceVisual("🏁", 0xFF4A2C00, 0xFFB8860B),
        RaceVisual("🏁", 0xFF3F1B3F, 0xFF8E24AA),
        RaceVisual("🏁", 0xFF17374A, 0xFF4E86A8),
    )

    /** Identita' visiva per una gara: nome → paese → palette per round. */
    fun visualFor(raceName: String?, country: String?, round: Int): RaceVisual {
        val nameKey = raceName?.trim()?.lowercase().orEmpty()
        if (nameKey.isNotBlank()) {
            byName.entries.firstOrNull { nameKey.contains(it.key) }?.let { return it.value }
        }
        val countryKey = country?.trim()?.lowercase().orEmpty()
        if (countryKey.isNotBlank()) {
            byCountry.entries.firstOrNull { countryKey.contains(it.key) }?.let { return it.value }
        }
        val index = if (round > 0) (round - 1) % fallback.size else 0
        return fallback[index]
    }
}

/** Gradiente orizzontale per l'header tematico di una gara. */
fun raceHeaderBrush(visual: RaceVisual): Brush =
    Brush.horizontalGradient(
        colors = listOf(Color(visual.startColor), Color(visual.endColor)),
    )