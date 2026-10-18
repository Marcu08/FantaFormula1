package com.fantaformula1.app.data.openf1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del parser SC/VSC con fixture ispirate alla semantica reale OpenF1
 * (categoria "SafetyCar", messaggi tipo "VSC DEPLOYED"/"VSC ENDING"/
 * "SAFETY CAR DEPLOYED"/"SAFETY CAR ENDING"/"SAFETY CAR IN THIS LAP").
 */
class OpenF1SafetyCarParserTest {

    private fun msg(
        message: String,
        category: String = "SafetyCar",
        lap: Int? = 1,
        date: String? = "2026-03-08T14:00:00Z",
    ): OpenF1RaceControlMessage =
        OpenF1RaceControlMessage(category = category, message = message, date = date, lapNumber = lap)

    private fun incrementSecond(raw: String, seconds: Int): String =
        java.time.OffsetDateTime.parse(raw).plusSeconds(seconds.toLong()).toString()

    private fun sequentialDates(count: Int, start: String = "2026-03-08T14:00:00Z"): List<String> =
        buildList { repeat(count) { add(java.time.OffsetDateTime.parse(start).plusSeconds(60L * it).toString()) } }

    // 1. Nessuna neutralizzazione ------------------------------------------------

    @Test
    fun `nessun messaggio SafetyCar propone zero e zero`() {
        val import = OpenF1SafetyCarParser.parse(listOf(msg("TRACK CLEAR", category = "Flag")))
        assertEquals(0, import.proposedSafetyCars)
        assertEquals(0, import.proposedVirtualSafetyCars)
        assertTrue(import.warnings.any { it.contains("Nessun messaggio") })
        assertEquals(OpenF1SafetyCarParser.Confidence.HIGH, import.confidence)
    }

    // 2. Una SC + vsc ----------------------------------------------------------------

