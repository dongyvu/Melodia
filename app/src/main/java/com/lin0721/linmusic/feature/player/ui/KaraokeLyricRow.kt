package com.lin0721.linmusic.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.domain.LyricLine

private const val TAG = "KaraokeLyricRow"

// 逐字词的物理渲染坐标缓存，避免每帧重复调用 getBoundingBox 的 JNI 开销
private class WordLayout(
    val startMs: Long,
    val endMs: Long,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val lineIndex: Int
)

private class LineLayout(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float
)

private class LyricLayoutInfo(
    val wordLayouts: List<WordLayout>,
    val lineLayouts: List<LineLayout>
)

// 扫色分界处的羽化带总宽度：过大像光晕、过小退化成硬竖线，16.dp 约一个字宽，观感最轻微
private val KaraokeEdgeFeather: Dp = 16.dp

// 每一视觉行的已播范围：clipRight 是实色裁剪右缘（当前演唱行已外扩半个羽化带），
// featherCenterX 非空表示该行是正在演唱的行，渐变中心应对齐到此处
private data class PlayedSpan(
    val clipRight: Float,
    val featherCenterX: Float?
)

// 按播放进度推导每一视觉行的已播右缘与羽化中心。纯计算、无状态读取，可在绘制阶段每帧调用
private fun computePlayedSpans(
    info: LyricLayoutInfo,
    relativeProgress: Long,
    featherHalfPx: Float
): List<PlayedSpan> {
    return info.lineLayouts.mapIndexed { lineIndex, lineLayout ->
        val lastWordOnLine = info.wordLayouts.lastOrNull { it.lineIndex == lineIndex }
        if (lastWordOnLine != null && relativeProgress >= lastWordOnLine.endMs) {
            // 整行已唱完，直接拉满高亮，不需要羽化
            PlayedSpan(clipRight = lineLayout.right, featherCenterX = null)
        } else {
            var maxRight = lineLayout.left
            var featherCenterX: Float? = null
            info.wordLayouts.forEach { word ->
                if (word.lineIndex == lineIndex) {
                    if (relativeProgress >= word.endMs) {
                        maxRight = maxRight.coerceAtLeast(word.right)
                    } else if (relativeProgress in word.startMs..word.endMs) {
                        // 在当前唱到的字词内进行线性像素高亮插值
                        val ratio = if (word.endMs > word.startMs) {
                            (relativeProgress - word.startMs).toFloat() / (word.endMs - word.startMs)
                        } else 1f
                        val edge = word.left + (word.right - word.left) * ratio
                        maxRight = maxRight.coerceAtLeast(edge)
                        featherCenterX = edge
                    }
                }
            }
            if (featherCenterX != null) {
                PlayedSpan(
                    clipRight = (maxRight + featherHalfPx).coerceAtMost(lineLayout.right),
                    featherCenterX = featherCenterX
                )
            } else {
                PlayedSpan(clipRight = maxRight, featherCenterX = null)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 逐字高亮（卡拉OK式）歌词行
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun KaraokeLyricRow(
    line: LyricLine,
    currentPositionProvider: () -> Long,
    inactiveColor: Color,
    activeColor: Color,
    fontSize: TextUnit = 22.sp,
    textAlign: TextAlign = TextAlign.Start,
    fontWeight: FontWeight = FontWeight.ExtraBold,
    isActive: Boolean = true,
    featherWidth: Dp = KaraokeEdgeFeather,
    // 行高必须大于字形实际纵向跨度（升部+降部），让相邻视觉行的行盒互不重叠：
    // 裁剪矩形与羽化带都是按行盒高度作用的，一旦行盒重叠，已播行白字会染到未播行升部、
    // 羽化带会擦掉已播行降部，且矩形裁剪无法在重叠像素上同时满足两行，只能从源头避开
    lineHeight: TextUnit = 30.sp
) {
    var textLayoutResult by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val currentPositionProviderState = rememberUpdatedState(currentPositionProvider)

    // 在排版结果解析后，仅计算并缓存一次每个字词与行的物理渲染坐标，彻底避免每帧重复调用 getBoundingBox 的 JNI 开销
    val lyricLayoutInfo = remember(line, textLayoutResult) {
        val layout = textLayoutResult
        if (layout == null) null else {
            val textLength = line.text.length
            var currentSearchIndex = 0
            val wordRanges = line.words.map { word ->
                val startIndex = line.text.indexOf(word.text, currentSearchIndex)
                if (startIndex != -1) {
                    currentSearchIndex = startIndex + word.text.length
                    startIndex until currentSearchIndex
                } else {
                    val start = currentSearchIndex
                    currentSearchIndex = (currentSearchIndex + word.text.length).coerceAtMost(textLength)
                    start until currentSearchIndex
                }
            }

            val wordLayouts = line.words.mapIndexed { i, word ->
                val range = wordRanges[i]
                val lineIndex = layout.getLineForOffset(range.first)
                val lineTop = layout.getLineTop(lineIndex)
                val lineBottom = layout.getLineBottom(lineIndex)

                val wordLeft = try {
                    layout.getBoundingBox(range.first).left
                } catch (e: Exception) {
                    AppLogger.d(TAG, "歌词字符定位 getBoundingBox 失败，回退 getHorizontalPosition", e)
                    layout.getHorizontalPosition(range.first, true)
                }
                val wordRight = try {
                    layout.getBoundingBox(range.last).right
                } catch (e: Exception) {
                    AppLogger.d(TAG, "歌词字符定位 getBoundingBox 失败，回退 getHorizontalPosition", e)
                    layout.getHorizontalPosition(range.last + 1, true)
                }

                WordLayout(
                    startMs = word.startOffsetMs,
                    endMs = word.startOffsetMs + word.durationMs,
                    left = wordLeft,
                    right = wordRight,
                    top = lineTop,
                    bottom = lineBottom,
                    lineIndex = lineIndex
                )
            }

            val lineLayouts = (0 until layout.lineCount).map { lineIndex ->
                LineLayout(
                    left = layout.getLineLeft(lineIndex),
                    right = layout.getLineRight(lineIndex),
                    top = layout.getLineTop(lineIndex),
                    bottom = layout.getLineBottom(lineIndex)
                )
            }

            LyricLayoutInfo(wordLayouts, lineLayouts)
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        // 底层灰色（未激活）歌词
        Text(
            text = line.text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            color = inactiveColor,
            textAlign = textAlign,
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier.fillMaxWidth()
        )

        // 顶层高亮（已激活）歌词：
        // ·实色部分沿用 Path 硬裁剪（GPU clip，不重绘文本），仅当前演唱行的裁剪右缘外扩半个羽化带
        // ·柔边用 DstIn 横向渐变把羽化带压成 1→0 透明，消除已播/未播之间的硬竖线
        // 进度读取全部发生在绘制阶段（graphicsLayer/draw），不触发重组；
        // 每帧额外开销只有一次 O(字数) 的区间推导加一个小渐变矩形填充
        // 通过 Path 对每一行分别建立独立的裁剪矩形，防止单行歌词折行时产生漏光和干扰
        Text(
            text = line.text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            color = activeColor,
            textAlign = textAlign,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val info = lyricLayoutInfo
                    if (info == null || !isActive) {
                        alpha = 0f
                    } else {
                        alpha = 1f
                        clip = true
                        // 离屏合成，让内层 DstIn 渐变只混合本层文字像素，不会擦除底层的灰色歌词与背景
                        compositingStrategy = CompositingStrategy.Offscreen
                        shape = object : Shape {
                            override fun createOutline(
                                size: Size,
                                layoutDirection: LayoutDirection,
                                density: Density
                            ): Outline {
                                val path = androidx.compose.ui.graphics.Path()
                                val featherHalfPx = with(density) { (featherWidth / 2).toPx() }
                                val relativeProgress = currentPositionProviderState.value() - line.timeMs
                                val spans = computePlayedSpans(info, relativeProgress, featherHalfPx)

                                info.lineLayouts.forEachIndexed { lineIndex, lineLayout ->
                                    val clipRight = spans[lineIndex].clipRight
                                    // 如果当前行存在已播放高亮范围，则将其生成裁剪矩形加入到 Path 中；
                                    // 纵向严格等于行盒：行高已保证行盒互不重叠，任何外扩都会染到邻行字形
                                    if (clipRight > lineLayout.left) {
                                        path.addRect(
                                            Rect(
                                                left = lineLayout.left,
                                                top = lineLayout.top,
                                                right = clipRight.coerceIn(lineLayout.left, lineLayout.right),
                                                bottom = lineLayout.bottom
                                            )
                                        )
                                    }
                                }

                                return Outline.Generic(path)
                            }
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    val info = lyricLayoutInfo
                    if (info != null && isActive) {
                        val featherHalfPx = (featherWidth / 2).toPx()
                        if (featherHalfPx > 0f) {
                            val relativeProgress = currentPositionProviderState.value() - line.timeMs
                            val spans = computePlayedSpans(info, relativeProgress, featherHalfPx)
                            val featherIndex = spans.indexOfFirst { it.featherCenterX != null }
                            if (featherIndex != -1) {
                                val lineLayout = info.lineLayouts[featherIndex]
                                val centerX = spans[featherIndex].featherCenterX!!
                                val left = (centerX - featherHalfPx).coerceAtLeast(lineLayout.left)
                                val right = (centerX + featherHalfPx).coerceAtMost(lineLayout.right)
                                // 羽化带纵向严格等于行盒，禁止外扩：带子是按 X 作用的，
                                // 高出部分扫到下一行时会擦掉已播行的降部（灰尾巴），低了同理
                                if (right > left) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0f to Color.Black,
                                            1f to Color.Transparent,
                                            startX = left,
                                            endX = right
                                        ),
                                        topLeft = Offset(left, lineLayout.top),
                                        size = Size(right - left, lineLayout.bottom - lineLayout.top),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                            }
                        }
                    }
                }
        )
    }
}
