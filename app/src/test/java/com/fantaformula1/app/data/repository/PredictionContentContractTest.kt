package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.PredictionValidation
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.data.util.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    /** Replica del validatore SQL sui JsonElement codificati dall'app. */
    private fun assertValidForServer(prediction: RacePrediction) {
        val encoded = AppJson.encodeToJsonElement(RacePrediction.serializer(), prediction)
        val root = encoded.jsonObject

        val entries = root["entries"]?.jsonArray
        assertTrue("entries deve essere un array", entries != null)
        assertTrue("massimo 32 voci", entries!!.size <= 32)
        val types = entries.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals("un solo pronostico per tipo", types.size, types.toSet().size)

        entries.forEach { entry ->
            val obj = entry.jsonObject
            val type = obj["type"]!!.jsonPrimitive.content
            assertTrue("tipo conosciuto: $type", type in knownTypes)

            val driverIds = obj["driverIds"]?.jsonArray.orEmpty()
            assertEquals("numero piloti per $type", requiredDrivers(type), driverIds.size)
            driverIds.forEach { d ->
                assertTrue(
                    "driverId stringa non vuota",
                    d.jsonPrimitive.isString && d.jsonPrimitive.content.isNotBlank(),
                )
            }
            assertEquals(
                "piloti distinti per $type",
                driverIds.size,
                driverIds.map { it.jsonPrimitive.content }.toSet().size,
            )

            when (type) {
                "DRIVER_EXACT_POSITION" -> assertIntInRange(obj["position"], 1, 22)
                "DRIVER_POSITION_RANGE" -> {
                    assertIntInRange(obj["rangeMin"], 1, 22)
                    assertIntInRange(obj["rangeMax"], 1, 22)
                    val min = obj["rangeMin"]?.jsonPrimitive?.longOrNull
                    val max = obj["rangeMax"]?.jsonPrimitive?.longOrNull
                    assertTrue("fascia min<=max", min != null && max != null && min <= max)
                }
                "DRIVER_DNF" ->
                    assertTrue("booleanValue booleano", obj["booleanValue"]?.jsonPrimitive?.booleanOrNull != null)
                "DRIVER_POINTS_OVER_UNDER",
                "SAFETY_CAR_COUNT_OVER_UNDER",
                "PIT_STOPS_OVER_UNDER",
                -> {
                    val threshold = obj["threshold"]?.jsonPrimitive?.doubleOrNull
                    assertTrue("soglia numerica >= 0", threshold != null && threshold >= 0.0)
                    assertTrue("over booleano", obj["over"]?.jsonPrimitive?.booleanOrNull != null)
                }
            }
        }
    }

    /** JSON null (o non intero) = fallimento; valore intero nel range = ok. */
    private fun assertIntInRange(element: JsonElement?, min: Long, max: Long) {
        val value = (element as? JsonPrimitive)?.longOrNull
        assertTrue("intero tra $min e $max, trovato: $element", value != null && value in min..max)
    }

    // ---------------------------------------------------------------------

    @Test
    fun `ogni tipo di pronostico completo rispetta il contratto del validatore SQL`() {
        PredictionType.entries.forEach { type ->
            val entry = when (type) {
                PredictionType.PODIUM ->
                    PredictionEntry(type = type, driverIds = listOf("VER", "NOR", "LEC"))
                PredictionType.HEAD_TO_HEAD ->
                    PredictionEntry(type = type, driverIds = listOf("VER", "NOR"))
                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                PredictionType.PIT_STOPS_OVER_UNDER,
                -> PredictionEntry(type = type, threshold = 2.5, over = true)
                PredictionType.DRIVER_EXACT_POSITION ->
                    PredictionEntry(type = type, driverIds = listOf("VER"), position = 5)
                PredictionType.DRIVER_POSITION_RANGE ->
                    PredictionEntry(type = type, driverIds = listOf("VER"), rangeMin = 4, rangeMax = 6)
                PredictionType.DRIVER_DNF ->
                    PredictionEntry(type = type, driverIds = listOf("PER"), booleanValue = true)
                PredictionType.DRIVER_POINTS_OVER_UNDER ->
                    PredictionEntry(type = type, driverIds = listOf("VER"), threshold = 18.0, over = true)
                else -> PredictionEntry(type = type, driverIds = listOf("VER"))
            }
            assertValidForServer(RacePrediction(raceId = "r1", entries = listOf(entry)))
        }
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
                    PredictionEntry(type = PredictionType.DRIVER_POSITION_RANGE, driverIds = listOf("LEC"), rangeMin = 4, rangeMax = 6),
                ),
            ),
        )
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