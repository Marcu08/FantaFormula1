package com.fantaformula1.app.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fantaformula1.app.FantaApplication
import java.util.concurrent.TimeUnit

/**
 * Worker WorkManager che sincronizza calendario e risultati F1 in background.
 * Richiede il `leagueId` come input.
 *
 * Usa lo stesso [F1SyncStateHolder] della UI: gli orari "Ultimo aggiornamento"
 * restano coerenti e le sincronizzazioni non si sovrappongono a quelle manuali.
 */
class F1SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val leagueId = inputData.getString(KEY_LEAGUE_ID)
            ?: return Result.failure()
        val container = (applicationContext as FantaApplication).container

        // Null = sincronizzazione manuale gia' in corso: si riprova piu' tardi.
        val calendar = container.f1SyncStateHolder.syncCalendar(leagueId)
        if (calendar?.isSuccess != true) {
            // Riprova piu' tardi: potrebbe essere un problema di rete.
            return Result.retry()
        }
        val results = container.f1SyncStateHolder.syncResults(leagueId)
        return if (results?.isSuccess == true) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_LEAGUE_ID = "league_id"
    }
}

/**
 * Schedula la sincronizzazione F1 ogni 30 minuti per la lega indicata.
 * Usa ExistingPeriodicWorkPolicy.KEEP per non creare duplicati.
 */
object F1SyncScheduler {

    fun schedule(context: Context, leagueId: String) {
        val request = PeriodicWorkRequestBuilder<F1SyncWorker>(30, TimeUnit.MINUTES)
            .setInputData(workDataOf(F1SyncWorker.KEY_LEAGUE_ID to leagueId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueName(leagueId),
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context, leagueId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(leagueId))
    }

    private fun uniqueName(leagueId: String): String = "f1_sync_$leagueId"
}
