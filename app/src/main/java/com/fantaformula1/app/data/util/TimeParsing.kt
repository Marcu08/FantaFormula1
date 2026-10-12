package com.fantaformula1.app.data.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

// Formato mostrato nel form gara: semplice da digitare sul telefono.
private val formFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Legge una deadline digitata dall'admin ("2026-03-15 14:00") nell'orario
 * locale del dispositivo e la converte in stringa ISO-8601 per Postgres.
 */
fun parseLocalDateTimeInput(text: String?): Instant? {
    if (text.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(text.trim(), formFormatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Inverse di [parseLocalDateTimeInput], per rieditare una deadline salvata. */
fun formatLocalDateTimeInput(instant: Instant?): String {
    if (instant == null) return ""
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(formFormatter)
}
