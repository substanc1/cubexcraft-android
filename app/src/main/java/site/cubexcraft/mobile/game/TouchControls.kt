// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import net.kdt.pojavlaunch.GrabListener
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * Touch controls over the game: the same buttons in the same places as CubeX Mobile 2.7.x and CubeXCraft for iPhone
 * (CXCTouchControls). Sizes and margins are in % of the screen height (H), so one description fits 16:9 to 21:9.
 * Drawn on a Canvas only when something changes; a touch goes to the game at once, nothing is allocated per event.
 *
 * In the world: joystick (pushed fully forward = sprint), jump, sneak, sprint toggle, drop, pick; on top hide, chat,
 * inventory, players, view, F3, pause and help. Free space: swipe = look, tap up to 0.25 s = use (right button), holding
 * still for 0.3 s = break (left button), two-finger tap = pick block (middle button), tap on the hotbar = choose the slot.
 * In menus the finger is the cursor (left button); a second finger scrolls or, tapped, right-clicks; «Back» and keyboard.
 */
@SuppressLint("ViewConstructor")
class TouchControls(
    ctx: Context,
    private val optionsFile: File,
    private val onKeyboard: (show: Boolean?) -> Unit,
    private val onHelp: () -> Unit,
    private val onGrab: (Boolean) -> Unit,
) : View(ctx), GrabListener {

    private enum class Layer { TOP, COLLAPSED, ACTIONS, MOVE, MENU }
    private enum class Style { RECT, ROUND, JUMP, SPRINT, INFO, KEYBOARD }

    /** Geometry in H: size w×h, from the left (l) or right (r) edge, from the top (t) or bottom (b) edge. */
    private class Btn(
        val layer: Layer, val label: String, val style: Style, val w: Float, val h: Float,
        var l: Float = Float.NaN, val r: Float = Float.NaN, val t: Float = Float.NaN, val b: Float = Float.NaN,
        val keys: IntArray = IntArray(0), val toggle: Boolean = false, val swipe: Boolean = false,
        val action: (() -> Unit)? = null,
    ) {
        val rect = RectF()
        var textSize = 0f
        var pointer = -1   // the finger on it
        var on = false     // a toggle that is on
        val pressed get() = pointer >= 0 || on
    }

    private val ru = Locale.getDefault().language == "ru"
    private fun label(ru: String, en: String) = (if (this.ru) ru else en).uppercase(Locale.ROOT)

    private val rowH = 13.4f
    private val sprint = Btn(Layer.MOVE, label("Бег", "Sprint"), Style.SPRINT, 23f, rowH, r = 3f, b = 33f,
        keys = intArrayOf(Glfw.KEY_LEFT_CONTROL), toggle = true)
    private val players = Btn(Layer.TOP, label("Игроки", "Players"), Style.RECT, 20f, rowH, t = 3f, keys = intArrayOf(Glfw.KEY_TAB), toggle = true)
    private var hidden = false

    private val top = listOf(
        Btn(Layer.TOP, label("Скрыть", "Hide"), Style.RECT, 20f, rowH, t = 3f, action = { setHidden(true) }),
        // «Chat» opens the keyboard right away (it closes itself when the game takes the mouse again)
        Btn(Layer.TOP, label("Чат", "Chat"), Style.RECT, 20f, rowH, t = 3f, keys = intArrayOf(Glfw.KEY_T), action = { onKeyboard(true) }),
        Btn(Layer.TOP, label("Инвентарь", "Inventory"), Style.RECT, 20f, rowH, t = 3f, keys = intArrayOf(Glfw.KEY_E)),
        players,
        Btn(Layer.TOP, label("Вид 1/3", "View 1/3"), Style.RECT, 20f, rowH, t = 3f, keys = intArrayOf(Glfw.KEY_F5)),
        Btn(Layer.TOP, "F3", Style.RECT, 20f, rowH, t = 3f, keys = intArrayOf(Glfw.KEY_F3)),
        Btn(Layer.TOP, label("Пауза", "Pause"), Style.RECT, 20f, rowH, t = 3f, keys = intArrayOf(Glfw.KEY_ESCAPE)),
    )
    private val info = Btn(Layer.TOP, "i", Style.INFO, rowH, rowH, t = 3f, action = { onHelp() })
    private val show = Btn(Layer.COLLAPSED, label("Показать", "Show"), Style.RECT, 20f, rowH, t = 3f, action = { setHidden(false) })

    private val buttons: List<Btn> = top + info + show + listOf(
        // bottom right: jump, sneak, sprint; above them drop and pick
        Btn(Layer.MOVE, label("Прыжок", "Jump"), Style.JUMP, 26f, 26f, r = 3f, b = 5f, keys = intArrayOf(Glfw.KEY_SPACE), swipe = true),
        Btn(Layer.MOVE, label("Присесть", "Sneak"), Style.ROUND, 19f, 19f, r = 31f, b = 5f, keys = intArrayOf(Glfw.KEY_LEFT_SHIFT), swipe = true),
        sprint,
        Btn(Layer.ACTIONS, label("Выброс", "Drop"), Style.RECT, 20f, rowH, r = 31f, b = 33f, keys = intArrayOf(Glfw.KEY_Q)),
        Btn(Layer.ACTIONS, label("Взять", "Pick"), Style.RECT, 20f, rowH, r = 52.5f, b = 33f, keys = intArrayOf(MOUSE_MIDDLE_KEY)),
        // menus and inventory: back and the keyboard (search fields, signs, anvil)
        Btn(Layer.MENU, label("Назад", "Back"), Style.RECT, 20f, rowH, r = 3f, t = 3f, keys = intArrayOf(Glfw.KEY_ESCAPE)),
        Btn(Layer.MENU, "", Style.KEYBOARD, rowH, rowH, r = 24.5f, t = 3f, action = { onKeyboard(null) }),
    )

    // joystick: bottom left, 36 % H
    private val stickSize = 36f
    private var stickX = 0f
    private var stickY = 0f
    private var stickR = 0f
    private var stickPointer = -1
    private var knobDx = 0f
    private var knobDy = 0f
    private var stickKeys = 0        // bits: W A S D, sprint lock
    private val stickKeyCodes = intArrayOf(Glfw.KEY_W, Glfw.KEY_A, Glfw.KEY_S, Glfw.KEY_D, Glfw.KEY_LEFT_CONTROL)

    // fingers: what each one does
    private val role = IntArray(MAX_POINTERS)
    private val roleBtn = IntArray(MAX_POINTERS) { -1 }
    private val downX = FloatArray(MAX_POINTERS)
    private val downY = FloatArray(MAX_POINTERS)
    private val lastX = FloatArray(MAX_POINTERS)
    private val lastY = FloatArray(MAX_POINTERS)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    // world gestures
    private var lookCount = 0
    private var lookFirstAt = 0L
    private var lookMoved = false
    private var holding = false
    private var twoFinger = false
    private val holdCheck = Runnable {
        if (inGame && lookCount == 1 && !lookMoved) {
            holding = true
            CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, true)
        }
    }
    private var hotbarSlot = -1

    // menu gestures
    private var cursorPointer = -1
    private var scrollPointer = -1
    private var scrollAcc = 0f
    private var scrollAt = 0L
    private var scrollMoved = false

    private var inGame = false
    private var unit = 1f            // 1 % H in px
    private var lookScale = 1f       // cursor units per px of a swipe
    private val hotbar = RectF()
    private var slotW = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val density = resources.displayMetrics.density
    private val corner = 6 * density

    init {
        stroke.strokeWidth = 1.5f * density
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        unit = h / 100f
        val wH = w / unit
        // the top row centred; on a narrow screen the buttons get narrower so that the row fits
        val margin = 3f
        val gap = 1.5f
        val fit = min(20f, (wH - 2 * margin - rowH - top.size * gap) / top.size)
        val start = (wH - (top.size * fit + top.size * gap + rowH)) / 2
        top.forEachIndexed { i, b -> b.l = start + i * (fit + gap); b.rect.place(b, fit, w, h) }
        info.l = start + top.size * (fit + gap)
        show.l = start
        for (b in buttons) if (b !in top) b.rect.place(b, if (b === show) fit else b.w, w, h)
        for (b in buttons) b.textSize = fitText(b)
        stickR = stickSize / 2 * unit
        stickX = (3f + stickSize / 2) * unit
        stickY = h - (5f + stickSize / 2) * unit
        // a swipe across the height turns the camera by about 180° at Minecraft's default sensitivity
        lookScale = 1300f / h
        updateHotbar()
    }

    private fun RectF.place(b: Btn, width: Float, w: Int, h: Int) {
        val left = if (!b.l.isNaN()) b.l * unit else w - (width + b.r) * unit
        val topPx = if (!b.t.isNaN()) b.t * unit else h - (b.h + b.b) * unit
        set(left, topPx, left + width * unit, topPx + b.h * unit)
    }

    private fun fitText(b: Btn): Float {
        var size = (if (b.style == Style.INFO) 20f else 12f) * resources.displayMetrics.scaledDensity
        if (b.label.isEmpty()) return size
        text.textSize = size
        val room = b.rect.width() - 8 * density
        val width = text.measureText(b.label)
        if (width > room) size *= room / width
        return size
    }

    /** Minecraft's hotbar on the screen: GUI scale from options.txt (0 = automatic, as the game picks it). */
    private fun updateHotbar() {
        val fbW = CallbackBridge.windowWidth
        val fbH = CallbackBridge.windowHeight
        if (fbW <= 0 || fbH <= 0 || width <= 0) return
        val wanted = runCatching {
            optionsFile.useLines { lines -> lines.firstOrNull { it.startsWith("guiScale:") }?.substringAfter(':')?.trim()?.toIntOrNull() }
        }.getOrNull() ?: 0
        var s = 1
        while (s != wanted && s < fbW && s < fbH && fbW / (s + 1) >= 320 && fbH / (s + 1) >= 240) s++
        val guiW = ceil(fbW.toDouble() / s).toInt()
        val guiH = ceil(fbH.toDouble() / s).toInt()
        val k = width.toFloat() / fbW * s       // view px per GUI px
        val left = (guiW / 2 - 91) * k
        hotbar.set(left, (guiH - 22 - 2) * k, left + 182 * k, guiH * k)
        slotW = 20 * k
    }

    private fun visible(layer: Layer) = when (layer) {
        Layer.MENU -> !inGame
        Layer.MOVE -> inGame
        Layer.TOP, Layer.ACTIONS -> inGame && !hidden
        Layer.COLLAPSED -> inGame && hidden
    }

    private fun setHidden(h: Boolean) {
        hidden = h
        if (h && players.on) { players.on = false; send(Glfw.KEY_TAB, false) }
        invalidate()
    }

    // --- the game state ---

    override fun onGrabState(isGrabbing: Boolean) {
        if (isGrabbing == inGame) return
        releaseAll()
        inGame = isGrabbing
        if (inGame) {
            updateHotbar()
            if (sprint.on) send(Glfw.KEY_LEFT_CONTROL, true)   // a screen releases every key; sprint stays on
        }
        onGrab(isGrabbing)
        invalidate()
    }

    /** Lets go of everything held (a screen opened or closed): no key may stay pressed in the game. */
    private fun releaseAll() {
        removeCallbacks(holdCheck)
        for (b in buttons) {
            if (b.pointer >= 0 && !b.toggle) b.keys.forEach { send(it, false) }
            b.pointer = -1
        }
        if (players.on) { players.on = false; send(Glfw.KEY_TAB, false) }
        if (sprint.on) send(Glfw.KEY_LEFT_CONTROL, false)
        setStickKeys(0)
        stickPointer = -1; knobDx = 0f; knobDy = 0f
        if (holding) CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
        holding = false; lookCount = 0; twoFinger = false
        if (cursorPointer >= 0) CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
        cursorPointer = -1; scrollPointer = -1; hotbarSlot = -1
        role.fill(R_NONE)
        roleBtn.fill(-1)
    }

    private fun send(key: Int, down: Boolean) {
        if (key == MOUSE_MIDDLE_KEY) {
            CallbackBridge.sendMouseButton(Glfw.MOUSE_MIDDLE, down)
        } else {
            CallbackBridge.setModifiers(key, down)
            CallbackBridge.sendKeyPress(key, CallbackBridge.getCurrentMods(), down)
        }
    }

    private fun click(button: Int) {
        CallbackBridge.sendMouseButton(button, true)
        CallbackBridge.sendMouseButton(button, false)
    }

    // --- touches ---

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                if (inGame && sprint.on) send(Glfw.KEY_LEFT_CONTROL, true)
                invalidate()
            }
        }
        return true
    }

    private fun buttonAt(x: Float, y: Float, swipeOnly: Boolean = false): Int {
        for (i in buttons.indices) {
            val b = buttons[i]
            if (visible(b.layer) && (!swipeOnly || b.swipe) && b.rect.contains(x, y)) return i
        }
        return -1
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (id !in 0 until MAX_POINTERS) return
        downX[id] = x; downY[id] = y; lastX[id] = x; lastY[id] = y
        val bi = buttonAt(x, y)
        if (bi >= 0) {
            role[id] = R_BUTTON
            roleBtn[id] = bi
            press(buttons[bi], id)
            return
        }
        if (inGame && stickPointer < 0 && hypot(x - stickX, y - stickY) <= stickR * 1.15f) {
            role[id] = R_STICK
            stickPointer = id
            moveStick(x, y)
            return
        }
        if (inGame) {
            if (hotbar.contains(x, y)) {
                role[id] = R_HOTBAR
                selectSlot(x)
                return
            }
            role[id] = R_LOOK
            lookCount++
            val now = SystemClock.uptimeMillis()
            if (lookCount == 1) {
                lookFirstAt = now; lookMoved = false; twoFinger = false; holding = false
                postDelayed(holdCheck, HOLD_MS)
            } else {
                removeCallbacks(holdCheck)
                twoFinger = lookCount == 2 && !lookMoved && !holding && now - lookFirstAt < TAP_MS
            }
            return
        }
        // menus
        val fx = x * CallbackBridge.windowWidth / width.coerceAtLeast(1)
        val fy = y * CallbackBridge.windowHeight / height.coerceAtLeast(1)
        if (cursorPointer < 0) {
            role[id] = R_CURSOR
            cursorPointer = id
            CallbackBridge.sendCursorPos(fx, fy)
            CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, true)
        } else if (scrollPointer < 0) {
            // a second finger: scroll; the first lets go of the button so nothing gets dragged
            role[id] = R_SCROLL
            scrollPointer = id
            scrollAcc = 0f; scrollMoved = false; scrollAt = SystemClock.uptimeMillis()
            CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id !in 0 until MAX_POINTERS) return
        val dx = x - lastX[id]
        val dy = y - lastY[id]
        if (dx == 0f && dy == 0f) return
        lastX[id] = x; lastY[id] = y
        when (role[id]) {
            R_BUTTON -> {
                val cur = roleBtn[id]
                if (cur >= 0 && !buttons[cur].swipe) return
                // jump and sneak follow the finger: slide from one to the other without lifting it
                val now = buttonAt(x, y, swipeOnly = true)
                if (now == cur) return
                if (cur >= 0) release(buttons[cur])
                roleBtn[id] = now
                if (now >= 0) press(buttons[now], id)
            }
            R_STICK -> moveStick(x, y)
            R_HOTBAR -> if (hotbar.contains(x, y)) selectSlot(x)
            R_LOOK -> {
                if (!lookMoved && hypot(x - downX[id], y - downY[id]) > slop) {
                    lookMoved = true
                    twoFinger = false
                    if (!holding) removeCallbacks(holdCheck)
                }
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX + dx * lookScale, CallbackBridge.mouseY + dy * lookScale)
            }
            R_CURSOR -> if (scrollPointer < 0) {
                CallbackBridge.sendCursorPos(x * CallbackBridge.windowWidth / width.coerceAtLeast(1),
                    y * CallbackBridge.windowHeight / height.coerceAtLeast(1))
            }
            R_SCROLL -> {
                if (hypot(x - downX[id], y - downY[id]) > slop) scrollMoved = true
                scrollAcc += dy
                val step = 4 * unit
                while (abs(scrollAcc) >= step) {
                    val dir = if (scrollAcc > 0) 1f else -1f   // finger down = back to the top, as on a phone
                    CallbackBridge.sendScroll(0f, dir)
                    scrollAcc -= dir * step
                }
            }
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        if (id !in 0 until MAX_POINTERS) return
        when (role[id]) {
            R_BUTTON -> roleBtn[id].takeIf { it >= 0 }?.let { release(buttons[it]) }
            R_STICK -> {
                stickPointer = -1; knobDx = 0f; knobDy = 0f
                setStickKeys(0)
                invalidate()
            }
            R_HOTBAR -> hotbarSlot = -1
            R_LOOK -> {
                lookCount = (lookCount - 1).coerceAtLeast(0)
                if (lookCount == 0) {
                    removeCallbacks(holdCheck)
                    val dt = SystemClock.uptimeMillis() - lookFirstAt
                    when {
                        holding -> CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
                        twoFinger && !lookMoved && dt <= TWO_TAP_MS -> click(Glfw.MOUSE_MIDDLE)
                        !twoFinger && !lookMoved && dt <= TAP_MS -> click(Glfw.MOUSE_RIGHT)
                    }
                    holding = false
                    twoFinger = false
                }
            }
            R_CURSOR -> {
                if (scrollPointer < 0) {
                    CallbackBridge.sendCursorPos(x * CallbackBridge.windowWidth / width.coerceAtLeast(1),
                        y * CallbackBridge.windowHeight / height.coerceAtLeast(1))
                    CallbackBridge.sendMouseButton(Glfw.MOUSE_LEFT, false)
                }
                cursorPointer = -1
            }
            R_SCROLL -> {
                // a quick tap of the second finger: right button where the cursor is (split a stack, context actions)
                if (!scrollMoved && SystemClock.uptimeMillis() - scrollAt <= TAP_MS) click(Glfw.MOUSE_RIGHT)
                scrollPointer = -1
            }
        }
        role[id] = R_NONE
        roleBtn[id] = -1
    }

    private fun press(b: Btn, id: Int) {
        if (b.toggle) {
            b.on = !b.on
            // sprint pushed by the joystick already holds Ctrl
            if (!(b === sprint && stickKeys and LOCK != 0)) b.keys.forEach { send(it, b.on) }
        } else {
            b.pointer = id
            if (b.action != null) {
                b.keys.forEach { CallbackBridge.sendKeyPress(it) }
                b.action.invoke()
            } else {
                b.keys.forEach { send(it, true) }
            }
        }
        invalidate()
    }

    private fun release(b: Btn) {
        if (!b.toggle && b.pointer >= 0) {
            if (b.action == null) b.keys.forEach { send(it, false) }
            b.pointer = -1
            invalidate()
        }
    }

    private fun selectSlot(x: Float) {
        val slot = ((x - hotbar.left - slotW / 20) / slotW).toInt().coerceIn(0, 8)
        if (slot == hotbarSlot) return
        hotbarSlot = slot
        CallbackBridge.sendKeyPress(Glfw.KEY_1 + slot)
    }

    private fun moveStick(x: Float, y: Float) {
        var dx = x - stickX
        var dy = y - stickY
        val len = hypot(dx, dy)
        if (len > stickR) { dx *= stickR / len; dy *= stickR / len }
        knobDx = dx; knobDy = dy
        var keys = 0
        if (len > stickR * DEAD_ZONE) {
            val nx = dx / min(len, stickR).coerceAtLeast(1f)
            val ny = dy / min(len, stickR).coerceAtLeast(1f)
            if (ny < -DIAGONAL) keys = keys or W
            if (nx < -DIAGONAL) keys = keys or A
            if (ny > DIAGONAL) keys = keys or S
            if (nx > DIAGONAL) keys = keys or D
            // pushed fully forward: sprint
            if (len >= stickR * 0.95f && ny < -0.9f) keys = keys or LOCK
        }
        setStickKeys(keys)
        invalidate()
    }

    private fun setStickKeys(keys: Int) {
        val changed = keys xor stickKeys
        if (changed == 0) return
        for (bit in stickKeyCodes.indices) {
            if (changed and (1 shl bit) == 0) continue
            val down = keys and (1 shl bit) != 0
            if (1 shl bit == LOCK && sprint.on) continue   // the sprint button holds Ctrl already
            send(stickKeyCodes[bit], down)
        }
        stickKeys = keys
    }

    // --- drawing ---

    override fun onDraw(c: Canvas) {
        for (b in buttons) if (visible(b.layer)) drawButton(c, b)
        if (inGame) drawStick(c)
    }

    private fun drawButton(c: Canvas, b: Btn) {
        val r = b.rect
        val round = b.style == Style.ROUND || b.style == Style.JUMP || b.style == Style.INFO
        val rad = if (round) min(r.width(), r.height()) / 2 else corner
        val yellow = b.style == Style.SPRINT || b.style == Style.INFO
        fill.color = when (b.style) {
            Style.JUMP -> if (b.pressed) RED_60 else RED_35
            Style.SPRINT, Style.INFO -> YELLOW_DARK_80
            else -> BG_80
        }
        c.drawRoundRect(r, rad, rad, fill)
        if (b.pressed && b.style != Style.JUMP) {
            fill.color = if (yellow) YELLOW_35 else RED_35
            c.drawRoundRect(r, rad, rad, fill)
        }
        stroke.color = if (yellow) YELLOW else RED
        c.drawRoundRect(r, rad, rad, stroke)
        if (b.style == Style.KEYBOARD) {
            drawKeyboardIcon(c, r)
            return
        }
        text.color = if (yellow) YELLOW else TEXT
        text.textSize = b.textSize
        c.drawText(b.label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
    }

    /** ic_keyboard of the design (24×24, line 1.75): the outline, three keys, the space bar. */
    private fun drawKeyboardIcon(c: Canvas, r: RectF) {
        val s = min(r.width(), r.height()) * 0.55f / 24f
        val ox = r.centerX() - 12 * s
        val oy = r.centerY() - 12 * s
        stroke.color = TEXT
        val w = stroke.strokeWidth
        stroke.strokeWidth = 1.75f * s
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawRect(ox + 3 * s, oy + 7 * s, ox + 21 * s, oy + 17 * s, stroke)
        for (kx in intArrayOf(7, 11, 15)) c.drawPoint(ox + kx * s, oy + 11 * s, stroke)
        c.drawLine(ox + 8 * s, oy + 14 * s, ox + 16 * s, oy + 14 * s, stroke)
        stroke.strokeWidth = w
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawStick(c: Canvas) {
        fill.color = BG_50
        c.drawCircle(stickX, stickY, stickR, fill)
        stroke.color = RED
        c.drawCircle(stickX, stickY, stickR, stroke)
        fill.color = if (stickKeys and LOCK != 0) YELLOW else RED_60
        c.drawCircle(stickX + knobDx, stickY + knobDy, stickR * 0.45f, fill)
    }

    companion object {
        private const val MAX_POINTERS = 16
        private const val R_NONE = 0
        private const val R_BUTTON = 1
        private const val R_STICK = 2
        private const val R_LOOK = 3
        private const val R_HOTBAR = 4
        private const val R_CURSOR = 5
        private const val R_SCROLL = 6

        private const val TAP_MS = 250L
        private const val HOLD_MS = 300L
        private const val TWO_TAP_MS = 350L
        private const val DEAD_ZONE = 0.2f
        private const val DIAGONAL = 0.38f   // sin 22.5°: eight directions

        private const val W = 1
        private const val A = 2
        private const val S = 4
        private const val D = 8
        private const val LOCK = 16

        /** Not a GLFW key: the middle mouse button on a button («Pick»). */
        private const val MOUSE_MIDDLE_KEY = -3

        // CubeX colours (as on iPhone)
        private const val BG_80 = 0xCC0B0509.toInt()
        private const val BG_50 = 0x800B0509.toInt()
        private const val RED = 0xFFFF2A3C.toInt()
        private const val RED_35 = 0x59FF2A3C
        private const val RED_60 = 0x99FF2A3C.toInt()
        private const val TEXT = 0xFFF0E2E3.toInt()
        private const val YELLOW = 0xFFFCEE0A.toInt()
        private const val YELLOW_DARK_80 = 0xCC2B2703.toInt()
        private const val YELLOW_35 = 0x59FCEE0A
    }
}
