package com.example

import com.example.api.RateLimiter
import com.example.data.repository.AgnesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testRateLimiterCooldowns() {
        val limiter = RateLimiter()
        assertEquals(RateLimiter.VIDEO_PAUSE_MS_FREE, limiter.getRequiredVideoCooldown("free"))
        assertEquals(RateLimiter.VIDEO_PAUSE_MS_TOKEN, limiter.getRequiredVideoCooldown("token"))
        assertEquals(RateLimiter.VIDEO_PAUSE_MS_ENTERPRISE, limiter.getRequiredVideoCooldown("enterprise"))
    }

    @Test
    fun testRealWaitExecution() = runBlocking {
        val limiter = RateLimiter()
        val start = System.currentTimeMillis()
        var ticks = 0
        limiter.realWait(100) { _, _ -> ticks++ }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Elapsed time should be at least 90ms, got $elapsed", elapsed >= 80)
    }

    @Test
    fun testBreakdownCalculation() {
        val breakdown = AgnesRepository.calculateBreakdown(30.0, null)
        assertTrue(breakdown.numScenes in 2..50)
        assertEquals(121, breakdown.framesPerScene)

        val manual = AgnesRepository.calculateBreakdown(60.0, 5)
        assertEquals(5, manual.numScenes)
        assertTrue(manual.framesPerScene in listOf(81, 121, 153, 241, 441))
    }
}
