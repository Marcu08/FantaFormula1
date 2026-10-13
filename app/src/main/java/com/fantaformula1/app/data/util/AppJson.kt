package com.fantaformula1.app.data.util

import kotlinx.serialization.json.Json

/**
 * Istanza JSON condivisa da tutti i repository (B22).
 *
 * Prima ogni repository aveva la sua `Json` privata: `coerceInputValues` non
 * era mai attivo, quindi valori null in campi non null (o enum sconosciuti in
 * un JSON storico) facevano fallire l'intera decodifica, scartando in silenzio
 * pronostici/risultati leggibili solo in parte.
 *
 * - `ignoreUnknownKeys`: campi aggiunti in futuro non rompono i client vecchi.
 * - `coerceInputValues`: `null` e valori fuori enum cadono sui default invece
 *   di lanciare un'eccezione.
 * - `encodeDefaults`: i campi con default vengono scritti nel jsonb, cosi' le
 *   letture vecchie restano complete.
 */
val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}
