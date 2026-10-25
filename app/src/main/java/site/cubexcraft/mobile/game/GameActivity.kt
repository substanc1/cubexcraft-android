// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.game

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
import kotlin.math.abs
import kotlin.math.hypot

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
    }

    private lateinit var view: GameView
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
        val root = FrameLayout(this)
        root.addView(view)
        root.addView(TextView(this).apply {
            text = "⌨"
            textSize = 20f
            setTextColor(0x99F0E2E3.toInt())
            setPadding(24, 12, 24, 12)
            setOnClickListener { view.toggleKeyboard() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
        setContentView(root)
        immersive()
    }

    private fun immersive() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
            while (true) {
                Thread.sleep(60_000)
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
            CallbackBridge.sendKeyPress(Glfw.KEY_ESCAPE)
            return true
        }
        return view.sendKey(event, true) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return view.sendKey(event, false) || super.onKeyUp(keyCode, event)
    }

    /** Touch for the first test build: in menus the finger is the cursor; in the world drag = look, tap = hit, hold = break. */
    inner class GameView(ctx: Context) : SurfaceView(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var downAt = 0L
        private var holding = false
        private var moved = false
        private val sensitivity = 1.6f

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        private fun fx(x: Float) = x * fbW / viewW.coerceAtLeast(1)
        private fun fy(y: Float) = y * fbH / viewH.coerceAtLeast(1)

        private val holdCheck = Runnable {
            if (!moved && CallbackBridge.isGrabbing()) {
                holding = true
                CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, true)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val grab = CallbackBridge.isGrabbing()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    holding = false
                    if (grab) {
                        postDelayed(holdCheck, 300)
                    } else {
                        CallbackBridge.sendCursorPos(fx(e.x), fy(e.y))
                        CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, true)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (grab) {
                    // second finger: use / place
                    CallbackBridge.sendMouseButton(Glfw.MOUSE_RIGHT, true)
                    CallbackBridge.sendMouseButton(Glfw.MOUSE_RIGHT, false)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - downX, e.y - downY) > 12) moved = true
                    if (grab) {
                        val dx = (e.x - lastX) * sensitivity
                        val dy = (e.y - lastY) * sensitivity
                        CallbackBridge.sendCursorPos(CallbackBridge.mouseX + dx, CallbackBridge.mouseY + dy)
                    } else {
                        CallbackBridge.sendCursorPos(fx(e.x), fy(e.y))
                    }
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(holdCheck)
                    if (grab) {
                        if (holding) {
                            CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
                        } else if (!moved && SystemClock.uptimeMillis() - downAt < 300 && abs(e.x - downX) < 12) {
                            CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, true)
                            CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
                        }
                    } else {
                        CallbackBridge.sendCursorPos(fx(e.x), fy(e.y))
                        CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
                    }
                    holding = false
                }
            }
            return true
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
