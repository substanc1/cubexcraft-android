// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.data.Device
import site.cubexcraft.mobile.data.sizeText
import site.cubexcraft.mobile.game.Launch
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.install.Runtimes
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.ApiException
import site.cubexcraft.mobile.net.SyncProgress
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.round

/**
 * The native side of the launcher UI (assets/cxc-ui, the same as CubeXCraft for iPhone): the commands of iPhone's
 * CXCWebLauncherViewController with the same answers — init, api, image, projectState, login, cancelLogin, logout, play,
 * cancelPlay, setSetting, openURL, share, openAdvanced; events «login2fa», «prep», «prepError», «prepCancelled», «resume».
 * The launcher token never reaches the page: API calls go through here.
 */
class WebBridge(private val main: MainActivity, private val web: WebView) {
    private val tag = "CubexWeb"

    @JavascriptInterface
    fun post(id: Int, json: String) {
        val m = runCatching { JSONObject(json) }.getOrElse { reply(id, false, JSONObject.quote("bad message")); return }
        val cmd = m.optString("cmd")
        thread(name = "cubex-web-$cmd") {
            try {
                when (cmd) {
                    "init" -> ok(id, init())
                    "api" -> ok(id, api(m))
                    "image" -> ok(id, image(m.optString("url")))
                    "projectState" -> ok(id, projectState(m.optString("filesDir", "CubeXCraft-mobile")))
                    "login" -> ok(id, login(m.optString("login"), m.optString("password")))
                    "cancelLogin" -> { loginCancelled = true; challenge?.let { c -> runCatching { Api.cancel2fa(c) } }; ok(id, JSONObject()) }
                    "logout" -> { main.runOnUiThread { main.logout() }; ok(id, JSONObject()) }
                    "play" -> { ok(id, JSONObject()); play(m.optString("title"), m.optString("server")) }
                    "cancelPlay" -> { playCancelled = true; installer?.cancelled = true; ok(id, JSONObject()) }
                    "setSetting" -> ok(id, setSetting(m.optString("key"), m.opt("value")))
                    "openURL" -> { openUrl(m.optString("url")); ok(id, JSONObject()) }
                    "share" -> { share(m.optString("url", SITE)); ok(id, JSONObject()) }
                    "openAdvanced" -> { main.runOnUiThread { main.diagnostics() }; ok(id, JSONObject()) }
                    "jitCheck" -> ok(id, JSONObject().put("stik", true).put("vpn", true).put("vpnApp", true))
                    else -> ok(id, JSONObject())   // iPhone-only (openStore, jitRetry): nothing to do here
                }
            } catch (t: Throwable) {
                Log.w(tag, "$cmd failed", t)
                reply(id, false, JSONObject.quote(t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun ok(id: Int, data: JSONObject) = reply(id, true, data.toString())

    private fun reply(id: Int, ok: Boolean, data: String) = web.post {
        web.evaluateJavascript("window.__cxcReply && window.__cxcReply($id, $ok, $data)", null)
    }

    fun emit(type: String, data: JSONObject = JSONObject()) = web.post {
        web.evaluateJavascript("window.cxc && window.cxc.emit(${JSONObject.quote(type)}, $data)", null)
    }

    // --- init and settings ---

    private fun qualityKey(q: Settings.Quality) = when (q) {
        Settings.Quality.AUTO -> "auto"
        Settings.Quality.MAX -> "max"
        Settings.Quality.BALANCE -> "balanced"
        Settings.Quality.ECO -> "light"
    }

    private fun settingsInfo() = JSONObject()
        .put("quality", qualityKey(Settings.quality))
        .put("qualityAuto", qualityKey(Settings.effective(main)))
        .put("shaders", Settings.shaders.name.lowercase())
        .put("shadersAuto", false)
        .put("joinOnStart", Settings.joinServer)

    private fun init(): JSONObject {
        val info = settingsInfo()
            .put("lang", if (L.ru) "ru" else "en")
            .put("version", BuildConfig.VERSION_NAME)
            .put("memTotalGB", round(Launch.totalRamMb(main) / 1024.0))
            .put("memAvailGB", round(Device.availRamMb(main) / 102.4) / 10.0)
            .put("jit", JSONObject().put("stik", true).put("vpn", true).put("vpnApp", true))
            .put("needsSetup", false)
            .put("lowMemory", lowMemoryOnce())
        val name = main.username
        if (main.token != null && name != null) info.put("account", JSONObject().put("username", name).put("uuid", main.uuid ?: ""))
        return info
    }

    /** A weak phone: once, the «memory is tight» window, and the light quality without shaders from then on. */
    private fun lowMemoryOnce(): Boolean {
        val prefs = main.getSharedPreferences("settings", 0)
        if (prefs.getBoolean("lowMemoryShown", false)) return false
        if (Device.check(main).verdict == Device.Verdict.OK) return false
        prefs.edit().putBoolean("lowMemoryShown", true).apply()
        if (Settings.quality == Settings.Quality.AUTO) Settings.quality = Settings.Quality.ECO
        Settings.shaders = Settings.Shaders.OFF
        return true
    }

    private fun setSetting(key: String, value: Any?): JSONObject {
        when (key) {
            "quality" -> Settings.quality = when (value) {
                "max" -> Settings.Quality.MAX
                "balanced" -> Settings.Quality.BALANCE
                "light" -> Settings.Quality.ECO
                else -> Settings.Quality.AUTO
            }
            "shaders" -> Settings.shaders = when (value) {
                "on" -> Settings.Shaders.ON
                "off" -> Settings.Shaders.OFF
                else -> Settings.Shaders.AUTO
            }
            "joinOnStart" -> Settings.joinServer = value == true || value == "true"
            "lang" -> Settings.lang = if (value == "en") "en" else "ru"
        }
        return settingsInfo()
    }

    // --- the site's API (the token stays here) ---

    private fun api(m: JSONObject): JSONObject {
        val path = m.optString("path")
        require(path.startsWith("/")) { "bad path" }
        val token = if (m.optBoolean("auth", true)) main.token ?: return JSONObject().put("status", 401).put("json", JSONObject()) else null
        val body = m.optJSONObject("body")?.toString()
        return try {
            val (status, text) = Api.raw(m.optString("method", "GET"), path, token, body)
            val json: Any = runCatching { JSONObject(text) }.getOrElse { runCatching { JSONArray(text) }.getOrDefault(JSONObject()) }
            JSONObject().put("status", status).put("json", json)
        } catch (e: IOException) {
            JSONObject().put("network", true)
        }
    }

    /** A news cover as a data: address (only pictures of the site, up to 2 MB), fetched here — through the RU relay when on. */
    private fun image(url: String): JSONObject {
        if (!url.startsWith("$SITE/")) return JSONObject()
        return runCatching {
            Api.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty().substringBefore(';')
                val bytes = r.body?.bytes() ?: return JSONObject()
                if (!r.isSuccessful || !type.startsWith("image/") || bytes.size > (2 shl 20)) return JSONObject()
                JSONObject().put("data", "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }.getOrDefault(JSONObject())
    }

    // --- the build ---

    @Volatile private var plan: Installer.Plan? = null
    @Volatile private var planAt = 0L

    /** Installed: its mods. Not yet: what the download weighs and what is in it (the plan is kept for «play»). */
    private fun projectState(filesDir: String): JSONObject {
        val installed = Installer.installed(main)
        if (installed != null) {
            val mods = File(installed.second.client, "mods").list()?.filter { it.endsWith(".jar") }.orEmpty().map(::modName).sortedBy { it.lowercase() }
            return JSONObject().put("installed", true).put("mods", JSONArray(mods))
        }
        val token = main.token ?: return JSONObject().put("installed", false)
        val p = plan?.takeIf { System.currentTimeMillis() - planAt < 10 * 60_000 } ?: Installer(main, token).plan().also { plan = it; planAt = System.currentTimeMillis() }
        return JSONObject().put("installed", false).put("sizeMB", round(p.bytes / 1048576.0).toLong())
            .put("mods", JSONArray(p.mods.map(::modName).sortedBy { it.lowercase() }))
    }

    /** «gtceu-1.21.1-8.0.0.jar» → «gtceu» (the API has no mod names; as on iPhone). */
    private fun modName(file: String): String {
        val words = ArrayList<String>()
        for (part in file.removeSuffix(".jar").split('-')) {
            val low = part.lowercase()
            if (part.isEmpty() || part[0].isDigit() || low.startsWith("mc1") || low.startsWith("v1") || low in setOf("neoforge", "forge", "fabric", "all")) break
            words += part
        }
        return if (words.isEmpty()) file.removeSuffix(".jar") else words.joinToString(" ")
    }

    @Volatile private var installer: Installer? = null
    @Volatile private var playCancelled = false
    @Volatile private var playing = false

    /** Check → download (progress twice a second) → start, as the iPhone's prepare screen expects. */
    private fun play(title: String, serverName: String) {
        if (playing) return
        playing = true
        playCancelled = false
        try {
            val token = main.token ?: run { emit("prepError", JSONObject().put("message", L.t("Войдите в аккаунт CubeXCraft заново", "Log in to CubeXCraft again"))); return }
            emit("prep", JSONObject().put("phase", 0).put("percent", 0))
            val inst = Installer(main, token)
            installer = inst
            val p = plan?.takeIf { System.currentTimeMillis() - planAt < 60_000 } ?: inst.plan()
            plan = null
            if (playCancelled) { emit("prepCancelled"); return }
            if (p.jobs.isNotEmpty()) {
                val need = p.bytes + (64L shl 20)
                val free = Device.freeBytes(main)
                if (free < need) {
                    emit("prepError", JSONObject().put("message",
                        L.t("Не хватает места: нужно ещё ${sizeText(need - free)}", "Not enough space: ${sizeText(need - free)} more is needed")))
                    return
                }
                var done = 0; var total = p.jobs.size; var bytes = 0L; var bytesTotal = p.bytes
                inst.onProgress = SyncProgress { d, t, b, bt -> done = d; total = t; bytes = b; bytesTotal = bt }
                val ticker = thread(name = "cubex-prep-tick") {
                    var lastB = 0L
                    var lastT = System.nanoTime()
                    var speed = 0.0
                    while (!Thread.currentThread().isInterrupted) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                        val now = System.nanoTime()
                        val dt = (now - lastT) / 1e9
                        if (dt >= 1) {
                            val mbs = (bytes - lastB) / 1048576.0 / dt
                            speed = if (speed > 0) speed * 0.6 + mbs * 0.4 else mbs
                            lastB = bytes; lastT = now
                        }
                        emit("prep", JSONObject().put("phase", 1).put("percent", if (bytesTotal > 0) bytes * 100.0 / bytesTotal else 0.0)
                            .put("done", done).put("total", total).put("mb", bytesTotal / 1048576.0).put("speed", speed.coerceAtLeast(0.0)))
                    }
                }
                try {
                    inst.run(p)
                } finally {
                    ticker.interrupt()
                }
            } else {
                inst.run(p)
            }
            if (playCancelled) { emit("prepCancelled"); return }
            emit("prep", JSONObject().put("phase", 2).put("percent", 100))
            Runtimes.ensure(main)
            if (playCancelled) { emit("prepCancelled"); return }
            val address = if (Settings.joinServer) serverAddress(serverName) else null
            main.playingName = serverName
            main.runOnUiThread { main.startGame(address) }
        } catch (e: Throwable) {
            if (playCancelled || e is InterruptedException || e is java.util.concurrent.CancellationException) {
                emit("prepCancelled")
            } else {
                Log.w(tag, "play failed", e)
                val msg = when (e) {
                    is IOException -> L.t("Нет связи с сервером", "No connection to the server")
                    is ApiException -> e.message ?: e.error
                    else -> e.message ?: e.javaClass.simpleName
                }
                emit("prepError", JSONObject().put("message", msg))
            }
        } finally {
            installer = null
            playing = false
        }
    }

    /** The server the player chose, by its name in /servers → «host:port» for quick join. */
    private fun serverAddress(name: String): String? = runCatching {
        val list = Api.servers().optJSONArray("servers") ?: return null
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i)
            if (s.optString("name").equals(name, ignoreCase = true)) return "${s.optString("host")}:${s.optInt("port", 25565)}"
        }
        null
    }.getOrNull()

    // --- login ---

    @Volatile private var loginCancelled = false
    @Volatile private var challenge: String? = null

    /** {ok, account} or {ok:false, code: network|rate|password|cancelled|other, message}; 2FA — «login2fa» and waiting in the bot. */
    private fun login(login: String, password: String): JSONObject {
        loginCancelled = false
        fun fail(code: String, msg: String? = null) = JSONObject().put("ok", false).put("code", code).put("message", msg ?: "")
        try {
            var r = Api.login(login.trim(), password, "${Build.MANUFACTURER} ${Build.MODEL} | CubeX Mobile 3")
            val twofa = r.optJSONObject("twofa")
            if (twofa != null) {
                val c = twofa.getString("challenge")
                challenge = c
                emit("login2fa")
                while (true) {
                    if (loginCancelled) return fail("cancelled")
                    try {
                        r = Api.poll2fa(c)
                    } catch (e: IOException) {
                        Thread.sleep(2000)
                        continue
                    }
                    if (r.has("accessToken")) break
                }
                challenge = null
            }
            val user = r.getJSONObject("user")
            main.saveLogin(r.getString("accessToken"), user.getString("username"), user.optString("uuid"))
            return JSONObject().put("ok", true).put("account", JSONObject().put("username", user.getString("username")).put("uuid", user.optString("uuid")))
        } catch (e: IOException) {
            return fail("network")
        } catch (e: ApiException) {
            if (loginCancelled) return fail("cancelled")
            return when {
                e.code == 429 -> fail("rate", e.message)
                e.error == "password" || e.error == "notfound" || e.error == "input" -> fail("password", e.message)
                else -> fail("other", e.message)
            }
        } finally {
            challenge = null
        }
    }

    // --- links ---

    private fun openUrl(url: String) {
        // the UI's «licences» link points to the iPhone channel; on Android — this app's source and licences
        val u = if (url.endsWith("/cxl/launcher/ios.json")) "https://github.com/substanc1/cubexcraft-android" else url
        main.runOnUiThread { main.openLink(u) }
    }

    private fun share(url: String) = main.runOnUiThread {
        runCatching {
            main.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "CubeXCraft"))
        }
    }

    companion object {
        const val SITE = "https://www.cubexcraft.site"
    }
}
