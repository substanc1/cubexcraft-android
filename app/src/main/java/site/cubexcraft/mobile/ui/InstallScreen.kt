// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.data.Device
import site.cubexcraft.mobile.data.groups
import site.cubexcraft.mobile.data.sizeText
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.install.Runtimes
import site.cubexcraft.mobile.net.Relay
import site.cubexcraft.mobile.net.SyncProgress
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 04: the first download of the build. Percent, bytes, speed, files per second, time left, files; «Через Москву» when the RU
 * relay carries it. A dropped connection is retried by itself (5 s, up to 10 times); no space — the player frees it and retries.
 * When done the game starts.
 */
class InstallScreen(main: MainActivity, private var plan: Installer.Plan?) : Screen(main) {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sub: TextView
    private lateinit var route: View
    private lateinit var bannerSlot: LinearLayout
    private lateinit var percent: TextView
    private lateinit var bytesText: TextView
    private lateinit var bar: Bar
    private lateinit var speed: TextView
    private lateinit var rate: TextView
    private lateinit var left: TextView
    private lateinit var files: TextView
    private lateinit var bottom: LinearLayout
    @Volatile private var installer: Installer? = null
    @Volatile private var running = false
    @Volatile private var cancelled = false

    // progress, written by the download threads and read by the 2 Hz tick
    @Volatile private var filesDone = 0
    @Volatile private var filesTotal = 0
    @Volatile private var bytesDone = 0L
    @Volatile private var bytesTotal = 0L
    private var lastBytes = 0L
    private var lastFiles = 0
    private var lastAt = 0L
    private var speedBps = 0.0
    private var filesPs = 0.0

    override fun build(): View {
        val c = main.vbox()
        c.addView(main.text(L.t("Скачиваем CubeXCraft", "Downloading CubeXCraft"), Cx.Font.DISPLAY, 20f, Cx.TEXT_HI))
        sub = main.text(L.t("Не закрывайте приложение", "Keep the app open"), Cx.Font.BODY, 14f, Cx.MUTED)
        c.addView(sub, lp().margins(t = dp(6)))
        route = main.hbox().apply {
            background = main.box(Cx.alpha(Cx.SOFT, 0.07f), Cx.alpha(Cx.SOFT, 0.53f))
            setPadding(dp(10), dp(6), dp(12), dp(6))
            addView(main.icon(R.drawable.ic_route, 16, Cx.SOFT), lp(dp(16), dp(16)).margins(r = dp(6)))
            addView(main.text(L.t("Через Москву", "Via Moscow"), Cx.Font.MONO, 11f, Cx.SOFT, 0.1f, upper = true))
        }
        c.addView(route, lp(WRAP, WRAP).margins(t = dp(14)))
        bannerSlot = main.vbox()
        c.addView(bannerSlot, lp().margins(t = dp(10)))
        percent = main.text("0%", Cx.Font.DISPLAY, 44f, Cx.ACCENT)
        c.addView(percent, lp().margins(t = dp(4)))
        val row = main.hbox()
        row.addView(main.text(L.t("Скачано", "Downloaded"), Cx.Font.BODY, 13f, Cx.MUTED), lp(0, WRAP, 1f))
        bytesText = main.text("—", Cx.Font.MONO, 13f, Cx.TEXT)
        row.addView(bytesText)
        c.addView(row, lp().margins(t = dp(4)))
        bar = Bar(main)
        c.addView(bar, lp(MATCH, dp(8)).margins(t = dp(8)))
        speed = stat(c, L.t("Скорость", "Speed"), L.t("Файлы", "Files"), true).also { rate = it.second }.first
        left = stat(c, L.t("Осталось", "Time left"), L.t("Файлов", "Files"), false).also { files = it.second }.first
        bottom = main.vbox()
        return page(titleBar(L.t("Установка сборки", "Installing the build")) { cancel() }, c, bottom)
    }

    /** Two stat boxes side by side (label above, big mono value). */
    private fun stat(parent: LinearLayout, a: String, b: String, first: Boolean): Pair<TextView, TextView> {
        val row = main.hbox()
        fun box(label: String): TextView {
            val v = main.text("—", Cx.Font.MONO_BOLD, 16f, Cx.TEXT, lines = 1)
            row.addView(main.vbox().apply {
                background = main.box(Cx.PANEL, Cx.LINE)
                setPadding(dp(13), dp(11), dp(13), dp(11))
                addView(main.text(label, Cx.Font.MONO, 11f, Cx.MUTED, 0.1f, upper = true))
                addView(v, lp().margins(t = dp(4)))
            }, lp(0, WRAP, 1f).margins(l = if (row.childCount == 0) 0 else dp(8)))
            return v
        }
        val x = box(a)
        val y = box(b)
        parent.addView(row, lp().margins(t = dp(if (first) 14 else 8)))
        return x to y
    }

    override fun onShow() {
        route.visibility = if (Relay.enabled) View.VISIBLE else View.GONE
        setBottom(retry = false)
        if (!running) start()
        ui.post(tick)
    }

    override fun onHide() {
        ui.removeCallbacks(tick)
    }

    override fun onBack(): Boolean {
        cancel()
        return true
    }

    private fun setBottom(retry: Boolean) {
        bottom.removeAllViews()
        if (retry) bottom.addView(main.button(L.t("Повторить", "Retry")) { bannerSlot.removeAllViews(); setBottom(false); start() })
        bottom.addView(main.button(L.t("Отмена", "Cancel"), BtnStyle.OUTLINE) { cancel() }, lp(MATCH, dp(52)).margins(t = if (retry) dp(8) else 0))
    }

