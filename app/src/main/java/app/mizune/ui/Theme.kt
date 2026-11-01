package app.mizune.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mizune.Prefs

/** Appearance settings, observable by Compose so changes apply immediately. */
class Appearance(private val prefs: Prefs) {
    // a theme picked before families keeps its family, on the side it was
    private val old = AppTheme.entries.firstOrNull { it.name == prefs.theme }

    /** The theme's family, as on the desktop: Jade, Sakura, Nord… */
    var family: ThemeFamily by mutableStateOf(
        ThemeFamily.entries.firstOrNull { it.name == prefs.themeFamily } ?: old?.let(ThemeFamily::of) ?: ThemeFamily.Jade,
    )
        private set

    /** Its light side, its dark side, or whichever the phone is set to. */
    var mode: ThemeMode by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.name == prefs.themeMode }
            ?: old?.let { if (ThemeFamily.of(it).dark == it) ThemeMode.Dark else ThemeMode.Light } ?: ThemeMode.Auto,
    )
        private set

    var roundedCorners by mutableStateOf(prefs.roundedCorners)
        private set

    /** The theme to show, [systemDark] deciding Auto. */
    fun theme(systemDark: Boolean): AppTheme = family.resolve(mode, systemDark)

    fun updateFamily(value: ThemeFamily) {
        family = value
        prefs.themeFamily = value.name
    }

    fun updateMode(value: ThemeMode) {
        mode = value
        prefs.themeMode = value.name
    }

    fun updateRoundedCorners(value: Boolean) {
        roundedCorners = value
        prefs.roundedCorners = value
    }
}
