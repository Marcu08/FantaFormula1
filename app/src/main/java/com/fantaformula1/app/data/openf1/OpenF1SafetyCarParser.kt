package com.fantaformula1.app.data.openf1

/**
 * Parser dei messaggi FIA Race Control (OpenF1, categoria "SafetyCar") in
 * conteggi SC (Safety Car fisica) e VSC (Virtual Safety Car).
 * NON e' "contare le righe": i messaggi di FINE/aggiornamento/coda non sono
 * attivazioni. Semantica ricavata sui dati reali 2026 (
 * "VSC DEPLOYED" @ giro 12 -> "VSC ENDING" @ giro 14, tre attivazioni VSC).
 *
 * Regole di conteggio (un'attivazione = uno START + eventuale END):
 * - testo contiene "VSC STARTING" o "VSC DEPLOYED" o "VIRTUAL SAFETY CAR
 *   DEPLOYED" -> VSC start;
 * - "SAFETY CAR DEPLOYED" (senza "VIRTUAL") -> SC start;
 * - "VSC ENDING" / "VIRTUAL SAFETY CAR ENDING" -> VSC end;
 * - "SAFETY CAR ENDING" o "SAFETY CAR IN THIS LAP" -> SC end;
 * - altro messaggio della categoria (es. "CARS REJOINING AFTER VSC")
 *   NE' attivazione NE' fine: avviso + fiducia BASSA, mai conteggiato.
 *
 * Invarianti della state machine:
 * - non conta MAI due volte la stessa attivazione: uno start mentre quel tipo
 *   e' attivo e' un duplicato/aggiornamento (avviso, nessun conteggio);
 * - una transizione reale SC<->VSC mentre l'altro tipo e' aperto E' contata
 *   (l'FIA sostituisce la neutralizzazione), avviso;
 * - end senza attivazione aperta -> avviso, nessun conteggio;
 * - attivazione aperta-che-fine-messaggi -> avviso (gara chiusa in
 *   neutralizzazione) ma IL CONTEGGIO resta valido;
 * - non produce MAI un conteggio da semantica ambigua: caso ambiguo
 *   = warnings + confidence LOW e revisione manuale dell'amministratore.
 *
 * Duplicati: due messaggi identici (stessa data E stesso testo) sono una
 * voce sola; i messaggi si ordinano per data; date illeggibili restano in coda
 * e non bloccano nulla.
 */
object OpenF1SafetyCarParser {

    enum class Kind { SC, VSC }

    /** Evento riconosciuto nel messaggio Race Control. */
    data class ScEvent(
        val kind: Kind,
        val isStart: Boolean,
        val lapNumber: Int?,
        val message: String,
        val at: String?,
    )

    enum class Confidence { HIGH, MEDIUM, LOW }

    /** Esito PROPOSTO all'amministratore: mai salvato in automatico. */
    data class Import(
        val proposedSafetyCars: Int,
        val proposedVirtualSafetyCars: Int,
        val events: List<ScEvent>,
        val warnings: List<String>,
        val confidence: Confidence,
    )

    fun parse(messagesIn: List<OpenF1RaceControlMessage>): Import {
        val warnings = mutableListOf<String>()
        val events = mutableListOf<ScEvent>()

        // Solo la categoria SafetyCar; altri FIA (Flag, Other, SessionStatus)
        // non sono neutralizzazioni. Le date illeggibili non bloccano: in coda
        // con chiave vuota.
        val messages = messagesIn
            .filter { it.category.equals("SafetyCar", ignoreCase = true) }
            .filter { !it.message.isNullOrBlank() }
            .distinctBy { "${it.date}::${it.message}" }
            .sortedBy { it.date.orEmpty().ifBlank { "" } }

        if (messages.isEmpty()) {
            warnings += "Nessun messaggio SafetyCar da OpenF1: nessuna neutralizzazione segnalata"
            return Import(0, 0, emptyList(), warnings, Confidence.HIGH)
        }

        var scCount = 0
        var vscCount = 0
        var scActive = false
        var vscActive = false
        var unknownCount = 0

        messages.forEach { item ->
            val text = item.message.orEmpty().trim().uppercase()
            val lap = item.lapNumber
            val at = item.date

            fun start(kind: Kind) {
                val active = if (kind == Kind.SC) scActive else vscActive
                if (active) {
                    warnings +=
                        "Attivazione ${kind.name} gia' in corso: messaggio duplicato/aggiornamento ignorato \"$text\" " +
                            "(lap $lap)"
                    return
                }
                if (kind == Kind.SC && vscActive || kind == Kind.VSC && scActive) {
                    warnings += "Transizione $kind -- mentre l'altro tipo e' ancora attivo: entrambe le conteggi " +
                        "seguono l'ordine FIA ($text, lap $lap)"
                }
                if (kind == Kind.SC) {
                    scCount++
                    scActive = true
                } else {
                    vscCount++
                    vscActive = true
                }
                events += ScEvent(kind, isStart = true, lapNumber = lap, message = text, at = at)
            }

            fun end(kind: Kind) {
                val active = if (kind == Kind.SC) scActive else vscActive
                if (!active) {
                    warnings += "Fine ${kind.name} senza attivazione corrispondente: \"$text\" (lap $lap)"
                    return
                }
                if (kind == Kind.SC) {
                    scActive = false
                } else {
                    vscActive = false
                }
                events += ScEvent(kind, isStart = false, lapNumber = lap, message = text, at = at)
            }

            when {
                text.contains("VSC STARTING") || text.contains("VSC DEPLOYED") ||
                    text.contains("VIRTUAL SAFETY CAR DEPLOYED") ->
                    start(Kind.VSC)

                text.contains("SAFETY CAR DEPLOYED") ->
                    start(Kind.SC)

                text.contains("VSC ENDING") || text.contains("VIRTUAL SAFETY CAR ENDING") ->
                    end(Kind.VSC)

                text.contains("SAFETY CAR ENDING") || text.contains("SAFETY CAR IN THIS LAP") ->
                    end(Kind.SC)

                else -> {
                    unknownCount++
                    warnings += "Messaggio SafetyCar non riconosciuto (NON conteggiato): \"$text\" (lap $lap)"
                }
            }
        }

        if (scActive) {
            warnings += "SC ancora attiva a fine messaggi: gara chiusa in neutralizzazione?"
        }
        if (vscActive) {
            warnings += "VSC ancora attiva a fine messaggi: gara chiusa in neutralizzazione?"
        }

        val confidence = when {
            unknownCount > 0 -> Confidence.LOW
            warnings.isNotEmpty() -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }

        return Import(scCount, vscCount, events, warnings.distinct(), confidence)
    }
}
