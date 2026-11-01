package app.mizune.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect

/**
 * The app's icon, [size] across including its margin, with the soft shadow macOS gives icons in
 * the Dock: without one its pale tile is lost on the light themes. The tile is the middle 412 of
 * the image's 512 px, its corners a little under a quarter of its side.
 */
@Composable
fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource("icon.png"),
        contentDescription = null,
        modifier = modifier.size(size).drawBehind {
            val side = this.size.width
            val tile = side * 412 / 512
            val inset = (side - tile) / 2
            val shadow = Paint().apply {
                color = 0x47000000
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, side * 0.02f)
            }
            drawIntoCanvas {
                it.nativeCanvas.drawRRect(RRect.makeXYWH(inset, inset + side * 0.016f, tile, tile, tile * 0.225f), shadow)
            }
        },
    )
}
