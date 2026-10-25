// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.ApiException
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class Server(
    val slug: String, val name: String, val host: String, val port: Int, val up: Boolean, val online: Int, val max: Int,
    val version: String, val statusLabel: String,
)

class News(val id: String, val title: String, val body: String, val cover: String?, val date: String)

class Friend(val username: String, val state: String, val server: String?)

class FriendRequest(val username: String, val mutual: Int)

class Note(val id: String, val kind: String, val actor: String, val server: String, val unread: Boolean, val agoSec: Long)

/**
 * What the screens show from the server: servers with online counts, news and voting, the wallet, friends and notifications.
 * Only real answers of the API (no examples, no guesses); the last answers are kept on disk so that without network the app
 * shows them with «Нет сети» instead of an empty screen.
 */
object Repo {
    private const val TAG = "CubexRepo"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "cubex-repo").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private lateinit var dir: File

    @Volatile var servers: List<Server> = emptyList(); private set
    @Volatile var news: List<News> = emptyList(); private set
    @Volatile var voteUrl: String? = null; private set
    @Volatile var voteReward = 0; private set
    @Volatile var voteSites = 0; private set
    /** -1: not known (not logged in, or never answered). */
    @Volatile var cubex = -1L; private set
    @Volatile var nanites = -1L; private set
    @Volatile var friends: List<Friend> = emptyList(); private set
    @Volatile var requests: List<FriendRequest> = emptyList(); private set
    @Volatile var notes: List<Note> = emptyList(); private set
    /** The last refresh could not reach the server: what is shown comes from the disk. */
    @Volatile var offline = false; private set
    /** Something to show (from the disk or the server). */
    @Volatile var loaded = false; private set
    @Volatile private var refreshing = false

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    /** The last answers from the disk, in the background (the first screen does not wait for it). */
    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "api-cache").apply { mkdirs() }
        pool.execute {
            runCatching { read("servers")?.let { parseServers(it) } }
            runCatching { read("news")?.let { parseNews(it) } }
            runCatching { read("wallet")?.let { parseWallet(it) } }
            runCatching { read("friends")?.let { parseSocial(it) } }
            if (servers.isNotEmpty()) loaded = true
            changed()
        }
    }

    private fun read(name: String): JSONObject? = File(dir, "$name.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
    private fun save(name: String, j: JSONObject) = runCatching { File(dir, "$name.json").writeText(j.toString()) }

    /** Everything at once, four requests in parallel; a call while one runs is skipped. */
    fun refresh(token: String?) {
        if (refreshing) return
        refreshing = true
        pool.execute {
            var netErrors = 0
            var tries = 0
            fun <T> attempt(name: String, call: () -> JSONObject, parse: (JSONObject) -> T) {
                tries++
                try {
                    val j = call()
                    parse(j)
                    save(name, j)
                } catch (e: IOException) {
                    netErrors++
                    Log.w(TAG, "$name: $e")
                } catch (e: ApiException) {
                    Log.w(TAG, "$name: API ${e.code} ${e.message}")
                } catch (t: Throwable) {
                    Log.w(TAG, "$name: $t")
                }
            }
            val jobs = listOf(
                pool.submit { attempt("servers", { Api.servers() }, ::parseServers) },
                pool.submit { attempt("news", { Api.news() }, ::parseNews) },
            ) + if (token != null) listOf(
                pool.submit { attempt("wallet", { Api.wallet(token) }, ::parseWallet) },
                pool.submit { attempt("friends", { Api.friends(token) }, ::parseSocial) },
            ) else emptyList()
            jobs.forEach { runCatching { it.get() } }
            offline = netErrors > 0 && netErrors == tries
            if (servers.isNotEmpty() || !offline) loaded = true
            refreshing = false
            changed()
        }
    }

    /** «In the app» for friends; the answer refreshes friends and notifications. */
    fun presence(token: String, state: String, server: String? = null) = pool.execute {
        runCatching { Api.presence(token, state, server).also { parseSocial(it); save("friends", it) } }
            .onFailure { Log.w(TAG, "presence: $it") }
        changed()
    }

    /** A friends action (accept, decline, mark read): its answer is the new snapshot. */
    fun social(call: () -> JSONObject, done: (Throwable?) -> Unit = {}) = pool.execute {
        val r = runCatching { call().also { parseSocial(it); save("friends", it) } }
        changed()
        main.post { done(r.exceptionOrNull()) }
    }

    /** After logout: nothing of the old account stays. */
    fun clearAccount() {
        cubex = -1; nanites = -1
        friends = emptyList(); requests = emptyList(); notes = emptyList()
        File(dir, "wallet.json").delete()
        File(dir, "friends.json").delete()
        changed()
    }

    private fun parseServers(j: JSONObject) {
        servers = objects(j.optJSONArray("servers")).map {
            Server(it.optString("slug"), it.optString("name"), it.optString("host"), it.optInt("port", 25565), it.optBoolean("up"),
                it.optInt("online"), it.optInt("max"), it.optString("version"), it.optString("statusLabel"))
        }
    }

    private fun parseNews(j: JSONObject) {
        news = objects(j.optJSONArray("news")).map {
            News(it.optString("id"), it.optString("title"), it.optString("body"), it.optString("cover").ifEmpty { null }?.takeIf { c -> c != "null" },
                it.optString("date"))
        }
        val v = j.optJSONObject("vote")
        voteUrl = v?.optString("url")?.ifEmpty { null } ?: j.optString("voteUrl").ifEmpty { null }
        voteReward = v?.optInt("reward") ?: 0
        voteSites = v?.optInt("sites") ?: 0
    }

    private fun parseWallet(j: JSONObject) {
        cubex = j.optLong("cubex", -1)
        nanites = j.optLong("nanites", -1)
    }

    private fun parseSocial(j: JSONObject) {
        friends = objects(j.optJSONArray("friends")).map { Friend(it.optString("username"), it.optString("state"), it.optString("server").ifEmpty { null }) }
        requests = objects(j.optJSONArray("requests")).map { FriendRequest(it.optString("username"), it.optInt("mutual")) }
        notes = objects(j.optJSONArray("notifications")).map {
            Note(it.optString("id"), it.optString("kind"), it.optString("actor"), it.optString("server"), it.optBoolean("unread"), it.optLong("agoSec"))
        }
    }

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
