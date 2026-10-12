package com.fantaformula1.app.data.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Conversioni tra le date ISO-8601 di Postgres/Supabase e i millisecondi
 * epoch usati dal motore di punteggio.
 *
 * Supabase restituisce i `timestamptz` sia come `...Z` sia come `...+00:00`:
 * vengono accettati entrambi i formati.
 */
fun parseInstantMillis(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/** Millisecondi epoch -> stringa ISO-8601 accettata da Postgres. */
fun Long.toIsoInstantString(): String = Instant.ofEpochMilli(this).toString()

/** Ora corrente come stringa ISO-8601. */
fun nowIsoInstantString(): String = Instant.now().toString()
