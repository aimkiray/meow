package io.github.madeye.meow.repo

import io.github.madeye.meow.preference.DataStore
import io.github.madeye.meow.preference.PerAppConfigStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Which apps the tunnel applies to. */
enum class PerAppMode(val key: String) {
    /** Only the selected packages are routed through the tunnel. */
    Proxy("proxy"),

    /** Everything except the selected packages is routed. */
    Bypass("bypass"),
    ;

    companion object {
        /** Parses a stored mode key; null when it is not one of the known keys. */
        fun from(key: String?): PerAppMode? =
            entries.firstOrNull { it.key == key }
    }
}

data class PerAppConfig(
    val mode: PerAppMode = PerAppMode.Proxy,
    val packages: Set<String> = emptySet(),
)

/**
 * Per-app proxy selection. `VpnService` reads the same config when building
 * the TUN interface — through [PerAppConfigStore]'s file, not SharedPreferences,
 * because `:vpn` caches the preferences file for the life of the process and
 * would otherwise replay a stale selection after edits. The legacy
 * SharedPreferences keys are still written so downgrades and first-run reads
 * keep working; the file wins whenever it exists and holds a known mode.
 */
class PerAppRepository(
    private val json: Json = Json,
    private val store: PerAppConfigStore = PerAppConfigStore.default,
) {

    suspend fun load(): PerAppConfig = withContext(Dispatchers.IO) {
        val stored = store.load()
        val storedMode = stored?.let { PerAppMode.from(it.mode) }
        if (stored != null && storedMode != null) {
            return@withContext PerAppConfig(storedMode, stored.packages)
        }
        // Missing/unreadable file, or one holding a mode key this build does
        // not know — treated the same, since the legacy prefs keys are
        // written alongside every save and stand in for the file. An unknown
        // prefs mode hits VpnService's null-branch — no per-app routing at
        // all — so report the disabled config (Proxy + empty) rather than
        // advertise a selection the tunnel ignores.
        val mode = PerAppMode.from(DataStore.perAppMode)
            ?: return@withContext PerAppConfig(mode = PerAppMode.Proxy, packages = emptySet())
        val packages = try {
            json.decodeFromString(ListSerializer(String.serializer()), DataStore.perAppPackages)
        } catch (e: Exception) {
            emptyList()
        }
        PerAppConfig(mode = mode, packages = packages.toSet())
    }

    /**
     * Persists the selection. The file is authoritative for every reader
     * (here and in `:vpn`), so success/failure is judged on where the data
     * actually landed:
     *
     *  - file write OK → the file is the durable copy; the prefs write is a
     *    best-effort fallback for missing files, so it stays async
     *    (`apply()`) and a later failure of it is NOT reported as a save
     *    failure;
     *  - file write failed → the stale file must first be verifiably GONE
     *    ([PerAppConfigStore.clear] checks `!file.exists()`): if it
     *    survives the delete it would shadow the fresh prefs on every
     *    subsequent read — an invisible revert — so the save throws. Only
     *    then do the prefs become the ONLY durable copy, so they must land
     *    synchronously (`commitPerApp`); a `false` return throws. Either
     *    throw surfaces the save as failed instead of silently reverting
     *    after restart.
     */
    suspend fun save(config: PerAppConfig) = withContext(Dispatchers.IO) {
        val packagesJson =
            json.encodeToString(ListSerializer(String.serializer()), config.packages.toList())
        val fileSaved = try {
            store.save(config.mode.key, config.packages)
            true
        } catch (e: Exception) {
            // Exception, not just IOException: cheap insurance for any
            // undeclared RuntimeException (and the extremely rare
            // SecurityException) — one "write failed" contract.
            Timber.w(e, "per-app config file write failed; falling back to prefs")
            // Clear FIRST, and verify: the file outranks prefs on every read
            // (here and in :vpn), so a stale file surviving the delete would
            // shadow the just-written prefs — the user's edit invisibly
            // reverted on the next read. If the file is not actually gone,
            // the save genuinely did not take effect. A clear() that throws
            // leaves the file state unknown — that counts as "not gone" too.
            val fileGone = try {
                store.clear()
            } catch (e2: Exception) {
                false
            }
            if (!fileGone) {
                throw IOException(
                    "per-app save failed: the stale config file could not be removed and would shadow the fresh prefs",
                    e,
                )
            }
            false
        }
        if (fileSaved) {
            DataStore.perAppMode = config.mode.key
            DataStore.perAppPackages = packagesJson
        } else if (!DataStore.commitPerApp(config.mode.key, packagesJson)) {
            // Neither copy reached disk. apply() would have swallowed this
            // and shown "success" before silently reverting after restart;
            // commit()'s boolean is the only synchronous failure signal
            // SharedPreferences offers.
            throw IOException("per-app save failed: config file write failed and prefs commit returned false")
        }
    }
}
