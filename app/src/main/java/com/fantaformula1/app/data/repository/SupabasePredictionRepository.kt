package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.data.model.PredictionRowDto
import com.fantaformula1.app.data.supabase.SupabaseHolder
import com.fantaformula1.app.data.util.AppJson
import com.fantaformula1.app.data.util.nowIsoInstantString
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

// Json condiviso con coerceInputValues (B22).
private val predictionJson = AppJson

@Serializable
private data class PredictionInsertDto(
    val id: String,
    @SerialName("race_id") val raceId: String,
    @SerialName("league_id") val leagueId: String,
    @SerialName("user_id") val userId: String,
    val content: JsonElement,
    @SerialName("submitted_at") val submittedAt: String,
)

@Serializable
private data class PredictionUpdateDto(
    val content: JsonElement,
    @SerialName("submitted_at") val submittedAt: String,
)

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
            val updated = client.from("predictions").update(
                PredictionUpdateDto(content = encoded, submittedAt = now),
            ) {
                filter {
                    eq("race_id", raceId)
                    eq("user_id", userId)
                }
            }.decodeList<PredictionRowDto>()

            if (updated.isEmpty()) {
                client.from("predictions").insert(
                    PredictionInsertDto(
                        id = java.util.UUID.randomUUID().toString(),
                        raceId = raceId,
                        leagueId = leagueId,
                        userId = userId,
                        content = encoded,
                        submittedAt = now,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw UserException(mapWriteError(throwable))
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
            throw UserException(mapWriteError(throwable))
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
     * Mappa l'errore di scrittura in un messaggio leggibile.
     *
     * Nota: la RLS del DB rifiuta le scritture dopo la deadline con un
     * "permission denied" generico, quindi quel ramo NON puo' essere tradotto
     * in "hai finito i permessi": il motivo reale piu' frequente e' la deadline.
     */
    private fun mapWriteError(throwable: Throwable): String {
        val raw = throwable.message?.lowercase().orEmpty()
        val isRlsOrPermission =
            raw.contains("row-level security") || raw.contains("permission denied")
        return when {
            isRlsOrPermission || raw.contains("violates check constraint") || raw.contains("deadline") ->
                "La deadline è passata: non puoi più modificare il pronostico"
            else -> throwable.message ?: "Si è verificato un errore"
        }
    }
}
