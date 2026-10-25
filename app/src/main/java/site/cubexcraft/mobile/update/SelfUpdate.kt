// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.Download
import site.cubexcraft.mobile.net.Sync
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * The app updates itself on start (from CubeX Mobile 2.7.x): reads the APK channel of the site, downloads a newer APK (8
 * connections in ranges, through the RU relay when it is on), checks SHA-256 and installs it with a PackageInstaller session.
 * Android asks once to allow installs from this app and to confirm the first update; then the app is its own installer of
 * record and on Android 12+ updates go without questions. Test builds read their own channel (android-dev.json).
 */
object SelfUpdate {
    private const val TAG = "CubexUpdate"
    private val CHANNEL = "https://www.cubexcraft.site/cxl/launcher/" + if (BuildConfig.DEBUG) "android-dev.json" else "android-arm64.json"
    private const val DIR = "update"
    const val ACTION_STATUS = "site.cubexcraft.mobile.UPDATE_STATUS"

    class Release(val version: String, val versionCode: Int, val url: String, val sha256: String, val size: Long)

    /** Status line for the UI: null when there is nothing to say. */
    @Volatile var status: String? = null
        private set
    var onStatus: ((String?) -> Unit)? = null
    @Volatile private var running = false

    private fun say(s: String?) {
        status = s
        s?.let { Log.i(TAG, it) }
        onStatus?.invoke(s)
    }

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        thread(name = "cubex-update") {
            try {
                File(app.cacheDir, DIR).listFiles()?.forEach { it.delete() }   // the APK of an update that is installed by now
                val r = latest() ?: return@thread
                if (r.versionCode <= BuildConfig.VERSION_CODE) return@thread
                say("Обновление ${r.version}: скачиваю…")
                val apk = File(File(app.cacheDir, DIR).apply { mkdirs() }, "CubeX-${r.versionCode}.apk")
                Sync.fetchAll(Api.http, listOf(Download(r.url, apk, r.size, "")))
                val got = sha256(apk)
                if (got != r.sha256.lowercase()) {
                    apk.delete()
                    error("SHA-256 не совпал")
                }
                say("Обновление ${r.version}: устанавливаю…")
                commit(app, apk)
            } catch (t: Throwable) {
                Log.w(TAG, "update failed", t)
                say("Обновление не удалось: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                running = false
            }
        }
    }

    private fun latest(): Release? {
        val request = Request.Builder().url(CHANNEL).header("Cache-Control", "no-cache").build()
        return runCatching {
            Api.http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                val j = JSONObject(r.body!!.string())
                Release(j.getString("version"), j.getInt("versionCode"), j.getString("url"), j.getString("sha256"), j.getLong("size"))
            }
        }.onFailure { Log.w(TAG, "channel unavailable: ${it.message}") }.getOrNull()
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // first update: Android asks to allow installs from this app and to confirm
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> say("Обновление установлено")
            PackageInstaller.STATUS_FAILURE_ABORTED -> say(null)
            else -> say("Обновление не установлено: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"}")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Result of the install session (see [SelfUpdate.commit]). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SelfUpdate.ACTION_STATUS) SelfUpdate.onResult(context, intent)
    }
}
