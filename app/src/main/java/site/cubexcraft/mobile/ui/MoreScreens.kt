// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.data.Device
import site.cubexcraft.mobile.data.Note
import site.cubexcraft.mobile.data.Repo
import site.cubexcraft.mobile.data.RunLog
import site.cubexcraft.mobile.data.groups
import site.cubexcraft.mobile.data.sizeText
import site.cubexcraft.mobile.game.Launch
import site.cubexcraft.mobile.net.Api
import kotlin.concurrent.thread
import kotlin.math.ceil

/** 08: quality, shaders, language, «Сразу на сервер», memory (view only). */
class SettingsScreen(main: MainActivity) : Screen(main) {
    override fun build(): View {
        val c = main.vbox()
        c.addView(main.section(L.t("Качество", "Quality")), lp().margins(b = dp(14)))
        val qualities = Settings.Quality.entries
        val caption = main.text("", Cx.Font.BODY, 12f, Cx.MUTED)
        fun captionText() {
            caption.text = L.t("Авто выбирает по памяти. Сейчас: ", "Auto picks by memory. Now: ") + Settings.qualityName(Settings.effective(main)) + "."
        }
        c.addView(main.segments(qualities.map { Settings.qualityName(it) }, qualities.indexOf(Settings.quality)) { i ->
            Settings.quality = qualities[i]
            captionText()
        })
        captionText()
        c.addView(caption, lp().margins(t = dp(8)))

        c.addView(main.section(L.t("Шейдеры", "Shaders")), lp().margins(t = dp(16), b = dp(14)))
        val shaders = Settings.Shaders.entries
        c.addView(main.segments(listOf(L.t("Авто", "Auto"), L.t("Вкл", "On"), L.t("Выкл", "Off")), shaders.indexOf(Settings.shaders)) { i ->
            Settings.shaders = shaders[i]
        })
        c.addView(main.text(L.t("Авто: на телефонах пока выключены — тяжело для видеокарты.", "Auto: off on phones for now — heavy for the GPU."),
            Cx.Font.BODY, 12f, Cx.MUTED), lp().margins(t = dp(8)))

        c.addView(main.section(L.t("Язык", "Language")), lp().margins(t = dp(16), b = dp(14)))
        c.addView(main.segments(listOf("RU", "EN"), if (L.ru) 0 else 1) { i ->
            val want = if (i == 0) "ru" else "en"
            if (want != Settings.lang) {
                Settings.lang = want
                // every screen is built in the language: start over with the new one
                main.replaceAll(HomeScreen(main))
                main.open(SettingsScreen(main))
            }
        })

        c.addView(main.section(L.t("Запуск", "Start")), lp().margins(t = dp(16), b = dp(14)))
        c.addView(main.hbox().apply {
            background = main.box(Cx.PANEL, Cx.LINE)
            setPadding(dp(15), dp(10), dp(15), dp(10))
            val col = main.vbox()
            col.addView(main.text(L.t("Сразу на сервер", "Straight to the server"), Cx.Font.BODY_SEMI, 14f, Cx.TEXT))
            col.addView(main.text(L.t("После запуска игры сразу заходить на выбранный сервер.", "Join the chosen server right after the game starts."),
                Cx.Font.BODY, 12f, Cx.MUTED), lp().margins(t = dp(2)))
            addView(col, lp(0, WRAP, 1f).margins(r = dp(12)))
            addView(main.toggle(Settings.joinServer) { Settings.joinServer = it })
        })

        c.addView(main.section(L.t("Память · только просмотр", "Memory · view only")), lp().margins(t = dp(16), b = dp(14)))
        val g = main.group()
        val gb = L.t("ГБ", "GB")
        g.addRow(main.row(R.drawable.ic_mem, L.t("Всего", "Total"), value = "${ceil(Launch.totalRamMb(main) / 1024.0).toInt()} $gb"))
        g.addRow(main.row(R.drawable.ic_mem, L.t("Свободно сейчас", "Free now"), value = sizeText(Device.availRamMb(main).toLong() shl 20)))
        g.addRow(main.row(R.drawable.ic_gpu, L.t("Качество сейчас", "Quality now"), value = Settings.qualityName(Settings.effective(main))))
        c.addView(g)

        c.addView(main.group().apply {
            addRow(main.row(R.drawable.ic_log, L.t("Журнал последнего запуска", "Log of the last run"), sub = L.t("Отправить файлом", "Share as a file"),
                chevron = R.drawable.ic_chevr) {
                thread(name = "cubex-sharelog") { RunLog.collect(main); main.runOnUiThread { RunLog.share(main) } }
            })
            addRow(main.row(R.drawable.ic_info, L.t("О приложении", "About"), chevron = R.drawable.ic_chevr) { main.open(AboutScreen(main)) })
        }, lp().margins(t = dp(14)))
        return page(titleBar(L.t("Настройки", "Settings")), c)
    }
}

