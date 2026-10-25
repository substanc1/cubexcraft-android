// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.game

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.ui.Bar
import site.cubexcraft.mobile.ui.BtnStyle
import site.cubexcraft.mobile.ui.Cx
import site.cubexcraft.mobile.ui.L
import site.cubexcraft.mobile.ui.MATCH
import site.cubexcraft.mobile.ui.WRAP
import site.cubexcraft.mobile.ui.box
import site.cubexcraft.mobile.ui.button
import site.cubexcraft.mobile.ui.dp
import site.cubexcraft.mobile.ui.hbox
import site.cubexcraft.mobile.ui.icon
import site.cubexcraft.mobile.ui.lp
import site.cubexcraft.mobile.ui.margins
import site.cubexcraft.mobile.ui.text
import site.cubexcraft.mobile.ui.vbox
import java.io.File
import java.io.RandomAccessFile

/**
 * 05, over the game while it starts: Java, mods, the world — with the time each took last time, the overall progress and a tip.
 * The stages are read from the game's log; the overlay goes away in the main menu or in the world, when the game shows an
 * error screen of its own, or after 4 minutes whatever happens (it must never hide the game).
 */
@SuppressLint("ViewConstructor")
class LaunchOverlay(ctx: Context, private val log: File, private val join: Boolean, private val onCancel: () -> Unit) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("launch", Context.MODE_PRIVATE)
    private val names = listOf(L.t("Подготовка Java", "Preparing Java"), L.t("Загрузка модов", "Loading mods"), L.t("Запуск мира", "Starting the world"))
    private val stages = if (join) 3 else 2
    private val estimate = LongArray(3) { i -> prefs.getLong("stage$i", longArrayOf(15, 50, 30)[i]) }
    private val began = LongArray(3)
    private val took = LongArray(3) { -1 }
    @Volatile private var stage = 0
    @Volatile private var gone = false
    private val start = SystemClock.elapsedRealtime()
    private val rows = ArrayList<Triple<FrameLayout, TextView, TextView>>()
    private val bar = Bar(ctx)
    private val leftText: TextView
    private val tip: TextView
    private val dots = ArrayList<View>()
    private var tipIndex = 0

    private val tips = listOf(
        L.t("Нажмите на кнопку «Взять» на блоке, чтобы получить такой же в инвентарь.", "Tap «Pick» on a block to get the same one into your inventory."),
        L.t("Держите палец на блоке 0,3 с, чтобы ломать его.", "Keep the finger on a block for 0.3 s to break it."),
        L.t("Тап двумя пальцами — взять блок под прицелом в руку.", "A two-finger tap picks the block you look at."),
        L.t("Кнопка «i» в игре — подсказка по всем кнопкам.", "The «i» button in the game explains every button."),
    )

    init {
        setBackgroundColor(Cx.BG)
        isClickable = true   // touches stay here, not in the game, while it loads
        val side = maxOf(ctx.dp(16), (resources.displayMetrics.widthPixels - ctx.dp(560)) / 2)
        val col = ctx.vbox().apply { setPadding(side, ctx.dp(16), side, ctx.dp(16)) }
        col.addView(ctx.hbox().apply {
            addView(ctx.icon(R.drawable.ic_play, 24, Cx.TEXT), lp(ctx.dp(24), ctx.dp(24)).margins(r = ctx.dp(16)))
            addView(ctx.text(L.t("Запуск", "Starting"), Cx.Font.DISPLAY_SEMI, 15f, Cx.TEXT, 0.06f, upper = true))
        })
        col.addView(ctx.text(L.t("Запускаем CubeXCraft", "Starting CubeXCraft"), Cx.Font.DISPLAY, 20f, Cx.TEXT_HI), lp().margins(t = ctx.dp(20)))
        col.addView(ctx.text(L.t("Обычно 1–2 минуты. Экран можно не трогать.", "Usually 1–2 minutes. No need to touch the screen."), Cx.Font.BODY, 14f, Cx.MUTED), lp().margins(t = ctx.dp(6)))
        for (i in 0 until stages) {
            val mark = FrameLayout(ctx)
            val name = ctx.text(names[i], Cx.Font.DISPLAY_SEMI, 14f, Cx.TEXT)
            val sub = ctx.text("", Cx.Font.MONO, 12f, Cx.MUTED)
            col.addView(ctx.hbox().apply {
                addView(mark, lp(ctx.dp(30), ctx.dp(30)).margins(r = ctx.dp(14)))
                addView(ctx.vbox().apply { addView(name); addView(sub, lp().margins(t = ctx.dp(3))) }, lp(0, WRAP, 1f))
            }, lp().margins(t = ctx.dp(if (i == 0) 22 else 16)))
            rows += Triple(mark, name, sub)
        }
        val row = ctx.hbox()
        row.addView(ctx.text(L.t("Общий прогресс", "Overall"), Cx.Font.BODY, 13f, Cx.MUTED), lp(0, WRAP, 1f))
        leftText = ctx.text("", Cx.Font.MONO, 13f, Cx.TEXT)
        row.addView(leftText)
        col.addView(row, lp().margins(t = ctx.dp(22)))
        col.addView(bar, lp(MATCH, ctx.dp(8)).margins(t = ctx.dp(8)))
        tip = ctx.text("", Cx.Font.BODY, 15f, Cx.TEXT)
        col.addView(ctx.vbox().apply {
            background = ctx.box(Cx.PANEL, Cx.LINE)
            setPadding(ctx.dp(15), ctx.dp(15), ctx.dp(15), ctx.dp(15))
            addView(ctx.text("//  " + L.t("Совет", "Tip"), Cx.Font.MONO, 11f, Cx.WARNING, 0.16f))
            addView(tip, lp().margins(t = ctx.dp(10)))
            addView(ctx.hbox().apply {
                tips.indices.forEach { _ -> View(ctx).also { dots += it; addView(it, lp(ctx.dp(18), ctx.dp(4)).margins(r = ctx.dp(6))) } }
            }, lp().margins(t = ctx.dp(12)))
        }, lp().margins(t = ctx.dp(16)))
        col.addView(ctx.button(L.t("Отмена", "Cancel"), BtnStyle.GHOST) { onCancel() }, lp(MATCH, ctx.dp(52)).margins(t = ctx.dp(16)))
        addView(ScrollView(ctx).apply { addView(col) }, LayoutParams(MATCH, MATCH))
        began[0] = start
        showTip(0)
        paint()
        ui.post(tick)
        Thread({ watch() }, "cubex-launchlog").apply { isDaemon = true }.start()
    }

    private fun showTip(i: Int) {
        tipIndex = i % tips.size
        tip.text = tips[tipIndex]
        dots.forEachIndexed { k, d -> d.setBackgroundColor(if (k == tipIndex) Cx.ACCENT else Cx.LINE2) }
    }

    private fun paint() {
        val now = SystemClock.elapsedRealtime()
        rows.forEachIndexed { i, (mark, name, sub) ->
            mark.removeAllViews()
            val (color, iconRes) = when {
                took[i] >= 0 -> Cx.SUCCESS to R.drawable.ic_check
                i == stage -> Cx.ACCENT to R.drawable.ic_refresh
                else -> 0 to 0
            }
            mark.background = if (color != 0) context.box(Cx.alpha(color, 0.08f), color) else context.box(0, Cx.LINE2)
            if (iconRes != 0) mark.addView(context.icon(iconRes, 18, color), LayoutParams(context.dp(18), context.dp(18), Gravity.CENTER))
            name.setTextColor(if (took[i] >= 0 || i == stage) Cx.TEXT else Cx.DIM)
            sub.text = when {
                took[i] >= 0 -> L.t("готово · ${took[i]} с", "done · ${took[i]} s")
                else -> "≈ ${secText(estimate[i])}"
            }
        }
        // overall: by the times of the last start, never «done» before it is
        val totalEst = (0 until stages).sumOf { if (took[it] >= 0) took[it] else estimate[it] }.coerceAtLeast(1)
        var spent = 0L
        for (i in 0 until stages) spent += when {
            took[i] >= 0 -> took[i]
            i == stage -> minOf((now - began[i]) / 1000, estimate[i] * 95 / 100)
            else -> 0
        }
        bar.set(spent.toFloat() / totalEst)
        val left = (totalEst - spent).coerceAtLeast(0)
        leftText.text = L.t("осталось ≈ ${secText(left)}", "≈ ${secText(left)} left")
    }

    private fun secText(s: Long) = if (s >= 60) L.t("${s / 60} мин ${s % 60} с", "${s / 60} min ${s % 60} s") else L.t("$s с", "$s s")

    private val tick = object : Runnable {
        override fun run() {
            if (gone) return
            if (SystemClock.elapsedRealtime() - start > 240_000) return finish(save = false)
            val sec = (SystemClock.elapsedRealtime() - start) / 1000
            if (sec > 0 && sec % 6 == 0L) showTip((sec / 6).toInt())
            paint()
            ui.postDelayed(this, 1000)
        }
    }

    private fun next(to: Int) = ui.post {
        if (gone || to <= stage) return@post
        val now = SystemClock.elapsedRealtime()
        // a stage passed without its own mark in the log counts as 1 s, not as the time since the start
        for (i in stage until minOf(to, stages)) took[i] = if (began[i] > 0) maxOf(1, (now - began[i]) / 1000) else 1
        stage = to
        if (to < stages) began[to] = now
        if (to >= stages) finish(save = true) else paint()
    }

    /** The game's log as it grows: stage 1 ends when the game starts writing it, stage 2 at the main menu, 3 in the world. */
    private fun watch() {
        var pos = 0L
        val buf = StringBuilder()
        while (!gone) {
            try {
                if (log.isFile) {
                    if (stage == 0) next(1)
                    RandomAccessFile(log, "r").use { f ->
                        if (f.length() < pos) pos = 0
                        if (f.length() > pos) {
                            f.seek(pos)
                            val bytes = ByteArray((f.length() - pos).coerceAtMost(1 shl 20).toInt())
                            f.readFully(bytes)
                            pos += bytes.size
                            buf.append(String(bytes, Charsets.UTF_8))
                            var nl = buf.indexOf("\n")
                            while (nl >= 0) {
                                line(buf.substring(0, nl))
                                buf.delete(0, nl + 1)
                                nl = buf.indexOf("\n")
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            Thread.sleep(300)
        }
    }

    private fun line(l: String) {
        when {
            // the game shows its own error screen: let the player see it
            "ModLoadingException" in l || "Crash report saved" in l || "Failed to load mods" in l -> ui.post { finish(save = false) }
            "Game took" in l || ("CubeXCraft Intro" in l && "TitleScreen" in l) -> next(2)
            join && ("Time from main menu to in-game" in l || ("CubeXCraft Intro" in l && "begin replay" in l)) -> next(3)
        }
    }

    /** In the world (the game took the mouse): nothing more to wait for. */
    fun onGrab(grabbing: Boolean) {
        if (grabbing) next(stages)
    }

    private fun finish(save: Boolean) {
        if (gone) return
        gone = true
        if (save) prefs.edit().apply { for (i in 0 until stages) if (took[i] > 0) putLong("stage$i", took[i]) }.apply()
        animate().alpha(0f).setDuration(200).withEndAction { (parent as? FrameLayout)?.removeView(this) }.start()
    }
}
