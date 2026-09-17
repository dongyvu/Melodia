package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing
import com.lin0721.linmusic.core.ui.theme.InfoCardRadius
import com.lin0721.linmusic.core.ui.theme.darken
import com.lin0721.linmusic.core.ui.theme.lighten
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricAlignment
import com.lin0721.linmusic.core.player.domain.lyricLineKey

// 卡片尺寸比全屏背景小得多，模糊半径按比例调小，避免整块糊成一片看不出光斑层次
private val LYRICS_CARD_BLUR_RADIUS = 32.dp

// ────────────────────────────────────────────────────────────────────────────
// 折叠播放页的歌词预览卡（流体光雾背景 + 自动滚动预览列表）
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun LyricsCard(
    lyrics: List<LyricLine>,
    sourceLabel: String? = null,
    currentIndex: Int,
    activeIndices: Set<Int>,
    isLoading: Boolean,
    base: Color,
    highlightColor: Color,
    onOpenFullScreen: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_mesh")

    // 左上角光斑动画
    val lightCenterX by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "light_x"
    )
    val lightCenterY by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "light_y"
    )
    val lightRadiusScale by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "light_radius"
    )

    // 右下角光斑动画
    val darkCenterX by infiniteTransition.animateFloat(
        initialValue = 1.20f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dark_x"
    )
    val darkCenterY by infiniteTransition.animateFloat(
        initialValue = 1.20f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(13000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dark_y"
    )
    val darkRadiusScale by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dark_radius"
    )

    val fillColor = remember(base) { base.darken(0.35f) }
    val lightBlob = remember(base) { base.lighten(0.05f) }
    val darkBlob = remember(base) { base.darken(0.15f) }

    val cardWidth = (LocalConfiguration.current.screenWidthDp - 32).dp
    val cardHeight = cardWidth * 0.88f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MelodiaSpacing.md, vertical = MelodiaSpacing.sm)
            .height(cardHeight)
            .clip(RoundedCornerShape(InfoCardRadius))
            .clickable(onClick = onOpenFullScreen)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(LYRICS_CARD_BLUR_RADIUS)
                .drawBehind {
                    val baseSize = size.minDimension
                    drawSingleHueMesh(
                        fill = fillColor,
                        lightBlob = lightBlob,
                        lightCenter = Offset(size.width * lightCenterX, size.height * lightCenterY),
                        lightRadius = baseSize * lightRadiusScale,
                        darkBlob = darkBlob,
                        darkCenter = Offset(size.width * darkCenterX, size.height * darkCenterY),
                        darkRadius = baseSize * darkRadiusScale
                    )
                }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("歌词", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    sourceLabel?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                Icon(
                    Icons.Rounded.OpenInFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onOpenFullScreen() }
                        .padding(MelodiaSpacing.xs)
                )
            }

            Spacer(modifier = Modifier.height(MelodiaSpacing.md))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                LyricsPreview(
                    lyrics = lyrics,
                    currentIndex = currentIndex,
                    activeIndices = activeIndices,
                    highlightColor = highlightColor
                )
            }
        }
    }
}

@Composable
fun LyricsPreview(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    activeIndices: Set<Int>,
    highlightColor: Color
) {
    val itemSpacingDp = 14.dp
    val itemHeightNoDpEst    = 36.dp
    val itemHeightWithTransDpEst = 56.dp
    val itemStrideDp      = itemHeightNoDpEst + itemSpacingDp

    val density       = LocalDensity.current
    val lazyListState = rememberLazyListState()
    var cardHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentIndex) {
        if (currentIndex < 0 || currentIndex >= lyrics.size) return@LaunchedEffect

        val cardHeightDp = with(density) { cardHeightPx.toDp() }
        val linesAboveCentre = (cardHeightDp / 2 / itemStrideDp).toInt()

        if (currentIndex < linesAboveCentre) {
            lazyListState.animateScrollToItem(index = 0, scrollOffset = 0)
            return@LaunchedEffect
        }
        val currentLine = lyrics[currentIndex]
        val hasTranslation = currentLine.translation != null || currentLine.romanization != null || currentLine.backgroundLine != null
        val itemHeightPx     = with(density) {
            if (hasTranslation) itemHeightWithTransDpEst.toPx() else itemHeightNoDpEst.toPx()
        }
        val centreOffsetPx = -(((cardHeightPx - itemHeightPx) / 2f).toInt())
        lazyListState.animateScrollToItem(
            index       = currentIndex,
            scrollOffset = centreOffsetPx
        )
    }

    LazyColumn(
        state   = lazyListState,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onSizeChanged { cardHeightPx = it.height.toFloat() },
        verticalArrangement = Arrangement.spacedBy(itemSpacingDp),
        userScrollEnabled   = false,
        contentPadding = PaddingValues(top = 0.dp, bottom = with(density) { (cardHeightPx / 2).toDp() })
    ) {
        itemsIndexed(items = lyrics, key = ::lyricLineKey) { index, line ->
            val isCurrent = index in activeIndices
            val distance  = kotlin.math.abs(index - currentIndex).coerceAtMost(4)
            val textAlign = if (line.alignment == LyricAlignment.END) TextAlign.End else TextAlign.Start
            val horizontalAlignment = if (line.alignment == LyricAlignment.END) Alignment.End else Alignment.Start

            val targetScale = if (isCurrent) 1.15f
                              else (1f - distance * 0.07f).coerceAtLeast(0.85f)
            val animatedScale by animateFloatAsState(
                targetValue   = targetScale,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                label         = "lyric_scale_$index"
            )

            val targetAlpha = if (isCurrent) 1f
                              else (0.55f - distance * 0.1f).coerceAtLeast(0.2f)
            val animatedAlpha by animateFloatAsState(
                targetValue   = targetAlpha,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label         = "lyric_alpha_$index"
            )

            val targetTransAlpha = 0.65f
            val animatedTransAlpha by animateFloatAsState(
                targetValue   = targetTransAlpha,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label         = "lyric_trans_alpha_$index"
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        alpha  = animatedAlpha
                        transformOrigin = TransformOrigin(if (line.alignment == LyricAlignment.END) 1f else 0f, 0.5f)
                    },
                horizontalAlignment = horizontalAlignment
            ) {
                Text(
                    text       = line.text,
                    fontSize   = 20.sp,
                    color      = if (isCurrent) Color.White else highlightColor,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign  = textAlign,
                    modifier   = Modifier.fillMaxWidth(0.88f)
                )
                if (line.translation != null) {
                    Spacer(modifier = Modifier.height(MelodiaSpacing.xs))
                    Text(
                        text      = line.translation,
                        fontSize  = 15.sp,
                        color     = Color.White.copy(alpha = animatedTransAlpha),
                        textAlign = textAlign,
                        modifier  = Modifier.fillMaxWidth(0.88f)
                    )
                }
                line.romanization?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = animatedTransAlpha * 0.9f), textAlign = textAlign, modifier = Modifier.fillMaxWidth(0.88f))
                }
                line.backgroundLine?.let { background ->
                    Spacer(modifier = Modifier.height(MelodiaSpacing.xs))
                    Text(
                        text = background.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCurrent) Color.White.copy(alpha = 0.8f) else highlightColor.copy(alpha = 0.65f),
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth(0.78f)
                    )
                    background.translation?.let {
                        Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), textAlign = textAlign, modifier = Modifier.fillMaxWidth(0.78f))
                    }
                    background.romanization?.let {
                        Text(it, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), textAlign = textAlign, modifier = Modifier.fillMaxWidth(0.78f))
                    }
                }
            }
        }
    }
}
