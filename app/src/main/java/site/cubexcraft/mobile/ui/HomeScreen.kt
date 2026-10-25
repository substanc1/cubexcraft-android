// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.data.Device
import site.cubexcraft.mobile.data.News
import site.cubexcraft.mobile.data.Repo
import site.cubexcraft.mobile.data.Server
import site.cubexcraft.mobile.data.groups
import site.cubexcraft.mobile.data.sizeText
import site.cubexcraft.mobile.install.BuildInfo
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.install.Runtimes
import kotlin.concurrent.thread

/**
 * 03, the main screen: the player and the wallet, servers with their online, the build (installs, updates itself), news,
 * voting, friends online and the one big button. Everything shown comes from the API or from the files on the phone.
 */
class HomeScreen(main: MainActivity) : Screen(main) {
    companion object {
        private const val TAG = "CubexHome"
        private val ui = Handler(Looper.getMainLooper())
        @Volatile private var plan: Installer.Plan? = null
        @Volatile private var planAt = 0L
        @Volatile private var planning = false
        @Volatile private var planFailed = false
        @Volatile private var updating = false
        @Volatile private var updDone = 0L
        @Volatile private var updTotal = 0L
        @Volatile private var updError: String? = null
        @Volatile private var refreshedAt = 0L
        private var current: HomeScreen? = null

        fun selected(): Server? = Repo.servers.firstOrNull { it.slug == Settings.server } ?: Repo.servers.firstOrNull()
        fun selectedAddress(): String? = selected()?.let { "${it.host}:${it.port}" }

        /** A finished install (screen 04) is the new state of the build. */
        fun installed() {
            planAt = 0L
            plan = null
        }

        /** [full] = false: only the build card and the button (progress ticks). */
        private fun rerender(full: Boolean = true) = ui.post { current?.let { if (full) it.render() else { it.renderBuild(); it.renderBottom() } } }
    }

    private lateinit var header: LinearLayout
    private lateinit var banners: LinearLayout
    private lateinit var servers: LinearLayout
    private lateinit var buildCard: LinearLayout
    private lateinit var news: LinearLayout
    private lateinit var extras: LinearLayout
    private lateinit var bottom: FrameLayout
    private var chipsOpen = true
    private val listener: () -> Unit = { render() }

    override fun build(): View {
        header = main.hbox()
        val c = main.vbox()
        banners = main.vbox()
        servers = main.vbox()
        buildCard = main.vbox()
        news = main.vbox()
        extras = main.vbox()
        c.addView(banners)
        c.addView(main.section(L.t("Серверы", "Servers")), lp().margins(b = dp(14)))
        c.addView(servers)
        c.addView(main.section(L.t("Сборка", "Build")), lp().margins(t = dp(14), b = dp(14)))
        c.addView(buildCard)
        c.addView(news)
        c.addView(extras)
        c.addView(main.text(L.t("О приложении · авторы библиотек", "About · library authors"), Cx.Font.BODY, 12f, Cx.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { main.open(AboutScreen(main)) }
        }, lp().margins(t = dp(16)))
        bottom = FrameLayout(main)
        return page(header, c, bottom)
    }

    override fun onShow() {
        current = this
        Repo.listen(listener)
        val now = System.currentTimeMillis()
        if (now - refreshedAt > 60_000) {
            refreshedAt = now
            Repo.refresh(main.token)
        }
        checkBuild()
        render()
    }

    override fun onHide() {
        Repo.unlisten(listener)
        if (current === this) current = null
    }

    // --- the build: what is installed, what an update needs; updates go by themselves ---

    private fun checkBuild() {
        val token = main.token ?: return
        if (planning || updating || System.currentTimeMillis() - planAt < 10 * 60_000) return
        planning = true
        planFailed = false
        val app = main.applicationContext
        thread(name = "cubex-plan") {
            try {
                val p = Installer(app, token).plan()
                plan = p
                planAt = System.currentTimeMillis()
                Log.i(TAG, "plan: ${p.jobs.size} files, ${p.bytes} of ${p.totalBytes} bytes")
                if (Installer.installed(app) != null && p.jobs.isNotEmpty()) update(app, token, p)
            } catch (t: Throwable) {
                Log.w(TAG, "plan failed: $t")
                planFailed = true
            } finally {
                planning = false
                rerender()
            }
        }
    }

