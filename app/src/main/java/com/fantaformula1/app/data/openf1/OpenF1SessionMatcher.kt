package com.fantaformula1.app.data.openf1

import java.time.Instant

/**
 * Associa una gara interna dell'app alla corretta SESSIONE OpenF1 "Race".
 *
 * Regola NON negoziabile: il round Jolpica NON e' un identificatore OpenF1
 * (i round possono differire di numeri: test, gare cancellate), quindi il match
 * usa solo dati reali:
 *
 * 1. PRIMARIA — istante di deadline: la deadline della gara coincide con
 *    lo START della QUALIFYING dello stesso meeting (es. Australia 2026:
 *    deadline 2026-03-07T06:00Z == Qualifying date_start). Tolleranza 15
 *    minuti per gare create manualmente; una sola candidatura = match.
 * 2. FALLBACK — paese: alias della nomenclatura Jolpica/Ergast -> ISO3
 *    (UK->GBR, UAE->ARE, USA->USA), confronto case-insensitive su
 *    country_code e country_name.
 * ZERO candidati o >1 candidato -> NULL match + motivo: l'admin procede
 *    a mano (mai il fallback "latest", mai presunto intuito).
 */
object OpenF1SessionMatcher {

    sealed class Match {
        data class Ok(val sessionKey: Int) : Match()
        data class Failed(val reason: String) : Match()
    }

    /** Chiavi FIA/OpenF1 note -> ISO3. Jolpica/Ergast scrive forme diverse. */
    private val COUNTRY_ALIAS: Map<String, String> = mapOf(
        "UK" to "GBR", "GREAT BRITAIN" to "GBR", "UNITED KINGDOM" to "GBR",
        "UAE" to "ARE", "UNITED ARAB EMIRATES" to "ARE", "ABU DHABI" to "ARE",
        "USA" to "USA", "UNITED STATES" to "USA", "UNITED STATES OF AMERICA" to "USA",
        "AZERBAIJAN" to "AZE", "SPAIN" to "ESP", "AUSTRALIA" to "AUS",
        "CHINA" to "CHN", "JAPAN" to "JPN", "ITALY" to "ITA", "MONACO" to "MON",
        "CANADA" to "CAN", "BRAZIL" to "BRA", "MEXICO" to "MEX", "AUSTRIA" to "AUT",
        "BELGIUM" to "BEL", "NETHERLANDS" to "NED", "HUNGARY" to "HUN",
        "BAHRAIN" to "BRN", "SAUDI ARABIA" to "KSA", "QATAR" to "QAT",
        "SINGAPORE" to "SIN",
    )

    fun match(
        raceSeason: Int,
        raceCountry: String?,
        raceName: String,
        deadline: Instant?,
        sessions: List<OpenF1Session>,
    ): Match {
        // ATTENZIONE (trovato collaudo live): nei weekend con SPRINT OpenF1
        // marca la sessione Sprint con session_type="Race": filtrare per
        // session_type includerebbe lo Sprint e renderebbe ambigue le gare.
        // L'identificatore corretto della GRAN PREMIO e' session_name="Race".
        val raceSessions = sessions
            .filter { !it.isCancelled }
            .filter { it.sessionName.equals("Race", ignoreCase = true) }

        if (raceSessions.isEmpty()) {
            return Match.Failed("OpenF1 non ha sessioni Race per la stagione $raceSeason: procedi a mano")
        }

        // 1) Deadline = istante di partenza della QUALIFYING dello stesso weekend.
        if (deadline != null) {
            val deadlineMs = deadline.toEpochMilli()
            val matchedMeetingKeys = sessions.filter { !it.isCancelled }
                .filter { it.sessionType.equals("Qualifying", ignoreCase = true) }
                .groupBy { it.meetingKey }
                .mapNotNull { (meetingKey, qualifyingSessions) ->
                    val qualInstant = qualifyingSessions
                        .mapNotNull { openF1DateOrNull(it.dateStart) }
                        .minOrNull()
                    if (qualInstant == null) {
                        null
                    } else {
                        val delta = kotlin.math.abs(qualInstant.toEpochMilli() - deadlineMs)
                        if (delta <= DEADLINE_TOLERANCE_MS) meetingKey else null
                    }
                }
            val candidatesInWeekend = raceSessions.filter { it.meetingKey in matchedMeetingKeys }
            when {
                candidatesInWeekend.size == 1 ->
                    return Match.Ok(candidatesInWeekend[0].sessionKey)
                candidatesInWeekend.size > 1 -> {
                    // Stessa deadline per piu' meeting: impossibile a calendario
                    // reale, il paese decide come difesa.
                    val byCountry = countryFilter(candidatesInWeekend, raceCountry)
                    if (byCountry.size == 1) {
                        return Match.Ok(byCountry[0].sessionKey)
                    }
                    return Match.Failed(
                        "Sessione OpenF1 ambigua (${candidatesInWeekend.size} candidate con la stessa deadline identica): " +
                            "risultato da inserire a mano",
                    )
                }
                else -> Unit // nessuna Qualifying vicina alla deadline: fallback paese
            }
        }

        // 2) Fallback paese (deadline assente o senza corrispondenze).
        val byCountry = countryFilter(raceSessions, raceCountry)
        return when (byCountry.size) {
            1 -> Match.Ok(byCountry[0].sessionKey)
            0 -> Match.Failed(
                "Nessuna sessione OpenF1 corrispondente al paese \"$raceCountry\" " +
                    "per la stagione $raceSeason: procedi a mano",
            )
            else -> Match.Failed(
                "Sessione OpenF1 ambigua: piu' gare di ${raceCountry.orEmpty()} nel calendario $raceSeason " +
                    "(e nessuna deadline utilizzabile per differenziare): procedi a mano",
            )
        }
    }

    /** Messaggi per paese: prova exact code, poi alias, poi nome corrispondente. */
    private fun countryFilter(candidates: List<OpenF1Session>, raceCountry: String?): List<OpenF1Session> {
        val country = raceCountry?.trim().orEmpty().uppercase()
        if (country.isEmpty()) return emptyList()
        val iso = COUNTRY_ALIAS[country] ?: country
        val exact = candidates.filter { it.countryCode.uppercase() == iso }
        if (exact.size >= 1) return exact
        // Ultima rete: match largo del nome (es. "Spagna" vs "Spain" omonimo).
        return candidates.filter { it.countryName.uppercase().contains(country) || country.contains(it.countryName.uppercase()) }
    }

    /** date_start ISO con offset ("2026-03-07T06:00:00+01:00") -> Instant. */
    private fun dateOrNull(raw: String): Instant? = openF1DateOrNull(raw)

    private const val DEADLINE_TOLERANCE_MS = 15 * 60_000L // 15 minuti
}
