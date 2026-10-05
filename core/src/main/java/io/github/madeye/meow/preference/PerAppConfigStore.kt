package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import java.io.File
import java.io.IOException

/**
 * The per-app proxy selection, replayed into every TUN establish.
 *
 * Same constraint as [RouteModeStore]: the UI process writes and `:vpn`
 * reads, so it cannot live in SharedPreferences — `VpnService` would see the
 * value from whatever the `:vpn` process first cached. A small file is read
 * fresh on every establish. The old `DataStore.perAppMode`/`perAppPackages`
 * keys stay written for backward compatibility but are no longer read here.
 *
 * Layout: first line is the mode key, remaining lines are package names
 * (package names cannot contain newlines).
 */
class PerAppConfigStore(private val file: File) {

    fun load(): Stored? = try {
        val lines = file.readLines()
        if (lines.isEmpty()) null else Stored(mode = lines[0], packages = lines.drop(1).toSet())
    } catch (_: Exception) {
        // IOException for a missing/gone file; the wide catch is cheap
        // insurance for undeclared RuntimeExceptions (and the extremely
        // rare SecurityException) — either way readers fall back to prefs.
        null
    }

    private val tmpFile get() = File(file.path + ".tmp")

    fun save(mode: String, packages: Set<String>) {
        // Same-directory rename publishes the complete config atomically.
        // If it fails, preserve the previous config and report save failure:
        // copying in place could expose a partially written routing policy.
        try {
            tmpFile.writeText((listOf(mode) + packages).joinToString("\n"))
            if (!tmpFile.renameTo(file)) throw IOException("cannot replace $file")
        } finally {
            tmpFile.delete()
        }
    }

    data class Stored(val mode: String, val packages: Set<String>)

    companion object {
        val default: PerAppConfigStore by lazy {
            PerAppConfigStore(File(Core.deviceStorage.noBackupFilesDir, "per_app"))
        }
    }
}
