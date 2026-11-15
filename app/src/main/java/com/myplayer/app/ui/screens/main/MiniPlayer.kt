package com.myplayer.app.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.myplayer.app.data.local.entity.SongEntity
import com.myplayer.app.ui.common.AlbumArtImage
import kotlin.math.roundToLong

// Signature Purple-to-Magenta Gradient from MyPlayer
private val MiniPurpleStart = Color(0xFF5D218E)
private val MiniPurpleMid = Color(0xFF831886)
private val MiniMagentaEnd = Color(0xFFA70F7B)
private val MiniThumbCore = Color(0xFF24093A)

private fun lerpDp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t
private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * MyPlayer Signature MiniPlayer with Open/Close Navbar Morphing Support (`collapseProgress` 0f -> 1f):
 * - At `collapseProgress = 0f` (Expanded / Open):
 *   100% identical to your signature Purple-Magenta MiniPlayer:
 *   Protruding 102x70dp top-left Album Art, Title + "by Artist", Ring-Thumb Seekbar (0:59 — 3:22),
 *   and 5 buttons (Heart, Previous, Solid White Play/Pause, Next, Repeat).
 * - As `collapseProgress -> 1f` (Closed / Docked between Left Active Tab Circle & Right Search Circle):
 *   Smoothly tucks into the 58.dp center dock pill between the two circular buttons while keeping
 *   your exact Purple-Magenta gradient, rounded album art, Title/Artist, and White Circle Play/Pause + Next!
 */
