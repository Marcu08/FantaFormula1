package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.OverUnderQuotes
import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.PredictionValidation
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.data.util.AppJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratto client↔server per il contenuto dei pronostici.
 *
 * Replica sul JSON PRODOTTO da AppJson gli stessi controlli che la
 * migrazione `0004_prediction_validation.sql` applica nel trigger
 * `check_prediction_content`. Se questo test passa, ogni pronostico
 * completo inviato dall'app viene accettato dal database; se il contratto
 * si rompe (es. enum rinominata senza aggiornare il SQL), il test lo
 * rileva senza bisogno di un database.
 *
 * Nota: le voci INCOMPLETE non vengono mai inviate dall'app
 * (PredictionViewModel.save filtra con DraftEntry.complete), quindi il
 * contratto richiede, per ogni entry presente, i campi del proprio tipo
 * — stessa semantica del validatore SQL.
 */
class PredictionContentContractTest {

    /** Lista dei tipi attesi dal validatore SQL (0004). */
    private val knownTypes = setOf(
        "WINNER", "PODIUM", "POLE", "FASTEST_LAP",
        "DRIVER_EXACT_POSITION", "DRIVER_POSITION_RANGE", "HEAD_TO_HEAD",
        "DRIVER_DNF", "DRIVER_POINTS_OVER_UNDER", "SPRINT_WINNER",
        "SAFETY_CAR_COUNT_OVER_UNDER", "PIT_STOPS_OVER_UNDER",
    )

    /** Replica di public.prediction_required_drivers (SQL 0004). */
    private fun requiredDrivers(type: String): Int = when (type) {
        "HEAD_TO_HEAD" -> 2
        "PODIUM" -> 3
        "SAFETY_CAR_COUNT_OVER_UNDER", "PIT_STOPS_OVER_UNDER" -> 0
        else -> 1
    }

