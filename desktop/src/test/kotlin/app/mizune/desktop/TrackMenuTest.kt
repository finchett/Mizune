package app.mizune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.mizune.ui.AppTheme
import app.mizune.ui.FillIcon
import app.mizune.ui.FrameIcon
import app.mizune.ui.MenuChoice
import app.mizune.ui.MenuGroup
import app.mizune.ui.MenuToggle
import app.mizune.ui.MenuToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The ⚙ menu's parts: Picture's toggles, and a group's other languages folded away. No video involved. */
@OptIn(ExperimentalTestApi::class)
class TrackMenuTest {
    private val shots = System.getenv("MIZUNE_TEST_SHOTS")?.let(::File)?.apply { mkdirs() }

    private fun SemanticsNodeInteractionsProvider.shot(name: String) {
        val dir = shots ?: return
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private fun SemanticsNodeInteractionsProvider.shows(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private val audio = listOf("Japanese", "English", "French", "German", "Spanish")

    @Test
    fun otherLanguagesFoldAwayAndOpen() = runDesktopComposeUiTest(width = 480, height = 640) {
        var fill by mutableStateOf(true)
        var frame by mutableStateOf(false)
        var picked by mutableStateOf("Japanese")
        var unfolded by mutableStateOf(false)
        setContent {
            DesktopTheme(AppTheme.Jade) {
                Box {
                    PlayerMenu(expanded = true, onDismiss = {}) {
                        MenuGroup("Picture", note = "Z", first = true)
                        MenuToggles(
                            listOf(
                                MenuToggle("Fill", fill, { FillIcon(it) }) { fill = it },
                                MenuToggle("Frame", frame, { FrameIcon(it) }) { frame = it },
                            ),
                        )
                        MenuGroup("Audio")
                        FoldedChoices(audio, { it == "Japanese" }, { it == picked }, unfolded, { unfolded = !unfolded }) { name ->
                            MenuChoice(name, name == picked, "AAC 2.0") { picked = name }
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(shows("Japanese"))
        assertTrue(shows("Other languages"))
        assertFalse("folded away", shows("French"))
        shot("track-menu-folded")

        onNodeWithText("Frame").performClick()
        assertTrue(frame)
        onNodeWithText("Fill").performClick()
        assertFalse(fill)

        onNodeWithText("Other languages").performClick()
        waitForIdle()
        assertTrue(shows("French"))
        shot("track-menu-open")

        // picking a folded one keeps the fold open even once it's closed by hand
        onNodeWithText("German").performClick()
        onNodeWithText("Other languages").performClick()
        waitForIdle()
        assertEquals("German", picked)
        assertTrue(shows("German"))
    }

    @Test
    fun aFewOthersAreAllShown() = runDesktopComposeUiTest(width = 480, height = 480) {
        setContent {
            DesktopTheme(AppTheme.Jade) {
                Box {
                    PlayerMenu(expanded = true, onDismiss = {}) {
                        FoldedChoices(listOf("Japanese", "English", "French"), { it == "Japanese" }, { false }, false, {}) { name ->
                            MenuChoice(name, false) {}
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertFalse(shows("Other languages"))
        assertTrue(shows("French"))
    }
}
