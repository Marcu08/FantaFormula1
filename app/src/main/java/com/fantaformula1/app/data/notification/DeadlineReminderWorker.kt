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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Worker che invia un promemoria un'ora prima della chiusura dei pronostici
 * della prossima gara. Controlla sul server l'apertura reale della gara e, se
 * l'utente ha gia' pronosticato, il messaggio resta informativo (modifiche
 * ancora possibili) invece di incarare a compilare.
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
        val container = (applicationContext as FantaApplication).container

        val race = runCatching { container.raceRepository.race(raceId) }.getOrNull()
        // Gara chiusa/completata (risultato gia' salvato): nessun promemoria.
        if (race != null && !race.isPredictionOpen()) {
            return Result.success()
        }

        val mine = race?.let {
            runCatching { container.predictionRepository.myPrediction(raceId) }.getOrNull()
        }
        val title = race?.name ?: "Scadenza pronostici"
        val body = when {
            mine != null -> "Hai già pronosticato: ti resta circa un'ora per modificarlo."
            else -> "Chiude tra circa un'ora: compila il tuo pronostico!"
        }
        DeadlineNotifications.showDeadlineNotification(applicationContext, raceId, title, body)
        return Result.success()
    }

    companion object {
        const val KEY_RACE_ID = "race_id"
    }
}

/**
 * Schedula il promemoria della gara indicata: lavoro unico per gara, con
 * REPLACE per rimpiazzare eventuali promemoria precedenti. Non schedula nulla
 * se il momento della notifica e' gia' passato (deadline entro un'ora).
 */
object DeadlineReminderScheduler {

    fun schedule(context: Context, raceId: String, deadline: Instant) {
        val now = Instant.now()
        val triggerAt = deadline.minus(NOTIFY_BEFORE)
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
            .setInputData(workDataOf(DeadlineReminderWorker.KEY_RACE_ID to raceId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "deadline_reminder_$raceId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, raceId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("deadline_reminder_$raceId")
    }

    private val NOTIFY_BEFORE: Duration = Duration.ofMinutes(60)
}

/** Canale e notifica del promemoria. */
internal object DeadlineNotifications {
    const val CHANNEL_ID = "deadline_reminder"
    private const val CHANNEL_NAME = "Scadenza pronostici"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    fun showDeadlineNotification(
        context: Context,
        raceId: String,
        title: String,
        body: String,
    ) {
        ensureChannel(context)
        // Se Android 13+ e' senza permesso, la notifica verra' rifiutata:
        // si rilascia la SecurityException senza far fallire il worker.
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(raceId.hashCode(), notification)
    }
}