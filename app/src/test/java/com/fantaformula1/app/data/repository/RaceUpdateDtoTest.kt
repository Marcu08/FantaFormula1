package com.fantaformula1.app.data.repository

import io.github.jan.supabase.encode
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7: il PATCH di `updateRace` deve contenere SEMPRE la chiave `deadline`,
 * anche quando e' null, altrimenti l'admin non puo' cancellare la scadenza
 * (B27). Il campo non ha default, ma il test verifica che il Json REALE del
 * client (KotlinXSerializer con Json.Default, encodeDefaults=false) si
 * comporti come previsto.
 */
class RaceUpdateDtoTest {

    /** Lo stesso serializer che supabase-kt usa di default nel client. */
    private val serializer = KotlinXSerializer(Json)

    private val sample = RaceUpdateDto(
        round = 3,
        name = "Gran Premio dell'Emilia-Romagna",
        country = "Italy",
        isSprint = false,
        deadline = null,
    )

    @Test
    fun `la chiave deadline e' presente anche quando e' null`() {
        val json = Json.parseToJsonElement(serializer.encode(sample)).jsonObject

        assertTrue("chiave deadline mancante: $json", json.containsKey("deadline"))
        // Nota: il valore serializzato e' JsonNull (non il null di Kotlin).
        assertTrue("deadline non e' null: ${json["deadline"]}", json["deadline"] is JsonNull)
    }

    @Test
    fun `la chiave deadline e' presente con un valore`() {
        val json = Json.parseToJsonElement(
            serializer.encode(sample.copy(deadline = "2026-04-05T13:00:00Z")),
        ).jsonObject

        assertEquals(
            "2026-04-05T13:00:00Z",
            json["deadline"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `campi obbligatori sempre serializzati`() {
        val json = Json.parseToJsonElement(serializer.encode(sample)).jsonObject

        assertNotNull(json["round"])
        assertEquals(3L, json["round"]?.jsonPrimitive?.longOrNull)
        assertEquals("Gran Premio dell'Emilia-Romagna", json["name"]?.jsonPrimitive?.content)
        // Campi con default: con encodeDefaults=false possono mancare, ma
        // round/name/deadline (senza default) devono esserci.
        assertNotNull(json["name"])
    }
}
