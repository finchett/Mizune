package app.mizune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The shader button's icon: a four-point star, its sides curving in, drawn in [color]. */
@Composable
fun ShaderIcon(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val c = this.size.width / 2
        // a little inside the box, so it weighs the same as the line icons beside it
        val r = c * 0.78f
        // how far the sides bow in towards the middle: the smaller, the thinner the points
        val pinch = r * 0.16f
        val star = Path().apply {
            moveTo(c, c - r)
            quadraticTo(c + pinch, c - pinch, c + r, c)
            quadraticTo(c + pinch, c + pinch, c, c + r)
            quadraticTo(c - pinch, c + pinch, c - r, c)
            quadraticTo(c - pinch, c - pinch, c, c - r)
            close()
        }
        drawPath(star, color)
    }
}
