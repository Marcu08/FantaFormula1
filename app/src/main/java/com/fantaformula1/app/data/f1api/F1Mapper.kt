package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.core.domain.DriverRaceResult
import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.data.f1api.model.F1PitStop
import com.fantaformula1.app.data.f1api.model.F1QualifyingResult
import com.fantaformula1.app.data.f1api.model.F1Race
import com.fantaformula1.app.data.f1api.model.F1Result
import com.fantaformula1.app.data.f1api.model.F1Session
import com.fantaformula1.app.data.f1api.model.F1SprintResult
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Dati necessari a creare una gara nel calendario dell'app. */
data class F1RaceCreate(
    val round: Int,
    val name: String,
    val country: String?,
    val isSprint: Boolean,
    val deadline: Instant?,
)

/**
 * Esito della conversione dei risultati API: [Success] con l'esito pronto,
 * oppure [UnknownDriver] quando l'API riporta un pilota fuori catalogo.
 * In quest'ultimo caso il risultato NON va salvato: le posizioni dietro al
 * pilota sconosciuto sarebbero spostate dalla rinormalizzazione (N2).
 */
sealed interface RaceMapping {
    data class Success(val data: RaceResultData) : RaceMapping
    data class UnknownDriver(val driverId: String) : RaceMapping
}

/**
 * Converte le risposte di Jolpica/Ergast in modelli di dominio dell'app.
 */
object F1Mapper {

    private val isoParser = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** Massimo pit stop per pilota accettato dai dati API (difesa da dati malformati). */
    private const val MAX_PIT_STOPS = 8

    /** True se il round ha una sessione sprint nel calendario. */
    fun hasSprint(race: F1Race): Boolean =
        race.sprint != null || race.sprintQualifying != null

