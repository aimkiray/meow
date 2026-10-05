package io.github.madeye.meow.preference

import androidx.preference.PreferenceManager
import io.github.madeye.meow.Core

object DataStore {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(Core.deviceStorage)

    var serviceMode: String
        get() = prefs.getString("serviceMode", "vpn") ?: "vpn"
        set(value) = prefs.edit().putString("serviceMode", value).apply()

    var portProxy: Int
        get() = prefs.getInt("portProxy", 7890)
        set(value) = prefs.edit().putInt("portProxy", value).apply()

    var portLocalDns: Int
        get() = prefs.getInt("portLocalDns", 1053)
        set(value) = prefs.edit().putInt("portLocalDns", value).apply()

    var perAppMode: String
        get() = prefs.getString("perAppMode", "proxy") ?: "proxy"
        set(value) = prefs.edit().putString("perAppMode", value).apply()

    var perAppPackages: String
        get() = prefs.getString("perAppPackages", "[]") ?: "[]"
        set(value) = prefs.edit().putString("perAppPackages", value).apply()

    /**
     * Synchronous variant of the two per-app setters above. Only the save
     * fallback path in `PerAppRepository` may use it: there the prefs copy
     * is the ONLY place the selection landed (the file write already
     * failed), so the caller must know whether it actually reached disk.
     * `apply()` never reports failures — `commit()` does, synchronously.
     * Every other writer keeps the async setters.
     */
    fun commitPerApp(mode: String, packagesJson: String): Boolean =
        prefs.edit().putString("perAppMode", mode).putString("perAppPackages", packagesJson).commit()

    /**
     * `DomesticAppClassifier`'s per-package verdict cache, JSON. UI-process only.
     * Legacy migration source — new writes go to `noBackupFilesDir/domestic_cache`;
     * the classifier removes this key once the migrated payload is safely saved.
     */
    var domesticAppCache: String
        get() = prefs.getString("domesticAppCache", "") ?: ""
        set(value) = prefs.edit().putString("domesticAppCache", value).apply()

    /**
     * Eagerly purges the legacy [domesticAppCache] prefs key. Called once from
     * `App.onCreate` (main process): the classifier only clears the key after a
     * domestic scan, so a user who never scans would otherwise carry the old
     * package-name list in cloud backups indefinitely — and CACHE_VERSION 4
     * rejects every legacy payload anyway, so the key is pure residue. No-op
     * when the key is already absent. `DomesticAppClassifier.saveCache()` keeps
     * its own purge as belt-and-braces (write-then-clear there is what makes
     * the migration crash-safe).
     */
    fun clearLegacyDomesticCache() {
        if (prefs.contains("domesticAppCache")) prefs.edit().remove("domesticAppCache").apply()
    }

    /** The UI asks for POST_NOTIFICATIONS once, ever; see `rememberNotificationPermissionRequest`. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notificationPermissionAsked", false)
        set(value) = prefs.edit().putBoolean("notificationPermissionAsked", value).apply()

    /**
     * Home's exit-IP card. Opt-in: every lookup shows the user's public IP to
     * a third-party service. Off means the lookup services are never contacted.
     */
    var showExitIp: Boolean
        get() = prefs.getBoolean("showExitIp", false)
        set(value) = prefs.edit().putBoolean("showExitIp", value).apply()

}
