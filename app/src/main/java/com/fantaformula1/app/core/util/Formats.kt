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
 * Tutto puro Kotlin/java.time, testato su JVM. I punti usano sempre il
 * formato italiano (virgola decimale) indipendentemente dal locale del
 * dispositivo; date e orari seguono il locale del telefono. Le schermate
 * usano queste funzioni invece di pattern hardcoded.
 */
object Formats {

    /** Fuso di riferimento della lega: le deadline sono mostrate in questo fuso. */
    private val RaceZone: ZoneId = ZoneId.of("Europe/Rome")

    /**
     * Countdown compatto: "2 g 4 h", "5 h 20 m", "42 m", "meno di 1 m".
     * Usato dalle card "Prossima gara" e dal banner di scadenza (U3).
     */
    fun countdown(remaining: Duration, locale: Locale = Locale.ITALY): String {
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
     * Deadline compatta: "7 nov · 19:00", con il fuso tra parentesi solo se
     * il fuso del dispositivo e' diverso da quello della gara. La gara e'
     * mostrata nel fuso di riferimento Europe/Rome: il modello non memorizza
     * un fuso per singola gara.
     */
    fun deadline(
        instant: Instant?,
        zone: ZoneId = RaceZone,
        deviceZone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (instant == null) return ""
        val base = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.ITALY)
            .withZone(zone)
            .format(instant)
        return if (zone.normalized() == deviceZone.normalized()) {
            base
        } else {
            "$base (${zone.id})"
        }
    }

    /**
     * Punti in formato italiano: virgola decimale fissa, segno opzionale,
     * niente zeri inutili e al massimo due decimali.
     */
    fun points(
        value: Double,
        signed: Boolean = true,
    ): String {
        val rounded = Math.round(value * 100.0) / 100.0
        val formatter = java.text.NumberFormat.getNumberInstance(Locale.ITALY).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        val text = formatter.format(rounded)
        return when {
            signed && rounded > 0 -> "+$text"
            else -> text
        }
    }
}
