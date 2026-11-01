package app.mizune.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Covers a video while it opens, so its picture, subtitles and lookup come in together rather than
 * one by one. It fades away once [visible] goes false. A quick open shows only the plain cover;
 * one that takes longer gets ripples in the middle.
 */
@Composable
fun LoadingCover(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(Motion.fade), exit = fadeOut(Motion.fade)) {
        var slow by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(RIPPLES_AFTER_MS)
            slow = true
        }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                // taps stay here rather than playing or pausing the video under it
                .pointerInput(Unit) { detectTapGestures {} },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(slow, enter = fadeIn(tween(400))) {
                Ripples(MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Rings spreading out from the middle and fading as they go, like rain on still water. */
@Composable
private fun Ripples(color: Color) {
    val t by rememberInfiniteTransition(label = "ripples").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(RIPPLE_MS, easing = LinearEasing)),
        label = "t",
    )
    Canvas(Modifier.size(64.dp)) {
        val reach = size.minDimension / 2
        repeat(RINGS) { i ->
            val age = (t + i.toFloat() / RINGS) % 1f
            // quick at first, slowing as it spreads
            val spread = 1f - (1f - age) * (1f - age)
            drawCircle(
                color.copy(alpha = (1f - age) * 0.9f),
                radius = reach * spread,
                style = Stroke(width = 1.dp.toPx() + 2.dp.toPx() * (1f - age)),
            )
        }
    }
}

/** A cover lifted sooner than this never shows the ripples, which would only flicker. */
private const val RIPPLES_AFTER_MS = 350L
private const val RIPPLE_MS = 2100
private const val RINGS = 3
