package app.mizune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.mizune.ui.ThemePicker

/** The theme, as Settings › Appearance and the welcome pick it: [app.mizune.ui.ThemePicker] on the settings. */
@Composable
fun ThemePicker(settings: Settings, modifier: Modifier = Modifier) =
    ThemePicker(settings.themeFamily, settings.themeMode, settings::updateThemeFamily, settings::updateThemeMode, modifier)
