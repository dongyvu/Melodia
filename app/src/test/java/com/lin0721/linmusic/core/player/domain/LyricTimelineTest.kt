package com.lin0721.linmusic.core.player.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimelineTest {
    @Test fun everyOverlappingLineActivatesRegardlessOfType() {
        val lines = listOf(
            LyricLine(1000, 3000, "left", alignment = LyricAlignment.START),
            LyricLine(1500, 2500, "right", alignment = LyricAlignment.END),
            LyricLine(2000, 1000, "with background", backgroundLine = LyricLine(2500, 2000, "background"))
        )
        assertEquals(setOf(0, 1, 2), LyricTimeline.activeIndices(lines, 2750))
        assertEquals(setOf(2), LyricTimeline.activeIndices(lines, 4250))
    }

    @Test fun primaryAnchorRemainsStableWhileItIsStillActive() {
        val lines = listOf(LyricLine(1000, 4000, "first"), LyricLine(2000, 4000, "second"))
        val active = LyricTimeline.activeIndices(lines, 2500)
        assertEquals(0, LyricTimeline.primaryIndex(lines, 2500, active, previousPrimary = 0))
        assertEquals(1, LyricTimeline.primaryIndex(lines, 2500, active, previousPrimary = -1))
    }

    @Test fun untimedLrcUsesNextLineAsItsEffectiveEnd() {
        val lines = listOf(LyricLine(1000, text = "one"), LyricLine(2000, text = "two"))
        assertEquals(setOf(0), LyricTimeline.activeIndices(lines, 1999))
        assertEquals(setOf(1), LyricTimeline.activeIndices(lines, 2000))
        assertTrue(LyricTimeline.activeIndices(lines, 999).isEmpty())
    }
}
