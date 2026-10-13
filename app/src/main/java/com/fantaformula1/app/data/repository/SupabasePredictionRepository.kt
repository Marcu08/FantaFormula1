package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.data.model.PredictionRowDto
import com.fantaformula1.app.data.supabase.SupabaseHolder
import com.fantaformula1.app.data.util.AppJson
import com.fantaformula1.app.data.util.nowIsoInstantString
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant

// Json condiviso con coerceInputValues (B22).
private val predictionJson = AppJson

@Serializable
private data class PredictionUpsertDto(
    @SerialName("race_id") val raceId: String,
    @SerialName("league_id") val leagueId: String,
    @SerialName("user_id") val userId: String,
    val content: JsonElement,
    @SerialName("submitted_at") val submittedAt: String,
)

/** Messaggi degli errori di scrittura, riusati dai test. */
internal object PredictionWriteMessages {
    const val DEADLINE = "La deadline è passata: non puoi più modificare il pronostico"
    const val PERMISSIONS =
        "Non hai i permessi per salvare questo pronostico: controlla la sessione e la tua appartenenza alla lega"
    const val NETWORK = "Problema di connessione: controlla la rete e riprova"
}

/**
 * Messaggio leggibile per un errore di scrittura del pronostico.
 *
 * Funzione pura (testata in unit test JVM): la decisione dipende dal
 * messaggio grezzo dell'errore e dalla verita' server-side
 * `is_prediction_open` ([deadlineOpen], null se non determinabile).
 * Un errore RLS viene tradotto in "deadline passata" SOLO se il server
 * conferma che la gara e' chiusa: altrimenti e' un problema di
 * autenticazione o appartenenza alla lega.
 */
internal fun predictionWriteErrorMessage(
    raw: String?,
    deadlineOpen: Boolean?,
): String {
    val rawLower = raw?.lowercase().orEmpty()
    val isRlsOrPermission =
        rawLower.contains("row-level security") || rawLower.contains("permission denied")
    return when {
        isRlsOrPermission && deadlineOpen == false -> PredictionWriteMessages.DEADLINE
        isRlsOrPermission -> PredictionWriteMessages.PERMISSIONS
        rawLower.contains("deadline") -> PredictionWriteMessages.DEADLINE
        else -> raw ?: "Si è verificato un errore"
    }
}

class SupabasePredictionRepository(
    private val holder: SupabaseHolder,
    private val authRepository: AuthRepository,
) : PredictionRepository {

    override suspend fun myPrediction(raceId: String): RacePrediction? {
        val client = requireClient()
        val userId = authRepository.currentUserId() ?: return null
        val row = client.from("predictions").select {
            filter {
                eq("race_id", raceId)
                eq("user_id", userId)
            }
        }.decodeSingleOrNull<PredictionRowDto>() ?: return null
        return decode(row)
    }

    override suspend fun myPredictions(leagueId: String): Map<String, RacePrediction> {
        val client = requireClient()
        val userId = authRepository.currentUserId() ?: return emptyMap()
        val rows = client.from("predictions").select {
            filter {
                eq("league_id", leagueId)
                eq("user_id", userId)
            }
        }.decodeList<PredictionRowDto>()
        return rows.mapNotNull { row -> decode(row)?.let { row.raceId to it } }.toMap()
    }

    override suspend fun othersPredictions(raceId: String): Map<String, RacePrediction> {
        val client = requireClient()
        val me = authRepository.currentUserId()
        val rows = client.from("predictions").select {
            filter { eq("race_id", raceId) }
        }.decodeList<PredictionRowDto>()
        return rows
            .filter { it.userId != me }
            .mapNotNull { row -> decode(row)?.let { row.userId to it } }
            .toMap()
    }

    override suspend fun savePrediction(
        raceId: String,
        leagueId: String,
        prediction: RacePrediction,
    ) {
        val client = requireClient()
        val userId = authRepository.currentUserId()
            ?: throw UserException("Devi essere autenticato per inviare un pronostico")

        val normalized = prediction.copy(
            raceId = raceId,
            submittedAtEpochMs = Instant.now().toEpochMilli(),
        )
        val encoded = predictionJson.encodeToJsonElement(
            RacePrediction.serializer(),
            normalized,
        )
        val now = nowIsoInstantString()

        try {
            // B21: upsert atomico su (race_id, user_id). Se la RLS blocca
            // (deadline passata o non membro) l'intera operazione fallisce con
            // un errore chiaro, invece di lasciare uno stato incoerente.
            client.from("predictions").upsert(
                PredictionUpsertDto(
                    raceId = raceId,
                    leagueId = leagueId,
                    userId = userId,
                    content = encoded,
                    submittedAt = now,
                ),
            ) {
                onConflict = "race_id,user_id"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw UserException(writeErrorMessage(throwable, raceId))
        }
    }

    override suspend fun deletePrediction(raceId: String) {
        val client = requireClient()
        val userId = authRepository.currentUserId() ?: return
        try {
            client.from("predictions").delete {
                filter {
                    eq("race_id", raceId)
                    eq("user_id", userId)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw UserException(writeErrorMessage(throwable, raceId))
        }
    }

    // ---------------------------------------------------------------------

    private fun decode(row: PredictionRowDto): RacePrediction? = runCatching {
        predictionJson.decodeFromJsonElement(RacePrediction.serializer(), row.content)
            // Sempre la colonna `race_id`: il valore nel JSON e' scritto dal
            // client e non e' affidabile (B4).
            .copy(raceId = row.raceId)
    }.getOrNull()

    private fun requireClient() = holder.get()
        ?: throw UserException("Configurazione Supabase mancante: apri le impostazioni e inserisci URL e chiave.")

    /**
     * Messaggio per un errore di scrittura. Per gli errori RLS interroga il
     * server (`is_prediction_open`) per distinguere la deadline da un
     * problema di autenticazione/appartenenza: il messaggio non si basa sul
     * solo testo dell'errore. Gli errori di rete hanno un messaggio dedicato.
     */
    private suspend fun writeErrorMessage(throwable: Throwable, raceId: String): String {
        if (throwable is IOException) return PredictionWriteMessages.NETWORK
        val raw = throwable.message
        val rawLower = raw?.lowercase().orEmpty()
        val isRls = rawLower.contains("row-level security") || rawLower.contains("permission denied")
        val deadlineOpen = if (isRls) serverPredictionOpen(raceId) else null
        return predictionWriteErrorMessage(raw, deadlineOpen)
    }

    /** Interroga il server sulla reale apertura della gara; null se non determinabile. */
    private suspend fun serverPredictionOpen(raceId: String): Boolean? {
        val client = holder.get() ?: return null
        return runCatching {
            val data = client.postgrest.rpc(
                function = "is_prediction_open",
                parameters = buildJsonObject { put("p_race", raceId) },
            ).data
            Json.parseToJsonElement(data).jsonPrimitive.booleanOrNull
        }.getOrNull()
    }
}
