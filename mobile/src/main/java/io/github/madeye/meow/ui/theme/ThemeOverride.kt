package io.github.madeye.meow.ui.theme

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import io.github.madeye.meow.AppGraph
import io.github.madeye.meow.preference.ThemeMode

/**
 * Applies Settings' theme pick to this Activity; call it from
 * `attachBaseContext`, after super. Returns the mode applied, which only a
 * new instance can change (MainActivity recreates itself for that).
 *
 * The pick overrides the Activity's night mode instead of being chosen in
 * Compose, so everything that reads the configuration agrees with it: the
 * DayNight window background seen through screen transitions, the bar
 * scrims and icon colors `enableEdgeToEdge` picks, and the
 * `isSystemInDarkTheme()` that [MeowTheme] defaults to.
 */
fun ContextThemeWrapper.applyThemeMode(): ThemeMode {
    val mode = AppGraph.themeMode.mode.value
    val night = mode.nightMode ?: return mode
    applyOverrideConfiguration(
        Configuration().apply {
            // A new Configuration says fontScale 1.0, which would override
            // the user's font size; 0 leaves it alone (AppCompat does the same).
            fontScale = 0f
            uiMode = night
        },
    )
    return mode
}
