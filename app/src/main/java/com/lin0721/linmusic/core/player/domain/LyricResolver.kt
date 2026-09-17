package com.lin0721.linmusic.core.player.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

enum class LyricSource { AMLL, NETEASE, NONE }
enum class LyricFormat { WORD, LINE, EMPTY }
data class LyricsResult(
    val lines: List<LyricLine>,
    val source: LyricSource,
    val format: LyricFormat
)

class LyricResolver {
    private companion object {
        // Real devices can need slightly over 1.2 s for TLS plus XML parsing.
        const val INITIAL_SELECTION_WINDOW_MS = 2500L
    }

    suspend fun resolve(
        amll: suspend () -> List<LyricLine>,
        netease: suspend () -> List<LyricLine>
    ): LyricsResult = coroutineScope {
        suspend fun safe(timeout: Long, source: suspend () -> List<LyricLine>): List<LyricLine> =
            try { withTimeoutOrNull(timeout) { source() } ?: emptyList() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }

        val a = async { safe(4000, amll) }
        val n = async { safe(5000, netease) }
        try {
            // Commit once. A slow mirror must not replace lyrics during playback.
            withTimeoutOrNull(INITIAL_SELECTION_WINDOW_MS) { a.await(); n.await() }
            val early = choose(if (a.isCompleted) a.await() else emptyList(),
                if (n.isCompleted) n.await() else emptyList())
            if (early.lines.isNotEmpty()) early else choose(a.await(), n.await())
        } finally { a.cancel(); n.cancel() }
    }

    internal fun choose(amll: List<LyricLine>, netease: List<LyricLine>): LyricsResult {
        fun cleanLine(line: LyricLine): LyricLine {
            val valid = line.words.isNotEmpty() && line.words.any { it.durationMs > 0 } &&
                line.words.all { it.startOffsetMs >= 0 && it.durationMs >= 0 &&
                    it.startOffsetMs <= line.durationMs && it.durationMs <= line.durationMs - it.startOffsetMs } &&
                line.words.zipWithNext().all { (a, b) -> a.startOffsetMs <= b.startOffsetMs } &&
                line.words.joinToString("") { it.text } == line.text
            return line.copy(
                words = if (valid) line.words else emptyList(),
                backgroundLine = line.backgroundLine?.takeIf { it.timeMs >= 0 && it.text.isNotBlank() }?.let(::cleanLine)
            )
        }
        fun clean(lines: List<LyricLine>) = lines.filter { it.timeMs >= 0 && it.text.isNotBlank() }
            .map(::cleanLine).sortedBy { it.timeMs }
        val a = clean(amll)
        val n = clean(netease)
        val (lines, source) = when {
            a.any { it.words.isNotEmpty() } -> a to LyricSource.AMLL
            n.any { it.words.isNotEmpty() } -> n to LyricSource.NETEASE
            a.isNotEmpty() -> a to LyricSource.AMLL
            n.isNotEmpty() -> n to LyricSource.NETEASE
            else -> emptyList<LyricLine>() to LyricSource.NONE
        }
        return LyricsResult(lines, source, when {
            lines.isEmpty() -> LyricFormat.EMPTY
            lines.any { it.words.isNotEmpty() } -> LyricFormat.WORD
            else -> LyricFormat.LINE
        })
    }
}
