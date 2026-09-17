package com.lin0721.linmusic.core.player.data

import com.lin0721.linmusic.core.player.domain.LyricsResult
import java.io.File
import java.util.LinkedHashMap

/** Parsed-result LRU plus the on-disk AMLL raw/404 cache. */
class LyricsCache(
    cacheRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val rawTtlMs: Long = RAW_TTL_MS,
    private val negativeTtlMs: Long = NEGATIVE_TTL_MS,
    private val maxDiskBytes: Long = MAX_DISK_BYTES
) {
    private data class MemoryKey(val songId: Long, val preferAmll: Boolean)

    private val directory = File(cacheRoot, "lyrics/amll")
    private val memory = object : LinkedHashMap<MemoryKey, LyricsResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MemoryKey, LyricsResult>?): Boolean =
            size > MEMORY_ENTRIES
    }

    @Synchronized
    fun getParsed(songId: Long, preferAmll: Boolean): LyricsResult? = memory[MemoryKey(songId, preferAmll)]

    @Synchronized
    fun putParsed(songId: Long, preferAmll: Boolean, result: LyricsResult) {
        memory[MemoryKey(songId, preferAmll)] = result
    }

    @Synchronized
    fun getRaw(songId: Long): String? {
        val file = rawFile(songId)
        if (!file.isFile) return null
        if (now() - file.lastModified() > rawTtlMs) {
            file.delete()
            return null
        }
        return runCatching { file.readText() }.getOrNull()?.takeIf(String::isNotBlank)
    }

    @Synchronized
    fun putRaw(songId: Long, xml: String) {
        if (songId <= 0 || xml.isBlank()) return
        directory.mkdirs()
        val target = rawFile(songId)
        val temporary = File(directory, "$songId.ttml.tmp")
        runCatching {
            temporary.writeText(xml)
            if (target.exists()) target.delete()
            check(temporary.renameTo(target))
            target.setLastModified(now())
            negativeFile(songId).delete()
            trimDisk()
        }.onFailure { temporary.delete() }
    }

    @Synchronized
    fun isNegative(songId: Long): Boolean {
        val file = negativeFile(songId)
        if (!file.isFile) return false
        if (now() - file.lastModified() > negativeTtlMs) {
            file.delete()
            return false
        }
        return true
    }

    @Synchronized
    fun putNegative(songId: Long) {
        if (songId <= 0) return
        directory.mkdirs()
        runCatching {
            negativeFile(songId).apply {
                writeText("")
                setLastModified(now())
            }
        }
    }

    @Synchronized
    fun removeRaw(songId: Long) {
        rawFile(songId).delete()
    }

    @Synchronized
    fun clear() {
        memory.clear()
        directory.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun diskSizeBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    internal fun parsedEntryCount(): Int = memory.size

    private fun rawFile(songId: Long) = File(directory, "$songId.ttml")
    private fun negativeFile(songId: Long) = File(directory, "$songId.miss")

    private fun trimDisk() {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= maxDiskBytes) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }

    companion object {
        const val MEMORY_ENTRIES = 10
        const val RAW_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val NEGATIVE_TTL_MS = 12L * 60 * 60 * 1000
        const val MAX_DISK_BYTES = 75L * 1024 * 1024
    }
}
