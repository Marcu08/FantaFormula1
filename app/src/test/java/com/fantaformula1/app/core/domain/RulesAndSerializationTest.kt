package com.fantaformula1.app.core.domain

import com.fantaformula1.app.data.util.AppJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test di validazione delle regole e di serializzazione jsonb (round-trip). */
class RulesAndSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- Validazione ----------------------------------------------------------

    @Test
    fun `le regole di default sono valide`() {
        assertEquals(emptyList<String>(), Rules.default().validate())
    }

    @Test
    fun `i preset sono validi`() {
        assertEquals(emptyList<String>(), Rules.presetF1Official().validate())
        assertEquals(emptyList<String>(), Rules.presetArcade().validate())
    }

    @Test
    fun `nome troppo corto produce errore`() {
        val errors = Rules.default().copy(name = "A").validate()
        assertTrue(errors.any { it.contains("nome", ignoreCase = true) })
    }

    @Test
    fun `nessun tipo attivo produce errore`() {
        val errors = Rules.default().copy(enabledTypes = emptySet()).validate()
        assertTrue(errors.any { it.contains("almeno un tipo", ignoreCase = true) })
    }

    @Test
    fun `punti negativi producono errore`() {
        val broken = Rules.default().copy(
            points = Rules.default().points + (PredictionType.WINNER to -5.0),
        )
        assertTrue(broken.validate().any { it.contains("Punti non validi", ignoreCase = true) })
    }

    @Test
    fun `punti oltre il massimo producono errore`() {
        val broken = Rules.default().copy(
            points = Rules.default().points + (PredictionType.WINNER to 999.0),
        )
        assertTrue(broken.validate().isNotEmpty())
    }

    @Test
    fun `moltiplicatore oltre il massimo produce errore`() {
        val broken = Rules.default().copy(sprintPointsMultiplier = 99.0)
        assertTrue(broken.validate().any { it.contains("sprint", ignoreCase = true) })
    }

    @Test
    fun `podio non decrescente produce errore`() {
        val broken = Rules.default().copy(podiumPoints = listOf(10.0, 20.0, 5.0))
        assertTrue(broken.validate().any { it.contains("decrescenti", ignoreCase = true) })
    }

    @Test
    fun `podio con meno di tre valori produce errore`() {
        val broken = Rules.default().copy(podiumPoints = listOf(25.0, 18.0))
        assertTrue(broken.validate().isNotEmpty())
    }

    // -- Serializzazione ------------------------------------------------------

    @Test
    fun `regole round-trip through json preserva i valori`() {
        val original = Rules.presetArcade()
        val encoded = json.encodeToString(Rules.serializer(), original)
        val decoded = json.decodeFromString(Rules.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `json vuoto su lega creata senza regole produce i default`() {
        // Le leghe possono essere create con rules = '{}'::jsonb: il client
        // deve degradare sui default invece di lanciare eccezione.
        val fromEmpty = json.decodeFromString(Rules.serializer(), "{}")
        assertEquals(Rules.default().copy(name = fromEmpty.name), fromEmpty)
        assertEquals(Rules.default().points, fromEmpty.points)
        assertEquals(Rules.defaultEnabledTypes(), fromEmpty.enabledTypes)
        assertEquals(emptyList<String>(), fromEmpty.validate())
    }

    @Test
    fun `nuove leghe hanno DRIVER_EXACT_POSITION disattivato di default`() {
        // Scelta dell'utente: probabilita' di indovinare ~1/22 lo rende poco
        // giocabile. Reste attivabile dall'editor: qui si verifica il default.
        assertFalse(Rules.defaultEnabledTypes().contains(PredictionType.DRIVER_EXACT_POSITION))
        // Re-attivabile e validabile:
        val reactivated = Rules.default().copy(
            enabledTypes = Rules.defaultEnabledTypes() + PredictionType.DRIVER_EXACT_POSITION,
        )
        assertEquals(emptyList<String>(), reactivated.validate())
    }

    @Test
    fun `F6 safety car e pit stop disattivati nei preset nuovi`() {
        // Jolpica non fornisce questi dati: nei preset nuovi restano spenti
        // (completamento manuale N3 o import OpenF1 parziale) e l'admin puo'
        // attivarli dall'editor regole.
        val defaults = Rules.defaultEnabledTypes()
        assertFalse(defaults.contains(PredictionType.SAFETY_CAR_COUNT_OVER_UNDER))
        assertFalse(defaults.contains(PredictionType.PIT_STOPS_OVER_UNDER))
        // Attivabili dall'editor e validabili come tutti gli altri:
        val withOverUnder = Rules.default().copy(
            enabledTypes = defaults +
                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER +
                PredictionType.PIT_STOPS_OVER_UNDER,
        )
        assertEquals(emptyList<String>(), withOverUnder.validate())
        // I default completi restano validi (nessun tipo a 0 punti mancante).
        assertEquals(emptyList<String>(), Rules.default().validate())
    }

    @Test
    fun `json con campi ignoti non rompe il parsing`() {
        val raw = """{"version":1,"name":"Legacy","campoSconosciuto":true}"""
        val decoded = json.decodeFromString(Rules.serializer(), raw)
        assertEquals("Legacy", decoded.name)
    }

    @Test
    fun `json con enum sconosciuti degrada sui default (B22)`() {
        // AppJson usa coerceInputValues: un enum sconosciuto (es. aggiunto in
        // una versione nuova del server) cade sul default invece di far
        // fallire l'intera decodifica della lega.
        val raw = """
            {"version":1,"name":"Legacy","tieBreaker":"SPRINT_OFF",
             "points":{"WINNER":12.0}}
        """.trimIndent()
        val decoded = AppJson.decodeFromString(Rules.serializer(), raw)
        assertEquals("Legacy", decoded.name)
        assertEquals(TieBreaker.RACE_WINS, decoded.tieBreaker)
        assertEquals(12.0, decoded.points[PredictionType.WINNER]!!, 0.001)
    }

    @Test
    fun `dnfMultiplier deprecato resta nel json per compatibilita' (B11)`() {
        val encoded = AppJson.encodeToString(Rules.serializer(), Rules.default())
        assertTrue(encoded.contains("dnfMultiplier"))
        val decoded = AppJson.decodeFromString(Rules.serializer(), encoded)
        assertEquals(Rules.default(), decoded)
    }

    // -- B3: punti effettivi --------------------------------------------------

    @Test
    fun `effectivePoints usa pointsScored se presente (B3)`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "VER", position = 1, pointsScored = 30.0),
            ),
        )
        assertEquals(30.0, result.effectivePoints(result.drivers.first()), 0.001)
    }

    @Test
    fun `effectivePoints senza pointsScored usa la tabella F1 (B3)`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "VER", position = 1),
                DriverRaceResult(driverId = "LEC", position = 2),
                DriverRaceResult(driverId = "NOR", position = 10),
                DriverRaceResult(driverId = "ALO", position = 11),
                DriverRaceResult(driverId = "PER", position = null, dnf = true),
            ),
        )
        assertEquals(25.0, result.effectivePoints(result.drivers[0]), 0.001)
        assertEquals(18.0, result.effectivePoints(result.drivers[1]), 0.001)
        assertEquals(1.0, result.effectivePoints(result.drivers[2]), 0.001)
        assertEquals(0.0, result.effectivePoints(result.drivers[3]), 0.001)
        assertEquals(0.0, result.effectivePoints(result.drivers[4]), 0.001)
    }

    @Test
    fun `risultato gara round-trip through json`() {
        val original = RaceResultData(
            raceId = "r1",
            drivers = listOf(
                DriverRaceResult(
                    driverId = "VER",
                    driverName = "Max Verstappen",
                    grid = 1,
                    position = 1,
                    sprintPosition = 1,
                    fastestLap = true,
                    dnf = false,
                    dnfReason = null,
                    penaltyPoints = 5,
                    pitStops = 2,
                    pointsScored = 26.0,
                ),
                DriverRaceResult(driverId = "PER", dnf = true, dnfReason = "Cambio del motore"),
            ),
            safetyCars = 2,
            redFlags = 1,
            laps = 58,
            weather = "Asciutto",
            notes = "Gara sotto pioggia nel primo stint",
        )
        val encoded = json.encodeToString(RaceResultData.serializer(), original)
        val decoded = json.decodeFromString(RaceResultData.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(2, decoded.confirmedTotalPitStops())
    }

    @Test
    fun `pronostico round-trip through json`() {
        val original = RacePrediction(
            raceId = "r1",
            submittedAtEpochMs = 1_700_000_000_000L,
            entries = listOf(
                PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER")),
                PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("VER", "RUS", "LEC")),
                PredictionEntry(
                    type = PredictionType.DRIVER_POSITION_RANGE,
                    driverIds = listOf("HAM"),
                    rangeMin = 3,
                    rangeMax = 5,
                ),
                PredictionEntry(
                    type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                    threshold = 1.5,
                    over = true,
                ),
                PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"), booleanValue = true),
            ),
        )
        val encoded = json.encodeToString(RacePrediction.serializer(), original)
        val decoded = json.decodeFromString(RacePrediction.serializer(), encoded)
        assertEquals(original, decoded)
    }

    /**
     * 0009/0010: i TRE tipi con quota congelata sopravvivono a encode/decode
     * con TUTTI i campi necessari (quota, soglia, over/under, driver, opzioni).
     * La quota e' congelata: anche frammenti infinitesimiali di precisione
     * (7.692307...) devono tornare identici, il payout dipende da esso.
     */
    @Test
    fun `quota congelata round-trip per dnf punti e h2h`() {
        val original = RacePrediction(
            raceId = "r9",
            submittedAtEpochMs = 1_700_000_000_123L,
            entries = listOf(
                // DNF: banda dedicata [0.5; 8], quota piena non arrotondata.
                PredictionEntry(
                    type = PredictionType.DRIVER_DNF,
                    driverIds = listOf("PER"),
                    booleanValue = true,
                    quota = 5.0 * 2.0 / 3.0, // ~3.3333... precisione piena
                ),
                // Punti: soglia x.5 + lato + quota generica [0.5; 3].
                PredictionEntry(
                    type = PredictionType.DRIVER_POINTS_OVER_UNDER,
                    driverIds = listOf("VER"),
                    threshold = 0.5,
                    over = false,
                    quota = 0.5 * 3.0, // 1.5 esatto
                ),
                // H2H: quota delle fasce reali.
                PredictionEntry(
                    type = PredictionType.HEAD_TO_HEAD,
                    driverIds = listOf("BOR", "VER"),
                    quota = 2.5,
                ),
            ),
        )
        val encoded = json.encodeToString(RacePrediction.serializer(), original)
        val decoded = json.decodeFromString(RacePrediction.serializer(), encoded)
        assertEquals(original, decoded)

        val dnf = decoded.entries[0]
        assertEquals(5.0 * 2.0 / 3.0, dnf.quota!!, 0.0)
        assertEquals(true, dnf.booleanValue)
        val punti = decoded.entries[1]
        assertEquals(0.5, punti.threshold!!, 0.0)
        assertEquals(false, punti.over)
        assertEquals(1.5, punti.quota!!, 0.0)
        val h2h = decoded.entries[2]
        assertEquals(listOf("BOR", "VER"), h2h.driverIds)
        assertEquals(2.5, h2h.quota!!, 0.0)
    }

    /**
     * I JSONB storici NON contengono il campo quota (pre-0009) e L'AP NUOVA
     * produce "quota": null (encodeDefaults + explicitNulls di AppJson):
     * entrambi devono decodificare senza errori con quota = null perche' il
     * motore li punteggia con il percorso vintage (piatto/fasce prior).
     */
    @Test
    fun `vintage senza quota e quota null decodificano come quota assente`() {
        // Formato storico: nessun campo quota nel JSON.
        val vintageJson = """{"raceId":"r1","entries":[
            {"type":"DRIVER_DNF","driverIds":["PER"],"booleanValue":true},
            {"type":"HEAD_TO_HEAD","driverIds":["VER","LEC"]}
        ]}"""
        val decodedVintage = json.decodeFromString(RacePrediction.serializer(), vintageJson)
        assertEquals(null, decodedVintage.entries[0].quota)
        assertEquals(null, decodedVintage.entries[1].quota)

        // Formato attuale con "quota": null esplicito — non doveva rompere.
        val modernNullJson = """{"raceId":"r1","entries":[
            {"type":"DRIVER_DNF","driverIds":["PER"],"booleanValue":false,"quota":null}
        ]}"""
        val decodedNull = json.decodeFromString(RacePrediction.serializer(), modernNullJson)
        assertEquals(null, decodedNull.entries.single().quota)
    }

    // -- Utility dominio ------------------------------------------------------

    @Test
    fun `classificationRank mette i ritirati dopo i classificati`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "A", position = 1, grid = 2),
                DriverRaceResult(driverId = "B", position = null, grid = 1, dnf = true),
                DriverRaceResult(driverId = "C", position = 2, grid = 3),
            ),
        )
        assertEquals(1, result.classificationRank("A"))
        assertEquals(2, result.classificationRank("C"))
        assertEquals(3, result.classificationRank("B"))
    }

    @Test
    fun `fastestLapDriver e poleDriver restituiscono il pilota giusto`() {
        val result = RaceResultData(
            drivers = listOf(
                DriverRaceResult(driverId = "A", grid = 2, fastestLap = true),
                DriverRaceResult(driverId = "B", grid = 1),
            ),
        )
        assertEquals("A", result.fastestLapDriver()?.driverId)
        assertEquals("B", result.poleDriver()?.driverId)
    }

    @Test
    fun `enableTypes e points coerenti nel preset arcade`() {
        val arcade = Rules.presetArcade()
        arcade.enabledTypes.forEach {
            assertTrue("manca il valore per $it", arcade.points.containsKey(it))
        }
        assertFalse(arcade.points.keys.any { it !in arcade.enabledTypes })
    }

    // -- U9: punteggio massimo per gara --------------------------------------

    @Test
    fun `U9 maxRacePoints del preset di default e' 132`() {
        // 25 (winner) + 58 (podio 25+18+15) + 10 (pole) + 8 (fastest lap) +
        // 6 (fascia) + 8 (h2h) + 5 (dnf) + 7 (punti) + 5 (sprint 10 x 0.5);
        // esclusi i tre tipi spenti nei preset nuovi (B/F6).
        assertEquals(132.0, Rules.default().maxRacePoints(), 0.001)
    }

    @Test
    fun `U9 maxRacePoints con tutti i tipi attivi e' 154`() {
        // 132 + posizione esatta 12 + safety car 5 + pit stop 5.
        val allTypes = Rules.default().copy(enabledTypes = PredictionType.entries.toSet())
        assertEquals(154.0, allTypes.maxRacePoints(), 0.001)
    }

    @Test
    fun `U9 maxRacePoints somma il bonus podio senza ordine`() {
        val withBonus = Rules.default().copy(podiumNoOrderPoints = 10.0)
        assertEquals(142.0, withBonus.maxRacePoints(), 0.001)
    }

    @Test
    fun `U9 maxRacePoints applica il moltiplicatore sprint`() {
        // SprintMultiplier 1.0: lo sprint paga 10 invece di 5 -> 137.
        val fullSprint = Rules.default().copy(sprintPointsMultiplier = 1.0)
        assertEquals(137.0, fullSprint.maxRacePoints(), 0.001)
        // Preset arcade: 10 (winner) + 24 (podio 10+8+6) + 5 (sprint x1) +
        // 4 (h2h) + 4 (pole) = 47.
        assertEquals(47.0, Rules.presetArcade().maxRacePoints(), 0.001)
    }
}
