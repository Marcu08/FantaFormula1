package com.fantaformula1.app.ui.components

import androidx.annotation.DrawableRes
import com.fantaformula1.app.R

/**
 * Caricature dei piloti (drawable-nodpi, WebP RGBA trasparenti 928x1152).
 * Ogni risorsa e' associata all'ID stabile del catalogo (es. "VER"), cosi'
 * l'associazione non dipende da nomi file o posizioni. Restituisce null se
 * la caricatura non esiste: il chiamante usa il fallback grafico
 * [DriverAvatar] (casco colore squadra + iniziali).
 */
object DriverImages {

    /** Mappa id -> risorsa: visibile ai test per verificare la copertura. */
    internal val byDriverIds: Map<String, Int> = mapOf(
        "VER" to R.drawable.max_verstappen,
        "HAD" to R.drawable.isack_hadjar,
        "LAW" to R.drawable.liam_lawson,
        "LIN" to R.drawable.arvid_lindblad,
        "NOR" to R.drawable.lando_norris,
        "PIA" to R.drawable.oscar_piastri,
        "LEC" to R.drawable.charles_leclerc,
        "HAM" to R.drawable.lewis_hamilton,
        "RUS" to R.drawable.george_russell,
        "ANT" to R.drawable.kimi_antonelli,
        "ALO" to R.drawable.fernando_alonso,
        "STR" to R.drawable.lance_stroll,
        "GAS" to R.drawable.pierre_gasly,
        "COL" to R.drawable.franco_colapinto,
        "OCO" to R.drawable.esteban_ocon,
        "BEA" to R.drawable.oliver_bearman,
        "ALB" to R.drawable.alex_albon,
        "SAI" to R.drawable.carlos_sainz,
        "HUL" to R.drawable.nico_hulkenberg,
        "BOR" to R.drawable.gabriel_bortoleto,
        "BOT" to R.drawable.valtteri_bottas,
        "PER" to R.drawable.sergio_perez,
    )

    /** Id della risorsa drawable della caricatura, oppure null. */
    @DrawableRes
    fun resIdOf(driverId: String): Int? = byDriverIds[driverId]
}