package com.example.api

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoModeTest {
    @Test
    fun textOnlyVideoUsesAgnesTi2VidMode() {
        assertEquals("ti2vid", selectAgnesVideoMode(hasReferenceImage = false))
    }

    @Test
    fun videoWithReferenceImageUsesKeyframesMode() {
        assertEquals("keyframes", selectAgnesVideoMode(hasReferenceImage = true))
    }
}
