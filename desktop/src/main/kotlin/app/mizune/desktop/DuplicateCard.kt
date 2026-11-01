package app.mizune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * When a card is already in the deck: add it anyway, leave it, or stop asking (which adds it too,
 * and can be turned on again under Settings › Anki).
 */
@Composable
fun DuplicateCardDialog(app: DesktopApp) {
    val duplicate by app.anki.duplicate.collectAsState()
    val word = duplicate?.word ?: return
    val scheme = MaterialTheme.colorScheme
    val deck = app.settings.ankiDeck
    Dialog(onDismissRequest = { app.anki.settleDuplicate(add = false) }) {
        Column(Modifier.width(400.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surface)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text("You already have this", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(
                    (if (deck != null) "“$deck”" else "Your deck") + " has a card for ${word.entry.expression} in this line.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            HorizontalDivider(color = scheme.hairline)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(
                    "Never warn me",
                    onClick = {
                        app.settings.updateAnkiWarnDuplicates(false)
                        app.anki.settleDuplicate(add = true)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                TextAction("Cancel", onClick = { app.anki.settleDuplicate(add = false) }, modifier = Modifier.padding(end = 6.dp))
                Button(onClick = { app.anki.settleDuplicate(add = true) }, shape = RoundedCornerShape(8.dp), elevation = null) {
                    Text("Add it anyway", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
