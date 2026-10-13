package com.fantaformula1.app.data.f1api

import kotlinx.serialization.json.Json

/**
 * Configurazione Json condivisa per le risposte di Jolpica/Ergast.
 * `ignoreUnknownKeys` tollera i campi extra dell'API (number, laps, url, ...),
 * `coerceInputValues` normalizza i null inattesi. Usata dal client e dai test
 * di deserializzazione, cosi' la configurazione resta unica.
 */
val F1Json: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}