/** 09: version and the authors of what the app is built on (their licences ask for it). */
class AboutScreen(main: MainActivity) : Screen(main) {
    override fun build(): View {
        val c = main.vbox()
        c.addView(main.statusCard(Cx.SUCCESS, R.drawable.ic_cube, "CubeX Mobile",
            L.t("Версия ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE}", "Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}")))
        c.addView(main.section(L.t("Авторы библиотек", "Library authors")), lp().margins(t = dp(14), b = dp(14)))
        c.addView(main.text(L.t(
            "Использует PojavLauncher / Amethyst (LGPL-3.0), MobileGlues (LGPL-2.1), OpenJDK (GPL-2.0 с Classpath Exception), LWJGL (BSD), " +
                "OpenAL Soft (LGPL), JNA (Apache-2.0), bytehook (MIT), OkHttp (Apache-2.0), XZ for Java. Шрифты Unbounded, Manrope, JetBrains Mono (OFL).",
            "Uses PojavLauncher / Amethyst (LGPL-3.0), MobileGlues (LGPL-2.1), OpenJDK (GPL-2.0 with Classpath Exception), LWJGL (BSD), " +
                "OpenAL Soft (LGPL), JNA (Apache-2.0), bytehook (MIT), OkHttp (Apache-2.0), XZ for Java. Fonts Unbounded, Manrope, JetBrains Mono (OFL).",
        ), Cx.Font.BODY, 15f, Cx.TEXT).apply { setLineSpacing(0f, 1.2f) })
        c.addView(main.group().apply {
            addRow(main.row(R.drawable.ic_ext, L.t("Исходный код и лицензии", "Source code and licences"), chevron = R.drawable.ic_ext) {
                main.openLink("https://github.com/substanc1/cubexcraft-android")
            })
        }, lp().margins(t = dp(14)))
        return page(titleBar(L.t("О приложении", "About")), c)
    }
}

/** 10: friends (online and not), notifications, the account with the wallet and logout. */
class SocialScreen(main: MainActivity, private var tab: Int) : Screen(main) {
    private lateinit var title: FrameLayout
    private lateinit var body: LinearLayout
    private val listener: () -> Unit = { render() }

    override fun build(): View {
        title = FrameLayout(main)
        val c = main.vbox()
        c.addView(main.segments(listOf(L.t("Друзья", "Friends"), L.t("Уведомления", "Notifications"), L.t("Аккаунт", "Account")), tab) { i ->
            tab = i
            render()
            markRead()
        })
        body = main.vbox()
        c.addView(body, lp().margins(t = dp(14)))
        return page(title, c)
    }

    override fun onShow() {
        Repo.listen(listener)
        Repo.refresh(main.token)
        render()
        markRead()
    }

    override fun onHide() = Repo.unlisten(listener)

    private fun markRead() {
        val t = main.token ?: return
        if (tab == 1 && Repo.notes.any { it.unread }) Repo.social({ Api.notificationsRead(t) })
    }

    private fun render() {
        title.removeAllViews()
        title.addView(titleBar(when (tab) { 0 -> L.t("Друзья", "Friends"); 1 -> L.t("Уведомления", "Notifications"); else -> L.t("Аккаунт", "Account") }))
        body.removeAllViews()
        when (tab) {
            0 -> friends()
            1 -> notifications()
            else -> account()
        }
    }

    private fun empty(text: String) = body.addView(main.panel().apply {
        setPadding(dp(15), dp(18), dp(15), dp(18))
        addView(main.text(text, Cx.Font.BODY, 13f, Cx.MUTED))
    })

    private fun friends() {
        val t = main.token
        if (Repo.requests.isNotEmpty() && t != null) {
            body.addView(main.section(L.t("Заявки · ${Repo.requests.size}", "Requests · ${Repo.requests.size}")), lp().margins(b = dp(14)))
            val g = main.group()
            for (r in Repo.requests) {
                g.addRow(main.hbox().apply {
                    background = main.box(Cx.PANEL)
                    setPadding(dp(15), dp(8), dp(8), dp(8))
                    val col = main.vbox()
                    col.addView(main.text(r.username, Cx.Font.BODY_SEMI, 14f, Cx.TEXT, lines = 1))
                    if (r.mutual > 0) col.addView(main.text(L.t("общих друзей: ${r.mutual}", "mutual friends: ${r.mutual}"), Cx.Font.BODY, 12f, Cx.MUTED))
                    addView(col, lp(0, WRAP, 1f))
                    addView(smallButton(L.t("Принять", "Accept"), Cx.ACCENT) { Repo.social({ Api.friendAccept(t, r.username) }) })
                    addView(smallButton(L.t("Нет", "No"), Cx.DIM) { Repo.social({ Api.friendDecline(t, r.username) }) }, lp(WRAP, dp(36)).margins(l = dp(6)))
                })
            }
            body.addView(g, lp().margins(b = dp(16)))
        }
        val online = Repo.friends.filter { it.state != "offline" }
        val offline = Repo.friends.filter { it.state == "offline" }
        if (Repo.friends.isEmpty()) {
            return empty(L.t("Друзей пока нет. Добавить друга можно в лаунчере на компьютере.", "No friends yet. You can add friends in the desktop launcher."))
        }
        if (online.isNotEmpty()) {
            body.addView(main.section(L.t("Онлайн · ${online.size}", "Online · ${online.size}")), lp().margins(b = dp(14)))
            val g = main.group()
            online.forEach { f ->
                val sub = when (f.state) {
                    "playing" -> f.server?.let { L.t("играет · $it", "playing · $it") } ?: L.t("играет", "playing")
                    "away" -> L.t("отошёл", "away")
                    else -> L.t("в сети", "online")
                }
                g.addRow(main.row(title = f.username, sub = sub, status = if (f.state == "away") Cx.WARNING else Cx.SUCCESS))
            }
            body.addView(g)
        }
        if (offline.isNotEmpty()) {
            body.addView(main.section(L.t("Не в сети", "Offline")), lp().margins(t = dp(14), b = dp(14)))
            val g = main.group()
            offline.forEach { g.addRow(main.row(title = it.username, sub = L.t("не в сети", "offline"), titleColor = Cx.DIM)) }
            body.addView(g)
        }
    }

