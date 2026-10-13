package io.github.madeye.meow.preference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [DataStore.themeMode] as a flow, so Settings shows the pick the moment it
 * moves and `MainActivity` recreates itself to apply it. Only the UI process
 * reads it, so — unlike [RouteModeStore] — SharedPreferences' read-once
 * caching is fine.
 *
 * Unlike [ExitIpPreference], the first read is synchronous: an Activity
 * applies the mode in `attachBaseContext`, before its first frame, and any
 * later answer would show the wrong theme first. It is one small preferences
 * file, read once per process.
 */
class ThemeModePreference(
    read: () -> ThemeMode = { ThemeMode.fromWire(DataStore.themeMode) },
    private val write: (ThemeMode) -> Unit = { DataStore.themeMode = it.toWire() },
) {
    private val _mode = MutableStateFlow(read())
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun set(mode: ThemeMode) {
        _mode.value = mode
        // SharedPreferences.apply() reaches disk asynchronously.
        write(mode)
    }
}