@Composable
fun MiniPlayer(
    song: SongEntity,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayerClick: () -> Unit,
    onPreviousClick: () -> Unit = {},
    collapseProgress: Float = 0f,
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = hiltViewModel()
) {
    val t = collapseProgress.coerceIn(0f, 1f)
    val livePositionMs by mainViewModel.currentPosition.collectAsStateWithLifecycle()
    val liveDurationMs by mainViewModel.currentDuration.collectAsStateWithLifecycle()
    val isFavorite by mainViewModel.isFavorite.collectAsStateWithLifecycle()
    val repeatMode by mainViewModel.repeatMode.collectAsStateWithLifecycle()

    val effectiveDurationMs = remember(song.duration, liveDurationMs) {
        when {
            liveDurationMs > 0L -> liveDurationMs
            song.duration > 0L -> song.duration
            else -> 1L
        }
    }
    val progress = (livePositionMs.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)

    var swipeOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = swipeOffsetX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "miniSwipe"
    )

    // Smoothly interpolate geometry from Open (t=0f) to Docked-Between-Orbs (t=1f)
    val topOverhangPadding = lerpDp(16.dp, 0.dp, t)
    val horizontalOuterPad = lerpDp(2.dp, 0.dp, t)
    val cornerRadius = lerpDp(24.dp, 29.dp, t)
    val artWidth = lerpDp(102.dp, 44.dp, t)
    val artHeight = lerpDp(70.dp, 44.dp, t)
    val artStartPad = lerpDp(14.dp, 7.dp, t)
    val artTopPad = lerpDp(0.dp, 7.dp, t)
    val artCorner = lerpDp(18.dp, 14.dp, t)
    val textStartPad = lerpDp(114.dp, 48.dp, t)
    val expandedContentAlpha = (1f - t * 2.2f).coerceIn(0f, 1f)
    val compactControlsAlpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalOuterPad)
            .graphicsLayer {
                translationX = animatedOffsetX * 0.35f
                rotationZ = (animatedOffsetX / 60f).coerceIn(-2.5f, 2.5f)
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        swipeOffsetX = (swipeOffsetX + dragAmount).coerceIn(-220f, 220f)
                    },
                    onDragEnd = {
                        if (swipeOffsetX < -100f) {
                            onNextClick()
                        } else if (swipeOffsetX > 100f) {
                            onPreviousClick()
                        }
                        swipeOffsetX = 0f
                    },
                    onDragCancel = { swipeOffsetX = 0f }
                )
            }
    ) {
        val cardShape = RoundedCornerShape(cornerRadius)

        // 1. Main Purple-Magenta Gradient Card
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topOverhangPadding)
                .shadow(elevation = 14.dp, shape = cardShape, spotColor = Color.Black)
                .clip(cardShape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(MiniPurpleStart, MiniPurpleMid, MiniMagentaEnd)
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = lerpFloat(0.12f, 0.22f, t)),
                    shape = cardShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayerClick
                )
                .padding(
                    top = lerpDp(10.dp, 6.dp, t),
                    bottom = lerpDp(10.dp, 6.dp, t),
                    start = lerpDp(14.dp, 8.dp, t),
                    end = lerpDp(14.dp, 10.dp, t)
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = if (t > 0.6f) Arrangement.Center else Arrangement.Top
            ) {
                // Top Row: Title & "by Artist" + (When Docked t > 0.35f: inline Play/Pause & Next on the right)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = textStartPad),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AnimatedContent(
                        targetState = song.title to song.artist,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                        transitionSpec = {
                            (slideInHorizontally { it / 2 } + fadeIn(tween(200)))
                                .togetherWith(slideOutHorizontally { -it / 2 } + fadeOut(tween(150)))
                        },
                        label = "miniTitleArtistAnim"
                    ) { (title, artist) ->
                        Column(
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.heightIn(min = lerpDp(44.dp, 38.dp, t))
                        ) {
                            Text(
                                text = title.ifBlank { "Unknown Track" },
                                fontSize = lerpFloat(16f, 14f, t).sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                text = buildAnnotatedString {
                                    if (t < 0.5f) {
                                        withStyle(
                                            SpanStyle(
                                                color = Color.White.copy(alpha = 0.80f),
                                                fontWeight = FontWeight.Normal
                                            )
                                        ) {
                                            append("by  ")
                                        }
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = Color.White.copy(alpha = if (t < 0.5f) 1f else 0.85f),
                                            fontWeight = if (t < 0.5f) FontWeight.Bold else FontWeight.Medium
                                        )
                                    ) {
                                        append(artist.ifBlank { "Unknown Artist" })
                                    }
                                },
                                fontSize = lerpFloat(12f, 11.5f, t).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Compact inline Play/Pause + Next controls that smoothly appear when docked between the two circles
                    if (compactControlsAlpha > 0.01f) {
                        Row(
                            modifier = Modifier.alpha(compactControlsAlpha),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .shadow(4.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onPlayPauseClick
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = MiniPurpleMid,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onNextClick
                                    )
                            )
                        }
                    }
                }

                // Bottom Section (Expanded State): Left Interactive Ring-Thumb Seekbar + Right 5 Action Buttons
                if (expandedContentAlpha > 0.01f) {
                    Spacer(Modifier.height(lerpDp(10.dp, 0.dp, t)))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(expandedContentAlpha),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Half: Custom Ring-Thumb Seekbar + Time Labels (0:59 ... 3:22)
                        Column(
                            modifier = Modifier
                                .weight(0.46f)
                                .padding(end = 12.dp)
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(18.dp)
                                    .pointerInput(effectiveDurationMs) {
                                        detectTapGestures { offset ->
                                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            mainViewModel.seekTo((frac * effectiveDurationMs).roundToLong())
                                        }
                                    }
                            ) {
                                val w = size.width
                                val cy = size.height / 2f
                                val thumbX = (progress * w).coerceIn(
                                    7.dp.toPx(),
                                    (w - 7.dp.toPx()).coerceAtLeast(7.dp.toPx())
                                )

                                drawLine(
                                    color = Color.White.copy(alpha = 0.85f),
                                    start = Offset(0f, cy),
                                    end = Offset(w, cy),
                                    strokeWidth = 2.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color.White,
                                    start = Offset(0f, cy),
                                    end = Offset(thumbX, cy),
                                    strokeWidth = 3.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 7.dp.toPx(),
                                    center = Offset(thumbX, cy)
                                )
                                drawCircle(
                                    color = MiniThumbCore,
                                    radius = 4.dp.toPx(),
                                    center = Offset(thumbX, cy)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatMiniPlayerTime(livePositionMs),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.94f)
                                )
                                Text(
                                    text = formatMiniPlayerTime(effectiveDurationMs),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.94f)
                                )
                            }
                        }

                        // Right Half: Heart | Previous | Solid White Circle Play/Pause | Next | Repeat
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { mainViewModel.toggleFavorite() }
                                    )
                            )
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onPreviousClick
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .shadow(4.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onPlayPauseClick
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = MiniPurpleMid,
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onNextClick
                                    )
                            )
                            val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Filled.RepeatOne
                            } else {
                                Icons.Filled.Repeat
                            }
                            Icon(
                                imageVector = repeatIcon,
                                contentDescription = "Repeat",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color.White else Color.White.copy(alpha = 0.78f),
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { mainViewModel.cycleRepeatMode() }
                                    )
                            )
                        }
                    }
                }
            }
        }

        // 2. Protruding Top-Left Rounded Rectangular Album Art (Smoothly scales from 102x70dp overhang -> 44x44dp inset)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = artStartPad, top = artTopPad)
                .size(width = artWidth, height = artHeight)
                .shadow(elevation = lerpDp(10.dp, 4.dp, t), shape = RoundedCornerShape(artCorner))
                .clip(RoundedCornerShape(artCorner))
                .background(Color(0xFF28123D))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(artCorner))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayerClick
                ),
            contentAlignment = Alignment.Center
        ) {
            AlbumArtImage(
                uri = song.albumArt,
                title = song.title,
                artist = song.artist,
                size = artWidth,
                shape = RoundedCornerShape(artCorner),
                iconSize = lerpDp(28.dp, 20.dp, t)
            )
        }
    }
}

private fun formatMiniPlayerTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