    private fun smallButton(label: String, color: Int, onClick: () -> Unit) = main.text(label, Cx.Font.DISPLAY_SEMI, 11f, if (color == Cx.ACCENT) Cx.ON_PRIMARY else Cx.TEXT, 0.06f, upper = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = pressable(main.box(Cx.alpha(color, 0.12f), color, cut = true), main.box(Cx.alpha(color, 0.3f), color, cut = true))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = lp(WRAP, dp(36))
    }

    private fun notifications() {
        if (Repo.notes.isEmpty()) return empty(L.t("Уведомлений нет.", "No notifications."))
        val g = main.group()
        Repo.notes.forEach { n -> g.addRow(main.row(noteIcon(n), noteText(n), value = ago(n.agoSec), titleColor = if (n.unread) Cx.TEXT else Cx.SOFT)) }
        body.addView(g)
    }

    private fun noteIcon(n: Note) = when (n.kind) {
        "accepted" -> R.drawable.ic_users
        "invite" -> R.drawable.ic_send
        "playing" -> R.drawable.ic_play
        else -> R.drawable.ic_bell
    }

    private fun noteText(n: Note) = when (n.kind) {
        "accepted" -> L.t("${n.actor} добавил вас в друзья", "${n.actor} added you as a friend")
        "invite" -> L.t("${n.actor} зовёт на «${n.server}»", "${n.actor} invites you to «${n.server}»")
        "playing" -> L.t("${n.actor} играет на «${n.server}»", "${n.actor} is playing on «${n.server}»")
        else -> n.actor
    }

    private fun ago(sec: Long) = when {
        sec < 60 -> L.t("сейчас", "now")
        sec < 3600 -> L.t("${sec / 60} мин", "${sec / 60} min")
        sec < 86400 -> L.t("${sec / 3600} ч", "${sec / 3600} h")
        sec < 172800 -> L.t("вчера", "yesterday")
        else -> L.t("${sec / 86400} дн", "${sec / 86400} d")
    }

    private fun account() {
        body.addView(main.statusCard(Cx.SUCCESS, R.drawable.ic_user, main.username ?: "", L.t("Аккаунт CubeXCraft", "CubeXCraft account")))
        if (Repo.cubex >= 0) {
            body.addView(main.section(L.t("Кошелёк", "Wallet")), lp().margins(t = dp(14), b = dp(14)))
            val g = main.group()
            g.addRow(main.row(R.drawable.ic_cube, "CUBEX", value = "${groups(Repo.cubex)} CX"))
            g.addRow(main.row(R.drawable.ic_nan, "NANITE", value = groups(Repo.nanites)))
            body.addView(g)
        }
        body.addView(main.vbox().apply {
            background = main.box(Cx.PANEL, Cx.alpha(Cx.ACCENT, 0.53f))
            setPadding(dp(17), dp(19), dp(17), dp(17))
            addView(main.hbox().apply {
                addView(FrameLayout(main).apply {
                    background = main.box(Cx.alpha(Cx.ACCENT, 0.08f), Cx.ACCENT)
                    addView(main.icon(R.drawable.ic_exit, 26, Cx.ACCENT), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                }, lp(dp(48), dp(48)).margins(r = dp(14)))
                addView(main.text(L.t("Выйти из аккаунта?", "Log out?"), Cx.Font.DISPLAY_SEMI, 16f, Cx.TEXT), lp(0, WRAP, 1f))
            })
            addView(main.text(L.t("Придётся войти снова.", "You will need to log in again."), Cx.Font.BODY, 14f, Cx.SOFT), lp().margins(t = dp(13)))
            addView(main.button(L.t("Выйти из аккаунта", "Log out"), BtnStyle.DANGER) { main.logout() }, lp(MATCH, dp(52)).margins(t = dp(16)))
        }, lp().margins(t = dp(14)))
    }
}
