package app.mizune.desktop

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Where the window goes: its bounds while floating, in AWT's units (which are Compose's dp), and
 * whether it's maximised over them. Kept in [Settings] as it moves, so the next launch opens it
 * where it was left.
 */
data class WindowPlace(val bounds: Rectangle, val maximized: Boolean = false) {
    fun encode() = "${bounds.x},${bounds.y},${bounds.width},${bounds.height}" + if (maximized) ",max" else ""

    companion object {
        const val DEFAULT_WIDTH = 1440
        const val DEFAULT_HEIGHT = 860
        /** Room kept round a first window on a screen too small for the default size. */
        private const val MARGIN = 32

        fun decode(text: String): WindowPlace? {
            val parts = text.split(',')
            val (x, y, w, h) = parts.take(4).map { it.toIntOrNull() ?: return null }.takeIf { it.size == 4 } ?: return null
            if (w <= 0 || h <= 0) return null
            return WindowPlace(Rectangle(x, y, w, h), maximized = parts.getOrNull(4) == "max")
        }

        /**
         * The window's place on launch: where it was left, brought wholly onto a screen (and shrunk
         * to fit) if the screens have changed since, centred on the main screen if the one it was
         * on has gone. A first launch gets the default size centred on the main screen, made
         * smaller if that doesn't fit. [screens] are their work areas, without the taskbar or menu
         * bar, the main screen first; with none known this is null.
         */
        fun onLaunch(saved: WindowPlace?, screens: List<Rectangle> = screenAreas()): WindowPlace? {
            val main = screens.firstOrNull() ?: return null
            if (saved == null) {
                val w = minOf(DEFAULT_WIDTH, main.width - 2 * MARGIN)
                val h = minOf(DEFAULT_HEIGHT, main.height - 2 * MARGIN)
                return WindowPlace(centred(w, h, main))
            }
            val screen = screens.maxBy { overlap(it, saved.bounds) }
            val bounds = if (overlap(screen, saved.bounds) > 0) {
                fitInto(saved.bounds, screen)
            } else {
                fitInto(centred(saved.bounds.width, saved.bounds.height, main), main)
            }
            return saved.copy(bounds = bounds)
        }

        private fun centred(w: Int, h: Int, screen: Rectangle) =
            Rectangle(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2, w, h)

        private fun overlap(a: Rectangle, b: Rectangle): Long =
            a.intersection(b).takeIf { !it.isEmpty }?.let { it.width.toLong() * it.height } ?: 0

        private fun fitInto(bounds: Rectangle, screen: Rectangle): Rectangle {
            val w = minOf(bounds.width, screen.width)
            val h = minOf(bounds.height, screen.height)
            return Rectangle(
                bounds.x.coerceIn(screen.x, screen.x + screen.width - w),
                bounds.y.coerceIn(screen.y, screen.y + screen.height - h),
                w, h,
            )
        }

        /** Each screen's work area, the main one first; none when headless. */
        private fun screenAreas(): List<Rectangle> = runCatching {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val main = env.defaultScreenDevice
            (listOf(main) + env.screenDevices.filter { it != main }).map { device ->
                val config = device.defaultConfiguration
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
                config.bounds.apply {
                    x += insets.left
                    y += insets.top
                    width -= insets.left + insets.right
                    height -= insets.top + insets.bottom
                }
            }
        }.getOrDefault(emptyList())
    }
}
