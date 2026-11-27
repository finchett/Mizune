package app.mizune.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.tan

// Kaisei (快晴, a clear sky): the summer scene the tutorial's video shows, as the browser
// extension's previews have it (extension/src/scene.ts): a girl in a straw hat on a hill above the
// sea. Drawn here from the same SVG, in its own units (400 by 300), and moving as its CSS does.

/** Seconds of the scene's motion, which stop going by while [paused]. */
@Composable
fun rememberSceneTime(paused: Boolean): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time.floatValue += (now - last) / 1e9f
                last = now
            }
        }
    }
    return time
}

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Where a looping move is, 0 to 1, [delay] seconds in (negative: started that long ago). */
private fun loop(time: Float, seconds: Float, delay: Float = 0f): Float {
    val at = (time - delay) / seconds
    return at - floor(at)
}

/** Where a move that goes there and back is, 0 to 1 and eased, as CSS's alternate. */
private fun swing(time: Float, seconds: Float): Float {
    val at = time / seconds
    val leg = floor(at).toInt()
    val f = at - leg
    return EaseInOut.transform(if (leg % 2 == 0) f else 1 - f)
}

private fun lerp(from: Float, to: Float, f: Float) = from + (to - from) * f

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

private fun hex(rgb: Long) = Color(0xFF000000 or rgb)

private object Shapes {
    val sky = path("M0 0h400v182H0z")
    val sea = path("M0 180h400v120H0z")
    val boat = path("M1 190v15h9zM-1 193v12h-7zM-9 206h21l-3 4h-15z")
    val glints1 = path("M324 186h10v1h-10zM316 196h14v1.3h-14zM332 207h16v1.6h-16z")
    val glints2 = path("M329 191h8v1h-8zM328 201h13v1.4h-13zM314 214h18v1.8h-18z")
    val hill = path("M0 216C90 192 190 196 260 226S360 262 400 256V300H0Z")
    val road = path("M166 203C150 228 120 262 95 300H170C176 262 180 228 180 204Z")
    val poleShadow = path("M297 243l6 1-41 24-5-3z")
    val wires = path("M278 108Q180 62 90-10M322 108Q230 56 150-10M278 108Q350 166 410 172M322 108Q372 150 410 152")
    val leftWing = path("M0 0q-5-4-10-1")
    val rightWing = path("M0 0q5-4 10-1")

    // the girl, in her own units: her feet at 0, 0
    val legBack = path("M-6-27h3.4v26H-6z")
    val legFront = path("M2.6-27H6v26H2.6z")
    val shoes = path("M-6.5-2.5h4.4V0h-4.4zM2.1-2.5h4.4V0H2.1z")
    val arms = path("M-8-60Q-12-48-11-38M8-60Q12-48 11-38")
    val dress = path("M-8-62C-10-50-15-36-17-27Q0-23 17-27C15-36 10-50 8-62Z")
    val dressShade = path("M-8-62C-10-50-15-36-17-27Q-9-25-4-24.5C-5-40-5-52-3-62Z")
    val neck = path("M-1.8-66h3.6v5h-3.6z")
    val strand = path("M6-66C12-64 16-60 22-61 16-57 11-58 6-59Z")
    val hair = path("M-8.5-73C-9-64-8-60-6-57H6C9-61 10-66 8.5-73Z")
    val crown = path("M-8-77C-8-86 8-86 8-77Z")
    val band = path("M-8-80H8v2.6H-8z")
    val ribbon = path("M8-80C15-83 22-77 30-80L29-75C21-73 15-78 8-77Z")

    // grass in front, drawn 25 higher than its path says
    val grassDark = path("M6 300Q10 276 20 258 18 280 16 300ZM30 300Q32 282 26 268 38 282 40 300ZM350 300Q356 274 368 254 364 280 362 300ZM376 300Q380 280 392 266 388 284 388 300Z")
    val grassLight = path("M16 300Q22 282 34 272 28 286 27 300ZM362 300Q366 284 360 270 372 284 374 300Z")

    /** The big cloud's puffs: x, y, radius. */
    val cloud = listOf(
        Triple(60f, 172f, 26f), Triple(95f, 160f, 34f), Triple(140f, 158f, 36f), Triple(182f, 166f, 28f), Triple(85f, 128f, 30f),
        Triple(125f, 118f, 36f), Triple(165f, 132f, 26f), Triple(105f, 88f, 26f), Triple(135f, 80f, 24f), Triple(120f, 62f, 18f),
    )
    val sparkles = listOf(
        Triple(40f, 250f, 3f) to 0xffffff, Triple(92f, 232f, 2.4f) to 0xfff3a6, Triple(68f, 258f, 3.2f) to 0xffc9dc,
        Triple(232f, 238f, 2.4f) to 0xffffff, Triple(238f, 270f, 3f) to 0xffffff, Triple(345f, 262f, 3.2f) to 0xfff3a6,
        Triple(372f, 266f, 2.4f) to 0xffffff,
    )
}

