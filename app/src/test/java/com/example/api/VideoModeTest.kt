package com.example.api

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoModeTest {
    @Test
    fun textOnlyVideoUsesAgnesTextMode() {
        assertEquals("text", selectAgnesVideoMode(hasReferenceImage = false))
    }

    @Test
    fun videoWithReferenceImageUsesKeyframeMode() {
        assertEquals("keyframe", selectAgnesVideoMode(hasReferenceImage = true))
    }
}
