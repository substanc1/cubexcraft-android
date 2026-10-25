// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import site.cubexcraft.mobile.R
import site.cubexcraft.mobile.data.Device
import site.cubexcraft.mobile.data.sizeText
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.ApiException
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.ceil

/** 01: will the game run here — memory, graphics, 64-bit, free space; on a weak phone «Экономия» is switched on. */
class DeviceScreen(main: MainActivity) : Screen(main) {
    override fun build(): View {
        val r = Device.check(main)
        val v = r.verdict
        val c = main.vbox()
        val (color, iconRes) = when (v) {
            Device.Verdict.OK -> Cx.SUCCESS to R.drawable.ic_check
            Device.Verdict.LIMITED -> Cx.WARNING to R.drawable.ic_warn
            Device.Verdict.NO -> Cx.ACCENT to R.drawable.ic_close
        }
        c.addView(main.statusCard(color, iconRes,
            when (v) {
                Device.Verdict.OK -> L.t("Телефон подходит", "This phone is fine")
                Device.Verdict.LIMITED -> L.t("Подойдёт с ограничениями", "Works with limits")
                Device.Verdict.NO -> L.t("Телефон не подходит", "This phone is not supported")
            },
            when (v) {
                Device.Verdict.OK -> L.t("Всё готово к игре.", "Ready to play.")
                Device.Verdict.LIMITED -> L.t("Памяти хватает впритык.", "Memory is just enough.")
                Device.Verdict.NO -> L.t("Игра на нём не запустится.", "The game will not run on it.")
            }))
        c.addView(main.section(L.t("Проверка устройства", "Device check")), lp().margins(t = dp(14), b = dp(14)))
        val g = main.group()
        val gb = L.t("ГБ", "GB")
        fun mark(ok: Boolean, warn: Boolean = false) = if (!ok) Cx.ACCENT else if (warn) Cx.WARNING else Cx.SUCCESS
        g.addRow(main.row(R.drawable.ic_mem, L.t("Память", "Memory"), value = "${ceil(r.ramMb / 1024.0).toInt()} $gb",
            status = mark(r.ramOk, !r.ramComfort), iconTint = mark(r.ramOk, !r.ramComfort)))
        g.addRow(main.row(R.drawable.ic_gpu, L.t("Графика", "Graphics"), value = r.glesName, status = mark(r.glesOk), iconTint = mark(r.glesOk)))
        g.addRow(main.row(R.drawable.ic_cpu, L.t("Процессор", "Processor"), value = if (r.arm64) L.t("64-бит", "64-bit") else L.t("32-бит", "32-bit"),
            status = mark(r.arm64), iconTint = mark(r.arm64)))
        g.addRow(main.row(R.drawable.ic_disk, L.t("Свободно", "Free space"), value = sizeText(r.freeBytes), status = mark(r.freeOk), iconTint = mark(r.freeOk)))
        c.addView(g)
        if (v == Device.Verdict.LIMITED) {
            c.addView(main.banner(Cx.WARNING, R.drawable.ic_info, L.t("Режим «Экономия»", "«Economy» mode"),
                L.t("Включим сами: ближе дальность прорисовки и без шейдеров.", "We turn it on: shorter view distance and no shaders.")),
                lp().margins(t = dp(14)))
        }
        if (v == Device.Verdict.NO) {
            c.addView(main.banner(Cx.ACCENT, R.drawable.ic_info, L.t("Что делать", "What to do"),
                L.t("Нужны 64-битный Android, от 3 ГБ RAM, OpenGL ES 3.2 и 1 ГБ места. Играть можно с компьютера — лаунчер на сайте.",
                    "It needs 64-bit Android, 3 GB RAM, OpenGL ES 3.2 and 1 GB free. You can play on a computer — the launcher is on the site.")),
                lp().margins(t = dp(14)))
        }
        val bottom = when (v) {
            Device.Verdict.NO -> bar(
                main.button(L.t("Открыть сайт", "Open the site"), BtnStyle.OUTLINE, R.drawable.ic_ext) { main.openLink("https://www.cubexcraft.site") },
                main.button(L.t("Закрыть", "Close"), BtnStyle.GHOST) { main.finish() },
            )
            Device.Verdict.LIMITED -> bar(main.button(L.t("Далее · режим «Экономия»", "Next · «Economy» mode")) {
                Settings.quality = Settings.Quality.ECO
                Settings.shaders = Settings.Shaders.OFF
                next()
            })
            Device.Verdict.OK -> bar(main.button(L.t("Далее", "Next")) { next() })
        }
        return page(brand(), c, bottom)
    }

