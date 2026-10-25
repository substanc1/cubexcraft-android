// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import site.cubexcraft.mobile.BuildConfig
import site.cubexcraft.mobile.install.Installer
import site.cubexcraft.mobile.net.LogUpload
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything about the last run in one text file: our error, our and the bridge's logcat lines (both processes share the uid),
 * what the JVM printed, the game log, the launch command, a JVM crash report and Minecraft's crash report. Kept in logs/
 * (last 10), sent to the developers and shared as a file — not as pasted text.
 */
object RunLog {
    @Volatile var lastFile: File? = null
        private set
    @Volatile var lastText = ""
        private set

    fun collect(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("CubeX Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE}\n")
        runCatching { sb.append(File(ctx.filesDir, "last-launch-error.txt").takeIf { it.isFile }?.readText().orEmpty()) }
        sb.append("--- logcat\n").append(LogUpload.logcat())
        Installer.installed(ctx)?.second?.let { layout ->
            for ((name, n) in listOf("logs/launch-command.txt" to 5, "logs/launcher-native.log" to 400, "logs/latest.log" to 400)) {
                sb.append(LogUpload.tail(File(layout.client, name), n))
            }
            LogUpload.recent(layout.client) { it.startsWith("hs_err_pid") }?.let { sb.append(LogUpload.head(it, 300)) }
            LogUpload.recent(File(layout.client, "crash-reports")) { it.startsWith("crash-") }?.let { sb.append(LogUpload.head(it, 300)) }
        }
        val text = sb.toString()
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val f = File(dir, "cubex-run-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt")
            f.writeText(text)
            dir.listFiles { x -> x.name.startsWith("cubex-run-") }?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
            lastFile = f
        }
        lastText = text
        return text
    }

    /** The log as a .txt file (Telegram, mail…). */
    fun share(a: Activity) {
        val f = lastFile ?: return
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
        a.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "CubeX Mobile",
        ))
    }

    /** What the end of the last run says about the cause, for the error window: a short line or null. */
    fun cause(text: String): String? {
        val lines = text.lines()
        lines.firstOrNull { "OutOfMemoryError" in it }?.let { return "OutOfMemoryError" }
        lines.firstOrNull { it.startsWith("# Problematic frame:") }?.let { i -> lines.getOrNull(lines.indexOf(i) + 1)?.trim()?.removePrefix("# ")?.take(80)?.let { return it } }
        lines.lastOrNull { it.startsWith("Caused by:") || it.startsWith("Exception in thread") }?.let { return it.take(90) }
        return null
    }

    fun outOfMemory(text: String) = "OutOfMemoryError" in text || "LOW_MEMORY" in text || "lowmemorykiller" in text
}
