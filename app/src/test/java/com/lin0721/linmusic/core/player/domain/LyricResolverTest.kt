package com.lin0721.linmusic.core.player.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricResolverTest {
    private val resolver = LyricResolver()
    private val line = LyricLine(1000, 1000, "hello")
    private val word = line.copy(words = listOf(WordInfo("hello", 0, 1000)))

    @Test fun priorityMatrix() {
        assertEquals(LyricSource.AMLL, resolver.choose(listOf(word), listOf(word)).source)
        assertEquals(LyricSource.NETEASE, resolver.choose(listOf(line), listOf(word)).source)
        assertEquals(LyricSource.AMLL, resolver.choose(listOf(line), listOf(line)).source)
        assertEquals(LyricSource.NETEASE, resolver.choose(emptyList(), listOf(line)).source)
        assertEquals(LyricFormat.EMPTY, resolver.choose(emptyList(), emptyList()).format)
        assertEquals(LyricSource.NETEASE, resolver.choose(listOf(word.copy(words = listOf(WordInfo("hello", -1, 1000)))), listOf(word)).source)
    }

    @Test fun failedAmllFallsBack() = runTest {
        val result = resolver.resolve({ error("500") }, { listOf(word) })
        assertEquals(LyricSource.NETEASE, result.source)
    }

    @Test fun slowMirrorDoesNotDelayOrReplaceNetease() = runTest {
        var cancelled = false
        val result = resolver.resolve({ try { delay(3000); listOf(word) } finally { cancelled = true } }, { listOf(line) })
        assertEquals(2500L, currentTime)
        assertEquals(LyricSource.NETEASE, result.source)
        assertTrue(cancelled)
    }

    @Test fun slowNeteaseDoesNotBlockAmll() = runTest {
        assertEquals(LyricSource.AMLL, resolver.resolve({ listOf(word) }, { awaitCancellation() }).source)
        assertEquals(2500L, currentTime)
    }

    @Test fun waitsForLyricsIfNeitherReadyAndBoundsBothFailures() = runTest {
        assertEquals(LyricSource.AMLL, resolver.resolve({ delay(2800); listOf(word) }, { emptyList() }).source)
        assertEquals(2800L, currentTime)
        assertEquals(LyricFormat.EMPTY, resolver.resolve({ awaitCancellation() }, { awaitCancellation() }).format)
        assertEquals(7800L, currentTime)
    }

    @Test fun switchingSongsCancelsBothSourcesWithoutPublishingOldLyrics() = runTest {
        var cancelled = 0
        var published = false
        val job = launch {
            resolver.resolve(
                { try { awaitCancellation() } finally { cancelled++ } },
                { try { awaitCancellation() } finally { cancelled++ } }
            )
            published = true
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(2, cancelled)
        assertFalse(published)
    }
}
