package app.mizune.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A menu from the player: a compact card of choices in groups, built from the parts in
 * shared-ui's Menus.kt, which the phone's options use too.
 */
@Composable
fun PlayerMenu(expanded: Boolean, onDismiss: () -> Unit, width: Dp = 320.dp, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).padding(horizontal = 6.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = scheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        content = content,
    )
}
