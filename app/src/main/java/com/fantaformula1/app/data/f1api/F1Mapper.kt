package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.core.domain.DriverRaceResult
import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.RaceResultData
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
 * Converte le risposte di Jolpica/Ergast in modelli di dominio dell'app.
 */
object F1Mapper {

    private val isoParser = DateTimeFormatter.ISO_LOCAL_DATE_TIME

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

    /** Estrae i dati per creare una gara nel DB. */
    fun toRaceCreate(race: F1Race): F1RaceCreate {
        val qualifyingStart = parseInstant(race.qualifying)
        // Fallback: se non c'e' qualifica, usiamo l'inizio gara.
        val deadline = qualifyingStart ?: parseInstant(F1Session(race.date, race.time))
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
     * griglie e posizioni uniche. I piloti non presenti nel catalogo vengono
     * scartati e le posizioni degli altri vengono rinormalizzate sull'ordine
     * di arrivo restituito dall'API.
     */
    fun toRaceResultData(
        raceResults: List<F1Result>,
        qualifyingResults: List<F1QualifyingResult>,
        sprintResults: List<F1SprintResult>,
    ): RaceResultData {
        // Griglia: preferita la qualifica (posizioni normalizzate sui piloti
        // conosciuti); se la qualifica manca del tutto si usa la griglia di
        // gara dell'API. Non si mescolano le due fonti per evitare duplicati.
        val gridByDriver = normalizedPositions(
            qualifyingResults.map { F1DriverMapping.toAppId(it.driver.driverId) },
        )
        val gridFromQualifying = qualifyingResults.isNotEmpty()

        val sprintByDriver = normalizedPositions(
            sprintResults.map { F1DriverMapping.toAppId(it.driver.driverId) },
        )

        val fastestLapDriverId = raceResults
            .firstOrNull { it.fastestLap?.rank == "1" }
            ?.driver?.driverId
            ?.let(F1DriverMapping::toAppId)

        val mapped = raceResults.mapNotNull { result ->
            val appId = F1DriverMapping.toAppId(result.driver.driverId) ?: return@mapNotNull null
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

        return RaceResultData(
            raceId = "",
            drivers = classified + notClassified,
            safetyCars = 0,
            redFlags = 0,
            laps = 0,
            weather = null,
            notes = null,
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
