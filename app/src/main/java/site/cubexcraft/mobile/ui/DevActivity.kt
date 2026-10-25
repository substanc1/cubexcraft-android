// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import site.cubexcraft.mobile.game.GameActivity
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.install.Runtimes
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.ApiException
import site.cubexcraft.mobile.net.Relay
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Test screen of the first 3.0 builds: login, install, play — to check the new core on a phone. The real screens follow the
 * design handoff (android3) and replace this one.
 */
class DevActivity : Activity() {
    companion object {
        const val EXTRA_EXIT_CODE = "exitCode"
        const val EXTRA_EXIT_SIGNAL = "exitSignal"
        private const val TAG = "CubexDev"
    }

    private lateinit var status: TextView
    private lateinit var login: EditText
    private lateinit var password: EditText
    private lateinit var account: TextView
    private val prefs by lazy { getSharedPreferences("auth", Context.MODE_PRIVATE) }
    @Volatile private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "CUBEX MOBILE 3 · ТЕСТ ЯДРА"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        account = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(account)
        login = EditText(this).apply { hint = "Логин"; inputType = InputType.TYPE_CLASS_TEXT }
        password = EditText(this).apply { hint = "Пароль"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.addView(login)
        col.addView(password)
        col.addView(button("ВОЙТИ") { doLogin() })
        col.addView(button("СКАЧАТЬ / ОБНОВИТЬ СБОРКУ") { doInstall() })
        col.addView(button("ИГРАТЬ") { doPlay(false) })
        col.addView(button("ИГРАТЬ · СРАЗУ НА СЕРВЕР") { doPlay(true) })
        col.addView(button("ПОДЕЛИТЬСЯ ЖУРНАЛОМ") { thread(name = "cubex-share") { val t = lastLog.ifEmpty { collectLog() }; runOnUiThread { shareLog(t) } } })
        col.addView(button("ВЫЙТИ ИЗ АККАУНТА") { prefs.edit().clear().apply(); refresh() })
        status = TextView(this).apply {
            setPadding(0, dp(16), 0, 0)
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
        showExit(intent)
        thread(name = "cubex-probe") { Relay.probe(); runOnUiThread { say("связь: ${if (Relay.enabled) "через Москву" else "напрямую"}") } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showExit(intent)
    }

    private fun showExit(i: Intent) {
        if (!i.hasExtra(EXTRA_EXIT_CODE)) return
        val code = i.getIntExtra(EXTRA_EXIT_CODE, 0)
        say("игра завершилась, код $code${if (i.getBooleanExtra(EXTRA_EXIT_SIGNAL, false)) " (сигнал)" else ""}")
        thread(name = "cubex-log") {
            // give the game process a moment to flush its last lines
            Thread.sleep(800)
            val log = collectLog()
            runOnUiThread { say(log.lines().takeLast(40).joinToString("\n")) }
        }
    }

    /**
     * The last run from the system log: the app may read its own lines (both processes share the uid), no permission needed.
     * Saved next to the game logs and shared with the button, so a failed start can be diagnosed without a computer.
     */
    private fun collectLog(): String {
        val sb = StringBuilder()
        runCatching { sb.append(java.io.File(filesDir, "last-launch-error.txt").takeIf { it.isFile }?.readText().orEmpty()) }
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
            val keep = Regex("Cubex|jrelog|LIBGL|NativeInput|DynamicLoader|AndroidRuntime|MobileGlues|EGLBridge|libc|DEBUG|ExitTrap|JLI|pojav", RegexOption.IGNORE_CASE)
            p.inputStream.bufferedReader().useLines { lines -> lines.filter { keep.containsMatchIn(it) }.forEach { sb.append(it).append('\n') } }
        }.onFailure { sb.append("logcat: $it\n") }
        // what the JVM printed (stdout/stderr) and the game's own log
        Installer.installed(this)?.second?.let { layout ->
            for ((name, n) in listOf("logs/launcher-native.log" to 80, "logs/latest.log" to 60)) {
                val f = java.io.File(layout.client, name)
                if (f.isFile) sb.append("--- $name\n").append(f.readLines().takeLast(n).joinToString("\n")).append('\n')
            }
        }
        val text = sb.toString().lines().takeLast(600).joinToString("\n")
        runCatching { java.io.File(getExternalFilesDir(null), "last-run-log.txt").writeText(text) }
        lastLog = text
        return text
    }

    @Volatile private var lastLog = ""

    private fun shareLog(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.takeLast(60000)), "Журнал CubeX Mobile 3"))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { if (!busy) onClick() }
    }

    private fun token(): String? = prefs.getString("token", null)

    private fun refresh() {
        val user = prefs.getString("username", null)
        account.text = if (user != null) "Аккаунт: $user" else "Не вошли"
        val v = if (user != null) View.GONE else View.VISIBLE
        login.visibility = v
        password.visibility = v
        val installed = Installer.installed(this)
        say("сборка: ${if (installed != null) "установлена" else "не установлена"} · память ${site.cubexcraft.mobile.game.Launch.totalRamMb(this)} МБ")
    }

    private fun say(text: String) {
        Log.i(TAG, text)
        runOnUiThread { status.text = text + "\n" + status.text.toString().lines().take(30).joinToString("\n") }
    }

    private fun work(name: String, block: () -> Unit) {
        busy = true
        thread(name = name) {
            try {
                block()
            } catch (e: ApiException) {
                say("ошибка API ${e.code}: ${e.message}")
            } catch (t: Throwable) {
                Log.e(TAG, name, t)
                say("ошибка: $t")
            } finally {
                busy = false
            }
        }
    }

    private fun doLogin() = work("cubex-login") {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} · CubeX Mobile 3"
        var r = Api.login(login.text.toString().trim(), password.text.toString(), device)
        val twofa = r.optJSONObject("twofa")
        if (twofa != null) {
            say("подтвердите вход в Telegram-боте…")
            val challenge = twofa.getString("challenge")
            while (true) {
                r = Api.poll2fa(challenge)
                if (r.has("accessToken")) break
                if (r.optString("status") !in setOf("pending", "")) error("2FA: ${r.optString("status")}")
            }
        }
        val user = r.getJSONObject("user")
        prefs.edit().putString("token", r.getString("accessToken")).putString("username", user.getString("username")).apply()
        say("вошли как ${user.getString("username")}")
        runOnUiThread { refresh() }
    }

    private fun doInstall() = work("cubex-install") {
        val token = token() ?: error("сначала войдите")
        val inst = Installer(this, token)
        inst.onStatus = { say(it) }
        var last = 0L
        inst.onProgress = site.cubexcraft.mobile.net.SyncProgress { done, total, bytes, bytesTotal ->
            val now = System.currentTimeMillis()
            if (now - last > 1000 || done == total) {
                last = now
                say(String.format(Locale.ROOT, "%d / %d файлов · %.1f / %.1f МБ", done, total, bytes / 1048576.0, bytesTotal / 1048576.0))
            }
        }
        val t = System.currentTimeMillis()
        inst.run()
        val rt = Runtimes.ensure(this)
        say("готово за ${(System.currentTimeMillis() - t) / 1000} с · Java: ${rt.javaHome.name}, LWJGL: ${rt.lwjglJars.size} jar")
        runOnUiThread { refresh() }
    }

    private fun doPlay(join: Boolean) = work("cubex-play") {
        val token = token() ?: error("сначала войдите")
        val (profile, layout) = Installer.installed(this) ?: error("сначала скачайте сборку")
        java.io.File(filesDir, "last-launch-error.txt").delete()
        lastLog = ""
        say("готовлю Java…")
        Runtimes.ensure(this)
        val s = Api.gameSession(token)
        val filesDir = layout.client.name
        say("запуск ${profile.optString("title")} (куча ${site.cubexcraft.mobile.game.Launch.heapMb(this, profile)} МБ)…")
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_FILES_DIR, filesDir)
                .putExtra(GameActivity.EXTRA_USERNAME, s.getString("username"))
                .putExtra(GameActivity.EXTRA_UUID, s.getString("uuid"))
                .putExtra(GameActivity.EXTRA_TOKEN, s.getString("accessToken"))
                .putExtra(GameActivity.EXTRA_JOIN, join)
                .putExtra(GameActivity.EXTRA_SCALE, 0.75f)
        )
    }
}
