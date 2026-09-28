package com.example.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        const val FREE_COOLDOWN_MS = 65_000L
        const val TOKEN_COOLDOWN_MS = 15_000L
        const val ENTERPRISE_COOLDOWN_MS = 0L

        const val MAX_STALL_RETRIES = 3
        const val STALL_THRESHOLD_MS = 25_000L
    }

    /**
     * Strict realWait implementation based on Date.now() / System.currentTimeMillis().
     * Emits tick callbacks with remaining seconds for micro-interaction countdown displays.
     */
    suspend fun realWait(targetDurationMs: Long, onTick: (suspend (remainingSeconds: Int) -> Unit)? = null) {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + targetDurationMs

        while (true) {
            val now = System.currentTimeMillis()
            val remainingMs = endTime - now
            if (remainingMs <= 0) break

            val remainingSec = ((remainingMs + 999) / 1000).toInt()
            onTick?.invoke(remainingSec)

            val step = if (remainingMs > 1000) 1000L else remainingMs
            delay(step)
        }
        onTick?.invoke(0)
    }

    fun getRequiredCooldown(profile: String): Long {
        return when (profile.lowercase(Locale.ROOT)) {
            "enterprise" -> ENTERPRISE_COOLDOWN_MS
            "token" -> TOKEN_COOLDOWN_MS
            else -> FREE_COOLDOWN_MS
        }
    }

    suspend fun checkVideoRateLimit(profile: String): Long = mutex.withLock {
        val cooldown = getRequiredCooldown(profile)
        if (cooldown == 0L) return 0L

        val elapsed = System.currentTimeMillis() - lastVideoTimestamp
        val remaining = cooldown - elapsed
        return if (remaining > 0) remaining else 0L
    }

    suspend fun registerVideoDispatch() = mutex.withLock {
        lastVideoTimestamp = System.currentTimeMillis()
    }
}