    @Test
    fun `una sc completa conta una sc`() {
        val dates = sequentialDates(2)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("SAFETY CAR DEPLOYED", date = dates[0], lap = 10),
                msg("SAFETY CAR ENDING", date = dates[1], lap = 14),
            ),
        )
        assertEquals(1, import.proposedSafetyCars)
        assertEquals(0, import.proposedVirtualSafetyCars)
        assertEquals(2, import.events.size)
        assertEquals(OpenF1SafetyCarParser.Confidence.HIGH, import.confidence)
    }

    // 3. Più SC ----------------------------------------------------------------------

    @Test
    fun `piu' sc complete contano il numero delle attivazioni`() {
        val dates = sequentialDates(4)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("SAFETY CAR DEPLOYED", date = dates[0], lap = 10),
                msg("SAFETY CAR ENDING", date = dates[1], lap = 14),
                msg("SAFETY CAR DEPLOYED", date = dates[2], lap = 20),
                msg("SAFETY CAR IN THIS LAP", date = dates[3], lap = 25),
            ),
        )
        assertEquals(2, import.proposedSafetyCars)
        assertEquals(OpenF1SafetyCarParser.Confidence.HIGH, import.confidence)
    }

    // 4. Una VSC ---------------------------------------------------------------------

    @Test
    fun `una vsc completa conta una vsc`() {
        val dates = sequentialDates(2)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC DEPLOYED", date = dates[0], lap = 12),
                msg("VSC ENDING", date = dates[1], lap = 14),
            ),
        )
        assertEquals(0, import.proposedSafetyCars)
        assertEquals(1, import.proposedVirtualSafetyCars)
        assertEquals(OpenF1SafetyCarParser.Confidence.HIGH, import.confidence)
    }

    // 5. Più VSC ---------------------------------------------------------------------

    @Test
    fun `tre vsc come in australia 2026 contano tre`() {
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC DEPLOYED", date = "2026-03-08T05:19:08+02:00", lap = 12),
                msg("VSC ENDING", date = "2026-03-08T05:23:07+02:00", lap = 14),
                msg("VSC DEPLOYED", date = "2026-03-08T05:29:55+02:00", lap = 18),
                msg("VSC ENDING", date = "2026-03-08T05:32:26+02:00", lap = 20),
                msg("VSC DEPLOYED", date = "2026-03-08T05:52:20+02:00", lap = 34),
                msg("VSC ENDING", date = "2026-03-08T05:52:58+02:00", lap = 34),
            ),
        )
        assertEquals(3, import.proposedVirtualSafetyCars)
        assertEquals(0, import.proposedSafetyCars)
        assertEquals(6, import.events.size)
        assertTrue(import.warnings.isEmpty())
    }

    // 6. SC e VSC nella stessa gara ---------------------------------------------------------------------

    @Test
    fun `sc e vsc nella stessa gara si contano separatamente`() {
        val dates = sequentialDates(4)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC DEPLOYED", date = dates[0], lap = 10),
                msg("VSC ENDING", date = dates[1], lap = 12),
                msg("SAFETY CAR DEPLOYED", date = dates[2], lap = 20),
                msg("SAFETY CAR ENDING", date = dates[3], lap = 25),
            ),
        )
        assertEquals(1, import.proposedSafetyCars)
        assertEquals(1, import.proposedVirtualSafetyCars)
        assertEquals(OpenF1SafetyCarParser.Confidence.HIGH, import.confidence)
    }

    // 7. Fine neutralizzazione ---------------------------------------------------------------------

    @Test
    fun `gara chiusa in neutralizzazione conta comunque l attivazione`() {
        // Solo il DEPLOY, nessun ENDING: gara chiusa in neutralizzazione:
        // normale ma va avvertito: 1 attivazione + warning, non blocca il conteggio.
        val import = OpenF1SafetyCarParser.parse(listOf(msg("VSC DEPLOYED", lap = 55)))
        assertEquals(1, import.proposedVirtualSafetyCars)
        assertTrue(import.warnings.any { it.contains("ancora attiva") })
        assertEquals(OpenF1SafetyCarParser.Confidence.MEDIUM, import.confidence)
    }

    // 8. Messaggi duplicati goodwill aggiornati ---------------------------------------------------------------------

    @Test
    fun `un deploy durante la stessa attivazione e' duplicato e non si conta due volte`() {
        val dates = sequentialDates(3)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC DEPLOYED", date = dates[0], lap = 12),
                msg("VSC DEPLOYED", date = dates[1], lap = 13), // quanto stesso attivo: aggiornamento
                msg("VSC ENDING", date = dates[2], lap = 14),
            ),
        )
        assertEquals(1, import.proposedVirtualSafetyCars)
        assertTrue(import.warnings.any { it.contains("duplicato/aggiornamento") })
        assertEquals(OpenF1SafetyCarParser.Confidence.MEDIUM, import.confidence)
    }

    @Test
    fun `messaggio ANSI della stessa IDENTICA rigaduplicado via api conta una volta`() {
        val date = "2026-03-08T05:19:08+02:00"
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC DEPLOYED", date = date, lap = 12),
                msg("VSC DEPLOYED", date = date, lap = 12), // duplicato identico
                msg("VSC ENDING"),
            ),
        )
        assertEquals(1, import.proposedVirtualSafetyCars)
        assertEquals(2, import.events.size)
    }

    @Test
    fun `end senza attivazione non conteggia e avverte`() {
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC ENDING", lap = 22),
            ),
        )
        assertEquals(0, import.proposedVirtualSafetyCars)
        assertTrue(import.warnings.any { it.contains("senza attivazione") })
        assertEquals(OpenF1SafetyCarParser.Confidence.MEDIUM, import.confidence)
    }

    // 9. Sequenze ambigue ---------------------------------------------------------------------

    @Test
    fun `messaggio safetycar non riconosciuto di fiducia bassa e mai conteggiato`() {
        val dates = sequentialDates(2)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("CARS REJOINING AFTER VSC", date = dates[0], lap = 30),
                msg("VSC DEPLOYED", date = dates[1], lap = 34),
            ),
        )
        assertEquals(1, import.proposedVirtualSafetyCars)
        assertTrue(import.warnings.any { it.contains("non riconosciuto") })
        assertEquals(OpenF1SafetyCarParser.Confidence.LOW, import.confidence)
    }

    @Test
    fun `validamatch tutto nel formato della categoria diversa influesce il conteggio`() {
        // Category "Other": red flag e medical car NON sono neutralizzazioni.
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("RED FLAG - SEMI-QUICK RESTART", category = "Other"),
                msg("MEDICAL CAR DEPLOYED", category = "Other"),
            ),
        )
        assertEquals(0, import.proposedSafetyCars)
        assertEquals(0, import.proposedVirtualSafetyCars)
    }

    // 10-12. Dati degradati ---------------------------------------------------------------------

    @Test
    fun `messaggi vuoti o senza testo non rompono nulla`() {
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                OpenF1RaceControlMessage(category = "SafetyCar", message = null, lapNumber = 8),
                OpenF1RaceControlMessage(category = "SafetyCar"), // nullabile messa vuota
                msg("VSC DEPLOYED"),
                msg("VSC ENDING"),
            ),
        )
        assertEquals(1, import.proposedVirtualSafetyCars)
    }

    @Test
    fun `date illeggibili ho messaggi restano conteggi per ordine di input`() {
        val dates = sequentialDates(2)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC DEPLOYED", date = null, lap = 10),
                msg("VSC ENDING", date = dates[1], lap = 14),
            ),
        )
        assertEquals(1, import.proposedVirtualSafetyCars)
    }

    @Test
    fun `transizione sc mentre vsc attiva conta entrambe le neutralizzazioni`() {
        // L'FIA sostituisce una VSC con una SC: due attivazioni distintas.
        val dates = sequentialDates(4)
        val import = OpenF1SafetyCarParser.parse(
            listOf(
                msg("VSC DEPLOYED", date = dates[0], lap = 10),
                msg("SAFETY CAR DEPLOYED", date = dates[1], lap = 15),
                msg("SAFETY CAR ENDING", date = dates[2], lap = 20),
                msg("VSC ENDING", date = dates[3], lap = 21),
            ),
        )
        assertEquals(1, import.proposedVirtualSafetyCars)
        assertEquals(1, import.proposedSafetyCars)
        assertTrue(import.warnings.isNotEmpty())
        assertEquals(OpenF1SafetyCarParser.Confidence.MEDIUM, import.confidence)
    }
}
