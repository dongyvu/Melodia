package com.lin0721.linmusic.core.player.domain

object LyricTimeline {
    fun activeIndices(lines: List<LyricLine>, positionMs: Long): Set<Int> = buildSet {
        lines.forEachIndexed { index, line ->
            val fallbackEnd = lines.getOrNull(index + 1)?.timeMs ?: Long.MAX_VALUE
            if (line.isActiveAt(positionMs, fallbackEnd) ||
                line.backgroundLine?.isActiveAt(positionMs, fallbackEnd) == true) add(index)
        }
    }

    fun primaryIndex(
        lines: List<LyricLine>,
        positionMs: Long,
        activeIndices: Set<Int>,
        previousPrimary: Int
    ): Int {
        if (previousPrimary in activeIndices) return previousPrimary
        return activeIndices.maxByOrNull { lines[it].timeMs }
            ?: lines.indexOfLast { it.timeMs <= positionMs }
    }
}

fun LyricLine.isActiveAt(positionMs: Long, fallbackEndMs: Long = Long.MAX_VALUE): Boolean {
    val end = when {
        durationMs > 0 && timeMs <= Long.MAX_VALUE - durationMs -> timeMs + durationMs
        else -> fallbackEndMs
    }
    return positionMs >= timeMs && positionMs < end
}
