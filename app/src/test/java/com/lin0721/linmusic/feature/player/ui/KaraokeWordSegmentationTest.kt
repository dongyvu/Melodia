package com.lin0721.linmusic.feature.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeWordSegmentationTest {
    @Test fun wrappedWordKeepsEachSegmentOnItsOwnVisualLine() {
        val segments = splitTimedWordAcrossLines(
            startMs = 100,
            durationMs = 900,
            characters = listOf(
                KaraokeCharacterBox(0, 250f, 270f),
                KaraokeCharacterBox(0, 270f, 290f),
                KaraokeCharacterBox(1, 20f, 40f)
            )
        )

        assertEquals(2, segments.size)
        assertEquals(0, segments[0].lineIndex)
        assertEquals(250f, segments[0].left)
        assertEquals(290f, segments[0].right)
        assertEquals(1, segments[1].lineIndex)
        assertEquals(20f, segments[1].left)
        assertEquals(40f, segments[1].right)
        assertEquals(100L, segments[0].startMs)
        assertEquals(700L, segments[0].endMs)
        assertEquals(700L, segments[1].startMs)
        assertEquals(1000L, segments[1].endMs)
        assertTrue(segments.all { it.right >= it.left })
    }
}
