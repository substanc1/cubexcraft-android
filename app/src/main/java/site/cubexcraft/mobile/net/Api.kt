// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.net

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, val error: String, message: String?) : Exception(message ?: error)

/** One file of a /files/<dir> manifest: [size] bytes, lowercase [sha1]. */
data class FileEntry(val path: String, val size: Long, val sha1: String)

class Manifest(val base: String, val files: Map<String, FileEntry>)

/** https://www.cubexcraft.site/api/launcher/v1 — the protocol of the desktop launcher and CubeX Mobile 2.7.x. */
object Api {
    private const val BASE = "https://www.cubexcraft.site/api/launcher/v1"
    private const val PLATFORM = "android"
    private val JSON_TYPE = "application/json".toMediaType()
    @Volatile var appVersion = "3.0.0"

    /** Parallel downloads keep their connections alive between files (OkHttp keeps 5 idle by default). */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Relay.dns)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .build()
    }

    private fun call(path: String, token: String? = null, body: JSONObject? = null): String {
        val b = Request.Builder().url(BASE + path)
            .header("X-Launcher-Platform", PLATFORM)
            .header("X-Launcher-Version", appVersion)
        if (token != null) b.header("Authorization", "Bearer $token")
        if (body != null) b.post(body.toString().toRequestBody(JSON_TYPE)) else b.get()
        return http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val e = runCatching { JSONObject(text) }.getOrNull()
                throw ApiException(r.code, e?.optString("error")?.ifEmpty { null } ?: "http${r.code}", e?.optString("message")?.ifEmpty { null })
            }
            text
        }
    }

    private fun obj(path: String, token: String? = null, body: JSONObject? = null) = JSONObject(call(path, token, body))

    /** Any call of the launcher API for the web UI: (HTTP status, body). IOException: no network. */
    fun raw(method: String, path: String, token: String?, body: String?): Pair<Int, String> {
        val b = Request.Builder().url(BASE + path)
            .header("X-Launcher-Platform", PLATFORM)
            .header("X-Launcher-Version", appVersion)
        if (token != null) b.header("Authorization", "Bearer $token")
        when {
            method == "GET" -> b.get()
            else -> b.method(method, (body ?: "{}").toRequestBody(JSON_TYPE))
        }
        return http.newCall(b.build()).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
    }

    /** {accessToken, user} or a Telegram 2FA challenge {twofa:{challenge, expiresIn, bot}}. */
    fun login(login: String, password: String, device: String): JSONObject =
        obj("/auth/login", body = JSONObject().put("login", login).put("password", password).put("device", device.take(128)).put("twofa", "poll"))

    /** Long poll (~20 s): {status:"pending", left} or the final {accessToken, user}. */
    fun poll2fa(challenge: String): JSONObject = obj("/auth/2fa", body = JSONObject().put("challenge", challenge))

    fun cancel2fa(challenge: String) {
        call("/auth/2fa/cancel", body = JSONObject().put("challenge", challenge))
    }

    fun logout(token: String) {
        call("/auth/logout", token, JSONObject())
    }

    fun me(token: String): JSONObject = obj("/me", token).getJSONObject("user")

    fun profiles(token: String): JSONArray = obj("/profiles?platform=$PLATFORM", token).getJSONArray("profiles")

    /** Token for the game itself (--accessToken): {accessToken, uuid, username}. */
    fun gameSession(token: String): JSONObject = obj("/game/session", token, JSONObject())

    fun manifest(token: String, dir: String): Manifest {
        val m = obj("/files/" + Sync.encodePath(dir), token)
        val base = m.getString("base").let { if (it.endsWith("/")) it else "$it/" }
        val files = m.getJSONObject("files")
        val out = HashMap<String, FileEntry>(files.length() * 2)
        val keys = files.keys()
        while (keys.hasNext()) {
            val p = keys.next()
            val v = files.getJSONArray(p)
            out[p] = FileEntry(p, v.getLong(0), v.getString(1).lowercase())
        }
        return Manifest(base, out)
    }

    fun news(): JSONObject = obj("/news")

    fun servers(): JSONObject = obj("/servers")

    /** {cubex, nanites}. */
    fun wallet(token: String): JSONObject = obj("/wallet", token)

    /** Friends, requests and notifications in one answer (launcher-friends.js «snapshot»). */
    fun friends(token: String): JSONObject = obj("/friends", token)

    /** «In the app» every ~30 s (state online / away / playing + server); answers with the same snapshot. */
    fun presence(token: String, state: String, server: String? = null): JSONObject =
        obj("/presence", token, JSONObject().put("state", state).apply { if (server != null) put("server", server) })

    fun presenceOffline(token: String) {
        call("/presence/offline", token, JSONObject())
    }

    fun friendAccept(token: String, username: String): JSONObject = obj("/friends/accept", token, JSONObject().put("username", username))

    fun friendDecline(token: String, username: String): JSONObject = obj("/friends/decline", token, JSONObject().put("username", username))

    fun notificationsRead(token: String): JSONObject = obj("/notifications/read", token, JSONObject())
}
