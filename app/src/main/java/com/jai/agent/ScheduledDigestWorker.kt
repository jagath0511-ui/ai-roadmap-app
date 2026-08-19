package com.jai.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ScheduledDigestWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val digestPrompt = """
                You are JAI Morning Companion. Generate a 3-part brief:
                1. 🚀 Founder/AI Update: One high-impact emerging tech or AI roadmap insight.
                2. 📚 JEE Mains Concept: One key physics/math formula or high-yield problem tip.
                3. ⚡ 24h Action Summary: A brief motivational directive for today's milestone.
                Keep it concise, high-yield, and easy to read.
            """.trimIndent()

            val brief = AiScreenAnalyzer.analyzeScreenImage(null, digestPrompt)
            postDigestNotification(brief)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun postDigestNotification(content: String) {
        val channelId = "jai_scheduled_digests"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "JAI Scheduled Briefs",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily Founder, JEE Mains & AI Roadmap digests"
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("⚡ JAI Morning Digest & Founder Brief")
            .setContentText("Tap to read your 24h briefing.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        manager.notify(201, notification)
    }

    companion object {
        fun scheduleDailyDigests(context: Context) {
            val dailyRequest = PeriodicWorkRequestBuilder<ScheduledDigestWorker>(24, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "JAIScheduledDigestDaily",
                ExistingPeriodicWorkPolicy.KEEP,
                dailyRequest
            )
        }
    }
}

