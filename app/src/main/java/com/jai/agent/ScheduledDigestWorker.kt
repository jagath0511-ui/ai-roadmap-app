package com.jai.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ScheduledDigestWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val digest = generateDailyDigest()
            postDigestNotification(digest)
            Result.success()
        } catch (e: Exception) {
            FailureLogger.log(context, "ScheduledDigestWorker", "Digest generation failed: ${e.localizedMessage}")
            Result.retry()
        }
    }

    private suspend fun generateDailyDigest(): String {
        val currentTime = SimpleDateFormat("h:mm a, EEEE", Locale.US).format(Date())
        val recentErrors = FailureLogger.readRecent(context, 3)

        val baseContext = """
            Current Time: $currentTime
            Active System Status: JAI Agent running smoothly.
            Recent Telemetry Notes: ${if (recentErrors.isEmpty()) "All services operational" else recentErrors.joinToString("; ")}
        """.trimIndent()

        val prompt = "Create an energetic, 2-sentence morning voice briefing for the user based on this context:\n$baseContext"
        return AiScreenAnalyzer.analyzeScreenImage(null, prompt)
    }

    private fun postDigestNotification(digestText: String) {
        val channelId = "jai_digest_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "JAI Daily Briefings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily proactive briefings and assistant updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tap to open Floating Overlay and listen to the briefing
        val openIntent = Intent(context, FloatingOverlayService::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getService(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚡ JAI Daily Briefing Ready")
            .setContentText(digestText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(digestText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(201, notification)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "JAI_SCHEDULED_DIGEST_WORK"

        /**
         * Schedules the digest worker to run periodically (every 12 hours) with battery-friendly constraints.
         */
        fun schedulePeriodicDigest(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<ScheduledDigestWorker>(
                12, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }
}
