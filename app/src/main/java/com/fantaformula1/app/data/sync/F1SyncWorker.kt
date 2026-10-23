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
import com.fantaformula1.app.data.notification.DeadlineNotifications
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

        // N5: la scrittura dei risultati e' riservata agli admin (RLS). Se
        // l'utente di questo dispositivo non e' piu' admin non ha senso
        // riprovare: il worker si ferma (Result.failure, nessun retry).
        val isAdmin = runCatching { container.leagueRepository.isAdmin(leagueId) }
            .getOrDefault(false)
        if (!isAdmin) {
            F1SyncScheduler.cancel(applicationContext, leagueId)
            return Result.failure()
        }

        // Null = sincronizzazione manuale gia' in corso: si riprova piu' tardi.
        val calendar = container.f1SyncStateHolder.syncCalendar(leagueId)
        if (calendar?.isSuccess != true) {
            // Riprova piu' tardi: potrebbe essere un problema di rete.
            return Result.retry()
        }
        val results = container.f1SyncStateHolder.syncResults(leagueId)
        return if (results?.isSuccess == true) {
            // F1: se la sincronizzazione ha davvero salvato dei risultati,
            // l'utente locale riceve l'avviso "risultati disponibili"
            // (solo se non ha spento le notifiche dall'impostazione).
            val completed = (results as? SyncResult.Success)?.completed ?: 0
            if (completed > 0) {
                DeadlineNotifications.showResultsAvailable(applicationContext, completed)
            }
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
 *
 * Riservato agli admin (N5): il worker riverifica il ruolo prima di ogni
 * esecuzione e si ferma da solo se l'utente ha perso il ruolo. Usa
 * ExistingPeriodicWorkPolicy.KEEP per non creare duplicati.
 */
object F1SyncScheduler {

    suspend fun schedule(context: Context, leagueId: String) {
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
        // Ricorda la lega schedulata su questo dispositivo, per poter
        // cancellare tutto al logout o all'uscita dalla lega.
        F1SyncPrefs(context).markLeagueScheduled(leagueId)
    }

    fun cancel(context: Context, leagueId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(leagueId))
    }

    /**
     * Cancella tutti i worker di sincronizzazione avviati da questo
     * dispositivo (logout, utente non piu' admin): le leghe schedulate sono
     * ricordate su DataStore.
     */
    suspend fun cancelAll(context: Context) {
        F1SyncPrefs(context).scheduledLeagues().forEach { leagueId ->
            cancel(context, leagueId)
        }
        F1SyncPrefs(context).clearAllScheduled()
    }

    private fun uniqueName(leagueId: String): String = "f1_sync_$leagueId"
}