/** A skew along x by [degrees], about ([x], [y]), as CSS's skewX with that transform-origin. */
private fun DrawScope.skewed(degrees: Float, x: Float, y: Float, draw: DrawScope.() -> Unit) {
    val skew = Matrix().apply { values[Matrix.SkewX] = tan(degrees * PI.toFloat() / 180) }
    withTransform({
        translate(x, y)
        transform(skew)
        translate(-x, -y)
    }, draw)
}

/**
 * The scene, its 400 by 300 drawn from ([left], [top]) at [width] wide, [time] seconds into its
 * motion (see [rememberSceneTime]). What's outside the scene's box is the caller's to clip.
 */
fun DrawScope.drawSummerScene(time: Float, left: Float, top: Float, width: Float) {
    val s = Shapes
    translate(left, top) {
        scale(width / 400f, Offset.Zero) {
            // sky, sun
            drawPath(s.sky, Brush.verticalGradient(0f to hex(0x1450c2), 0.55f to hex(0x58aaf0), 1f to hex(0xc8ecfb), startY = 0f, endY = 182f))
            drawCircle(
                Brush.radialGradient(
                    0f to hex(0xfffdf0), 0.2f to hex(0xfff8d8).copy(alpha = 0.8f), 0.45f to hex(0xfff1bf).copy(alpha = 0.25f), 1f to hex(0xfff1bf).copy(alpha = 0f),
                    center = Offset(330f, 52f), radius = 70f,
                ),
                radius = 70f, center = Offset(330f, 52f),
            )
            drawCircle(hex(0xfffef6), 12f, Offset(330f, 52f))

            // two small clouds drifting across, the second smaller and further off
            translate(lerp(-60f, 450f, loop(time, 22f)), 72f) { smallCloud(wide = false) }
            translate(lerp(-60f, 450f, loop(time, 34f, -14f)), 0f) {
                scale(0.7f, Offset.Zero) { translate(0f, 150f) { smallCloud(wide = true) } }
            }

            // the big cloud, its shade under it, and little ones on the horizon
            for ((x, y, r) in s.cloud) drawCircle(hex(0xb7c4ec), r, Offset(x - 30, y))
            for ((x, y, r) in s.cloud) drawCircle(Color.White, r, Offset(x - 25, y - 6))
            drawCircle(Color.White, 13f, Offset(300f, 178f))
            drawCircle(Color.White, 17f, Offset(321f, 171f))
            drawCircle(Color.White, 11f, Offset(344f, 179f))

            // three birds flying up and across, flapping
            val flight = loop(time, 9f)
            translate(lerp(-30f, 430f, flight), lerp(125f, 55f, flight)) {
                val flap = swing(time, 0.3f)
                val wing = Stroke(1.6f, cap = StrokeCap.Round)
                for (bird in listOf(Offset(0f, 0f), Offset(16f, 8f), Offset(10f, -10f))) {
                    rotate(lerp(38f, -28f, flap), bird) { translate(bird.x, bird.y) { drawPath(s.leftWing, hex(0x1f3a57), style = wing) } }
                    rotate(lerp(-38f, 28f, flap), bird) { translate(bird.x, bird.y) { drawPath(s.rightWing, hex(0x1f3a57), style = wing) } }
                }
            }

            // the sea, its waves sliding along, a boat, the horizon and the sun's glints
            drawPath(s.sea, Brush.verticalGradient(listOf(hex(0x1b5fc0), hex(0x2aa0d4)), startY = 180f, endY = 300f))
            data class Wave(val y: Float, val width: Float, val dash: Float, val gap: Float, val alpha: Float, val by: Float, val seconds: Float)
            for (w in listOf(
                Wave(187f, 0.9f, 8f, 30f, 0.5f, 38f, 6f), Wave(196f, 1.2f, 14f, 42f, 0.45f, -56f, 5f),
                Wave(208f, 1.5f, 22f, 60f, 0.4f, 82f, 4.5f), Wave(222f, 1.8f, 30f, 80f, 0.35f, -110f, 4f),
            )) {
                val x = w.by * loop(time, w.seconds)
                drawLine(
                    Color.White.copy(alpha = w.alpha), Offset(-120f + x, w.y), Offset(520f + x, w.y), w.width,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(w.dash, w.gap)),
                )
            }
            translate(lerp(450f, -60f, loop(time, 26f, -9f)), 0f) { drawPath(s.boat, Color.White) }
            drawLine(hex(0xdff3ff), Offset(0f, 180f), Offset(400f, 180f), 0.8f)
            drawPath(s.glints1, Color.White.copy(alpha = lerp(1f, 0.15f, swing(time, 1.7f))))
            drawPath(s.glints2, Color.White.copy(alpha = lerp(0.15f, 1f, swing(time, 2.3f))))

            // the hill, its path, and shadows
            val hill = s.hill.getBounds()
            drawPath(s.hill, Brush.verticalGradient(listOf(hex(0xb2e05a), hex(0x4aa648)), startY = hill.top, endY = hill.bottom))
            drawPath(s.road, hex(0xe8dcae))
            val shadow = hex(0x0f3f4a).copy(alpha = 0.22f)
            rotate(-4f, Offset(176f, 211f)) { drawOval(shadow, Offset(146f, 208f), Size(60f, 6f)) }
            drawPath(s.poleShadow, shadow)

            // the pole and its wires
            val ink = hex(0x232c44)
            drawPath(s.wires, ink.copy(alpha = 0.8f), style = Stroke(1.2f, cap = StrokeCap.Round))
            drawLine(ink, Offset(300f, 96f), Offset(300f, 244f), 5f)
            drawLine(ink, Offset(276f, 108f), Offset(324f, 108f), 3f, cap = StrokeCap.Round)

            for ((spot, rgb) in s.sparkles) drawCircle(hex(rgb.toLong()), spot.third, Offset(spot.first, spot.second))

            translate(206f, 209f) { scale(0.8f, Offset.Zero) { girl(time) } }

            // grass in front, swaying
            skewed(lerp(-5f, 9f, swing(time, 1.8f)), 199f, 275f) {
                translate(0f, -25f) {
                    drawPath(s.grassDark, hex(0x2f8a45))
                    drawPath(s.grassLight, hex(0x8fd24a))
                }
            }
        }
    }
}

