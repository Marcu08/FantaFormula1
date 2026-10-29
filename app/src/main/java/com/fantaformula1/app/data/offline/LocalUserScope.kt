package com.fantaformula1.app.data.offline

/**
 * Namespace locale per i dati che appartengono a un utente.
 *
 * Il DataStore e' condiviso dall'app, ma cache, bozze e coda offline non
 * devono mai passare da un account all'altro sullo stesso dispositivo.
 * Un userId assente/non valido non produce una chiave: il chiamante deve
 * considerare l'operazione non disponibile, invece di scrivere in una chiave
 * "legacy" che il prossimo utente potrebbe leggere.
 */
internal fun scopedPreferenceName(base: String, userId: String?): String? {
    val id = userId?.trim().orEmpty()
    if (id.isEmpty()) return null
    return "u_${id}_$base"
}
