package com.example.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CinematicShotPlannerTest {
    @Test
    fun storyboardCycleContainsEightDistinctFamilies() {
        val families = (1..8).map { CinematicShotPlanner.forScene(it).family }
        assertEquals(8, families.toSet().size)
    }

    @Test
    fun adjacentScenesDoNotRepeatTheSameShotFamily() {
        for (scene in 1..32) {
            assertNotEquals(
                "Adjacent shot family repeated at scene $scene",
                CinematicShotPlanner.forScene(scene).family,
                CinematicShotPlanner.forScene(scene + 1).family
            )
        }
    }

    @Test
    fun firstSceneOpensOnTheWorldRatherThanAProtagonistPortrait() {
        val opening = CinematicShotPlanner.forScene(1)
        assertEquals("ESTABLISHING_WIDE", opening.family)
        assertEquals(true, opening.imageDirective.contains("location"))
    }
}
