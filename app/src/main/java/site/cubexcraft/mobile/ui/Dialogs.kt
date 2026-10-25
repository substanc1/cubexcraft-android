// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.data.RunLog
import site.cubexcraft.mobile.data.sizeText
import site.cubexcraft.mobile.net.LogUpload
import site.cubexcraft.mobile.update.SelfUpdate
import kotlin.concurrent.thread

/** Windows over the main screen (design 11 and 12): the app update, the game's exit with an error, no memory, no server. */
object Dialogs {
    enum class Kind { CRASH, MEMORY, NET, START }

    /** The card: coloured frame, icon in a square, title, text; buttons are added below. */
    fun card(main: MainActivity, color: Int, iconRes: Int, title: CharSequence, text: CharSequence?): LinearLayout = main.vbox().apply {
        background = main.box(Cx.PANEL, Cx.alpha(color, 0.53f))
        setPadding(main.dp(17), main.dp(19), main.dp(17), main.dp(17))
        addView(main.hbox().apply {
            addView(FrameLayout(main).apply {
                background = main.box(Cx.alpha(color, 0.08f), color)
                addView(main.icon(iconRes, 26, color), FrameLayout.LayoutParams(main.dp(26), main.dp(26), Gravity.CENTER))
            }, lp(main.dp(48), main.dp(48)).margins(r = main.dp(14)))
            addView(main.text(title, Cx.Font.DISPLAY_SEMI, 16f, Cx.TEXT, lines = 3), lp(0, WRAP, 1f))
        })
        if (text != null) addView(main.text(text, Cx.Font.BODY, 14f, Cx.SOFT), lp().margins(t = main.dp(14)))
    }

    private fun LinearLayout.buttons(vararg b: android.view.View) = b.forEachIndexed { i, v ->
        addView(v, (v.layoutParams as LinearLayout.LayoutParams).apply { topMargin = context.dp(if (i == 0) 16 else 8) })
    }

    // --- 11: the app updates itself ---

    private var updateBar: Bar? = null
    private var updateBytes: TextView? = null
    private var shownPhase: String? = null

    fun update(main: MainActivity, st: SelfUpdate.State) {
        when (st) {
            is SelfUpdate.State.Downloading -> {
                if (shownPhase != "dl") {
                    shownPhase = "dl"
                    val c = card(main, Cx.SOFT, R.drawable.ic_download, L.t("Скачиваем ${st.version}…", "Downloading ${st.version}…"),
                        L.t("Затем установим автоматически. Не закрывайте приложение.", "Then it installs by itself. Keep the app open."))
                    val row = main.hbox()
                    row.addView(main.text(L.t("Загрузка", "Download"), Cx.Font.MONO, 12f, Cx.MUTED), lp(0, WRAP, 1f))
                    updateBytes = main.text("", Cx.Font.MONO, 12f, Cx.TEXT).also { row.addView(it) }
                    c.addView(row, lp().margins(t = main.dp(13)))
                    updateBar = Bar(main, Cx.SOFT).also { c.addView(it, (it.layoutParams as LinearLayout.LayoutParams).margins(t = main.dp(6))) }
                    main.showDialog(c, cancelable = false)
                }
                updateBytes?.text = "${sizeText(st.done).substringBefore(' ')} / ${sizeText(st.total)}"
                updateBar?.set(if (st.total > 0) st.done.toFloat() / st.total else 0f)
            }
            is SelfUpdate.State.Installing -> {
                shownPhase = "inst"
                val c = card(main, Cx.SOFT, R.drawable.ic_download, L.t("Устанавливаем ${st.version}…", "Installing ${st.version}…"),
                    L.t("Скачано. Приложение перезапустится само.", "Downloaded. The app restarts by itself."))
                c.addView(main.text(L.t("Установка", "Installing"), Cx.Font.MONO, 12f, Cx.MUTED), lp().margins(t = main.dp(13)))
                c.addView(Bar(main, Cx.SOFT).apply { set(1f) }, lp(MATCH, main.dp(8)).margins(t = main.dp(6)))
                main.showDialog(c, cancelable = false)
            }
            is SelfUpdate.State.Failed -> {
                shownPhase = null
                main.closeDialog()
                val c = card(main, Cx.WARNING, R.drawable.ic_warn, L.t("Обновление не установилось", "The update failed"), st.message)
                c.buttons(main.button(L.t("Закрыть", "Close"), BtnStyle.GHOST) { main.closeDialog() })
                main.showDialog(c)
            }
            SelfUpdate.State.Idle -> {
                if (shownPhase != null) main.closeDialog()
                shownPhase = null
            }
        }
    }

