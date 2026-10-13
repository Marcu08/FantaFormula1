package com.fantaformula1.app.data.f1api

/**
 * Mappa i `driverId` di Jolpica/Ergast (es. "max_verstappen") sugli ID stabili
 * usati dall'app (es. "VER"). Gli ID dell'app sono la sorgente di verita' per
 * pronostici e risultati salvati su Supabase.
 */
object F1DriverMapping {

    private val apiToApp: Map<String, String> = mapOf(
        "max_verstappen" to "VER",
        "isack_hadjar" to "HAD",
        "liam_lawson" to "LAW",
        "arvid_lindblad" to "LIN",
        "lando_norris" to "NOR",
        "oscar_piastri" to "PIA",
        "charles_leclerc" to "LEC",
        "lewis_hamilton" to "HAM",
        "george_russell" to "RUS",
        "kimi_antonelli" to "ANT",
        "fernando_alonso" to "ALO",
        "lance_stroll" to "STR",
        "pierre_gasly" to "GAS",
        "franco_colapinto" to "COL",
        "esteban_ocon" to "OCO",
        "oliver_bearman" to "BEA",
        "alexander_albon" to "ALB",
        "carlos_sainz" to "SAI",
        "nico_hulkenberg" to "HUL",
        "gabriel_bortoleto" to "BOR",
        "valtteri_bottas" to "BOT",
        "sergio_perez" to "PER",
        // Legacy: per risultati storici
        "yuki_tsunoda" to "TSU",
    )

    /** Restituisce l'ID app o null se il pilota non e' nel catalogo. */
    fun toAppId(apiDriverId: String): String? = apiToApp[apiDriverId.lowercase()]

    /** True se il pilota e' conosciuto dall'app. */
    fun isKnown(apiDriverId: String): Boolean = apiDriverId.lowercase() in apiToApp
}
