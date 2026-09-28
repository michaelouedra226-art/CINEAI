package com.example

import com.example.api.RateLimiter
import com.example.data.model.SceneItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testRateLimiterCooldowns() {
        val limiter = RateLimiter()
        assertEquals(RateLimiter.FREE_COOLDOWN_MS, limiter.getRequiredCooldown("free"))
        assertEquals(RateLimiter.TOKEN_COOLDOWN_MS, limiter.getRequiredCooldown("token"))
        assertEquals(RateLimiter.ENTERPRISE_COOLDOWN_MS, limiter.getRequiredCooldown("enterprise"))
    }

    @Test
    fun testRealWaitExecution() = runBlocking {
        val limiter = RateLimiter()
        val start = System.currentTimeMillis()
        var ticks = 0
        limiter.realWait(200) { ticks++ }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Elapsed time should be at least 190ms, got $elapsed", elapsed >= 180)
    }

    @Test
    fun testSceneSerialization() {
        val scenes = listOf(
            SceneItem(
                number = 1,
                title = "Intro",
                description = "Plan séquence",
                image_prompt = "Cyberpunk street",
                video_prompt = "Travelling avant",
                camera_movement = "Travelling",
                status = "done"
            )
        )
        val json = SceneItem.serializeList(scenes)
        val parsed = SceneItem.parseList(json)
        assertEquals(1, parsed.size)
        assertEquals("Intro", parsed[0].title)
        assertEquals("done", parsed[0].status)
    }
}
