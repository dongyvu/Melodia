package com.lin0721.linmusic.core.player.data

import com.lin0721.linmusic.core.player.domain.LyricFormat
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricSource
import com.lin0721.linmusic.core.player.domain.LyricsResult
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LyricsCacheTest {
    private val result = LyricsResult(listOf(LyricLine(0, text = "line")), LyricSource.AMLL, LyricFormat.LINE)

    @Test fun parsedResultsUseTenEntryAccessOrderedLru() {
        val root = Files.createTempDirectory("lyrics-cache").toFile()
        try {
            val cache = LyricsCache(root)
            repeat(10) { cache.putParsed(it.toLong(), true, result) }
            assertNotNull(cache.getParsed(0, true)) // make zero most recently used
            cache.putParsed(10, true, result)
            assertEquals(10, cache.parsedEntryCount())
            assertNull(cache.getParsed(1, true))
            assertNotNull(cache.getParsed(0, true))
            assertNull(cache.getParsed(0, false))
        } finally { root.deleteRecursively() }
    }

    @Test fun rawAndNegativeEntriesExpireAndClear() {
        val root = Files.createTempDirectory("lyrics-cache").toFile()
        var now = 1_000_000L
        try {
            val cache = LyricsCache(root, now = { now }, rawTtlMs = 100, negativeTtlMs = 50)
            cache.putRaw(1, "<tt>raw</tt>")
            cache.putNegative(2)
            assertEquals("<tt>raw</tt>", cache.getRaw(1))
            assertTrue(cache.isNegative(2))
            now += 101
            assertNull(cache.getRaw(1))
            assertFalse(cache.isNegative(2))
            cache.putParsed(1, true, result)
            cache.putRaw(1, "again")
            cache.clear()
            assertEquals(0, cache.parsedEntryCount())
            assertEquals(0L, cache.diskSizeBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun diskCacheEnforcesConfiguredCapacity() {
        val root = Files.createTempDirectory("lyrics-cache").toFile()
        try {
            val cache = LyricsCache(root, maxDiskBytes = 10)
            cache.putRaw(1, "12345678")
            cache.putRaw(2, "abcdefgh")
            assertTrue(cache.diskSizeBytes() <= 10)
        } finally { root.deleteRecursively() }
    }
}