    private fun next() {
        Settings.deviceChecked = true
        main.replaceAll(if (main.token == null) LoginScreen(main) else HomeScreen(main))
    }
}

/** 02: login with the CubeXCraft account; with Telegram 2FA — waiting for «✅ Это я» in the bot. */
class LoginScreen(main: MainActivity) : Screen(main) {
    private lateinit var login: Field
    private lateinit var password: Field
    private lateinit var bannerSlot: LinearLayout
    private lateinit var submit: LinearLayout
    @Volatile private var busy = false

    override fun build(): View {
        val c = main.vbox()
        c.addView(main.section(L.t("Вход", "Login")), lp().margins(b = dp(13)))
        c.addView(main.text(L.t("С возвращением", "Welcome back"), Cx.Font.DISPLAY, 22f, Cx.TEXT_HI))
        c.addView(main.text(L.t("Войдите аккаунтом CubeXCraft.", "Log in with your CubeXCraft account."), Cx.Font.BODY, 14f, Cx.MUTED), lp().margins(t = dp(6)))
        bannerSlot = main.vbox()
        c.addView(bannerSlot, lp().margins(t = dp(14)))
        login = main.field(L.t("Логин", "Login"), value = main.getSharedPreferences("auth", 0).getString("lastLogin", "") ?: "")
        password = main.field(L.t("Пароль", "Password"), password = true)
        password.edit.imeOptions = EditorInfo.IME_ACTION_DONE
        password.edit.setOnEditorActionListener { _, _, _ -> submit(); true }
        c.addView(login.view)
        c.addView(password.view, lp().margins(t = dp(8)))
        c.addView(main.group().apply {
            addRow(main.row(R.drawable.ic_ext, L.t("Забыли пароль", "Forgot password"), chevron = R.drawable.ic_ext) {
                main.openLink("https://www.cubexcraft.site/forgot-password")
            })
        }, lp().margins(t = dp(8)))
        submit = main.vbox()
        setSubmit(L.t("Войти", "Log in"))
        val bottom = bar(submit, main.button(L.t("Регистрация на сайте", "Sign up on the site"), BtnStyle.GHOST, R.drawable.ic_ext) {
            main.openLink("https://www.cubexcraft.site/register")
        })
        return page(brand(), c, bottom)
    }

    private fun setSubmit(label: String) {
        submit.removeAllViews()
        submit.addView(main.button(label) { submit() })
    }

    private fun banner(color: Int, iconRes: Int, title: String, sub: String?) {
        bannerSlot.removeAllViews()
        bannerSlot.addView(main.banner(color, iconRes, title, sub), lp().margins(b = dp(14)))
    }

