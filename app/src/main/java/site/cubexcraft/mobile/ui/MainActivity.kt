// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.CrashReporter
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.data.RunLog
import site.cubexcraft.mobile.game.GameActivity
import site.cubexcraft.mobile.game.Launch
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.LogUpload
import site.cubexcraft.mobile.net.Relay
import site.cubexcraft.mobile.update.SelfUpdate
import kotlin.concurrent.thread

/**
 * CubeX Mobile 3.0: the launcher is the same web UI as CubeXCraft for iPhone (assets/cxc-ui) in a WebView, the phone side is
 * WebBridge. Over it, native windows: the app update and the game's exit with an error. The game runs in its own process
 * (GameActivity) and comes back here with its exit code.
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_EXIT_CODE = "exitCode"
        const val EXTRA_EXIT_SIGNAL = "exitSignal"
        private const val TAG = "CubexMain"
    }

    private lateinit var frame: FrameLayout
    private lateinit var web: WebView
    private lateinit var bridge: WebBridge
    private var dialog: View? = null
    private var dialogCancelable = true
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("auth", Context.MODE_PRIVATE) }
    @Volatile var gameRunning = false
    /** The last server the game was started for («Попробовать снова») and its name (friends see «playing on …»). */
    @Volatile var lastServer: String? = null
    @Volatile var playingName: String? = null

    val token: String? get() = prefs.getString("token", null)
    val username: String? get() = prefs.getString("username", null)
    val uuid: String? get() = prefs.getString("uuid", null)

    fun sidePadding(): Int {
        val w = resources.configuration.screenWidthDp
        return dp(maxOf(16, (w - 560) / 2))
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Cx.BG
            window.navigationBarColor = Cx.BG
        }
        frame = FrameLayout(this).apply { setBackgroundColor(Cx.BG) }
        // Android 15 draws apps under the system bars: keep the UI out of them (the page's safe-area insets are 0 here)
        frame.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(frame)

        // first what must work even if the UI is broken: crash reports and the update that fixes it
        uploadCrashReports()
        SelfUpdate.onState = { st -> runOnUiThread { runCatching { Dialogs.update(this, st) } } }
        SelfUpdate.start(this)
        thread(name = "cubex-probe") { Relay.probe() }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web = WebView(this).apply {
            setBackgroundColor(Cx.BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // the UI keeps its cache (servers, profiles) in localStorage
            settings.allowFileAccess = false           // file:///android_asset still loads
            settings.allowContentAccess = false
            settings.textZoom = 100                    // the design's sizes, whatever the system font scale
            settings.mediaPlaybackRequiresUserGesture = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                // the page never leaves the app's assets: links open in the browser
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    openLink(request.url.toString())
                    return true
                }

                // the site's pictures (skins) through the app's client: through the RU relay when it is on
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    val host = request.url.host ?: return null
                    if (request.method != "GET" || (host != "www.cubexcraft.site" && host != "cubexcraft.site")) return null
                    return runCatching {
                        val r = Api.http.newCall(okhttp3.Request.Builder().url(request.url.toString()).build()).execute()
                        val type = r.header("Content-Type").orEmpty().substringBefore(';').ifEmpty { "application/octet-stream" }
                        android.webkit.WebResourceResponse(type, null, r.code, r.message.ifEmpty { "OK" },
                            mapOf("Access-Control-Allow-Origin" to "*"), r.body!!.byteStream())
                    }.getOrNull()
                }
            }
        }
        bridge = WebBridge(this, web)
        web.addJavascriptInterface(bridge, "CxcAndroid")
        frame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        web.loadUrl("file:///android_asset/cxc-ui/index.html")
        handleExit(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExit(intent)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // the game's process is gone without telling us (killed by Android for memory): its end all the same
        if (gameRunning && !gameAlive()) {
            Log.w(TAG, "game process gone without an exit call")
            handleExit(Intent().putExtra(EXTRA_EXIT_CODE, 0))
        }
    }

    private fun gameAlive(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.runningAppProcesses.orEmpty().any { it.processName == "$packageName:game" }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dialog != null) {
            if (dialogCancelable) closeDialog()
            return
        }
        // the page's own «back»; with nothing to go back to — the app goes to the background (as Home)
        web.evaluateJavascript("window.__cxcBack ? window.__cxcBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    // --- windows over the UI ---

    /** A window over the screen (dimmed background); a tap outside closes it when [cancelable]. */
    fun showDialog(card: View, cancelable: Boolean = true) {
        closeDialog()
        val shade = FrameLayout(this).apply {
            setBackgroundColor(Cx.alpha(0, 0.77f))
            isClickable = true
            if (cancelable) setOnClickListener { closeDialog() }
            val side = sidePadding()
            addView(card.apply { isClickable = true }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(side, 0, side, 0) })
        }
        dialog = shade
        dialogCancelable = cancelable
        frame.addView(shade, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun closeDialog() {
        dialog?.let { frame.removeView(it) }
        dialog = null
    }

    fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** Seven taps on the version (as on iPhone): for us, not for players — the log of the last run. */
    fun diagnostics() {
        val c = Dialogs.card(this, Cx.SOFT, R.drawable.ic_log, "CubeX Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Launch.totalRamMb(this)} MB")
        c.addView(button(L.t("Поделиться журналом", "Share the log"), BtnStyle.OUTLINE) {
            thread(name = "cubex-sharelog") { RunLog.collect(this); runOnUiThread { RunLog.share(this) } }
        }, lp(MATCH, dp(52)).margins(t = dp(16)))
        c.addView(button(L.t("Отправить журнал разработчикам", "Send the log to the developers")) {
            thread(name = "cubex-sendlog") {
                val ok = LogUpload.send(this, "manual", RunLog.collect(this))
                runOnUiThread { if (ok) closeDialog() else RunLog.share(this) }
            }
        }, lp(MATCH, dp(52)).margins(t = dp(8)))
        c.addView(button(L.t("Закрыть", "Close"), BtnStyle.GHOST) { closeDialog() }, lp(MATCH, dp(52)).margins(t = dp(8)))
        showDialog(c)
    }

    // --- account ---

    fun saveLogin(token: String, username: String, uuid: String?) {
        prefs.edit().putString("token", token).putString("username", username).putString("uuid", uuid).apply()
    }

    fun logout() {
        val t = token
        prefs.edit().clear().apply()
        if (t != null) thread(name = "cubex-logout") { runCatching { Api.presenceOffline(t); Api.logout(t) } }
        closeDialog()
    }

    // --- «playing» for friends while the game runs (the UI sends «online» itself) ---

    private val presenceTick = object : Runnable {
        override fun run() {
            if (!gameRunning) return
            val t = token ?: return
            thread(name = "cubex-presence") { runCatching { Api.presence(t, "playing", playingName) } }
            handler.postDelayed(this, 30_000)
        }
    }

    // --- the game ---

    /** Starts the installed build; [server] — «host:port» to join right away, or null for the main menu. */
    fun startGame(server: String?) {
        val t = token ?: return
        lastServer = server
        thread(name = "cubex-play") {
            try {
                val (profile, layout) = Installer.installed(this) ?: error("build not installed")
                java.io.File(filesDir, "last-launch-error.txt").delete()
                Settings.applyToGame(this, layout.client)
                val s = Api.gameSession(t)
                gameRunning = true
                startActivity(
                    Intent(this, GameActivity::class.java)
                        .putExtra(GameActivity.EXTRA_FILES_DIR, layout.client.name)
                        .putExtra(GameActivity.EXTRA_USERNAME, s.getString("username"))
                        .putExtra(GameActivity.EXTRA_UUID, s.getString("uuid"))
                        .putExtra(GameActivity.EXTRA_TOKEN, s.getString("accessToken"))
                        // «Сразу на сервер» without the server found: the game takes the profile's server
                        .putExtra(GameActivity.EXTRA_JOIN, server != null || Settings.joinServer)
                        .putExtra(GameActivity.EXTRA_SERVER, server)
                        .putExtra(GameActivity.EXTRA_SCALE, Settings.renderScale(this))
                )
                handler.post { handler.removeCallbacks(presenceTick); handler.post(presenceTick) }
                Log.i(TAG, "game started: ${profile.optString("title")}, heap ${Launch.heapMb(this, profile)} MB")
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                gameRunning = false
                bridge.emit("prepError", JSONObject().put("message", if (e is java.io.IOException) L.t("Нет связи с сервером", "No connection to the server") else (e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    /** Back from the game: the UI's main screen again; on a failure the error window, and the log to the developers (test builds). */
    private fun handleExit(i: Intent) {
        if (!i.hasExtra(EXTRA_EXIT_CODE)) return
        gameRunning = false
        handler.removeCallbacks(presenceTick)
        val code = i.getIntExtra(EXTRA_EXIT_CODE, 0)
        i.removeExtra(EXTRA_EXIT_CODE)
        bridge.emit("resume")
        thread(name = "cubex-exit") {
            Thread.sleep(800)   // the game process flushes its last lines
            val log = RunLog.collect(this)
            if (BuildConfig.DEBUG) LogUpload.send(this, if (code == 0) "run" else "crash", log)
            if (code != 0) runOnUiThread {
                if (RunLog.outOfMemory(log)) Dialogs.error(this, Dialogs.Kind.MEMORY, null)
                else Dialogs.error(this, Dialogs.Kind.CRASH, "exit code $code" + (RunLog.cause(log)?.let { " · $it" } ?: ""))
            }
        }
    }

    /** A crash of the last start of the app (ours or Android's account of it) goes to the developers. */
    private fun uploadCrashReports() = thread(name = "cubex-crashes") {
        runCatching {
            val report = CrashReporter.pending(this) ?: return@thread
            if (LogUpload.send(this, "appcrash", report.text + "\n--- logcat\n" + LogUpload.logcat(1500))) report.done()
        }
    }
}
