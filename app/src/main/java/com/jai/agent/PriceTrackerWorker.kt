package com.jai.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class PriceTrackerWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val productName = inputData.getString("PRODUCT_NAME") ?: return Result.failure()
        val targetPrice = inputData.getDouble("TARGET_PRICE", 0.0)

        return withContext(Dispatchers.IO) {
            try {
                // Scrape lowest detected price
                val query = "$productName price buy online"
                val searchUrl = "https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                val doc = Jsoup.connect(searchUrl).userAgent("Mozilla/5.0").get()
                val snippetText = doc.select(".result__snippet").text()

                // Extract potential price digits
                val pricePattern = Regex("""(?:₹|Rs\.?|\$)\s?(\d+[\d,]*)""")
                val match = pricePattern.find(snippetText)
                val detectedPriceStr = match?.groupValues?.get(1)?.replace(",", "")
                val currentPrice = detectedPriceStr?.toDoubleOrNull() ?: targetPrice

                // Send Alert Notification
                sendNotification(
                    title = "⚡ JAI Price Watch: $productName",
                    message = "Current estimated price: ₹$currentPrice (Checked 24h cycle)"
                )

                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    private fun sendNotification(title: String, message: String) {
        val channelId = "jai_price_tracker_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "JAI Price Tracker",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        fun schedule24HourTracker(context: Context, productName: String, currentPrice: Double) {
            val data = workDataOf(
                "PRODUCT_NAME" to productName,
                "TARGET_PRICE" to currentPrice
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val dailyWorkRequest = PeriodicWorkRequestBuilder<PriceTrackerWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "Tracker_$productName",
                ExistingPeriodicWorkPolicy.UPDATE,
                dailyWorkRequest
            )
        }
    }
}
