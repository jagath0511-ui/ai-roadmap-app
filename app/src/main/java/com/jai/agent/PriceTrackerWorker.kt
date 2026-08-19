package com.jai.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PriceTrackerWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val productName = inputData.getString("PRODUCT_NAME") ?: return@withContext Result.failure()
        try {
            val encodedQuery = URLEncoder.encode(productName, "UTF-8")
            val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery+price"

            val doc = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(10000)
                .get()

            val snippets = doc.select(".result__snippet").eachText()
            if (snippets.isNotEmpty()) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule24HourTracker(context: Context, productName: String, targetPrice: Double) {
            val data = workDataOf(
                "PRODUCT_NAME" to productName,
                "TARGET_PRICE" to targetPrice
            )

            val workRequest = PeriodicWorkRequestBuilder<PriceTrackerWorker>(24, TimeUnit.HOURS)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "PriceTracker_$productName",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}
    
