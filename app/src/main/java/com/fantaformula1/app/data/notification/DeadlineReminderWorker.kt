package com.fantaformula1.app.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fantaformula1.app.FantaApplication
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.Rules
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Worker che invia i promemoria di scadenza dei pronostici (F1): due avvisi
 * per gara, 24 ore e 1 ora prima della deadline, SOLO se il pronostico
 * dell'utente manca o e' incompleto (chi ha gia' compilato tutto non riceve
 * nulla). Controlla sul server l'apertura reale della gara prima di notificare.
 *
 * Il tocco sulla notifica apre l'app (via launcher intent): la navigazione
 * diretta alla gara richiederebbe un deep link dedicato.
 */
class DeadlineReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val raceId = inputData.getString(KEY_RACE_ID)
            ?: return Result.failure()
        val leadMinutes = inputData.getInt(KEY_LEAD_MINUTES, LEAD_1_HOUR_MINUTES)
        val container = (applicationContext as FantaApplication).container

        // Impostazione "Notifiche" spenta dall'utente: nessun avviso.
        if (!NotificationPrefs(applicationContext).enabled()) return Result.success()

        val race = runCatching { container.raceRepository.race(raceId) }.getOrNull()
        // Gara chiusa/completata (risultato gia' salvato): nessun promemoria.
        if (race != null && !race.isPredictionOpen()) {
            return Result.success()
        }

        val mine = race?.let {
            runCatching { container.predictionRepository.myPrediction(raceId) }.getOrNull()
        }
        val rules = race?.let {
            runCatching { container.leagueRepository.league(it.leagueId)?.rules }.getOrNull()
        }
        // F1: il promemoria serve solo a chi deve ancora compilare (o finire)
        // il pronostico; chi ha completato tutti i tipi attivi non viene
        // disturbato.
        if (!isPredictionIncomplete(mine, rules)) {
            return Result.success()
        }

        val title = race?.name ?: "Scadenza pronostici"
        val whenText = if (leadMinutes >= LEAD_24_HOURS_MINUTES) "entro 24 ore" else "entro circa un'ora"
        val body = if (mine == null) {
            "I pronostici $whenText: compilali prima della chiusura!"
        } else {
            "I tuoi pronostici $whenText sono ancora incompleti: finiscili!"
        }
        DeadlineNotifications.showDeadlineNotification(applicationContext, raceId, title, body)
        return Result.success()
    }

    companion object {
        const val KEY_RACE_ID = "race_id"
        const val KEY_LEAD_MINUTES = "lead_minutes"
        const val LEAD_24_HOURS_MINUTES = 24 * 60
        const val LEAD_1_HOUR_MINUTES = 60
    }
}

/**
 * Funzione pura (F1, testata su JVM): il pronostico e' "incompleto" quando
 * manca del tutto oppure quando non copre tutti i tipi attivi dalle regole.
 * Senza regole nota (errore di rete) il richiamo degrada sul solo controllo
 * "almeno qualcosa compilato".
 */
internal fun isPredictionIncomplete(prediction: RacePrediction?, rules: Rules?): Boolean {
    if (prediction == null) return true
    val savedTypes = prediction.entries.map { it.type }.toSet()
    if (rules == null) return savedTypes.isEmpty()
    return rules.enabledTypes.any { it !in savedTypes }
}

/**
 * Schedula i promemoria della gara indicata: un lavoro unico per gara e
 * orizzonte (24h e 1h), con REPLACE per rimpiazzare eventuali promemoria
 * precedenti. Non schedula un orizzonte se il suo momento e' gia' passato.
 */
object DeadlineReminderScheduler {

    fun schedule(context: Context, raceId: String, deadline: Instant) {
        scheduleAt(context, raceId, deadline, DeadlineReminderWorker.LEAD_24_HOURS_MINUTES)
        scheduleAt(context, raceId, deadline, DeadlineReminderWorker.LEAD_1_HOUR_MINUTES)
    }

    private fun scheduleAt(
        context: Context,
        raceId: String,
        deadline: Instant,
        leadMinutes: Int,
    ) {
        val now = Instant.now()
        val triggerAt = deadline.minus(Duration.ofMinutes(leadMinutes.toLong()))
        if (!triggerAt.isAfter(now)) return
        val request = OneTimeWorkRequestBuilder<DeadlineReminderWorker>()
            // Overload long/TimeUnit: il Duration API 26+ e' bloccato dal lint
            // con minSdk 21.
            .setInitialDelay(Duration.between(now, triggerAt).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    DeadlineReminderWorker.KEY_RACE_ID to raceId,
                    DeadlineReminderWorker.KEY_LEAD_MINUTES to leadMinutes,
                ),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(raceId, leadMinutes),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, raceId: String) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(uniqueName(raceId, DeadlineReminderWorker.LEAD_24_HOURS_MINUTES))
        manager.cancelUniqueWork(uniqueName(raceId, DeadlineReminderWorker.LEAD_1_HOUR_MINUTES))
    }

    /**
     * Cancella tutti i promemoria di scadenza (F1: quando l'utente spegne le
     * notifiche dall'impostazione dedicata).
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    internal const val TAG = "deadline_reminder"

    private fun uniqueName(raceId: String, leadMinutes: Int): String =
        "deadline_reminder_${raceId}_$leadMinutes"
}

/** Canali e notifiche dell'app (F1: scadenze e risultati disponibili). */
internal object DeadlineNotifications {
    const val CHANNEL_ID = "deadline_reminder"
    private const val CHANNEL_NAME = "Scadenza pronostici"
    const val RESULTS_CHANNEL_ID = "results_available"
    private const val RESULTS_CHANNEL_NAME = "Risultati disponibili"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            createChannelIfMissing(manager, CHANNEL_ID, CHANNEL_NAME)
            createChannelIfMissing(manager, RESULTS_CHANNEL_ID, RESULTS_CHANNEL_NAME)
        }
    }

    private fun createChannelIfMissing(
        manager: NotificationManager,
        id: String,
        name: String,
    ) {
        // Guard locale (lint NewApi): i canali esistono solo da API 26.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun showDeadlineNotification(
        context: Context,
        raceId: String,
        title: String,
        body: String,
    ) {
        notify(context, CHANNEL_ID, raceId.hashCode(), title, body)
    }

    /** F1: avviso quando la sincronizzazione ha salvato risultati nuovi. */
    fun showResultsAvailable(context: Context, completedRaces: Int) {
        val body = if (completedRaces == 1) {
            "E' disponibile il risultato di una gara: controlla la classifica!"
        } else {
            "Sono disponibili i risultati di $completedRaces gare: controlla la classifica!"
        }
        notify(context, RESULTS_CHANNEL_ID, RESULTS_NOTIFICATION_ID, "Risultati F1 aggiornati", body)
    }

    private fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
    ) {
        ensureChannel(context)
        // Se Android 13+ e' senza permesso, la notifica verra' rifiutata:
        // niente crash, il flag di sistema resta l'unica autorita'.
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!granted) return

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private const val RESULTS_NOTIFICATION_ID = 7_2026
}
