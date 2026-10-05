package io.github.madeye.meow.preference

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerAppConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile(): File = File(tmp.root, "per_app")

    @Test
    fun `nothing saved means legacy prefs still own the config`() {
        assertNull(PerAppConfigStore(storeFile()).load())
    }

    @Test
    fun `a save is visible to a separate instance`() {
        // Stands in for the UI process writing and `:vpn` reading.
        PerAppConfigStore(storeFile())
            .save("bypass", setOf("com.tencent.mm", "com.eg.android.AlipayGphone"))

        val stored = PerAppConfigStore(storeFile()).load()
        assertEquals("bypass", stored?.mode)
        assertEquals(setOf("com.tencent.mm", "com.eg.android.AlipayGphone"), stored?.packages)
    }

    @Test
    fun `a later save replaces the earlier one`() {
        val store = PerAppConfigStore(storeFile())
        store.save("proxy", setOf("a.b.c"))
        store.save("bypass", setOf("x.y.z"))

        val stored = store.load()
        assertEquals("bypass", stored?.mode)
        assertEquals(setOf("x.y.z"), stored?.packages)
        assertFalse(File(tmp.root, "per_app.tmp").exists())
    }

    @Test
    fun `an empty file is ignored`() {
        storeFile().writeText("")

        assertNull(PerAppConfigStore(storeFile()).load())
    }

    @Test
    fun `mode-only file round-trips with an empty package set`() {
        PerAppConfigStore(storeFile()).save("proxy", emptySet())

        val stored = PerAppConfigStore(storeFile()).load()
        assertEquals("proxy", stored?.mode)
        assertTrue(stored?.packages?.isEmpty() == true)
    }

    @Test
    fun `failed save preserves the last cross-process config`() {
        val file = storeFile()
        val store = PerAppConfigStore(file)
        store.save("bypass", setOf("a.b.c"))
        // A directory at the staging path forces a real write failure.
        File(file.path + ".tmp").mkdir()
        val repo = io.github.madeye.meow.repo.PerAppRepository(store = store)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.save(io.github.madeye.meow.repo.PerAppConfig(
                    io.github.madeye.meow.repo.PerAppMode.Proxy, setOf("x.y.z"),
                ))
            }
        }
        assertEquals(PerAppConfigStore.Stored("bypass", setOf("a.b.c")),
            PerAppConfigStore(file).load())
    }
}