/** A small flat cloud with a shaded underside; [wide], the longer of the two. */
private fun DrawScope.smallCloud(wide: Boolean) {
    val shade = hex(0xc3cff0)
    if (!wide) {
        drawRoundRect(Color.White, Offset(-8f, -4f), Size(56f, 10f), CornerRadius(5f))
        for ((x, y, r) in listOf(Triple(2f, -4f, 8f), Triple(16f, -10f, 13f), Triple(32f, -7f, 10f), Triple(42f, -2f, 6f))) drawCircle(Color.White, r, Offset(x, y))
        drawRoundRect(shade, Offset(-3f, 2.5f), Size(46f, 3.5f), CornerRadius(1.75f))
    } else {
        drawRoundRect(Color.White, Offset(-10f, -3f), Size(74f, 9f), CornerRadius(4.5f))
        for ((x, y, r) in listOf(Triple(0f, -3f, 7f), Triple(14f, -8f, 11f), Triple(31f, -13f, 14f), Triple(47f, -6f, 10f), Triple(57f, -2f, 6f))) {
            drawCircle(Color.White, r, Offset(x, y))
        }
        drawRoundRect(shade, Offset(-4f, 2.5f), Size(62f, 3.5f), CornerRadius(1.75f))
    }
}

/** The girl, her feet at 0, 0: her dress, hair and hat ribbon blowing in the wind. */
private fun DrawScope.girl(time: Float) {
    val s = Shapes
    drawPath(s.legBack, hex(0xefcdb5))
    drawPath(s.legFront, hex(0xe5bea4))
    drawPath(s.shoes, hex(0x7a4b33))
    drawPath(s.arms, hex(0xefcdb5), style = Stroke(2.6f, cap = StrokeCap.Round))
    // the dress leans from its shoulders
    skewed(lerp(-5f, 9f, swing(time, 1.4f)), 0f, -62f) {
        drawPath(s.dress, hex(0xfdfeff))
        drawPath(s.dressShade, hex(0xd3e1f1))
    }
    drawPath(s.neck, hex(0xe5bea4))
    val hairColor = hex(0x2e2433)
    // a strand of hair, and the ribbon, turn about where they start
    rotate(lerp(-14f, 12f, swing(time, 1.1f)), Offset(6f, -62.5f)) { drawPath(s.strand, hairColor) }
    drawCircle(hairColor, 8.5f, Offset(0f, -72f))
    drawPath(s.hair, hairColor)
    drawOval(hex(0xc9a45e), Offset(-16f, -80.6f), Size(32f, 8.4f))
    drawOval(hex(0xf1d18a), Offset(-16f, -81.2f), Size(32f, 8f))
    drawPath(s.crown, hex(0xf5dc9c))
    drawPath(s.band, hex(0xe0483e))
    rotate(lerp(-14f, 12f, swing(time, 0.7f)), Offset(8f, -78f)) { drawPath(s.ribbon, hex(0xe0483e)) }
}
