package io.github.madeye.meow.ui.theme

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import io.github.madeye.meow.AppGraph

/**
 * Applies Settings' theme pick to this Activity; call it from
 * `attachBaseContext`, after super.
 *
 * The pick overrides the Activity's night mode before the window exists, so
 * the first frame already follows it, and so does everything that reads the
 * configuration: the DayNight window background, the bar scrims and icon
 * colors `enableEdgeToEdge` picks, and the `isSystemInDarkTheme()` that
 * [MeowTheme] defaults to. A pick changed while the Activity is open does not
 * reach its configuration; MainActivity applies that one itself.
 */
fun ContextThemeWrapper.applyThemeMode() {
    val night = AppGraph.themeMode.mode.value.nightMode ?: return
    applyOverrideConfiguration(
        Configuration().apply {
            // A new Configuration says fontScale 1.0, which would override
            // the user's font size; 0 leaves it alone (AppCompat does the same).
            fontScale = 0f
            uiMode = night
        },
    )
}
