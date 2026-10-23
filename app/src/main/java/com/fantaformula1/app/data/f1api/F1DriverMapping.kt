package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.data.f1api.model.F1Driver

/**
 * Mappa i piloti di Jolpica/Ergast sugli ID stabili usati dall'app
 * (es. "VER"). Gli ID dell'app sono la sorgente di verita' per pronostici e
 * risultati salvati su Supabase e coincidono con i codici FIA.
 *
 * Strategia (verificata su una risposta reale `f1/2026/drivers.json`):
 * 1. si usa per primo il campo `code` del pilota (VER, NOR, HAM...), che
 *    coincide con gli ID dell'app e non dipende dalla convenzione dei
 *    `driverId` dell'API;
 * 2. come ripiego, una tabella di `driverId` noti. Jolpica usa il cognome
 *    ("norris", "hamilton", "sainz", "hulkenberg"...) con poche eccezioni
 *    nome+cognome ("max_verstappen", "arvid_lindblad"): la tabella include
 *    entrambe le forme piu' lo storico Ergast.
 */
object F1DriverMapping {

    private val apiToApp: Map<String, String> = mapOf(
        // Forma nome+cognome (eccezioni Jolpica e storico Ergast).
        "max_verstappen" to "VER",
        "arvid_lindblad" to "LIN",
        // Forma cognome (convenzione principale Jolpica/Ergast).
        "verstappen" to "VER",
        "hadjar" to "HAD",
        "lawson" to "LAW",
        "lindblad" to "LIN",
        "norris" to "NOR",
        "piastri" to "PIA",
        "leclerc" to "LEC",
        "hamilton" to "HAM",
        "russell" to "RUS",
        "antonelli" to "ANT",
        "alonso" to "ALO",
        "stroll" to "STR",
        "gasly" to "GAS",
        "colapinto" to "COL",
        "ocon" to "OCO",
        "bearman" to "BEA",
        "albon" to "ALB",
        "sainz" to "SAI",
        "hulkenberg" to "HUL",
        "bortoleto" to "BOR",
        "bottas" to "BOT",
        "perez" to "PER",
        // Legacy: piloti non titolari ma presenti nei dati gia' salvati.
        "tsunoda" to "TSU",
    )

    /**
     * Mappa un pilota dell'API: preferisce il codice FIA (`code`), che e' lo
     * stesso ID dell'app; altrimenti usa la tabella dei `driverId`.
     */
    fun toAppId(driver: F1Driver): String? {
        val code = driver.code?.trim()?.uppercase().orEmpty()
        if (code.isNotEmpty() && DriversCatalog.byId(code) != null) {
            return code
        }
        return toAppId(driver.driverId)
    }

    /** Restituisce l'ID app dal `driverId` Jolpica, oppure null se sconosciuto. */
    fun toAppId(apiDriverId: String): String? = apiToApp[apiDriverId.lowercase()]

    /** True se il `driverId` e' conosciuto dall'app. */
    fun isKnown(apiDriverId: String): Boolean = apiDriverId.lowercase() in apiToApp
}
