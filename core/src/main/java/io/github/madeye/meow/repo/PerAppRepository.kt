package io.github.madeye.meow.repo

import io.github.madeye.meow.preference.DataStore
import io.github.madeye.meow.preference.PerAppConfigStore
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

    /** A save succeeds only when the cross-process authoritative file is replaced. */
    suspend fun save(config: PerAppConfig) = withContext(Dispatchers.IO) {
        store.save(config.mode.key, config.packages)
        // Downgrade compatibility only. A preferences write must not determine
        // success: the running VPN process cannot see fresh preferences.
        try {
            DataStore.perAppMode = config.mode.key
            DataStore.perAppPackages =
                json.encodeToString(ListSerializer(String.serializer()), config.packages.toList())
        } catch (e: Exception) {
            Timber.w(e, "per-app legacy preferences update failed")
        }
    }
}
