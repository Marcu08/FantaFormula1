package com.fantaformula1.app.data.f1api

import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.data.f1api.model.F1PitStopResponse
import com.fantaformula1.app.data.f1api.model.F1Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deserializzazione di payload realistici Jolpica/Ergast con la stessa
 * configurazione Json usata dal client (F1Json): la struttura MRData ->
 * RaceTable -> Races e i campi extra (number, laps, url, ...) devono essere
 * tollerati.
 */
class F1JsonTest {

    @Test
    fun `calendario ergast viene deserializzato e mappato`() {
        val payload = """
            {"MRData":{"xmlns":"http://ergast.com/mrd/1.5","series":"f1",
            "url":"http://ergast.com/api/f1/2026.json","limit":"30","offset":"0","total":"2",
            "RaceTable":{"season":"2026","round":"1","Races":[
              {"season":"2026","round":"1","raceName":"Bahrain Grand Prix",
               "Circuit":{"circuitId":"bahrain","circuitName":"Bahrain International Circuit",
                 "Location":{"lat":"26.0325","long":"50.5106","locality":"Sakhir","country":"Bahrain"}},
               "date":"2026-03-08","time":"15:00:00Z",
               "FirstPractice":{"date":"2026-03-06","time":"11:30:00Z"},
               "Qualifying":{"date":"2026-03-07","time":"16:00:00Z"}},
              {"season":"2026","round":"2","raceName":"Saudi Arabian Grand Prix",
               "Circuit":{"circuitId":"jeddah","circuitName":"Jeddah Corniche Circuit",
                 "Location":{"lat":"21.6319","long":"39.1636","locality":"Jeddah","country":"Saudi Arabia"}},
               "date":"2026-03-15","time":"17:00:00Z",
               "Qualifying":{"date":"2026-03-14","time":"17:00:00Z"},
               "Sprint":{"date":"2026-03-14","time":"14:00:00Z"},
               "SprintQualifying":{"date":"2026-03-13","time":"16:30:00Z"}}
            ]}}}
        """.trimIndent()

        val response = F1Json.decodeFromString(F1Response.serializer(), payload)
        val races = response.mrData.raceTable.races

        assertEquals(2, races.size)
        assertEquals("Bahrain Grand Prix", races[0].raceName)
        assertEquals("Bahrain", races[0].circuit?.location?.country)
        assertFalse(F1Mapper.hasSprint(races[0]))
        assertTrue(F1Mapper.hasSprint(races[1]))

        val create = F1Mapper.toRaceCreate(races[0])
        assertEquals(1, create.round)
        assertEquals("Bahrain", create.country)
        assertFalse(create.isSprint)
        assertEquals("2026-03-07T16:00:00Z", create.deadline.toString())
    }

    @Test
    fun `risultati ergast vengono mappati e passano la validazione`() {
        val payload = """
            {"MRData":{"xmlns":"http://ergast.com/mrd/1.5","series":"f1",
            "url":"http://ergast.com/api/f1/2026/1/results.json","limit":"30","offset":"0","total":"3",
            "RaceTable":{"season":"2026","round":"1","Races":[
              {"season":"2026","round":"1","raceName":"Bahrain Grand Prix","date":"2026-03-08",
               "Results":[
                 {"number":"1","position":"1","positionText":"1","points":"25",
                  "Driver":{"driverId":"max_verstappen","code":"VER","givenName":"Max",
                    "familyName":"Verstappen","dateOfBirth":"1997-09-30","nationality":"Dutch"},
                  "Constructor":{"constructorId":"red_bull","name":"Red Bull","nationality":"Austrian"},
                  "grid":"1","laps":"57","status":"Finished",
                  "Time":{"millis":"5324063","time":"1:28:44.063"},
                  "FastestLap":{"rank":"1","lap":"39","Time":{"millis":"918482","time":"1:31.447"}}},
                 {"number":"4","position":"2","positionText":"2","points":"18",
                  "Driver":{"driverId":"lando_norris","code":"NOR","givenName":"Lando",
                    "familyName":"Norris","dateOfBirth":"1999-11-13","nationality":"British"},
                  "Constructor":{"constructorId":"mclaren","name":"McLaren","nationality":"British"},
                  "grid":"3","laps":"57","status":"Finished",
                  "Time":{"millis":"5361490","time":"+6.217"}},
                 {"number":"55","position":"3","positionText":"R","points":"0",
                  "Driver":{"driverId":"carlos_sainz","code":"SAI","givenName":"Carlos",
                    "familyName":"Sainz","dateOfBirth":"1994-09-01","nationality":"Spanish"},
                  "Constructor":{"constructorId":"williams","name":"Williams","nationality":"British"},
                  "grid":"5","laps":"12","status":"Engine"}
               ]}
            ]}}}
        """.trimIndent()

        val response = F1Json.decodeFromString(F1Response.serializer(), payload)
        val results = response.mrData.raceTable.races.firstOrNull()?.results.orEmpty()

        val data = F1Mapper.toRaceResultData(results, emptyList(), emptyList())

        assertEquals(1, data.positionOf("VER"))
        assertEquals(2, data.positionOf("NOR"))
        assertTrue(data.driver("VER")?.fastestLap == true)
        assertEquals("Max Verstappen", data.driver("VER")?.driverName)
        assertTrue(data.driver("SAI")?.dnf == true)
        assertEquals("Engine", data.driver("SAI")?.dnfReason)
        assertEquals(emptyList<String>(), RaceResultValidation.validate(data))
    }

