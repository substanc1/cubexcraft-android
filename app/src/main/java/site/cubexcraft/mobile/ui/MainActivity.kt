// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.CrashReporter
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.data.Repo
import site.cubexcraft.mobile.data.RunLog
import site.cubexcraft.mobile.game.GameActivity
import site.cubexcraft.mobile.game.Launch
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.install.Runtimes
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.LogUpload
import site.cubexcraft.mobile.net.Relay
import site.cubexcraft.mobile.update.SelfUpdate
import kotlin.concurrent.thread

/** One screen of the app: built once, shown in MainActivity's frame. */
abstract class Screen(val main: MainActivity) {
    val ctx: Context get() = main
    protected abstract fun build(): View
    val view: View by lazy { build() }
    open fun onShow() {}
    open fun onHide() {}
    /** true: handled (the screen stays). */
    open fun onBack(): Boolean = false

    protected fun dp(v: Int) = main.dp(v)

    /** The page: a fixed header, the scrolling content, a fixed bottom bar with the main action (design: 16 dp sides). */
    protected fun page(header: View?, content: LinearLayout, bottom: View? = null): View {
        val side = main.sidePadding()
        val col = main.vbox()
        if (header != null) col.addView(header, lp().apply { setMargins(side, dp(12), side, dp(4)) })
        content.setPadding(side, dp(16), side, dp(24))
        col.addView(ScrollView(main).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        }, lp(MATCH, 0, 1f))
        if (bottom != null) {
            col.addView(View(main).apply { setBackgroundColor(Cx.LINE) }, lp(MATCH, dp(1)))
            col.addView(bottom, lp().apply { setMargins(side, dp(10), side, dp(10)) })
        }
        return col
    }

    /** CUBEX MOBILE with the cube (screens before login). */
    protected fun brand(): View = main.hbox().apply {
        addView(main.icon(R.drawable.logo_cube, 30, 0).apply { imageTintList = null; layoutParams = lp(dp(30), dp(33)) })
        addView(main.text("CUBEX ", Cx.Font.DISPLAY, 15f, Cx.TEXT, 0.08f), lp(WRAP, WRAP).margins(l = dp(10)))
        addView(main.text("MOBILE", Cx.Font.DISPLAY, 15f, Cx.ACCENT, 0.08f))
        minimumHeight = dp(44)
    }

    /** ← TITLE (inner screens). */
    protected fun titleBar(title: CharSequence, onBack: () -> Unit = { main.back() }): View = main.hbox().apply {
        addView(main.icon(R.drawable.ic_back, 24, Cx.TEXT).apply {
            setPadding(0, 0, 0, 0)
            setOnClickListener { onBack() }
        }, lp(dp(24), dp(24)).margins(r = dp(16)))
        addView(main.text(title, Cx.Font.DISPLAY_SEMI, 15f, Cx.TEXT, 0.06f, upper = true, lines = 1))
        minimumHeight = dp(44)
    }

    /** Buttons stacked in the bottom bar, 8 dp apart. */
    protected fun bar(vararg buttons: View): LinearLayout = main.vbox().apply {
        buttons.forEachIndexed { i, b -> addView(b, (b.layoutParams as? LinearLayout.LayoutParams ?: lp()).apply { if (i > 0) topMargin = dp(8) }) }
    }
}