    /**
     * True se la gara e' gia' stata disputata (data di gara precedente a
     * [today]). Le gare del giorno corrente non sono "fatte": si possono
     * ancora pronosticare fino alla deadline. Data non riconosciuta = non
     * passata (si crea comunque la gara, comportamento conservativo).
     */
    fun isPast(
        race: F1Race,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Boolean {
        val date = runCatching { LocalDate.parse(race.date) }.getOrNull() ?: return false
        return date.isBefore(today)
    }

    /**
     * Estrae i dati per creare una gara nel DB.
     *
     * La deadline e' il primo evento utile del weekend (N4): nei weekend
     * sprint lo sprint si corre il sabato PRIMA della qualifica, quindi
     * pronosticare dopo la qualifica significherebbe conoscere gia' l'esito
     * dello sprint. Ordine: SprintQualifying -> Sprint -> Qualifying -> gara.
     */
    fun toRaceCreate(race: F1Race): F1RaceCreate {
        val deadline = parseInstant(race.sprintQualifying)
            ?: parseInstant(race.sprint)
            ?: parseInstant(race.qualifying)
            ?: parseInstant(F1Session(race.date, race.time))
        return F1RaceCreate(
            round = race.round.toIntOrNull() ?: 0,
            name = race.raceName,
            country = race.circuit?.location?.country,
            isSprint = hasSprint(race),
            deadline = deadline,
        )
    }

    /**
     * Costruisce un RaceResultData dai risultati API.
     *
     * Il formato deve rispettare le invarianti del motore di punteggio
     * (decisione B12): posizioni classificate contigue da 1 senza buchi,
     * griglie e posizioni uniche.
     *
     * Se l'API riporta un pilota non mappabile (riserva, sostituzione
     * straordinaria, catalogo non aggiornato) la conversione fallisce con
     * [RaceMapping.UnknownDriver]: salvare comunque sposterebbe le posizioni
     * di tutti i piloti dietro al sconosciuto (N2). La gara resta in attesa
     * e si riprovera' alla sync successiva.
     */
    fun toRaceResultData(
        raceResults: List<F1Result>,
        qualifyingResults: List<F1QualifyingResult>,
        sprintResults: List<F1SprintResult>,
    ): RaceMapping {
        // Un solo pilota sconosciuto blocca l'intero risultato: la griglia,
        // le posizioni e lo sprint devono riferirsi alla stessa griglia.
        val unknown = (raceResults.map { it.driver } +
            qualifyingResults.map { it.driver } +
            sprintResults.map { it.driver })
            .firstOrNull { F1DriverMapping.toAppId(it) == null }
        if (unknown != null) {
            return RaceMapping.UnknownDriver(unknown.driverId)
        }

        // Griglia: preferita la qualifica (posizioni normalizzate sui piloti
        // conosciuti); se la qualifica manca del tutto si usa la griglia di
        // gara dell'API. Non si mescolano le due fonti per evitare duplicati.
        val gridByDriver = normalizedPositions(
            qualifyingResults.map { F1DriverMapping.toAppId(it.driver) },
        )
        val gridFromQualifying = qualifyingResults.isNotEmpty()

        val sprintByDriver = normalizedPositions(
            sprintResults.map { F1DriverMapping.toAppId(it.driver) },
        )

        val fastestLapDriverId = raceResults
            .firstOrNull { it.fastestLap?.rank == "1" }
            ?.driver
            ?.let(F1DriverMapping::toAppId)

        val mapped = raceResults.mapNotNull { result ->
            val appId = F1DriverMapping.toAppId(result.driver) ?: return@mapNotNull null
            val status = result.status?.lowercase().orEmpty()
            val classified = isClassified(result.positionText, status)
            MappedDriver(
                driverId = appId,
                driverName = driverName(result.driver.givenName, result.driver.familyName, appId),
                ergastPosition = result.position.toIntOrNull(),
                classified = classified,
                dnfReason = if (classified) null else result.status,
                grid = if (gridFromQualifying) {
                    gridByDriver[appId]
                } else {
                    // "0" in Ergast significa partenza dai box: fuori range.
                    result.grid?.toIntOrNull()?.takeIf { it >= 1 }
                },
                sprintPosition = sprintByDriver[appId],
                fastestLap = appId == fastestLapDriverId,
                pointsScored = result.points?.toDoubleOrNull(),
            )
        }

        // I risultati Ergast arrivano gia' ordinati; per sicurezza si ordina
        // sulla posizione API prima di rinormalizzare 1..n sui classificati.
        val classified = mapped
            .filter { it.classified }
            .sortedBy { it.ergastPosition ?: Int.MAX_VALUE }
            .mapIndexed { index, driver -> driver.toDriverRaceResult(position = index + 1) }
        val notClassified = mapped
            .filter { !it.classified }
            .map { it.toDriverRaceResult(position = null) }

        return RaceMapping.Success(
            RaceResultData(
                raceId = "",
                drivers = classified + notClassified,
                // Safety car: Jolpica non la fornisce. Null = "da confermare"
                // (N3): mai inventare uno zero che falserebbe gli over/under.
                safetyCars = null,
                redFlags = 0,
                laps = 0,
                weather = null,
                notes = null,
            ),
        )
    }

    /** Dato intermedio prima della rinormalizzazione delle posizioni. */
    private data class MappedDriver(
        val driverId: String,
        val driverName: String,
        /** Posizione numerica riportata dall'API (l'ordine di arrivo). */
        val ergastPosition: Int?,
        val classified: Boolean,
        val dnfReason: String?,
        val grid: Int?,
        val sprintPosition: Int?,
        val fastestLap: Boolean,
        val pointsScored: Double?,
    ) {
        fun toDriverRaceResult(position: Int?): DriverRaceResult = DriverRaceResult(
            driverId = driverId,
            driverName = driverName,
            grid = grid,
            position = position,
            sprintPosition = sprintPosition,
            fastestLap = fastestLap,
            dnf = !classified,
            dnfReason = dnfReason,
            penaltyPoints = 0,
            pitStops = 0,
            pointsScored = pointsScored,
        )
    }

    /**
     * Assegna posizioni 1..n contigue nell'ordine della lista (che segue
     * l'ordine dell'API), scartando i piloti non mappabili e i duplicati.
     */
    private fun normalizedPositions(driverIds: List<String?>): Map<String, Int> =
        driverIds.filterNotNull()
            .distinct()
            .mapIndexed { index, appId -> appId to (index + 1) }
            .toMap()

    /**
     * Conteggio pit stop per pilota (app ID) dai pit stops Ergast: una riga
     * per ogni fermata, quindi il conteggio e' il numero di righe per driver.
     * I piloti non presenti nel catalogo vengono scartati; per sicurezza un
     * pilota non supera un massimo ragionevole di fermate (protegge da dati
     * malformati senza inventare regole).
     */
    fun pitStopsByDriver(
        pitStops: List<F1PitStop>,
        maxPerDriver: Int = MAX_PIT_STOPS,
    ): Map<String, Int> =
        pitStops
            .mapNotNull { stop -> F1DriverMapping.toAppId(stop.driverId) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it in 1..maxPerDriver }

    /**
     * Giri totali della gara: il massimo dei giri completati dai piloti
     * (Ergast fornisce `laps` per ogni risultato). Null se nessun dato.
     */
    fun raceLaps(raceResults: List<F1Result>): Int? =
        raceResults.mapNotNull { it.laps?.toIntOrNull() }.maxOrNull()

    /**
     * Un pilota e' classificato se ha finito o ha giri di ritardo.
     *
     * Il segnale piu' affidabile e' `positionText` numerico: in Ergast i
     * ritirati hanno "R", i squalificati "D", ecc. Lo stato testuale resta
     * come fallback per i dati storici ("Finished", "+1 Lap").
     */
    private fun isClassified(positionText: String?, status: String): Boolean {
        if (positionText?.toIntOrNull() != null) return true
        if (status == "finished") return true
        if (status.startsWith("+") && status.any { it.isDigit() }) return true
        return false
    }

    private fun driverName(given: String?, family: String?, appId: String): String {
        val full = listOfNotNull(given, family).joinToString(" ").trim()
        if (full.isNotBlank()) return full
        return DriversCatalog.byId(appId)?.name ?: appId
    }

    /** Parsing di `2026-03-15T05:00:00Z` in Instant. */
    private fun parseInstant(session: F1Session?): Instant? {
        val date = session?.date ?: return null
        val time = session.time ?: "00:00:00Z"
        return runCatching {
            val text = "${date}T${time}".replace("Z", "")
            LocalDateTime.parse(text, isoParser).toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }
}