    private fun submit() {
        if (busy) return
        val l = login.value.trim()
        val p = password.value
        login.setError(null); password.setError(null)
        if (l.isEmpty()) return login.setError(L.t("Введите логин", "Enter the login"))
        if (p.isEmpty()) return password.setError(L.t("Введите пароль", "Enter the password"))
        busy = true
        setSubmit(L.t("Входим…", "Logging in…"))
        thread(name = "cubex-login") {
            try {
                val r = Api.login(l, p, "${Build.MANUFACTURER} ${Build.MODEL} | CubeX Mobile 3")
                main.getSharedPreferences("auth", 0).edit().putString("lastLogin", l).apply()
                val twofa = r.optJSONObject("twofa")
                main.runOnUiThread {
                    busy = false
                    if (twofa != null) main.open(TwoFaScreen(main, twofa.getString("challenge"), twofa.optString("bot"), twofa.optInt("expiresIn", 120)))
                    else main.loggedIn(r.getString("accessToken"), r.getJSONObject("user").getString("username"))
                }
            } catch (e: Throwable) {
                main.runOnUiThread {
                    busy = false
                    when {
                        e is IOException -> {
                            banner(Cx.WARNING, R.drawable.ic_wifioff, L.t("Нет сети", "No network"), L.t("Проверьте подключение и повторите.", "Check the connection and retry."))
                            setSubmit(L.t("Повторить", "Retry"))
                            return@runOnUiThread
                        }
                        e is ApiException && (e.error == "password" || e.error == "notfound") -> {
                            banner(Cx.ACCENT, R.drawable.ic_warn, L.t("Неверный логин или пароль", "Wrong login or password"),
                                L.t("Проверьте данные и попробуйте снова.", "Check them and try again."))
                            password.setError(L.t("Пароль не подошёл", "The password did not match"))
                        }
                        e is ApiException -> banner(Cx.ACCENT, R.drawable.ic_warn, e.message ?: e.error, null)
                        else -> banner(Cx.ACCENT, R.drawable.ic_warn, L.t("Не получилось войти", "Could not log in"), e.message)
                    }
                    setSubmit(L.t("Войти", "Log in"))
                }
            }
        }
    }
}

/**
 * 02d, as our 2FA works: the server sends a request to the CubeXCraft bot in Telegram and waits for «✅ Это я» (no code to
 * type). Long poll every ~20 s until approved, refused or expired.
 */
class TwoFaScreen(main: MainActivity, private val challenge: String, private val bot: String, private var left: Int) : Screen(main) {
    private lateinit var timer: TextView
    @Volatile private var active = true
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            left = (left - 1).coerceAtLeast(0)
            timer.text = if (left > 0) L.t("Запрос действует ещё $left с", "The request is valid for $left s") else L.t("Время вышло — войдите снова.", "Time is up — log in again.")
            if (left > 0) handler.postDelayed(this, 1000)
        }
    }

    override fun build(): View {
        val c = main.vbox()
        c.addView(main.section(L.t("Подтверждение", "Confirmation")), lp().margins(b = dp(13)))
        c.addView(main.text(L.t("Подтвердите в Telegram", "Confirm in Telegram"), Cx.Font.DISPLAY, 22f, Cx.TEXT_HI))
        c.addView(main.text(L.t("Мы отправили запрос в бота CubeXCraft. Нажмите там «✅ Это я».",
            "We sent a request to the CubeXCraft bot. Tap «✅ Это я» there."), Cx.Font.BODY, 14f, Cx.MUTED), lp().margins(t = dp(6)))
        timer = main.text("", Cx.Font.BODY, 13f, Cx.MUTED)
        c.addView(timer, lp().margins(t = dp(16)))
        val buttons = mutableListOf<View>()
        if (bot.isNotEmpty()) buttons += main.button(L.t("Открыть бота", "Open the bot"), BtnStyle.PRIMARY, R.drawable.ic_send) { main.openLink("https://t.me/$bot") }
        buttons += main.button(L.t("Назад", "Back"), BtnStyle.GHOST) { main.back() }
        return page(brand(), c, bar(*buttons.toTypedArray()))
    }

    override fun onShow() {
        active = true
        handler.post(tick)
        thread(name = "cubex-2fa") {
            while (active) {
                try {
                    val r = Api.poll2fa(challenge)
                    if (r.has("accessToken")) {
                        active = false
                        main.runOnUiThread { main.loggedIn(r.getString("accessToken"), r.getJSONObject("user").getString("username")) }
                        return@thread
                    }
                    r.optInt("left", -1).takeIf { it >= 0 }?.let { left = it }
                } catch (e: ApiException) {
                    active = false
                    main.runOnUiThread { timer.text = e.message ?: e.error; timer.setTextColor(Cx.ACCENT) }
                    return@thread
                } catch (e: IOException) {
                    Thread.sleep(2000)
                }
            }
        }
    }

    override fun onHide() {
        if (active) thread(name = "cubex-2fa-cancel") { runCatching { Api.cancel2fa(challenge) } }
        active = false
        handler.removeCallbacks(tick)
    }
}