/**
 * CubeX Mobile 3.0: one activity, the screens of the android3 design swapped in a frame (no fragments: the fastest start).
 * The game runs in its own process (GameActivity) and comes back here with its exit code.
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_EXIT_CODE = "exitCode"
        const val EXTRA_EXIT_SIGNAL = "exitSignal"
        private const val TAG = "CubexMain"
    }

    private lateinit var frame: FrameLayout
    private val stack = ArrayList<Screen>()
    private var dialog: View? = null
    private var dialogCancelable = true
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("auth", Context.MODE_PRIVATE) }
    @Volatile var gameRunning = false

    val token: String? get() = prefs.getString("token", null)
    val username: String? get() = prefs.getString("username", null)

    fun sidePadding(): Int {
        val w = resources.configuration.screenWidthDp
        return dp(maxOf(16, (w - 560) / 2))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Cx.BG
            window.navigationBarColor = Cx.BG
        }
        frame = FrameLayout(this).apply { setBackgroundColor(Cx.BG) }
        // Android 15 draws apps under the system bars: keep the content out of them
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

        // first the things that must work even if a screen is broken: crash reports and the update that fixes it
        uploadCrashReports()
        SelfUpdate.onState = { st -> runOnUiThread { runCatching { Dialogs.update(this, st) } } }
        SelfUpdate.start(this)
        thread(name = "cubex-probe") { Relay.probe() }
        Repo.init(this)
        replaceAll(firstScreen())
        handleExit(intent)
    }

    private fun firstScreen(): Screen = when {
        !Settings.deviceChecked -> DeviceScreen(this)
        token == null -> LoginScreen(this)
        else -> HomeScreen(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExit(intent)
    }

    // --- navigation ---

    private fun setScreen(s: Screen) {
        frame.removeAllViews()
        try {
            (s.view.parent as? FrameLayout)?.removeView(s.view)
            frame.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
            dialog?.let { frame.addView(it) }
            s.onShow()
        } catch (t: Throwable) {
            // a broken screen must not close the app: the update that fixes it is downloading meanwhile
            Log.e(TAG, "screen ${s.javaClass.simpleName} failed", t)
            frame.removeAllViews()
            frame.addView(brokenScreen(t), FrameLayout.LayoutParams(MATCH, MATCH))
            thread(name = "cubex-ui-error") { LogUpload.send(this, "error", "screen ${s.javaClass.simpleName}: " + Log.getStackTraceString(t)) }
        }
    }

    private fun brokenScreen(t: Throwable): View = vbox().apply {
        setPadding(dp(16), dp(24), dp(16), dp(24))
        addView(text("CubeX Mobile ${BuildConfig.VERSION_NAME}", Cx.Font.DISPLAY_SEMI, 15f, Cx.TEXT))
        addView(text(L.t("Экран не открылся. Мы уже получили отчёт; обновление придёт само.", "This screen failed to open. We got the report; an update will come by itself."),
            Cx.Font.BODY, 14f, Cx.SOFT), lp().margins(t = dp(12)))
        addView(text(t.toString().take(300), Cx.Font.MONO, 12f, Cx.WARNING), lp().margins(t = dp(12)))
        addView(button(L.t("Поделиться журналом", "Share the log"), BtnStyle.OUTLINE) {
            thread(name = "cubex-sharelog") { RunLog.collect(this@MainActivity); runOnUiThread { RunLog.share(this@MainActivity) } }
        }, lp(MATCH, dp(52)).margins(t = dp(16)))
    }

    fun open(s: Screen) {
        stack.lastOrNull()?.onHide()
        stack += s
        setScreen(s)
    }

    fun replaceAll(s: Screen) {
        stack.lastOrNull()?.onHide()
        stack.clear()
        stack += s
        setScreen(s)
    }

    fun back() {
        if (dialog != null) {
            if (dialogCancelable) closeDialog()
            return
        }
        val top = stack.lastOrNull() ?: return finish()
        if (top.onBack()) return
        pop()
    }

    /** Closes the top screen without asking it (its own «back» already decided). */
    fun pop() {
        val top = stack.lastOrNull() ?: return finish()
        if (stack.size > 1) {
            top.onHide()
            stack.removeAt(stack.size - 1)
            setScreen(stack.last())
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

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

    // --- account ---

    fun loggedIn(token: String, username: String) {
        prefs.edit().putString("token", token).putString("username", username).apply()
        Repo.refresh(token)
        replaceAll(HomeScreen(this))
    }

    fun logout() {
        val t = token
        prefs.edit().clear().apply()
        Repo.clearAccount()
        if (t != null) thread(name = "cubex-logout") { runCatching { Api.presenceOffline(t); Api.logout(t) } }
        closeDialog()
        replaceAll(LoginScreen(this))
    }

    // --- «in the app» for friends: every 30 s while open ---

    private val presenceTick = object : Runnable {
        override fun run() {
            val t = token ?: return
            Repo.presence(t, if (gameRunning) "playing" else "online", if (gameRunning) Settings.server else null)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(presenceTick)
        handler.post(presenceTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(presenceTick)
        if (!gameRunning) token?.let { Repo.presence(it, "away") }
    }

    // --- the game ---

    /** Starts the installed build; [server] — «host:port» to join right away, or null for the main menu. */
    fun startGame(server: String?) {
        val t = token ?: return replaceAll(LoginScreen(this))
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
                        // «Сразу на сервер» without the list loaded yet: the game takes the profile's server
                        .putExtra(GameActivity.EXTRA_JOIN, server != null || Settings.joinServer)
                        .putExtra(GameActivity.EXTRA_SERVER, server)
                        .putExtra(GameActivity.EXTRA_SCALE, Settings.renderScale(this))
                )
                Log.i(TAG, "game started: ${profile.optString("title")}, heap ${Launch.heapMb(this, profile)} MB")
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                gameRunning = false
                runOnUiThread {
                    if (t is java.io.IOException) Dialogs.error(this, Dialogs.Kind.NET, null)
                    else Dialogs.error(this, Dialogs.Kind.START, t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    /** Back from the game: on a failure the error window, and the log of the run to the developers (test builds). */
    private fun handleExit(i: Intent) {
        if (!i.hasExtra(EXTRA_EXIT_CODE)) return
        gameRunning = false
        val code = i.getIntExtra(EXTRA_EXIT_CODE, 0)
        i.removeExtra(EXTRA_EXIT_CODE)
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