    private fun update(app: android.content.Context, token: String, p: Installer.Plan) {
        if (updating) return
        updating = true
        updError = null
        updDone = 0; updTotal = p.bytes
        rerender()
        thread(name = "cubex-update-build") {
            try {
                val inst = Installer(app, token)
                var last = 0L
                inst.onProgress = site.cubexcraft.mobile.net.SyncProgress { _, _, bytes, total ->
                    updDone = bytes; updTotal = total
                    val now = System.currentTimeMillis()
                    if (now - last > 250) { last = now; rerender(full = false) }
                }
                inst.run(p)
                Runtimes.ensure(app)
                plan = null
                planAt = System.currentTimeMillis()
            } catch (t: Throwable) {
                Log.w(TAG, "update failed: $t")
                updError = t.message ?: t.javaClass.simpleName
                planAt = 0L
            } finally {
                updating = false
                rerender()
            }
        }
    }

    // --- drawing ---

    fun render() {
        renderHeader()
        renderBanners()
        renderServers()
        renderBuild()
        renderNews()
        renderExtras()
        renderBottom()
    }

    private fun renderHeader() {
        header.removeAllViews()
        val name = main.username ?: ""
        header.addView(FrameLayout(main).apply {
            background = main.box(Cx.alpha(Cx.ACCENT, 0.08f), Cx.ACCENT)
            addView(main.text(name.take(1).uppercase(), Cx.Font.DISPLAY, 16f, Cx.ACCENT), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }, lp(dp(40), dp(40)).margins(r = dp(12)))
        val col = main.vbox()
        col.addView(main.text(name, Cx.Font.DISPLAY_SEMI, 14f, Cx.TEXT, lines = 1))
        if (Repo.cubex >= 0) {
            col.addView(main.hbox().apply {
                addView(main.icon(R.drawable.ic_cube, 14, Cx.ACCENT), lp(dp(14), dp(14)).margins(r = dp(4)))
                addView(main.text(groups(Repo.cubex), Cx.Font.MONO, 12f, Cx.MUTED))
                addView(main.icon(R.drawable.ic_nan, 14, Cx.WARNING), lp(dp(14), dp(14)).margins(l = dp(12), r = dp(4)))
                addView(main.text(groups(Repo.nanites), Cx.Font.MONO, 12f, Cx.MUTED))
            }, lp().margins(t = dp(3)))
        }
        header.addView(col, lp(0, WRAP, 1f))
        val unread = Repo.notes.count { it.unread } + Repo.requests.size
        header.addView(headerIcon(R.drawable.ic_bell, unread) { main.open(SocialScreen(main, 1)) })
        header.addView(headerIcon(R.drawable.ic_users, 0) { main.open(SocialScreen(main, 0)) })
        header.addView(headerIcon(R.drawable.ic_gear, 0) { main.open(SettingsScreen(main)) })
    }

    private fun headerIcon(res: Int, badge: Int, onClick: () -> Unit): View = FrameLayout(main).apply {
        addView(main.icon(res, 24, Cx.TEXT), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        if (badge > 0) addView(main.text(if (badge > 9) "9+" else badge.toString(), Cx.Font.MONO_BOLD, 11f, Cx.BG).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Cx.ACCENT)
        }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(4), 0) })
        background = pressable(main.box(0), main.box(Cx.TRACK))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = lp(dp(48), dp(48)).margins(l = dp(8))
    }

    private fun renderBanners() {
        banners.removeAllViews()
        if (Repo.offline) {
            banners.addView(main.banner(Cx.WARNING, R.drawable.ic_wifioff, L.t("Нет сети", "No network"),
                L.t("Показаны сохранённые данные. Онлайн и новости не обновляются.", "Showing saved data. Online and news do not update.")),
                lp().margins(b = dp(14)))
        }
        val p = plan
        val free = Device.freeBytes(main)
        if (p != null && p.bytes > 0 && free < p.bytes + (64L shl 20)) {
            banners.addView(main.banner(Cx.ACCENT, R.drawable.ic_disk, L.t("Мало места", "Low on space"),
                L.t("Для сборки нужно ${sizeText(p.bytes)}, свободно ${sizeText(free)}. Освободите место на телефоне.",
                    "The build needs ${sizeText(p.bytes)}, ${sizeText(free)} is free. Free up space on the phone.")),
                lp().margins(b = dp(14)))
        }
    }

    private fun renderServers() {
        servers.removeAllViews()
        val list = Repo.servers
        if (list.isEmpty()) {
            servers.addView(main.panel().apply {
                setPadding(dp(15), dp(18), dp(15), dp(18))
                addView(main.text(if (Repo.loaded) L.t("Серверов нет", "No servers") else L.t("Загружаем…", "Loading…"), Cx.Font.MONO, 12f, Cx.MUTED))
            })
            return
        }
        val sel = selected()
        list.forEachIndexed { i, s -> servers.addView(serverRow(s, s === sel), lp().margins(t = if (i == 0) 0 else dp(8))) }
    }

    private fun serverRow(s: Server, on: Boolean): View = main.hbox().apply {
        background = if (on) main.box(Cx.alpha(Cx.ACCENT, 0.07f), Cx.ACCENT) else pressable(main.box(Cx.PANEL, Cx.LINE), main.box(Cx.TRACK, Cx.LINE))
        minimumHeight = dp(60)
        setPadding(dp(15), dp(10), dp(15), dp(10))
        addView(FrameLayout(main).apply {
            background = main.ring(if (on) Cx.ACCENT else Cx.LINE2)
            if (on) addView(View(main).apply { background = main.dot(Cx.ACCENT) }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
        }, lp(dp(20), dp(20)).margins(r = dp(12)))
        val col = main.vbox()
        col.addView(main.text(s.name, Cx.Font.DISPLAY_SEMI, 14f, Cx.TEXT, lines = 1))
        val line = main.hbox()
        val (dotColor, label) = when {
            Repo.offline -> Cx.DIM to "—"
            !s.up -> Cx.DIM to L.t("выключен", "offline")
            s.statusLabel.isNotEmpty() -> Cx.WARNING to s.statusLabel
            else -> Cx.SUCCESS to L.t("${s.online} онлайн", "${s.online} online")
        }
        line.addView(View(main).apply { setBackgroundColor(dotColor) }, lp(dp(8), dp(8)).margins(r = dp(6)))
        line.addView(main.text(label, Cx.Font.MONO, 12f, Cx.MUTED, lines = 1))
        col.addView(line, lp().margins(t = dp(4)))
        addView(col, lp(0, WRAP, 1f))
        isClickable = true
        setOnClickListener {
            Settings.server = s.slug
            render()
        }
    }

    private fun renderBuild() {
        buildCard.removeAllViews()
        val installed = Installer.installed(main)
        val info = BuildInfo.load(main)
        val p = plan
        val card = main.panel().apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }
        val top = main.hbox()
        top.addView(main.icon(R.drawable.ic_cube, 24, Cx.ACCENT), lp(dp(24), dp(24)).margins(r = dp(12)))
        val col = main.vbox()
        val title = installed?.first?.optString("title")?.ifEmpty { null } ?: "CubeXCraft"
        col.addView(main.text(L.t("Сборка $title", "$title build"), Cx.Font.DISPLAY_SEMI, 14f, Cx.TEXT, lines = 1))
        val mods = info?.mods ?: p?.mods ?: emptyList()
        val total = info?.totalBytes ?: p?.totalBytes ?: 0L
        val version = Repo.servers.firstOrNull { it.version.isNotEmpty() }?.version
        val meta = buildList {
            if (installed != null && version != null) add(if (version.first().isDigit()) "v$version" else version)
            if (total > 0) add((if (installed == null) "~" else "") + sizeText(total))
            if (installed != null && mods.isNotEmpty()) add(L.t("${mods.size} модов", "${mods.size} mods"))
            if (installed == null) add(L.t("не установлена", "not installed"))
        }.joinToString(" · ")
        if (meta.isNotEmpty()) col.addView(main.text(meta, Cx.Font.MONO, 12f, Cx.MUTED, lines = 2), lp().margins(t = dp(3)))
        val (stColor, stText) = when {
            updating -> Cx.WARNING to L.t("обновл.", "updating")
            updError != null -> Cx.ACCENT to L.t("ошибка обновления", "update failed")
            installed == null -> Cx.ACCENT to L.t("нет", "none")
            p != null && p.jobs.isNotEmpty() -> Cx.WARNING to L.t("обновл.", "update")
            else -> Cx.SUCCESS to L.t("готова", "ready")
        }
        col.addView(main.text("●  $stText", Cx.Font.MONO, 11f, stColor, 0.08f, upper = true), lp().margins(t = dp(3)))
        top.addView(col, lp(0, WRAP, 1f))
        if (installed != null && mods.isNotEmpty()) {
            top.addView(main.icon(R.drawable.ic_chevd, 20, Cx.MUTED).apply {
                rotation = if (chipsOpen) 0f else -90f
                setOnClickListener { chipsOpen = !chipsOpen; renderBuild() }
            }, lp(dp(20), dp(20)))
        }
        card.addView(top)
        if (updating) {
            val row = main.hbox()
            row.addView(main.text(L.t("обновление сборки", "updating the build"), Cx.Font.MONO, 12f, Cx.MUTED), lp(0, WRAP, 1f))
            row.addView(main.text("${sizeText(updDone).substringBefore(' ')} / ${sizeText(updTotal)}", Cx.Font.MONO, 12f, Cx.WARNING))
            card.addView(row, lp().margins(t = dp(12)))
            card.addView(Bar(main, Cx.WARNING, 6).apply { set(if (updTotal > 0) updDone.toFloat() / updTotal else 0f) },
                lp(MATCH, dp(6)).margins(t = dp(6)))
        } else if (updError != null) {
            card.addView(main.text(updError!!, Cx.Font.MONO, 12f, Cx.ACCENT, lines = 3), lp().margins(t = dp(10)))
        } else if (installed != null && chipsOpen && mods.isNotEmpty()) {
            val flow = Flow(main, dp(6), dp(5))
            val named = BuildInfo.highlights(mods)
            named.forEach { flow.addView(chip(it)) }
            if (mods.size > named.size) flow.addView(chip(L.t("ещё +${mods.size - named.size}", "+${mods.size - named.size} more")))
            card.addView(flow, lp().margins(t = dp(14)))
        }
        buildCard.addView(card)
    }

    private fun chip(s: String) = main.text(s, Cx.Font.MONO, 12f, Cx.TEXT, lines = 1).apply {
        background = main.box(0, Cx.LINE2)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun renderNews() {
        news.removeAllViews()
        val list = Repo.news.take(4)
        if (list.isEmpty()) return
        news.addView(main.section(L.t("Новости", "News")), lp().margins(t = dp(14), b = dp(14)))
        val w = main.resources.displayMetrics.widthPixels - 2 * main.sidePadding()
        list.forEachIndexed { i, n -> news.addView(newsCard(n, w), lp().margins(t = if (i == 0) 0 else dp(10))) }
    }

    private fun newsCard(n: News, widthPx: Int): View = main.vbox().apply {
        background = pressable(main.box(Cx.PANEL, Cx.LINE), main.box(Cx.TRACK, Cx.LINE))
        setPadding(dp(1), dp(1), dp(1), dp(12))
        if (n.cover != null) {
            val img = ImageView(main).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Cx.IMAGE)
            }
            addView(img, lp(MATCH, (widthPx - dp(2)) * 9 / 16))
            Images.load(main, n.cover, img, widthPx)
        }
        addView(main.text(n.title, Cx.Font.BODY_SEMI, 15f, Cx.TEXT, lines = 3), lp().margins(l = dp(12), t = dp(12), r = dp(12)))
        addView(main.text(dateText(n.date), Cx.Font.MONO, 12f, Cx.MUTED), lp().margins(l = dp(12), t = dp(6), r = dp(12)))
        isClickable = true
        setOnClickListener { showNews(n) }
    }

    private fun showNews(n: News) {
        val card = main.vbox().apply {
            background = main.box(Cx.PANEL, Cx.LINE2)
            setPadding(dp(17), dp(17), dp(17), dp(17))
            addView(main.text(n.title, Cx.Font.DISPLAY_SEMI, 16f, Cx.TEXT))
            addView(main.text(dateText(n.date), Cx.Font.MONO, 12f, Cx.MUTED), lp().margins(t = dp(6)))
            addView(ScrollView(main).apply {
                addView(main.text(n.body.replace(Regex("<[^>]+>"), "").trim(), Cx.Font.BODY, 14f, Cx.SOFT).apply { setLineSpacing(0f, 1.25f) })
            }, lp(MATCH, if (n.body.length > 500) main.resources.displayMetrics.heightPixels / 2 else WRAP).margins(t = dp(12)))
            addView(main.button(L.t("Закрыть", "Close"), BtnStyle.GHOST) { main.closeDialog() }, lp(MATCH, dp(52)).margins(t = dp(12)))
        }
        main.showDialog(card)
    }

    private fun dateText(iso: String): String = runCatching {
        val d = iso.take(10).split('-')
        "${d[2]}.${d[1]}.${d[0]}"
    }.getOrDefault(iso)

    private fun renderExtras() {
        extras.removeAllViews()
        val url = Repo.voteUrl
        if (url != null && Repo.voteSites > 0) {
            extras.addView(main.hbox().apply {
                background = pressable(main.box(Cx.alpha(Cx.WARNING, 0.06f), Cx.alpha(Cx.WARNING, 0.53f)), main.box(Cx.alpha(Cx.WARNING, 0.16f), Cx.alpha(Cx.WARNING, 0.53f)))
                setPadding(dp(15), dp(13), dp(15), dp(13))
                val col = main.vbox()
                col.addView(main.text(L.t("Голосовать за проект", "Vote for the project"), Cx.Font.DISPLAY_SEMI, 13f, Cx.WARNING, upper = true))
                if (Repo.voteReward > 0) col.addView(main.text(L.t("+${Repo.voteReward} CX за голос", "+${Repo.voteReward} CX per vote"), Cx.Font.MONO, 12f, Cx.TEXT), lp().margins(t = dp(3)))
                addView(col, lp(0, WRAP, 1f))
                addView(main.icon(R.drawable.ic_vote, 24, Cx.WARNING))
                isClickable = true
                setOnClickListener { main.openLink(url) }
            }, lp().margins(t = dp(16)))
        }
        val online = Repo.friends.filter { it.state != "offline" }
        if (online.isNotEmpty()) {
            extras.addView(main.text(L.t("Друзья онлайн · ${online.size}", "Friends online · ${online.size}"), Cx.Font.MONO, 12f, Cx.MUTED, 0.12f, upper = true),
                lp().margins(t = dp(18), b = dp(7)))
            val flow = Flow(main, dp(8), dp(8))
            online.forEach { f ->
                flow.addView(main.hbox().apply {
                    background = pressable(main.box(Cx.PANEL, Cx.LINE), main.box(Cx.TRACK, Cx.LINE))
                    setPadding(dp(15), 0, dp(15), 0)
                    minimumHeight = dp(48)
                    addView(View(main).apply { setBackgroundColor(if (f.state == "away") Cx.WARNING else Cx.SUCCESS) }, lp(dp(8), dp(8)).margins(r = dp(8)))
                    addView(main.text(f.username, Cx.Font.BODY_SEMI, 14f, Cx.TEXT, lines = 1))
                    isClickable = true
                    setOnClickListener { main.open(SocialScreen(main, 0)) }
                })
            }
            extras.addView(flow)
        }
    }

    private fun renderBottom() {
        bottom.removeAllViews()
        val installed = Installer.installed(main) != null
        val p = plan
        val free = Device.freeBytes(main)
        val sel = selected()
        val v: View = when {
            updating -> disabled(L.t("Обновляется · ${if (updTotal > 0) (updDone * 100 / updTotal) else 0}%", "Updating · ${if (updTotal > 0) (updDone * 100 / updTotal) else 0}%"))
            !Repo.loaded && !installed -> disabled(L.t("Загрузка…", "Loading…"))
            Repo.offline -> disabled(L.t("Нет сети", "No network"))
            !installed && p == null && planning -> disabled(L.t("Загрузка…", "Loading…"))
            !installed && p != null && free < p.bytes + (64L shl 20) -> disabled(L.t("Нет места · нужно ${sizeText(p.bytes)}", "No space · ${sizeText(p.bytes)} needed"))
            !installed -> main.button(L.t("Скачать и играть", "Download and play") + (p?.let { " · ~${sizeText(it.bytes)}" } ?: ""), BtnStyle.PLAY, R.drawable.ic_download) {
                main.open(InstallScreen(main, p))
            }
            updError != null -> main.button(L.t("Обновить", "Update"), BtnStyle.PLAY, R.drawable.ic_refresh) {
                planAt = 0L; updError = null; checkBuild(); render()
            }
            Settings.joinServer && sel != null && !sel.up -> disabled(L.t("Сервер выключен", "Server offline"))
            else -> main.button(L.t("Играть", "Play"), BtnStyle.PLAY) {
                main.startGame(if (Settings.joinServer) selectedAddress() else null)
            }
        }
        bottom.addView(v, FrameLayout.LayoutParams(MATCH, dp(72)))
    }

    /** The big button greyed out: why one cannot play right now. */
    private fun disabled(label: String): View = FrameLayout(main).apply {
        background = main.box(0, Cx.LINE2)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(main.text(label, Cx.Font.DISPLAY, 17f, Cx.MUTED, 0.08f, upper = true, lines = 2).apply {
            gravity = Gravity.CENTER
            background = main.box(Cx.TRACK, Cx.LINE2, cut = true)
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

/** A 2 dp circle (the radio of the server list). */
fun android.content.Context.ring(color: Int) = android.graphics.drawable.GradientDrawable().apply {
    shape = android.graphics.drawable.GradientDrawable.OVAL
    setStroke(dp(2), color)
    setColor(Color.TRANSPARENT)
}

fun android.content.Context.dot(color: Int) = android.graphics.drawable.GradientDrawable().apply {
    shape = android.graphics.drawable.GradientDrawable.OVAL
    setColor(color)
}