    // --- 12: errors ---

    fun error(main: MainActivity, kind: Kind, detail: String?) {
        val c = when (kind) {
            Kind.CRASH -> card(main, Cx.ACCENT, R.drawable.ic_warn, L.t("Игра закрылась с ошибкой", "The game closed with an error"),
                L.t("Отправьте журнал — так мы быстрее найдём причину.", "Send the log — it helps us find the cause sooner.")).apply {
                if (detail != null) addView(main.text(detail, Cx.Font.MONO, 12f, Cx.WARNING, lines = 3).apply {
                    background = main.box(Cx.FIELD, Cx.LINE)
                    setPadding(main.dp(11), main.dp(10), main.dp(11), main.dp(10))
                }, lp().margins(t = main.dp(13)))
                var sendBtn: android.view.View? = null
                sendBtn = main.button(L.t("Отправить журнал", "Send the log")) {
                    thread(name = "cubex-sendlog") {
                        val text = RunLog.lastText.ifEmpty { RunLog.collect(main) }
                        val ok = LogUpload.send(main, "manual", text)
                        main.runOnUiThread {
                            if (ok) main.closeDialog() else RunLog.share(main)   // no network: hand the file to a messenger
                        }
                    }
                }
                buttons(sendBtn, main.button(L.t("Попробовать снова", "Try again"), BtnStyle.OUTLINE) {
                    main.closeDialog()
                    main.startGame(if (Settings.joinServer) HomeScreen.selectedAddress() else null)
                })
            }
            Kind.MEMORY -> card(main, Cx.WARNING, R.drawable.ic_mem, L.t("Не хватило памяти", "Out of memory"),
                L.t("Телефону не хватило RAM. В режиме «Экономия» ближе прорисовка и нет шейдеров.",
                    "The phone ran out of RAM. «Economy» draws less far and has no shaders.")).apply {
                buttons(
                    main.button(L.t("Включить «Экономия»", "Turn on «Economy»")) {
                        Settings.quality = Settings.Quality.ECO
                        Settings.shaders = Settings.Shaders.OFF
                        main.closeDialog()
                    },
                    main.button(L.t("Закрыть", "Close"), BtnStyle.GHOST) { main.closeDialog() },
                )
            }
            Kind.NET -> card(main, Cx.ACCENT, R.drawable.ic_wifioff, L.t("Нет связи с сервером", "No connection to the server"),
                detail ?: L.t("Проверьте сеть или повторите позже.", "Check the network or try later.")).apply {
                buttons(
                    main.button(L.t("Повторить", "Retry")) {
                        main.closeDialog()
                        main.startGame(if (Settings.joinServer) HomeScreen.selectedAddress() else null)
                    },
                    main.button(L.t("К списку серверов", "To the server list"), BtnStyle.OUTLINE) { main.closeDialog() },
                )
            }
            Kind.START -> card(main, Cx.ACCENT, R.drawable.ic_warn, L.t("Игра не запустилась", "The game did not start"), detail).apply {
                buttons(main.button(L.t("Закрыть", "Close"), BtnStyle.GHOST) { main.closeDialog() })
            }
        }
        main.showDialog(c)
    }
}
