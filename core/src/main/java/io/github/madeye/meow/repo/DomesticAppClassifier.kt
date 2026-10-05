package io.github.madeye.meow.repo

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.madeye.meow.Core
import io.github.madeye.meow.preference.DataStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Bulk "is this app domestic" verdicts for the per-app proxy picker.
 *
 * A package is classified Chinese when, in order:
 *   1. it is not on the foreign-exception skip list (free),
 *   2. its name matches a Chinese vendor prefix (free),
 *   3. one of its manifest components — including the `<application>` class —
 *      has a Chinese-vendor class name, which catches apps that embed CN SDKs
 *      or CN packer stubs under a neutral package name (the PackageInfo is
 *      already in memory for the cache check, so this costs nothing extra), or
 *   4. it was installed by a Chinese app store (one more binder call).
 *
 * Verdicts are cached keyed by `PackageInfo.lastUpdateTime`, which bumps on
 * every app update, so a rescan only pays for new/changed packages. The cache
 * lives in a `noBackupFilesDir` file — an installed-app inventory has no
 * business riding cloud/D2D backups — and is versioned: bump [CACHE_VERSION]
 * whenever the signal lists change so a stale regex doesn't keep outdated
 * verdicts alive. Installs predating the file migrate their legacy
 * SharedPreferences copy on first load, then drop the prefs key.
 *
 * Deliberately absent: locale/language checks (`getLocales()` includes
 * framework locales, and app-bundle language splits strip the data anyway)
 * and full dex scanning (what sing-box used to do; the NekoBox fork dropped
 * it for causing crashes). Manifest component names capture the same SDK and
 * packer fingerprints at a fraction of the cost.
 */
