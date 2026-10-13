package com.fantaformula1.app.data.f1api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Risposta radice di Jolpica/Ergast: contiene sempre MRData -> RaceTable. */
@Serializable
data class F1Response(
    @SerialName("MRData") val mrData: F1MRData,
)

@Serializable
data class F1MRData(
    @SerialName("RaceTable") val raceTable: F1RaceTable,
)

@Serializable
data class F1RaceTable(
    val season: String? = null,
    val round: String? = null,
    @SerialName("Races") val races: List<F1Race> = emptyList(),
)

@Serializable
data class F1Race(
    val season: String,
    val round: String,
    @SerialName("raceName") val raceName: String,
    @SerialName("Circuit") val circuit: F1Circuit? = null,
    val date: String? = null,
    val time: String? = null,
    @SerialName("Qualifying") val qualifying: F1Session? = null,
    @SerialName("Sprint") val sprint: F1Session? = null,
    @SerialName("SprintQualifying") val sprintQualifying: F1Session? = null,
    @SerialName("FirstPractice") val firstPractice: F1Session? = null,
    @SerialName("SecondPractice") val secondPractice: F1Session? = null,
    @SerialName("ThirdPractice") val thirdPractice: F1Session? = null,
    @SerialName("Results") val results: List<F1Result>? = null,
    @SerialName("QualifyingResults") val qualifyingResults: List<F1QualifyingResult>? = null,
    @SerialName("SprintResults") val sprintResults: List<F1SprintResult>? = null,
    @SerialName("PitStops") val pitStops: List<F1PitStop>? = null,
)

@Serializable
data class F1PitStop(
    @SerialName("driverId") val driverId: String,
    @SerialName("stop") val stop: String? = null,
    @SerialName("lap") val lap: String? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("time") val time: String? = null,
)

@Serializable
data class F1Circuit(
    @SerialName("circuitId") val circuitId: String,
    @SerialName("circuitName") val circuitName: String,
    @SerialName("Location") val location: F1Location? = null,
)

@Serializable
data class F1Location(
    val lat: String? = null,
    @SerialName("long") val long: String? = null,
    val locality: String? = null,
    val country: String? = null,
)

@Serializable
data class F1Session(
    val date: String? = null,
    val time: String? = null,
)

@Serializable
data class F1Result(
    val position: String,
    val positionText: String? = null,
    val points: String? = null,
    val grid: String? = null,
    val status: String? = null,
    /** Giri completati dal pilota (Ergast): il massimo e' il totale di gara. */
    @SerialName("laps") val laps: String? = null,
    @SerialName("Driver") val driver: F1Driver,
    @SerialName("Constructor") val constructor: F1Constructor? = null,
    @SerialName("Time") val time: F1Time? = null,
    @SerialName("FastestLap") val fastestLap: F1FastestLap? = null,
)

@Serializable
data class F1QualifyingResult(
    val position: String,
    @SerialName("Driver") val driver: F1Driver,
)

@Serializable
data class F1SprintResult(
    val position: String,
    @SerialName("Driver") val driver: F1Driver,
)

@Serializable
data class F1Driver(
    @SerialName("driverId") val driverId: String,
    @SerialName("givenName") val givenName: String? = null,
    @SerialName("familyName") val familyName: String? = null,
    @SerialName("code") val code: String? = null,
)

@Serializable
data class F1Constructor(
    @SerialName("constructorId") val constructorId: String,
    @SerialName("name") val name: String,
)

@Serializable
data class F1Time(
    val millis: String? = null,
    val time: String? = null,
)

@Serializable
data class F1FastestLap(
    val rank: String? = null,
    @SerialName("Time") val time: F1Time? = null,
)

/**
 * Risposta dell'endpoint `f1/{season}/{round}/pitstops.json`: la struttura
 * MRData contiene PitStopTable (non RaceTable come gli altri endpoint).
 */
@Serializable
data class F1PitStopResponse(
    @SerialName("MRData") val mrData: F1PitStopMRData,
)

@Serializable
data class F1PitStopMRData(
    @SerialName("PitStopTable") val pitStopTable: F1PitStopTable,
)

@Serializable
data class F1PitStopTable(
    val season: String? = null,
    val round: String? = null,
    @SerialName("PitStops") val pitStops: List<F1PitStop> = emptyList(),
)
