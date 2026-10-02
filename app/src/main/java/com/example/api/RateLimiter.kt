package com.example.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class TechLog(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: String = "INFO" // "INFO" | "WARN" | "ERROR" | "RATE_LIMIT"
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

object TechnicalLogManager {
    private val _logs = MutableStateFlow<List<TechLog>>(emptyList())
    val logs: StateFlow<List<TechLog>> = _logs.asStateFlow()

    fun log(tag: String, message: String, level: String = "INFO") {
        val entry = TechLog(tag = tag, message = message, level = level)
        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 200) {
            _logs.value = current.take(200)
        } else {
            _logs.value = current
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}

class RateLimiter {
    private val mutex = Mutex()
    private var lastVideoTimestamp: Long = 0L

    companion object {
        const val MAX_VIDEO_SECONDS_PER_DAY = 500.0

        const val FIRST_POLL_DELAY_MS = 4_000L
        const val POLL_INTERVAL_MS = 4_000L
        const val MAX_POLL_ATTEMPTS = 60

        const val STALL_THRESHOLD = 15 // 15 polls identiques (60s)
        const val STALL_RETRY_DELAY_MS = 8_000L
        const val MAX_STALL_RETRIES = 3

        const val RETRY_429_WAIT_MS = 90_000L
        const val RETRY_503_WAIT_MS = 10_000L
        const val IMAGE_CALL_DELAY_MS = 1_500L

        const val VIDEO_PAUSE_MS_FREE = 5_000L
        const val VIDEO_PAUSE_MS_TOKEN = 2_000L
        const val VIDEO_PAUSE_MS_ENTERPRISE = 0L
    }

    /**
     * Attente anti-throttling basée sur System.currentTimeMillis() avec support d'annulation propre.
     */
    suspend fun realWait(
        totalMs: Long,
        stopRequested: () -> Boolean = { false },
        onTick: (suspend (remainingSeconds: Int, elapsedMs: Long) -> Unit)? = null
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < totalMs) {
            if (stopRequested()) {
                throw CancellationException("Annulé par l'utilisateur")
            }
            val elapsed = System.currentTimeMillis() - start
            val remaining = max(0L, ceil((totalMs - elapsed) / 1000.0).toLong()).toInt()
            onTick?.invoke(remaining, elapsed)
            delay(800)
        }
        if (stopRequested()) {
            throw CancellationException("Annulé par l'utilisateur")
        }
        onTick?.invoke(0, totalMs)
    }

    fun getRequiredVideoCooldown(profile: String): Long {
        return when (profile.lowercase(Locale.ROOT)) {
            "enterprise" -> VIDEO_PAUSE_MS_ENTERPRISE
            "token" -> VIDEO_PAUSE_MS_TOKEN
            else -> VIDEO_PAUSE_MS_FREE
        }
    }

    suspend fun checkVideoRateLimit(profile: String): Long = mutex.withLock {
        val cooldown = getRequiredVideoCooldown(profile)
        if (cooldown == 0L) return 0L

        val elapsed = System.currentTimeMillis() - lastVideoTimestamp
        val remaining = cooldown - elapsed
        return if (remaining > 0) remaining else 0L
    }

    suspend fun registerVideoDispatch() = mutex.withLock {
        lastVideoTimestamp = System.currentTimeMillis()
    }
}
