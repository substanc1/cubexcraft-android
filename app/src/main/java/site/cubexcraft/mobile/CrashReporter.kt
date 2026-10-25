// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crashes that kill a process before it can send its log: a Java crash is written to a file synchronously by the default
 * handler; why a process ended (Java or native crash, low memory, killed) comes from Android itself (ApplicationExitInfo, 11+).
 * The next start sends both (MainActivity → LogUpload, kind "appcrash").
 */
object CrashReporter {
    private const val TAG = "CubexCrash"
    private const val PREFS = "crash"

    private fun dir(ctx: Context) = File(ctx.filesDir, "crashes").apply { mkdirs() }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                File(dir(app), "crash-$stamp-${processName()}.txt")
                    .writeText("process ${processName()} thread ${thread.name}\n${Log.getStackTraceString(e)}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.app.Application.getProcessName().substringAfterLast(':', "main") else "app"

    /** A report and what to do once it reached the server (forget what it contains). */
    class Report(val text: String, val done: () -> Unit)

    /**
     * Everything not reported yet, or null. Blocking (reads files and the exit history); call off the main thread. Nothing is
     * forgotten until [Report.done]: a report the server did not take is sent again on the next start.
     */
    fun pending(ctx: Context): Report? {
        val sb = StringBuilder()
        val files = dir(ctx).listFiles()?.sortedBy { it.name }.orEmpty()
        files.forEach { f ->
            sb.append("--- ").append(f.name).append('\n').append(runCatching { f.readText() }.getOrDefault("")).append('\n')
        }
        var seen: () -> Unit = {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val since = prefs.getLong("exitsSeen", 0L)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10) }.getOrDefault(emptyList())
            var newest = since
            for (x in exits.sortedBy { it.timestamp }) {
                if (x.timestamp <= since) continue
                newest = maxOf(newest, x.timestamp)
                // a normal end of the game process (exit code 0 / killed by us after the JVM returned) is not news
                if (x.reason == ApplicationExitInfo.REASON_EXIT_SELF && x.status == 0) continue
                sb.append("--- exit ").append(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(x.timestamp)))
                    .append(" process=").append(x.processName).append(" reason=").append(reasonName(x.reason))
                    .append(" status=").append(x.status).append(" importance=").append(x.importance)
                    .append(" pss=").append(x.pss / 1024).append("MB rss=").append(x.rss / 1024).append("MB\n")
                x.description?.let { sb.append(it).append('\n') }
                if (x.reason == ApplicationExitInfo.REASON_ANR || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    // ANR: text traces; native crash on Android 12+: a tombstone (protobuf) — the readable strings are enough
                    runCatching {
                        x.traceInputStream?.use { s ->
                            val bytes = s.readBytes()
                            val text = String(bytes, Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7E\\n]+"), " ")
                            sb.append(text.take(60_000)).append('\n')
                        }
                    }
                }
            }
            seen = { prefs.edit().putLong("exitsSeen", newest).apply() }
        }
        val text = sb.toString().takeIf { it.isNotBlank() } ?: run { seen(); return null }
        return Report(text) {
            files.forEach { it.delete() }
            seen()
        }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason$r"
    }
}