class DomesticAppClassifier(
    private val context: Context,
    private val json: Json = Json,
) {

    private val packageManager: PackageManager get() = context.packageManager

    /** The classifier is an AppGraph singleton; serialise concurrent scans. */
    private val scan = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()
    private var cacheLoaded = false

    // noBackupFilesDir, same file convention as PerAppConfigStore/RouteModeStore.
    private val cacheFile get() = File(Core.deviceStorage.noBackupFilesDir, "domestic_cache")
    private val tmpCacheFile get() = File(cacheFile.path + ".tmp")

    /** Set when [loadCache] had to fall back to the legacy SharedPreferences copy. */
    private var legacyPrefsLoaded = false

    /**
     * Returns the subset of [packageNames] classified as Chinese.
     * Runs on [Dispatchers.IO]; PackageManager calls dominate the cost.
     * Individual package failures degrade to "not domestic" rather than
     * failing the scan — a binder hiccup must not crash the picker.
     */
    suspend fun classify(packageNames: Collection<String>): Set<String> =
        withContext(Dispatchers.IO) {
            scan.withLock {
                loadCache()
                // No pruning against the query set: entries self-invalidate
                // via lastUpdateTime, and a scoped scan (system apps hidden)
                // would otherwise evict — and persist the eviction of —
                // valid verdicts for packages it simply didn't ask about.
                val domestic = mutableSetOf<String>()
                var failures = 0
                var firstFailure: Exception? = null
                for (pkg in packageNames) {
                    ensureActive()
                    try {
                        if (isDomestic(pkg)) domestic += pkg
                    } catch (e: Exception) {
                        // Uninstalls mid-scan, dead-system binder errors,
                        // OEM quirks — all mean "unknown", not "crash".
                        // Tallied so a systemic failure (e.g. the component
                        // path breaking for every package) isn't invisible.
                        failures++
                        if (firstFailure == null) firstFailure = e
                    }
                }
                if (failures > 0) {
                    Timber.w(
                        firstFailure,
                        "domestic scan: %d/%d packages failed to classify",
                        failures,
                        packageNames.size,
                    )
                }
                try {
                    saveCache()
                } catch (e: Exception) {
                    // A persist hiccup must not forfeit a finished scan.
                }
                domestic
            }
        }

    private fun isDomestic(packageName: String): Boolean {
        if (ChinaPackageMatcher.isSkipped(packageName)) return false
        if (ChinaPackageMatcher.isChineseName(packageName)) return true
        // Cheap call first: lastUpdateTime is the cache key, and a flagless
        // PackageInfo parcels far less than the component-flagged one.
        val stamp = packageInfo(packageName, components = false)?.lastUpdateTime ?: return false
        cache[packageName]
            ?.takeIf { it.updated == stamp }
            ?.let { return it.domestic }
        val info = packageInfo(packageName, components = true)
        val domestic = info?.let(::hasChineseComponent) == true ||
            installedFromCnStore(packageName)
        // A failed component query makes the verdict "unknown", not "false"
        // — caching it would pin a wrong answer to this APK until its next
        // update. A positive installer verdict is durable either way.
        if (info != null || domestic) {
            cache[packageName] = CacheEntry(stamp, domestic)
        }
        return domestic
    }

    /**
     * The store that performed the install or last update is a positive-only
     * signal: a CN-store origin almost certainly means a domestic build, while
     * a foreign store says nothing either way (WeChat from Play is still CN).
     * Relies on QUERY_ALL_PACKAGES: under package-visibility rules both
     * installer fields come back filtered, so without it the signal silently
     * returns nothing.
     */
    private fun installedFromCnStore(packageName: String): Boolean {
        val sources = try {
            if (Build.VERSION.SDK_INT >= 30) {
                packageManager.getInstallSourceInfo(packageName).let {
                    listOfNotNull(it.initiatingPackageName, it.installingPackageName)
                }
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(packageManager.getInstallerPackageName(packageName))
            }
        } catch (e: Exception) {
            // NameNotFoundException (uninstalled mid-scan) plus OEM
            // SecurityException oddities — same "unknown" verdict either way.
            return false
        }
        return sources.any { it in CN_STORES }
    }

    /**
     * Component class names expose bundled CN SDKs (Bugly, MiPush, JPush) and
     * packer stubs — including `applicationInfo.className`, which is where the
     * classic packer fingerprint (`com.stub.StubApp`, `com.secneo.apkwrapper.AW`)
     * actually lives. Skipped namespaces are excluded so e.g. a foreign game
     * embedding the Vivox voice SDK isn't caught by the `com.vivo` prefix.
     */
    private fun hasChineseComponent(info: PackageInfo): Boolean =
        componentNames(info).any(ChinaPackageMatcher::isChineseComponent)

    /**
     * Iterated explicitly rather than `sequenceOf(activities, services, …)
     * .flatMap { it.asSequence() }`. What is known: the vararg form was
     * observed failing on-device (the author's device A/B — a cold-cache
     * scan matched 35 apps before this rewrite, 53 after), and no unit test
     * could have caught it, because none can build a real `PackageInfo`
     * (this project has no Robolectric). What is not known: the exact
     * failure mechanism. The earlier explanation — K2 inferring `Void[]`
     * from four platform-typed array arguments, making the flatMap lambda
     * throw `ClassCastException` on every packaged app — does not match the
     * bytecode the real Kotlin 2.2.0 compiler produces for that form (the
     * bridge casts `ComponentInfo[]`, and `Void` appears nowhere in the
     * constant pool), so it is not asserted here. The explicit builder is
     * provably equivalent — a differential fuzz against the vararg form
     * over 60k inputs found zero differences — and avoids the hazard class
     * entirely, which is why it stays regardless of the mechanism.
     */
    private fun componentNames(info: PackageInfo): Sequence<String> = sequence {
        for (ci in info.activities.orEmpty()) yield(ci.name)
        for (ci in info.services.orEmpty()) yield(ci.name)
        for (ci in info.receivers.orEmpty()) yield(ci.name)
        for (ci in info.providers.orEmpty()) yield(ci.name)
        for (inst in info.instrumentation.orEmpty()) yield(inst.name)
        info.applicationInfo?.className?.let { yield(it) }
        if (Build.VERSION.SDK_INT >= 28) {
            info.applicationInfo?.appComponentFactory?.let { yield(it) }
        }
    }

    @Suppress("DEPRECATION") // int flag constants remain the documented input to PackageInfoFlags.of()
    private fun packageInfo(packageName: String, components: Boolean): PackageInfo? = try {
        // MATCH_DISABLED_COMPONENTS keeps the component set a pure function of
        // the APK — runtime enable/disable toggles don't bump lastUpdateTime,
        // so including disabled components is what makes the cache key sound.
        val flags = if (components) {
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_INSTRUMENTATION or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        // Uninstalled (or uninstalled-for-user) between the enumeration
        // snapshot and the query — expected during a scan, not a signal.
        null
    } catch (e: Exception) {
        Timber.w(e, "packageInfo(%s, components=%b) failed", packageName, components)
        null
    }

    private fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        // The file is authoritative once it decodes; a missing or
        // undecodable file falls back to the legacy SharedPreferences copy —
        // the one-time migration source for installs predating the file
        // store. A decode-success with an old version still skips prefs:
        // nothing newer lives there, and CACHE_VERSION owns invalidation.
        val stored = try {
            json.decodeFromString(CacheFile.serializer(), cacheFile.readText())
        } catch (e: Exception) {
            legacyPrefsLoaded = true
            try {
                json.decodeFromString(CacheFile.serializer(), DataStore.domesticAppCache)
            } catch (e2: Exception) {
                return
            }
        }
        if (stored.version == CACHE_VERSION) cache.putAll(stored.apps)
    }

    private fun saveCache() {
        // Write-then-rename so a concurrent load never sees a torn value.
        // File.renameTo, not Files.move: java.nio.file only exists at API
        // 26+ (the default desugar_jdk_libs flavor doesn't cover it — that's
        // the _nio variant), while renameTo lowers to rename(2) — already an
        // atomic replace for a same-directory move. copyTo is the fallback
        // for a filesystem that refuses the rename — not atomic, but
        // strictly better than failing the write.
        tmpCacheFile.writeText(
            json.encodeToString(CacheFile.serializer(), CacheFile(version = CACHE_VERSION, apps = cache)),
        )
        if (!tmpCacheFile.renameTo(cacheFile)) {
            try {
                tmpCacheFile.copyTo(cacheFile, overwrite = true)
            } catch (e: Exception) {
                // copyTo's declared failures are already IOExceptions
                // (kotlin.io.FileSystemException extends IOException); the
                // wide catch folds in undeclared RuntimeExceptions so every
                // failure surfaces as write-failed to the caller's catch.
                throw IOException("copy fallback failed", e)
            } finally {
                tmpCacheFile.delete()
            }
        }
        // Write-then-clear keeps the migration safe: the file is in place
        // by here, so removing the only other copy cannot lose the cache —
        // a failed write throws above and leaves the prefs value as the
        // last good copy. Purge the key whenever it is still present, not
        // only when it fed this load: a crash between rename and removal
        // leaves it riding cloud backups forever otherwise. Same operation
        // as App.onCreate's eager cleanup — through DataStore so the key
        // string lives in one place only.
        val staleKey = legacyPrefsLoaded || DataStore.domesticAppCache.isNotEmpty()
        if (staleKey) {
            legacyPrefsLoaded = false
            DataStore.clearLegacyDomesticCache()
        }
    }

    @Serializable
    private data class CacheFile(
        // No default: a file without "version" fails decode and is dropped,
        // which is exactly the invalidation bumping CACHE_VERSION relies on.
        val version: Int,
        val apps: Map<String, CacheEntry> = emptyMap(),
    )

    @Serializable
    private data class CacheEntry(val updated: Long, val domestic: Boolean)

    private companion object {
        /** Bump when SKIP_PREFIXES / CN_PREFIXES / CN_STORES change. */
        const val CACHE_VERSION = 4

        val CN_STORES = setOf(
            "com.xiaomi.market",              // 小米应用商店
            "com.huawei.appmarket",           // 华为应用市场
            "com.tencent.android.qqdownloader", // 应用宝
            "com.oppo.market", "com.heytap.market", // OPPO / 欢太
            "com.bbk.appstore", "com.vivo.appstore", // vivo
            "com.qihoo.appstore",             // 360
            "com.baidu.appsearch",            // 百度手机助手
            "com.meizu.mstore",
            "com.coolapk.market",             // 酷安
            "com.wandoujia.phoenix2",         // 豌豆荚
            "com.pp.assistant",
            "com.lenovo.leos.appstore",
            "zte.com.market", "com.nubia.neostore",
            "com.gionee.aora.market", "com.smartisanos.appstore",
            "com.yulong.android.coolmart",
            "com.hiapk.marketpho", "cn.goapk.market",
            "com.yingyonghui.market", "com.sogou.androidtool",
        )
    }
}
