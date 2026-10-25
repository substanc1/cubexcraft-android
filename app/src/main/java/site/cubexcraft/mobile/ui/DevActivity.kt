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
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.game.GameActivity
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.install.Runtimes
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.ApiException
import site.cubexcraft.mobile.net.LogUpload
import site.cubexcraft.mobile.net.Relay
import site.cubexcraft.mobile.update.SelfUpdate
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
            text = "CUBEX MOBILE 3 · ТЕСТ ЯДРА · ${BuildConfig.VERSION_NAME}"
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
        col.addView(button("ПОДЕЛИТЬСЯ ЖУРНАЛОМ (ФАЙЛ)") { bg("cubex-share") { if (lastLogFile == null) collectLog(); runOnUiThread { shareLog() } } })
        col.addView(button("ОТПРАВИТЬ ЖУРНАЛ РАЗРАБОТЧИКАМ") {
            bg("cubex-send") { val ok = LogUpload.send(this, "manual", collectLog()); say(if (ok) "журнал отправлен" else "не отправлен — нет сети или не вошли") }
        })
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
        bg("cubex-probe") { Relay.probe(); runOnUiThread { say("связь: ${if (Relay.enabled) "через Москву" else "напрямую"}") } }
        SelfUpdate.onStatus = { s -> if (s != null) say(s) }
        SelfUpdate.start(this)
        // a crash of the last run (ours or Android's account of it) goes to the developers before anything else
        bg("cubex-crashes") {
            val report = site.cubexcraft.mobile.CrashReporter.pending(this) ?: return@bg
            say("прошлый запуск упал — отправляю отчёт разработчикам")
            val ok = LogUpload.send(this, "appcrash", report.text + "\n--- logcat\n" + LogUpload.logcat(1500))
            if (ok) report.done()
            say(if (ok) "отчёт о падении отправлен" else "отчёт о падении не отправлен — повторю при следующем запуске")
            runOnUiThread { say(report.text.lines().take(12).joinToString("\n")) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showExit(intent)
    }

    private fun showExit(i: Intent) {
        if (!i.hasExtra(EXTRA_EXIT_CODE)) return
        val code = i.getIntExtra(EXTRA_EXIT_CODE, 0)
        say("игра завершилась, код $code${if (i.getBooleanExtra(EXTRA_EXIT_SIGNAL, false)) " (сигнал)" else ""}")
        bg("cubex-log") {
            // give the game process a moment to flush its last lines
            Thread.sleep(800)
            val log = collectLog()
            val err = runCatching { java.io.File(filesDir, "last-launch-error.txt").takeIf { it.isFile }?.readLines()?.take(3) }.getOrNull()
            runOnUiThread {
                say(log.lines().takeLast(30).joinToString("\n"))
                if (!err.isNullOrEmpty()) say("ОШИБКА: " + err.joinToString("\n"))
            }
            val sent = LogUpload.send(this, if (code == 0) "run" else "crash", log)
            say(if (sent) "журнал запуска отправлен разработчикам" else "журнал не отправлен (нет сети?) — кнопка «Поделиться журналом»")
        }
    }

    /**
     * Everything about the last run in one text file: our error, our and the bridge's logcat lines (the app may read its own,
     * both processes share the uid), what the JVM printed, the game log and the launch command. Kept in logs/ (last 10), sent
     * to the server and shared as a file.
     */
    private fun collectLog(): String {
        val sb = StringBuilder()
        sb.append("CubeX Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
        runCatching { sb.append(java.io.File(filesDir, "last-launch-error.txt").takeIf { it.isFile }?.readText().orEmpty()) }
        sb.append("--- logcat\n").append(LogUpload.logcat())
        Installer.installed(this)?.second?.let { layout ->
            for ((name, n) in listOf("logs/launch-command.txt" to 5, "logs/launcher-native.log" to 400, "logs/latest.log" to 400)) {
                sb.append(LogUpload.tail(java.io.File(layout.client, name), n))
            }
            // a native crash of the JVM: its report (the native and Java stacks are at the top); Minecraft's own crash report
            LogUpload.recent(layout.client) { it.startsWith("hs_err_pid") }?.let { sb.append(LogUpload.head(it, 300)) }
            LogUpload.recent(java.io.File(layout.client, "crash-reports")) { it.startsWith("crash-") }?.let { sb.append(LogUpload.head(it, 300)) }
        }
        val text = sb.toString()
        runCatching {
            val dir = java.io.File(getExternalFilesDir(null), "logs").apply { mkdirs() }
            val f = java.io.File(dir, "cubex-run-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(java.util.Date())}.txt")
            f.writeText(text)
            dir.listFiles { x -> x.name.startsWith("cubex-run-") }?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
            lastLogFile = f
        }
        lastLog = text
        return text
    }

    @Volatile private var lastLog = ""
    @Volatile private var lastLogFile: java.io.File? = null

    /** The log as a .txt file (Telegram, mail…), not pasted text. */
    private fun shareLog() {
        val f = lastLogFile ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Журнал CubeX Mobile 3",
        ))
    }

    /** Background work of this screen: an error is shown and logged, it never takes the app down. */
    private fun bg(name: String, block: () -> Unit) {
        thread(name = name) {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, name, t)
                say("ошибка ($name): $t")
            }
        }
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
                LogUpload.send(this, "error", "$name: " + Log.getStackTraceString(t) + "\n--- logcat\n" + LogUpload.logcat(500))
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