    /**
     * Replica integrale del validatore SQL (0004 + 0006/0008 + 0009/0010) sul
     * JSON PRODOTTO da AppJson. Restituisce le VIOLAZIONI trovate (in italiano,
     * lato messaggi del trigger); vuota = il database accetterebbe il JSON.
     *
     * Differenze inevitabili rispetto al trigger SQL, documentate:
     * 1. Il trigger valuta `quota` per QUALSIASI tipo se il campo e' presente
     *    (range per tipo); il client non la produce mai su tipi non a quota
     *    (encodeDefaults serializza "quota": null) e qui la replica e'
     *    comunque fedele, cosi' un'eventuale quota "sorpassata" verrebbe
     *    rilevata senza DB.
     * 2. Il trigger lavora su jsonb `numeric` e rifiuta NaN/valori fuori
     *    scala; la serializzazione kotlinx non li genera mai, quindi il gap
     *    non e' testabile JVM (lato SQL e' coperto da supabase/tests).
     */
    internal fun serverContractViolations(prediction: RacePrediction): List<String> {
        val violations = mutableListOf<String>()
        val encoded = AppJson.encodeToJsonElement(RacePrediction.serializer(), prediction)
        val root = encoded.jsonObject

        val entries = root["entries"]?.jsonArray
        if (entries == null) return listOf("entries deve essere un array")
        if (entries.size > 32) violations += "troppi tipi di pronostico"
        val types = entries.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        if (types.size != types.toSet().size) violations += "solo un pronostico per tipo"

        entries.forEach { entry ->
            val obj = entry.jsonObject
            val type = obj["type"]!!.jsonPrimitive.content
            if (type !in knownTypes) return@forEach

            val driverIds = obj["driverIds"]?.jsonArray.orEmpty()
            if (driverIds.size != requiredDrivers(type)) violations += "numero piloti per $type"
            val distinct = driverIds.map { it.jsonPrimitive.content }.distinct()
            if (distinct.size != driverIds.size) violations += "piloti distinti per $type"
            if (driverIds.any { !it.jsonPrimitive.isString || it.jsonPrimitive.content.isBlank() }) {
                violations += "pilota non valido ($type)"
            }

            // -- quota congelata (0009/0010) --------------------------------
            val quota = (obj["quota"] as? JsonPrimitive)?.doubleOrNull
            if (quota != null) {
                val qOk = if (type == "DRIVER_DNF") {
                    quota in OverUnderQuotes.QUOTA_MIN_DNF..OverUnderQuotes.QUOTA_MAX_DNF
                } else {
                    quota in OverUnderQuotes.QUOTA_MIN..OverUnderQuotes.QUOTA_MAX
                }
                if (!quota.isFinite() || !qOk) violations += "quota fuori range ($type)"
            }

            when (type) {
                "DRIVER_EXACT_POSITION" -> {
                    val pos = obj["position"]?.jsonPrimitive?.longOrNull
                    if (pos == null || pos !in 1L..22L) violations += "posizione tra 1 e 22"
                }
                "DRIVER_POSITION_RANGE" -> {
                    val min = obj["rangeMin"]?.jsonPrimitive?.longOrNull
                    val max = obj["rangeMax"]?.jsonPrimitive?.longOrNull
                    if (min == null || !min.let { it in 1..22 }) violations += "rangeMin 1-22"
                    if (max == null || !max.let { it in 1..22 }) violations += "rangeMax 1-22"
                    // Trigger 0009: "min<=max E banda predefinita" in un'unica
                    // condizione: una fascia INVERTITA (es. 22-5) non e' una
                    // banda ammessa tanto quanto una libera.
                    val bandValida = min != null && max != null && min <= max &&
                        PredictionValidation.RANGE_BANDS.any {
                            it.first.toLong() == min && it.second.toLong() == max
                        }
                    if (!bandValida) violations += "fascia predefinita: $min-$max"
                }
                "DRIVER_DNF" -> {
                    if (obj["booleanValue"]?.jsonPrimitive?.booleanOrNull == null) {
                        violations += "booleanValue booleano (DNF)"
                    }
                    // 0009/0010: quota obbligatoria, range dedicato [0.5; 8].
                    if (quota == null) violations += "quota obbligatoria (DNF)"
                }
                "DRIVER_POINTS_OVER_UNDER" -> {
                    val threshold = obj["threshold"]?.jsonPrimitive?.doubleOrNull
                    // 0009: soglia multiplo di 0.5 in [0.5; 25].
                    val sogliaOk = threshold != null && threshold.isFinite() &&
                        threshold in 0.5..25.0 && (threshold * 2) == (threshold * 2).roundToInt().toDouble()
                    if (!sogliaOk) violations += "soglia multiplo di 0.5 tra 0.5 e 25"
                    if (obj["over"]?.jsonPrimitive?.booleanOrNull == null) violations += "over booleano (punti)"
                    if (quota == null) violations += "quota obbligatoria (punti)"
                }
                "HEAD_TO_HEAD" -> {
                    if (quota == null) violations += "quota obbligatoria (testa a testa)"
                }
                "SAFETY_CAR_COUNT_OVER_UNDER" -> {
                    val min = obj["rangeMin"]?.jsonPrimitive?.longOrNull
                    val max = obj["rangeMax"]?.jsonPrimitive?.longOrNull
                    val ok = min != null && max != null &&
                        (min == max && min in 0L..5L || min == 6L && max == 99L)
                    if (!ok) violations += "opzione safety car ammessa"
                }
                "PIT_STOPS_OVER_UNDER" -> {
                    val min = obj["rangeMin"]?.jsonPrimitive?.longOrNull
                    val max = obj["rangeMax"]?.jsonPrimitive?.longOrNull
                    val ok = min != null && max != null &&
                        (min to max) in listOf(
                            0L to 14L, 15L to 25L, 26L to 35L,
                            36L to 45L, 46L to 55L, 56L to 65L, 66L to 99L,
                        )
                    if (!ok) violations += "fascia pit stop ammessa"
                }
            }
        }
        return violations
    }

