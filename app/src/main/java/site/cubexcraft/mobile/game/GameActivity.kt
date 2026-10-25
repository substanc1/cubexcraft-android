// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.game

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kdt.pojavlaunch.utils.JREUtils
import org.json.JSONObject
import org.lwjgl.glfw.CallbackBridge
import site.cubexcraft.core.Core
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.Host
import site.cubexcraft.mobile.install.GameLayout
import site.cubexcraft.mobile.install.Runtimes
import site.cubexcraft.mobile.net.LogUpload
import java.io.File

/**
 * The game screen, in its own process (":game"): the JVM runs inside the process and cannot be started twice, so when the game
 * exits the process ends and the launcher process stays. The framebuffer is the screen times the render scale.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_FILES_DIR = "filesDir"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_JOIN = "join"
        const val EXTRA_SCALE = "scale"
        private const val TAG = "CubexGame"

        // «#» starts a section (the same text as CubeXCraft for iPhone)
        private val HELP_RU = listOf(
            "#" to "ЖЕСТЫ",
            "Палец по экрану" to "Обзор — повернуть камеру.",
            "Тап" to "Поставить блок, открыть, использовать (ПКМ).",
            "Удержание" to "Ломать и бить (ЛКМ): держите палец на месте 0,3 с.",
            "Тап двумя пальцами" to "Взять блок под прицелом в руку (СКМ).",
            "Тап по хотбару" to "Выбрать предмет в руку.",
            "#" to "КНОПКИ",
            "Джойстик" to "Слева внизу — ходьба; до упора вперёд — бег.",
            "Прыжок" to "Прыгнуть; в воде — всплыть.",
            "Присесть" to "Красться; не даёт упасть с края блока.",
            "Бег" to "Включает и выключает бег — жёлтая, пока бег включён.",
            "Выброс" to "Выбросить предмет из руки.",
            "Взять" to "Взять блок под прицелом в руку.",
            "Скрыть / Показать" to "Спрятать кнопки — остаются ходьба, прыжок, присесть и бег.",
            "Чат" to "Открыть чат и клавиатуру.",
            "Инвентарь" to "Открыть инвентарь.",
            "Игроки" to "Список игроков — включить и выключить.",
            "Вид 1/3" to "Вид от первого или третьего лица.",
            "F3" to "Экран отладки: кадры в секунду, координаты.",
            "Пауза" to "Меню паузы и настройки.",
            "#" to "В МЕНЮ И ИНВЕНТАРЕ",
            "Палец" to "Курсор: тап — нажать, вести — перетащить предмет.",
            "Второй палец" to "Вести — прокрутка; тап — ПКМ (взять половину стопки).",
            "Назад" to "Закрыть окно.",
            "Клавиатура" to "Ввести текст: поиск, табличка, наковальня.",
            "i" to "Эта подсказка.",
        )
        private val HELP_EN = listOf(
            "#" to "GESTURES",
            "Swipe" to "Look around — turn the camera.",
            "Tap" to "Place a block, open, use (right click).",
            "Hold" to "Break and attack (left click): keep the finger still for 0.3 s.",
            "Two-finger tap" to "Pick the block you are looking at (middle click).",
            "Tap the hotbar" to "Pick the item to hold.",
            "#" to "BUTTONS",
            "Joystick" to "Bottom left — walk; push fully forward to sprint.",
            "Jump" to "Jump; in water — swim up.",
            "Sneak" to "Sneak; keeps you from falling off edges.",
            "Sprint" to "Turns sprinting on and off — yellow while it is on.",
            "Drop" to "Drop the item in your hand.",
            "Pick" to "Pick the block you are looking at.",
            "Hide / Show" to "Hide the buttons — walking, jump, sneak and sprint stay.",
            "Chat" to "Open the chat and the keyboard.",
            "Inventory" to "Open the inventory.",
            "Players" to "Player list — on and off.",
            "View 1/3" to "First or third person view.",
            "F3" to "Debug screen: frames per second, coordinates.",
            "Pause" to "Pause menu and settings.",
            "#" to "MENUS AND INVENTORY",
            "Finger" to "The cursor: tap to click, drag to move an item.",
            "Second finger" to "Drag to scroll; tap for a right click (take half a stack).",
            "Back" to "Close the window.",
            "Keyboard" to "Type: search, signs, anvil.",
            "i" to "This help.",
        )
    }

    private lateinit var view: GameView
    private lateinit var controls: TouchControls
    private lateinit var root: FrameLayout
    private var help: View? = null
    private var started = false
    private var viewW = 0
    private var viewH = 0
    private var fbW = 0
    private var fbH = 0
    private var scale = 0.75f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scale = intent.getFloatExtra(EXTRA_SCALE, 0.75f).coerceIn(0.4f, 1f)
        (Core.host() as? Host)?.scale = scale
        view = GameView(this)
        view.holder.addCallback(this)
        val layout = GameLayout(this, intent.getStringExtra(EXTRA_FILES_DIR) ?: "CubeXCraft-mobile")
        controls = TouchControls(this, File(layout.client, "options.txt"),
            onKeyboard = { show -> keyboard(show) },
            onHelp = { showHelp() },
            onGrab = { grabbing -> if (grabbing) keyboard(false) },
        )
        root = FrameLayout(this)
        root.addView(view)
        root.addView(controls)
        setContentView(root)
        CallbackBridge.addGrabListener(controls)
        immersive()
    }

    override fun onDestroy() {
        CallbackBridge.removeGrabListener(controls)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 8-10
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /** The on-screen keyboard: true = show, false = hide, null = toggle. Typed text goes into the game through GameView. */
    private fun keyboard(show: Boolean?) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        when (show) {
            true -> { view.requestFocus(); imm.showSoftInput(view, 0) }   // 0 = asked by the player (not implicit)
            false -> imm.hideSoftInputFromWindow(view.windowToken, 0)
            null -> view.toggleKeyboard()
        }
    }

    /** «i»: what the gestures and buttons do; a tap anywhere closes it. */
    private fun showHelp() {
        if (help != null) return
        val ru = java.util.Locale.getDefault().language == "ru"
        val rows = if (ru) HELP_RU else HELP_EN
        val sb = SpannableStringBuilder()
        for ((name, desc) in rows) {
            if (name == "#") {
                if (sb.isNotEmpty()) sb.append("\n")
                val s = sb.length
                sb.append(desc).append("\n")
                sb.setSpan(ForegroundColorSpan(0xFFFF2A3C.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val s = sb.length
                sb.append(name)
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" — ").append(desc).append("\n")
            }
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val panel = ScrollView(this).apply {
            setBackgroundColor(0xF00B0509.toInt())
            addView(TextView(this@GameActivity).apply {
                text = sb
                textSize = 14f
                setTextColor(0xFFF0E2E3.toInt())
                setPadding(pad, pad, pad, pad)
                setOnClickListener { closeHelp() }
            })
            setOnClickListener { closeHelp() }
        }
        help = panel
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun closeHelp() {
        help?.let { root.removeView(it) }
        help = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (fbW == 0) {
            // first call: the view's size; ask for a smaller buffer (render scale), the compositor scales it up for free
            viewW = width
            viewH = height
            fbW = ((width * scale).toInt() and 1.inv()).coerceAtLeast(2)
            fbH = ((height * scale).toInt() and 1.inv()).coerceAtLeast(2)
            holder.setFixedSize(fbW, fbH)
            if (fbW == width && fbH == height) startOrAttach(holder)
            return
        }
        startOrAttach(holder)
    }

    private fun startOrAttach(holder: SurfaceHolder) {
        CallbackBridge.physicalWidth = viewW
        CallbackBridge.physicalHeight = viewH
        CallbackBridge.windowWidth = fbW
        CallbackBridge.windowHeight = fbH
        JREUtils.setupBridgeWindow(holder.surface)
        CallbackBridge.sendUpdateWindowSize(fbW, fbH)
        if (started) return
        started = true
        Thread({ runGame() }, "cubex-game").start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // the bridge keeps the last window; a new surface after returning to the app is attached in surfaceChanged
    }

    private fun runGame() {
        try {
            val filesDir = intent.getStringExtra(EXTRA_FILES_DIR) ?: "CubeXCraft-mobile"
            val layout = GameLayout(this, filesDir)
            val profile = JSONObject(layout.profileFile.readText())
            val runtime = Runtimes.ensure(this)
            val session = Session(
                intent.getStringExtra(EXTRA_USERNAME)!!, intent.getStringExtra(EXTRA_UUID)!!, intent.getStringExtra(EXTRA_TOKEN)!!,
            )
            val cfg = Launch.config(this, profile, layout, runtime, session, fbW, fbH, intent.getBooleanExtra(EXTRA_JOIN, false))
            File(layout.client, "logs/launch-command.txt").writeText(Launch.redacted(cfg))
            val lib = File(runtime.javaHome, "lib")
            Log.i(TAG, "launch: heap ${cfg.heapMb} MB, window ${fbW}x$fbH, java ${runtime.javaHome}: " +
                "libjli=${File(lib, "libjli.so").isFile} libjvm=${File(lib, "server/libjvm.so").isFile} " +
                "lib/=${lib.list()?.size ?: -1} files, lwjgl jars=${runtime.lwjglJars.size}, natives=${runtime.lwjglNatives.list()?.size ?: -1}")
            if (BuildConfig.DEBUG) liveLog(layout)
            val code = JREUtils.launchJavaVM(this, cfg)
            Log.i(TAG, "JVM returned $code")
            Core.host().onGameExit(this, code, false)
        } catch (t: Throwable) {
            // -2: our code failed before or around the JVM start (-1 comes from the native launcher: no libjli / JLI_Launch)
            Log.e(TAG, "launch failed", t)
            runCatching { File(filesDir, "last-launch-error.txt").writeText(Log.getStackTraceString(t)) }
            Core.host().onGameExit(this, -2, false)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Test builds: while the game runs, the tail of its log goes to the server every minute (a freeze shows up without exiting). */
    private fun liveLog(layout: GameLayout) {
        val t = Thread({
            // right away what is being launched (if the JVM dies natively there may be nothing else), then at 15 s and every minute
            LogUpload.send(this, "start", LogUpload.tail(File(layout.client, "logs/launch-command.txt"), 5) + "--- logcat\n" + LogUpload.logcat(300))
            var first = true
            while (true) {
                Thread.sleep(if (first) 15_000 else 60_000)
                first = false
                val text = "uptime ${SystemClock.elapsedRealtime() / 1000} s\n" +
                    LogUpload.tail(File(layout.client, "logs/latest.log"), 300) +
                    LogUpload.tail(File(layout.client, "logs/launcher-native.log"), 100) +
                    "--- logcat\n" + LogUpload.logcat(300)
                LogUpload.send(this, "live", text)
            }
        }, "cubex-livelog")
        t.isDaemon = true
        t.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (help != null) closeHelp() else CallbackBridge.sendKeyPress(Glfw.KEY_ESCAPE)
            return true
        }
        return view.sendKey(event, true) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return view.sendKey(event, false) || super.onKeyUp(keyCode, event)
    }

    /** The game's surface; touches are TouchControls' (on top of it), the keyboard's text comes in here. */
    inner class GameView(ctx: Context) : SurfaceView(ctx) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun toggleKeyboard() {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }

        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEND
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    text.forEach { CallbackBridge.sendChar(it, 0) }
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceAtLeast(1)) { CallbackBridge.sendKeyPress(Glfw.KEY_BACKSPACE) }
                    return true
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    CallbackBridge.sendKeyPress(Glfw.KEY_ENTER)
                    return true
                }
            }
        }

        /** Hardware or soft-keyboard key events: Enter, Backspace, arrows; printable characters go as chars. */
        fun sendKey(e: KeyEvent, down: Boolean): Boolean {
            val glfw = when (e.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Glfw.KEY_ENTER
                KeyEvent.KEYCODE_DEL -> Glfw.KEY_BACKSPACE
                KeyEvent.KEYCODE_TAB -> Glfw.KEY_TAB
                KeyEvent.KEYCODE_DPAD_UP -> Glfw.KEY_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> Glfw.KEY_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> Glfw.KEY_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> Glfw.KEY_RIGHT
                KeyEvent.KEYCODE_ESCAPE -> Glfw.KEY_ESCAPE
                else -> 0
            }
            if (glfw != 0) {
                CallbackBridge.sendKeycode(glfw, '\u0000', 0, CallbackBridge.getCurrentMods(), down)
                return true
            }
            val ch = e.unicodeChar
            if (down && ch != 0) {
                CallbackBridge.sendChar(ch.toChar(), CallbackBridge.getCurrentMods())
                return true
            }
            return false
        }
    }
}
