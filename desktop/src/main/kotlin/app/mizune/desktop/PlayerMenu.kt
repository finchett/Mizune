package app.mizune.desktop

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mizune.ui.MenuCard

/**
 * A menu from the player: a compact card of choices in groups, built from the parts in
 * shared-ui's Menus.kt, which the phone's options use too.
 */
@Composable
fun PlayerMenu(expanded: Boolean, onDismiss: () -> Unit, width: Dp = 320.dp, content: @Composable ColumnScope.() -> Unit) =
    // no shadow: here it only draws once the menu has faded in, and pops. A shade lighter than the
    // page, and the card's border, set it apart instead.
    MenuCard(expanded, onDismiss, width, shadow = 0.dp, color = raised(MaterialTheme.colorScheme.surface), content = content)

/** [surface] a shade lighter: a little on a dark theme, where a little shows, more on a light one. */
private fun raised(surface: Color): Color = lerp(surface, Color.White, if (surface.luminance() < 0.5f) 0.05f else 0.6f)