    @Test
    fun `qualifica ergast viene deserializzata`() {
        val payload = """
            {"MRData":{"xmlns":"http://ergast.com/mrd/1.5","series":"f1",
            "url":"http://ergast.com/api/f1/2026/1/qualifying.json","limit":"30","offset":"0","total":"2",
            "RaceTable":{"season":"2026","round":"1","Races":[
              {"season":"2026","round":"1","raceName":"Bahrain Grand Prix","date":"2026-03-08",
               "QualifyingResults":[
                 {"number":"1","position":"1",
                  "Driver":{"driverId":"max_verstappen","code":"VER","givenName":"Max",
                    "familyName":"Verstappen","dateOfBirth":"1997-09-30","nationality":"Dutch"},
                  "Constructor":{"constructorId":"red_bull","name":"Red Bull","nationality":"Austrian"},
                  "Q1":{"time":"1:30.012"},"Q2":{"time":"1:29.338"},"Q3":{"time":"1:29.001"}},
                 {"number":"4","position":"2",
                  "Driver":{"driverId":"lando_norris","code":"NOR","givenName":"Lando",
                    "familyName":"Norris","dateOfBirth":"1999-11-13","nationality":"British"},
                  "Constructor":{"constructorId":"mclaren","name":"McLaren","nationality":"British"},
                  "Q1":{"time":"1:30.220"},"Q2":{"time":"1:29.550"},"Q3":{"time":"1:29.120"}}
               ]}
            ]}}}
        """.trimIndent()

        val response = F1Json.decodeFromString(F1Response.serializer(), payload)
        val qualifying = response.mrData.raceTable.races.firstOrNull()?.qualifyingResults.orEmpty()

        assertEquals(2, qualifying.size)
        assertEquals("1", qualifying[0].position)
        assertEquals("max_verstappen", qualifying[0].driver.driverId)
    }

    @Test
    fun `pitstops ergast viene deserializzato e contato`() {
        val payload = """
            {"MRData":{"xmlns":"http://ergast.com/mrd/1.5","series":"f1",
            "url":"http://ergast.com/api/f1/2026/1/pitstops.json","limit":"100","offset":"0","total":"3",
            "PitStopTable":{"season":"2026","round":"1","PitStops":[
              {"driverId":"max_verstappen","lap":"14","stop":"1","duration":"22.315","time":"14:21:22"},
              {"driverId":"max_verstappen","lap":"38","stop":"2","duration":"21.873","time":"14:52:30"},
              {"driverId":"lando_norris","lap":"20","stop":"1","duration":"23.102","time":"14:31:40"}
            ]}}}
        """.trimIndent()

        val response = F1Json.decodeFromString(F1PitStopResponse.serializer(), payload)
        val counts = F1Mapper.pitStopsByDriver(response.mrData.pitStopTable.pitStops)

        assertEquals(2, counts["VER"])
        assertEquals(1, counts["NOR"])
    }

    @Test
    fun `payload senza risultati per il round restituisce lista vuota`() {
        // Il round non e' ancora disputato: RaceTable non contiene Races.
        val payload = """
            {"MRData":{"xmlns":"http://ergast.com/mrd/1.5","series":"f1",
            "url":"http://ergast.com/api/f1/2026/5/results.json","limit":"30","offset":"0","total":"0",
            "RaceTable":{"season":"2026","round":"5","Races":[]}}}
        """.trimIndent()

        val response = F1Json.decodeFromString(F1Response.serializer(), payload)

        assertTrue(response.mrData.raceTable.races.isEmpty())
    }

    @Test
    fun `campi extra e valori mancanti sono tollerati`() {
        // Campi extra non modellati (number, laps, url, ...) ignorati; i
        // campi opzionali assenti restano null senza errori.
        val payload = """
            {"MRData":{"xmlns":"http://ergast.com/mrd/1.5","series":"f1","total":"1",
            "RaceTable":{"season":"2026","round":"1","Races":[
              {"season":"2026","round":"1","raceName":"Test Grand Prix","date":"2026-05-10",
               "Results":[{"number":"7","position":"1","positionText":"1","points":"25",
                 "Driver":{"driverId":"max_verstappen","code":"VER","givenName":"Max",
                   "familyName":"Verstappen","dateOfBirth":"1997-09-30","nationality":"Dutch",
                   "permanentNumber":"1","url":"http://ergast.com/api/f1/drivers/verstappen"},
                 "Constructor":{"constructorId":"red_bull","name":"Red Bull","nationality":"Austrian",
                   "url":"http://ergast.com/api/f1/constructors/red_bull"},
                 "grid":"1","laps":"50","status":"Finished"}]}
            ]}}}
        """.trimIndent()

        val response = F1Json.decodeFromString(F1Response.serializer(), payload)
        val result = response.mrData.raceTable.races.single().results.orEmpty().single()

        assertEquals("max_verstappen", result.driver.driverId)
        assertEquals("1", result.position)
        assertEquals(null, result.time)
        assertEquals(null, result.fastestLap)
    }
}