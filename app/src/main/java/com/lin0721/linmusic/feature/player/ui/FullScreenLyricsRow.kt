package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricAlignment
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MelodiaPress
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing

private const val MAX_LYRIC_SCALE = 1.15f
private const val SCALED_LYRIC_WIDTH_FRACTION = 1f / MAX_LYRIC_SCALE

// 歌词单行：按距当前行的远近做缩放与透明度递减，当前行走逐字扫色，可选附带翻译副行
// 缩放/透明度动画值只在 graphicsLayer 块内读取，变化时仅刷新绘制阶段
@Composable
fun FullScreenLyricsRow(
    index: Int,
    line: LyricLine,
    isCurrent: Boolean,
    isCenterTarget: Boolean,
    distance: Int,
    highlightColor: Color,
    currentPositionProvider: () -> Long,
    onClick: () -> Unit
) {
    val textAlign = if (line.alignment == LyricAlignment.END) TextAlign.End else TextAlign.Start
    val horizontalAlignment = if (line.alignment == LyricAlignment.END) Alignment.End else Alignment.Start
    val targetScale = if (isCurrent) MAX_LYRIC_SCALE
                      else if (isCenterTarget) 1.05f
                      else (1f - distance * 0.05f).coerceAtLeast(0.82f)
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "fs_lyric_scale_$index"
    )

    val targetAlpha = if (isCurrent) 1f
                      else if (isCenterTarget) 0.85f
                      else (0.65f - distance * 0.08f).coerceAtLeast(0.2f)
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(250),
        label = "fs_lyric_alpha_$index"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MelodiaSpacing.lg),
        contentAlignment = if (line.alignment == LyricAlignment.END) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                // graphicsLayer scales after layout. Reserve the maximum scale here so
                // active long lines wrap before either edge can leave the viewport.
                .fillMaxWidth(SCALED_LYRIC_WIDTH_FRACTION)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    alpha = animatedAlpha
                    transformOrigin = TransformOrigin(if (line.alignment == LyricAlignment.END) 1f else 0f, 0.5f)
                }
                .pressable(MelodiaPress.None) { onClick() },
            horizontalAlignment = horizontalAlignment
        ) {
            if (line.words.isNotEmpty()) {
                KaraokeLyricRow(line, currentPositionProvider, Color.White.copy(alpha = 0.35f), Color.White, 22.sp, textAlign, isActive = isCurrent)
            } else {
                Text(line.text, fontSize = 22.sp, lineHeight = 30.sp, color = if (isCurrent) Color.White else highlightColor, fontWeight = FontWeight.ExtraBold, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
            }
            line.translation?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
            }
            line.romanization?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), textAlign = textAlign, modifier = Modifier.fillMaxWidth())
            }
            line.backgroundLine?.let { background ->
                val backgroundAlign = if (background.alignment == LyricAlignment.END) TextAlign.End else TextAlign.Start
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth(0.9f), horizontalAlignment = if (background.alignment == LyricAlignment.END) Alignment.End else Alignment.Start) {
                    if (background.words.isNotEmpty()) {
                        KaraokeLyricRow(background, currentPositionProvider, Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.82f), 17.sp, backgroundAlign, FontWeight.Bold, isCurrent, lineHeight = 24.sp)
                    } else {
                        Text(background.text, fontSize = 17.sp, lineHeight = 24.sp, color = if (isCurrent) Color.White.copy(alpha = 0.82f) else highlightColor.copy(alpha = 0.72f), fontWeight = FontWeight.Bold, textAlign = backgroundAlign, modifier = Modifier.fillMaxWidth())
                    }
                    background.translation?.let {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), textAlign = backgroundAlign, modifier = Modifier.fillMaxWidth())
                    }
                    background.romanization?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), textAlign = backgroundAlign, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
