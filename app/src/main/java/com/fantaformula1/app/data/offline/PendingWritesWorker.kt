package com.fantaformula1.app.data.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fantaformula1.app.FantaApplication
import com.fantaformula1.app.R
import com.fantaformula1.app.data.notification.DeadlineNotifications
import com.fantaformula1.app.data.notification.NotificationPrefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * F3 — worker periodico che svuota la coda dei pronostici salvati offline
 * ([PendingWritesStore]). Per ogni voce in coda:
 *
 *  1. carica la gara: se non esiste piu' la voce viene rimossa;
 *  2. se la gara non accetta piu' pronostici (deadline passata o chiusa) la
 *     voce viene rimossa e l'utente riceve un avviso;
 *  3. altrimenti riprova il salvataggio: successo -> rimosso; errore di
 *     rete -> la voce resta in coda e il ciclo si ferma; ogni altro errore
 *     (permessi, validazione) -> rimosso + avviso con il messaggio.
 */
class PendingWritesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FantaApplication).container
        val store = container.pendingWritesStore
        val writes = store.all()
        if (writes.isEmpty()) return Result.success()

        for (write in writes) {
            val race = try {
                container.raceRepository.race(write.raceId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Errore di rete -> resta in coda; altro errore -> comunque
                // non si butta via il lavoro dell'utente: si riprova.
                return Result.retry()
            }
            if (race == null) {
                // Gara non esiste piu': niente da salvare.
                store.remove(write.raceId)
                continue
            }
            if (!race.isPredictionOpen()) {
                store.remove(write.raceId)
                notifyIfEnabled(
                    write.raceId,
                    title = applicationContext.getString(R.string.coda_titolo),
                    body = applicationContext.getString(R.string.coda_deadline_passata, race.name),
                )
                continue
            }
            try {
                container.predictionRepository.savePrediction(
                    write.raceId,
                    race.leagueId,
                    write.prediction,
                )
                store.remove(write.raceId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (isOfflineError(throwable)) {
                    // La rete e' ancora giu': la voce resta in coda e si
                    // riprova alla prossima esecuzione del worker.
                    return Result.retry()
                }
                store.remove(write.raceId)
                notifyIfEnabled(
                    write.raceId,
                    title = applicationContext.getString(R.string.coda_titolo),
                    body = applicationContext.getString(
                        R.string.coda_salvataggio_fallito,
                        throwable.message ?: applicationContext.getString(R.string.stato_errore),
                    ),
                )
            }
        }
        return Result.success()
    }

    /** Notifica solo se l'utente non ha spento le notifiche (F1). */
    private suspend fun notifyIfEnabled(raceId: String, title: String, body: String) {
        if (!NotificationPrefs(applicationContext).enabled()) return
        DeadlineNotifications.showDeadlineNotification(applicationContext, raceId, title, body)
    }
}

/**
 * F3 — schedulazione della coda salvataggi: un unico worker periodico (15
 * minuti, serve la rete), KEEP per non creare duplicati. Chiamato all'avvio
 * dell'app: e' un'operazione locale e non bloccante.
 */
object PendingWritesScheduler {

    const val UNIQUE_NAME = "pending_writes"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<PendingWritesWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
