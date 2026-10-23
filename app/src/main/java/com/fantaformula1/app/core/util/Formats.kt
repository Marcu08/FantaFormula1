package com.fantaformula1.app.core.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Formattazione di date, orari, punti e countdown (U3, U12).
 *
 * Tutto puro Kotlin/java.time, con il [Locale] del dispositivo (nessuna
 * stringa fissa) e testato su JVM. Le schermate usano queste funzioni invece
 * di pattern hardcoded, cosi' l'app parla la lingua del telefono.
 */
object Formats {

    /**
     * Countdown compatto: "2 g 4 h", "5 h 20 m", "42 m", "meno di 1 m".
     * Usato dalle card "Prossima gara" e dal banner di scadenza (U3).
     */
    fun countdown(remaining: Duration, locale: Locale = Locale.getDefault()): String {
        val days = remaining.toDays()
        val hours = remaining.toHours() % 24
        val minutes = remaining.toMinutes() % 60
        val unitDay = if (locale.language == "it") "g" else "d"
        val unitHour = if (locale.language == "it") "h" else "h"
        val unitMinute = if (locale.language == "it") "m" else "m"
        return when {
            days > 0 -> "$days $unitDay $hours $unitHour"
            hours > 0 -> "$hours $unitHour $minutes $unitMinute"
            minutes > 0 -> "$minutes $unitMinute"
            else -> if (locale.language == "it") "meno di 1 $unitMinute" else "< 1 $unitMinute"
        }
    }

    /**
     * Data e ora locali nel fuso del dispositivo, secondo il Locale del
     * telefono (es. "15 mar 2026, 14:00" in italiano).
     */
    fun dateTime(
        instant: Instant?,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (instant == null) return ""
        val formatter = DateTimeFormatter.ofLocalizedDateTime(
            FormatStyle.MEDIUM,
            FormatStyle.SHORT,
        ).withLocale(locale).withZone(zone)
        return formatter.format(instant)
    }

    /**
     * Deadline come [dateTime] ma con il fuso orario esplicito: la chiusura
     * dei pronostici e' un istante UTC e l'utente deve sapere in che fuso la
     * sta leggendo (es. "15 mar 2026, 14:00 (Europe/Rome)").
     */
    fun deadline(
        instant: Instant?,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (instant == null) return ""
        val base = dateTime(instant, locale, zone)
        return "$base (${zone.id})"
    }

    /**
     * Punti con segno opzionale e decimali secondo il Locale: "+12", "-3.5"
     * diventa "-3,5" in italiano.
     */
    fun points(
        value: Double,
        locale: Locale = Locale.getDefault(),
        signed: Boolean = true,
    ): String {
        val rounded = Math.round(value * 10.0) / 10.0
        val formatter = java.text.NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        val text = formatter.format(rounded)
        return when {
            signed && rounded > 0 -> "+$text"
            else -> text
        }
    }
}
