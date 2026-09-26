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
        val prepared = LyricTimeline.prepareLines(lines)
        assertEquals(setOf(0, 1, 2), LyricTimeline.activeIndices(prepared, 2750))
        assertEquals(setOf(2), LyricTimeline.activeIndices(prepared, 4250))
    }

    @Test fun primaryAnchorRemainsStableWhileItIsStillActive() {
        val lines = listOf(LyricLine(1000, 4000, "first"), LyricLine(2000, 4000, "second"))
        val active = LyricTimeline.activeIndices(lines, 2500)
        assertEquals(0, LyricTimeline.primaryIndex(lines, 2500, active, previousPrimary = 0))
        assertEquals(0, LyricTimeline.primaryIndex(lines, 2500, active, previousPrimary = -1))
    }

    @Test fun untimedLrcUsesNextLineAsItsEffectiveEnd() {
        val lines = listOf(LyricLine(1000, text = "one"), LyricLine(2000, text = "two"))
        assertEquals(setOf(0), LyricTimeline.activeIndices(lines, 1999))
        assertEquals(setOf(1), LyricTimeline.activeIndices(lines, 2000))
        assertTrue(LyricTimeline.activeIndices(lines, 999).isEmpty())
        val prepared = LyricTimeline.prepareLines(lines)
        assertEquals(2000, prepared[0].timeMs + prepared[0].durationMs)
        assertEquals(12_000, prepared[1].timeMs + prepared[1].durationMs)
    }

    @Test fun smallOverlapIsClippedButIntentionalOverlapIsPreserved() {
        val small = LyricTimeline.prepareLines(listOf(
            LyricLine(1000, 1200, "first"),
            LyricLine(2100, 2000, "second")
        ))
        assertEquals(2100, small[0].timeMs + small[0].durationMs)
        assertEquals(setOf(1), LyricTimeline.activeIndices(small, 2100))

        val smallRelativeToNext = LyricTimeline.prepareLines(listOf(
            LyricLine(1000, 2000, "first"),
            LyricLine(2850, 3000, "second")
        ))
        assertEquals(2850, smallRelativeToNext[0].timeMs + smallRelativeToNext[0].durationMs)

        val intentional = LyricTimeline.prepareLines(listOf(
            LyricLine(1000, 3000, "first"),
            LyricLine(3500, 1000, "second")
        ))
        assertEquals(4000, intentional[0].timeMs + intentional[0].durationMs)
        assertEquals(setOf(0, 1), LyricTimeline.activeIndices(intentional, 3500))
    }

    @Test fun bufferedOverlapKeepsPreviousLineVisibleUntilGroupEndsOrSeek() {
        val lines = listOf(LyricLine(1000, 3000, "first"), LyricLine(2500, 2500, "second"))
        val first = LyricTimeline.advance(lines, 1500, LyricPlaybackState())
        val overlap = LyricTimeline.advance(lines, 3000, first)
        assertEquals(setOf(0, 1), overlap.hotIndices)
        assertEquals(0, overlap.primaryIndex)

        val afterFirstEnd = LyricTimeline.advance(lines, 4000, overlap)
        assertEquals(setOf(1), afterFirstEnd.hotIndices)
        assertEquals(setOf(0, 1), afterFirstEnd.displayIndices)
        assertEquals(0, afterFirstEnd.primaryIndex)

        val seeked = LyricTimeline.advance(lines, 4000, afterFirstEnd, isSeek = true)
        assertEquals(setOf(1), seeked.displayIndices)
        assertEquals(1, seeked.primaryIndex)
        assertTrue(LyricTimeline.advance(lines, 5000, afterFirstEnd).displayIndices.isEmpty())
    }

    @Test fun advancingLineStartKeepsWordTimesAbsolute() {
        val prepared = LyricTimeline.prepareLines(listOf(
            LyricLine(1000, 1500, "hello", words = listOf(WordInfo("hello", 200, 800)))
        )).single()
        assertEquals(1200, prepared.timeMs + prepared.words.single().startOffsetMs)
        assertEquals(2000, prepared.timeMs + prepared.words.single().startOffsetMs + prepared.words.single().durationMs)
        assertEquals(600, prepared.timeMs)
    }

    @Test fun backgroundExtendsGroupWithoutChangingWordTiming() {
        val prepared = LyricTimeline.prepareLines(listOf(LyricLine(
            1000, 1000, "main", words = listOf(WordInfo("main", 0, 1000)),
            backgroundLine = LyricLine(1500, 2000, "bg", words = listOf(WordInfo("bg", 0, 2000)))
        ))).single()
        assertEquals(3500, prepared.timeMs + prepared.durationMs)
        assertEquals(1000, prepared.timeMs + prepared.words.single().startOffsetMs)
        assertEquals(1500, prepared.backgroundLine!!.timeMs + prepared.backgroundLine.words.single().startOffsetMs)
        assertEquals(setOf(0), LyricTimeline.activeIndices(listOf(prepared), 3400))
    }
}