    private fun cancel() {
        cancelled = true
        installer?.cancelled = true
        HomeScreen.installed()
        main.pop()
    }

    private fun banner(color: Int, icon: Int, title: String, text: String?) {
        bannerSlot.removeAllViews()
        bannerSlot.addView(main.banner(color, icon, title, text))
    }

    private fun start() {
        val token = main.token ?: return
        running = true
        cancelled = false
        val app = main.applicationContext
        thread(name = "cubex-install") {
            var attempt = 0
            while (!cancelled) {
                try {
                    val inst = Installer(app, token)
                    installer = inst
                    inst.onProgress = SyncProgress { done, total, bytes, bt ->
                        filesDone = done; filesTotal = total; bytesDone = bytes; bytesTotal = bt
                    }
                    val p = plan ?: inst.plan().also { plan = it }
                    filesTotal = p.jobs.size
                    bytesTotal = p.bytes
                    main.runOnUiThread { sub.text = L.t("${p.profile.optString("title", "CubeXCraft")} · не закрывайте приложение", "${p.profile.optString("title", "CubeXCraft")} · keep the app open") }
                    val need = p.bytes + (64L shl 20)
                    val free = Device.freeBytes(app)
                    if (free < need) {
                        main.runOnUiThread {
                            banner(Cx.ACCENT, R.drawable.ic_disk, L.t("Не хватает места", "Not enough space"),
                                L.t("Нужно ещё ${sizeText(need - free)}. Удалите ненужное на телефоне и повторите.",
                                    "${sizeText(need - free)} more is needed. Free up space on the phone and retry."))
                            setBottom(retry = true)
                        }
                        plan = null
                        break
                    }
                    inst.run(p)
                    main.runOnUiThread { sub.text = L.t("Готовим Java…", "Preparing Java…") }
                    Runtimes.ensure(app)
                    HomeScreen.installed()
                    main.runOnUiThread {
                        if (cancelled) return@runOnUiThread
                        main.replaceAll(HomeScreen(main))
                        main.startGame(if (Settings.joinServer) HomeScreen.selectedAddress() else null)
                    }
                    break
                } catch (e: IOException) {
                    if (cancelled) break
                    attempt++
                    plan = null
                    Log.w("CubexInstall", "attempt $attempt: $e")
                    if (attempt >= 10) {
                        main.runOnUiThread {
                            banner(Cx.ACCENT, R.drawable.ic_wifioff, L.t("Нет связи", "No connection"), L.t("Не удалось скачать за 10 попыток.", "Could not download in 10 attempts."))
                            setBottom(retry = true)
                        }
                        break
                    }
                    for (s in 5 downTo 1) {
                        if (cancelled) break
                        main.runOnUiThread {
                            banner(Cx.WARNING, R.drawable.ic_wifioff, L.t("Обрыв сети", "Connection lost"),
                                L.t("Автоповтор через $s с · попытка ${attempt + 1} из 10", "Retrying in $s s · attempt ${attempt + 1} of 10"))
                        }
                        Thread.sleep(1000)
                    }
                    main.runOnUiThread { bannerSlot.removeAllViews() }
                } catch (t: Throwable) {
                    if (cancelled) break
                    Log.e("CubexInstall", "install failed", t)
                    main.runOnUiThread {
                        banner(Cx.ACCENT, R.drawable.ic_warn, L.t("Сборка не установилась", "The build did not install"), t.message ?: t.javaClass.simpleName)
                        setBottom(retry = true)
                    }
                    break
                }
            }
            running = false
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val b = bytesDone
            val f = filesDone
            if (lastAt > 0) {
                val dt = (now - lastAt) / 1000.0
                if (dt > 0) {
                    speedBps = speedBps * 0.7 + (b - lastBytes) / dt * 0.3
                    filesPs = filesPs * 0.7 + (f - lastFiles) / dt * 0.3
                }
            }
            lastAt = now; lastBytes = b; lastFiles = f
            val total = bytesTotal
            val frac = if (total > 0) b.toFloat() / total else 0f
            percent.text = "${(frac * 100).toInt()}%"
            bar.set(frac)
            bytesText.text = if (total > 0) "${sizeText(b).substringBefore(' ')} / ${sizeText(total)}" else "—"
            val mbps = speedBps / 1048576.0
            speed.text = if (speedBps > 1024) String.format(Locale.ROOT, "%.1f %s", mbps, L.t("МБ/с", "MB/s")).let { if (L.ru) it.replace('.', ',') else it } else "—"
            rate.text = if (filesPs > 0.5) "${filesPs.toInt()} /${L.t("с", "s")}" else "—"
            left.text = if (speedBps > 1024 && total > b) "≈ ${timeText(((total - b) / speedBps).toLong())}" else "—"
            files.text = if (filesTotal > 0) "${groups(f.toLong())} / ${groups(filesTotal.toLong())}" else "—"
            ui.postDelayed(this, 500)
        }
    }

    private fun timeText(sec: Long) = when {
        sec < 60 -> L.t("$sec с", "$sec s")
        sec < 3600 -> L.t("${sec / 60} мин ${sec % 60} с", "${sec / 60} min ${sec % 60} s")
        else -> L.t("${sec / 3600} ч ${sec % 3600 / 60} мин", "${sec / 3600} h ${sec % 3600 / 60} min")
    }
}
