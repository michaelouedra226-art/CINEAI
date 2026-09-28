package com.example.api

import com.example.data.dao.UsageDao
import com.example.data.model.UsageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageTracker(private val usageDao: UsageDao) {

    fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }

    suspend fun getUsageForDate(date: String): UsageEntity? = withContext(Dispatchers.IO) {
        usageDao.getUsageForDateDirect(date)
    }

    suspend fun recordTextRequest() = withContext(Dispatchers.IO) {
        val date = getTodayDateString()
        val current = usageDao.getUsageForDateDirect(date) ?: UsageEntity(date = date)
        usageDao.insertOrUpdate(
            current.copy(
                textRequests = current.textRequests + 1,
                lastUpdated = System.currentTimeMillis()
            )
        )
        TechnicalLogManager.log("USAGE", "Requête texte comptabilisée (+1, total: ${current.textRequests + 1})")
    }

    suspend fun recordImageRequest(count: Int = 1) = withContext(Dispatchers.IO) {
        val date = getTodayDateString()
        val current = usageDao.getUsageForDateDirect(date) ?: UsageEntity(date = date)
        usageDao.insertOrUpdate(
            current.copy(
                imageRequests = current.imageRequests + count,
                lastUpdated = System.currentTimeMillis()
            )
        )
        TechnicalLogManager.log("USAGE", "Requête(s) image enregistrée(s) (+$count, total: ${current.imageRequests + count})")
    }

    suspend fun recordVideoRequest(seconds: Double) = withContext(Dispatchers.IO) {
        val date = getTodayDateString()
        val current = usageDao.getUsageForDateDirect(date) ?: UsageEntity(date = date)
        usageDao.insertOrUpdate(
            current.copy(
                videoRequests = current.videoRequests + 1,
                videoSeconds = current.videoSeconds + seconds,
                lastUpdated = System.currentTimeMillis()
            )
        )
        TechnicalLogManager.log("USAGE", "Génération vidéo comptabilisée (+1, +${seconds}s, total: ${current.videoSeconds + seconds}s)")
    }
}
