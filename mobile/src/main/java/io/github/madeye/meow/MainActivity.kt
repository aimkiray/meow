package io.github.madeye.meow

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.madeye.meow.bg.MeowInstance
import io.github.madeye.meow.preference.ThemeMode
import io.github.madeye.meow.ui.MeowApp
import io.github.madeye.meow.ui.screens.subscribe.InstallConfigLink
import io.github.madeye.meow.ui.theme.MeowTheme
import io.github.madeye.meow.ui.theme.applyThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The app's only UI Activity (the Quick Settings tile's TileConnectActivity
 * draws nothing).
 *
 * Was a `FlutterActivity` hosting a MethodChannel bridge; the Compose UI runs
 * in this process and calls the same Kotlin directly, so the channels are gone.
 *
 * Two things here are load-bearing for `test-e2e.sh` and must not be renamed:
 * the `.MainActivity` class name, and the `auto_connect` boolean extra.
 *
 * Also the entry point for `clash://install-config` links (see
 * [InstallConfigLink]); the UI confirms them on the Subscriptions page, which
 * it opens over Home, cold start included.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Raw install-config link awaiting the user's answer; cleared once answered. */
    private var pendingLink by mutableStateOf<String?>(null)

    /** The system's night bits, read before [applyThemeMode] overrides them. */
    private var systemNight = Configuration.UI_MODE_NIGHT_NO

    /** Whether the UI draws dark: Settings' pick, else the system's mode. */
    private var dark by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        systemNight = newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        applyThemeMode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPick(AppGraph.themeMode.mode.value)

        // A pick made in Settings applies in place. recreate() would keep this
        // window, and on Android 15 the kept window came back fitted inside
        // the system bars: opaque bars, content no longer drawn behind them.
        scope.launch { AppGraph.themeMode.mode.collect(::applyPick) }

        AppGraph.vpn.bind(this)

        // Seed the GeoIP databases and register the engine home directory up
        // front, so config validation works before the first VPN start.
        // Idempotent; off the main thread to avoid first-run jank.
        scope.launch(Dispatchers.IO) {
            try {
                MeowInstance.prepareEngineHome(applicationContext)
            } catch (e: Exception) {
                Timber.w(e, "prepareEngineHome failed")
            }
        }

        // MainActivity is exported, so any app could send this extra. Only the
        // e2e harness needs it, and it installs the debug APK: ignore it in
        // non-debuggable builds so third-party apps cannot force-start the VPN.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val autoConnect = debuggable &&
            intent?.getBooleanExtra("auto_connect", false) == true

        // Recreation (rotation, process death) hands back the launch intent;
        // only a link the user has not answered yet may survive it.
        pendingLink = if (savedInstanceState == null) {
            installLinkOf(intent)
        } else {
            savedInstanceState.getString(KEY_PENDING_LINK)
        }

        setContent {
            val link = pendingLink
            val installLink = remember(link) { link?.let(InstallConfigLink::parse) }
            MeowTheme(darkTheme = dark) {
                MeowApp(
                    autoConnect = autoConnect,
                    installLink = installLink,
                    onInstallLinkHandled = { pendingLink = null },
                )
            }
        }
    }

    private fun applyPick(mode: ThemeMode) {
        dark = (mode.nightMode ?: systemNight) == Configuration.UI_MODE_NIGHT_YES
        // enableEdgeToEdge's default styles, but dark when the pick is: the
        // configuration only agrees with the pick this instance started with.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
    }

    // singleTask: a link tapped while the app is alive arrives here. auto_connect
    // stays a cold-start-only contract; the harness force-stops before using it.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        installLinkOf(intent)?.let { pendingLink = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_LINK, pendingLink)
    }

    /**
     * The install-config link [intent] opens, or null. Validation is the
     * parser's job; a rejected link still reaches the UI so it can say why.
     *
     * Reopening the task from Recents after Back replays the intent that
     * created it, which would offer an already-answered link a second time.
     */
    private fun installLinkOf(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return intent.dataString
    }

    override fun onDestroy() {
        AppGraph.vpn.unbind(this)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val KEY_PENDING_LINK = "pending_install_link"

        /** enableEdgeToEdge's navigation bar scrims; only drawn before API 29. */
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
