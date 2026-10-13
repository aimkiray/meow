package io.github.madeye.meow.preference

import android.content.res.Configuration

/** The app's light/dark choice, stored as the [DataStore.themeMode] string. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** The [Configuration.uiMode] night bits this mode forces; null follows the system. */
    val nightMode: Int?
        get() = when (this) {
            SYSTEM -> null
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            DARK -> Configuration.UI_MODE_NIGHT_YES
        }

    fun toWire(): String = name.lowercase()

    companion object {
        /** Unrecognised values — written by another build — fall back to [SYSTEM]. */
        fun fromWire(value: String): ThemeMode =
            entries.firstOrNull { it.toWire().equals(value.trim(), ignoreCase = true) } ?: SYSTEM
    }
}
