package app.mizune.desktop

import app.mizune.ui.ThemeFamily
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The browser extension draws with the app's theme families even without the app, from a copy of
 * their colours in extension/src/themes.json. This keeps the copy up to date: with
 * MIZUNE_WRITE_THEMES set it writes it afresh, and otherwise fails when it's out of date.
 */
class ExtensionThemesTest {
    private val file = File("../extension/src/themes.json")

    /** In the app's order, each colour in the same order, so the file reads the same each time it's written. */
    private fun themes(): String {
        fun side(theme: app.mizune.ui.AppTheme?) = theme?.let { t ->
            BrowserHost.colors(t).entries.joinToString(", ", "{ ", " }") { (name, value) -> "\"$name\": \"$value\"" }
        } ?: "null"
        return ThemeFamily.entries.joinToString(",\n", "[\n", "\n]\n") { family ->
            "  { \"name\": \"${family.name}\", \"label\": \"${family.label}\",\n" +
                "    \"light\": ${side(family.light)},\n" +
                "    \"dark\": ${side(family.dark)} }"
        }
    }

    @Test fun theExtensionsCopyMatchesTheApp() {
        val expected = themes()
        if (System.getenv("MIZUNE_WRITE_THEMES") != null) file.writeText(expected)
        assertEquals("extension/src/themes.json is out of date: run this test with MIZUNE_WRITE_THEMES=1", expected, file.readText())
    }
}