    /** Replica del validatore SQL: vuota = il database accetta. */
    private fun assertValidForServer(prediction: RacePrediction) {
        val violations = serverContractViolations(prediction)
        assertTrue(
            "il contratto ha violazioni impreviste: $violations",
            violations.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------

    @Test
    fun `ogni tipo di pronostico completo rispetta il contratto del validatore SQL`() {
        PredictionType.entries.forEach { type ->
            assertValidForServer(RacePrediction(raceId = "r1", entries = listOf(validEntryFor(type))))
        }
    }

    /**
     * Fixture valida per ogni tipo, con rifuses complete: i tre tipi a quota
     * (0009) portano la quota con valori realistici al momento del save.
     */
    private fun validEntryFor(type: PredictionType): PredictionEntry = when (type) {
        PredictionType.PODIUM ->
            PredictionEntry(type = type, driverIds = listOf("VER", "NOR", "LEC"))
        PredictionType.HEAD_TO_HEAD ->
            PredictionEntry(type = type, driverIds = listOf("VER", "NOR"), quota = 1.2)
        PredictionType.SAFETY_CAR_COUNT_OVER_UNDER ->
            PredictionEntry(type = type, rangeMin = 2, rangeMax = 2)
        PredictionType.PIT_STOPS_OVER_UNDER ->
            PredictionEntry(type = type, rangeMin = 46, rangeMax = 55)
        PredictionType.DRIVER_EXACT_POSITION ->
            PredictionEntry(type = type, driverIds = listOf("VER"), position = 5)
        PredictionType.DRIVER_POSITION_RANGE ->
            PredictionEntry(type = type, driverIds = listOf("VER"), rangeMin = 6, rangeMax = 10)
        PredictionType.DRIVER_DNF ->
            PredictionEntry(type = type, driverIds = listOf("PER"), booleanValue = true, quota = 2.5)
        PredictionType.DRIVER_POINTS_OVER_UNDER ->
            PredictionEntry(type = type, driverIds = listOf("VER"), threshold = 12.5, over = true, quota = 1.5)
        else -> PredictionEntry(type = type, driverIds = listOf("VER"))
    }

    /**
     * Copertura ESUSTIVA della quota su tutti i tipi (il trigger controlla la
     * quota per QUALSIASI voce se presente): 9.0 violate la banda per ogni
     * tipo; 1.0 e' valida per tutti; l'assenza e' violazione SOLO per i tre
     * tipi a quota.
     */
    @Test
    fun `quota su tutti i tipi di range per tipo e corretta`() {
        PredictionType.entries.forEach { type ->
            // (a) Quota sopra ogni banda: violazione SEMPRE (whichever banda).
            val above = mapValidEntry(type, 9.0)
            val violationsAbove = serverContractViolations(RacePrediction(raceId = "r", entries = listOf(above)))
            assertTrue(
                "quota 9.0 deve violare il range per $type",
                violationsAbove.any { it.contains("quota fuori range") },
            )

            // (b) Quota dentro la banda (generica): nessuna violazione per bounding.
            val ok = mapValidEntry(type, 1.0)
            val violationsOk = serverContractViolations(RacePrediction(raceId = "r", entries = listOf(ok)))
            assertTrue(
                "quota 1.0 non deve violare il bounding per $type: $violationsOk",
                violationsOk.none { it.contains("quota") },
            )

            // (c) Quota ASSente: violazione solo per i tre tipi a quota.
            val senzaQuota = ok.copy(quota = null)
            val violationsNone = serverContractViolations(
                RacePrediction(raceId = "r", entries = listOf(senzaQuota)),
            )
            val richiede = type in setOf(
                PredictionType.DRIVER_DNF,
                PredictionType.DRIVER_POINTS_OVER_UNDER,
                PredictionType.HEAD_TO_HEAD,
            )
            assertEquals(
                "quota obbligatoria per $type",
                richiede,
                violationsNone.any { it.contains("quota obbligatoria") },
            )
        }
    }

    /** Fixture valida del tipo con la quota sostituita. */
    private fun mapValidEntry(type: PredictionType, quota: Double?): PredictionEntry =
        validEntryFor(type).copy(quota = quota)

    @Test
    fun `fascia invertita e' violazione (min maggiore di max)`() {
        // Il trigger 0009 rifiuta (22, 5): non e' una banda predefinita.
        val violations = serverContractViolations(
            RacePrediction(raceId = "r", entries = listOf(
                PredictionEntry(
                    type = PredictionType.DRIVER_POSITION_RANGE,
                    driverIds = listOf("VER"),
                    rangeMin = 22,
                    rangeMax = 5,
                ),
            )),
        )
        assertTrue(violations.any { it.contains("fascia predefinita: 22-5") })
    }

    @Test
    fun `pronostico con piu' tipi rispetta il contratto`() {
        assertValidForServer(
            RacePrediction(
                raceId = "r1",
                entries = listOf(
                    PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER")),
                    PredictionEntry(type = PredictionType.POLE, driverIds = listOf("VER")),
                    PredictionEntry(type = PredictionType.FASTEST_LAP, driverIds = listOf("NOR")),
                ),
            ),
        )
    }

    @Test
    fun `pronostico vuoto (parziale) rispetta il contratto`() {
        // B15: pronostici parziali ammessi; entries vuota e' valida anche per
        // il validatore SQL (array presente, zero voci da controllare).
        assertValidForServer(RacePrediction(raceId = "r1", entries = emptyList()))
    }

    @Test
    fun `i draft completi rispettano il contratto`() {
        // Simula il flusso reale: solo i draft completi vengono inviati e
        // tutti devono passare il validatore SQL.
        assertValidForServer(
            RacePrediction(
                raceId = "r1",
                entries = listOf(
                    PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER")),
                    PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("RUS"), position = 3),
                    PredictionEntry(type = PredictionType.DRIVER_POSITION_RANGE, driverIds = listOf("LEC"), rangeMin = 6, rangeMax = 10),
                ),
            ),
        )
    }

    //---------------------------------------------------------------
    // 0009/0010 — quote congelate: casi NEGATIVI replicati dal trigger
    //---------------------------------------------------------------

    @Test
    fun `quota mancante su dnf punti e h2h e' violazione del contratto`() {
        listOf(
            PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"), booleanValue = true),
            PredictionEntry(type = PredictionType.DRIVER_POINTS_OVER_UNDER, driverIds = listOf("VER"), threshold = 12.5, over = true),
            PredictionEntry(type = PredictionType.HEAD_TO_HEAD, driverIds = listOf("VER", "BOR")),
        ).forEach { entry ->
            val violations = serverContractViolations(RacePrediction(raceId = "r1", entries = listOf(entry)))
            assertTrue("quota obbligatori per $entry", violations.any { it.contains("quota obbligatoria") })
        }
    }

    @Test
    fun `quota fuori range e' violazione del contratto per tipo`() {
        // DNF: banda dedicata [0.5; 8]; 3.1 e' AMMISSIBILE per DNF ma NON per punti/H2H.
        assertTrue(
            serverContractViolations(
                RacePrediction(raceId = "r", entries = listOf(
                    PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"), booleanValue = true, quota = 0.4),
                )),
            ).any { it.contains("quota fuori range") },
        )
        assertTrue(
            serverContractViolations(
                RacePrediction(raceId = "r", entries = listOf(
                    PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"), booleanValue = true, quota = 8.1),
                )),
            ).any { it.contains("quota fuori range") },
        )
        // 3.1_DNF_nic per il DNF, violerà per gli altri tipi.
        assertFalse(
            serverContractViolations(
                RacePrediction(raceId = "r", entries = listOf(
                    PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"), booleanValue = true, quota = 3.1),
                )),
            ).any { it.contains("quota fuori range") },
        )
        for (bad in listOf(3.1, 8.5, 0.4)) {
            assertTrue(
                "quota $bad fuori range per punti",
                serverContractViolations(
                    RacePrediction(raceId = "r", entries = listOf(
                        PredictionEntry(type = PredictionType.DRIVER_POINTS_OVER_UNDER, driverIds = listOf("VER"), threshold = 2.5, over = true, quota = bad),
                    )),
                ).any { it.contains("quota fuori range") },
            )
            assertTrue(
                "quota $bad fuori range per H2H",
                serverContractViolations(
                    RacePrediction(raceId = "r", entries = listOf(
                        PredictionEntry(type = PredictionType.HEAD_TO_HEAD, driverIds = listOf("VER", "BOR"), quota = bad),
                    )),
                ).any { it.contains("quota fuori range") },
            )
        }
    }

    @Test
    fun `soglia punti non valida e' violazione del contratto`() {
        for (bad in listOf(0.0, 0.3, 7.3, 25.5, 26.0)) {
            val violations = serverContractViolations(
                RacePrediction(raceId = "r", entries = listOf(
                    PredictionEntry(
                        type = PredictionType.DRIVER_POINTS_OVER_UNDER,
                        driverIds = listOf("VER"),
                        threshold = bad,
                        over = true,
                        quota = 1.5,
                    ),
                )),
            )
            assertTrue("soglia $bad rifiutata", violations.any { it.contains("soglia multiplo di 0.5 tra 0.5 e 25") })
        }
    }

    @Test
    fun `quota su un tipo senza quote oltre il range generico e' violazione`() {
        // Il trigger controlla la quota su QUALSIASI tipo se presente (ermes
        // client non la genera): la replica deve rimanere fedele anche qui.
        val violations = serverContractViolations(
            RacePrediction(raceId = "r", entries = listOf(
                PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER"), quota = 9.0),
            )),
        )
        assertTrue(violations.any { it.contains("quota fuori range") })
    }

    @Test
    fun `le voci incomplete non vengono inviate (filtro DraftEntry_complete)`() {
        // Voce incompleta in Kotlin: il validatore SQL la rifiuterebbe, ma
        // l'app non la invia mai (DraftEntry.complete la esclude al salvataggio).
        val incomplete = PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"))
        assertFalse(replicaDraftComplete(incomplete))
    }

    /** Replica del filtro DraftEntry.complete per DRIVER_DNF. */
    private fun replicaDraftComplete(entry: PredictionEntry): Boolean =
        entry.driverIds.size == PredictionValidation.requiredDrivers(entry.type) &&
            entry.booleanValue != null
}
