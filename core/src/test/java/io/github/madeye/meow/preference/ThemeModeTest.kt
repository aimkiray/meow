package io.github.madeye.meow.preference

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `only the forced modes override the night bits`() {
        assertNull(ThemeMode.SYSTEM.nightMode)
        assertEquals(Configuration.UI_MODE_NIGHT_NO, ThemeMode.LIGHT.nightMode)
        assertEquals(Configuration.UI_MODE_NIGHT_YES, ThemeMode.DARK.nightMode)
    }

    @Test
    fun `the preference starts from the stored mode and writes every pick`() {
        val written = mutableListOf<ThemeMode>()
        val pref = ThemeModePreference(read = { ThemeMode.DARK }, write = { written += it })
        assertEquals(ThemeMode.DARK, pref.mode.value)

        pref.set(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, pref.mode.value)
        assertEquals(listOf(ThemeMode.LIGHT), written)
    }

    @Test
    fun `toWire round-trips through fromWire for every mode`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromWire(mode.toWire()))
        }
    }

    @Test
    fun `fromWire accepts the stored spellings case-insensitively`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromWire("Light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire("DARK\n"))
    }

    @Test
    fun `fromWire falls back to system on anything else`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire("auto"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire(""))
    }
}
