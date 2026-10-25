// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.net

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Logs of the test builds to the CubeXCraft server (POST /api/launcher/v1/devlog, launcher token), so the developers read a failed
 * start or a stuck game without the player copying anything. Sent gzipped (nginx takes 1 MB), at most 3 MB of text.
 */
object LogUpload {
    private const val TAG = "CubexLogUpload"
    private const val URL = "https://www.cubexcraft.site/api/launcher/v1/devlog"
    private const val MAX_CHARS = 3_000_000

    fun token(ctx: Context): String? = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("token", null)

    /** Blocking; call off the main thread. [kind]: run, live, install, error. */
    fun send(ctx: Context, kind: String, text: String): Boolean {
        val token = token(ctx) ?: return false
        val body = text.takeLast(MAX_CHARS)
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val request = Request.Builder().url("$URL?kind=$kind")
            .header("Authorization", "Bearer $token")
            .header("Content-Encoding", "gzip")
            .header("X-Launcher-Version", Api.appVersion)
            .header("X-Device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE)
            .post(gz.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            Api.http.newCall(request).execute().use { r ->
                Log.i(TAG, "$kind: HTTP ${r.code}, ${body.length} chars, ${gz.size} bytes")
                r.isSuccessful
            }
        }.getOrElse {
            Log.w(TAG, "$kind not sent: $it")
            false
        }
    }

    /** Our and the bridge's logcat tags only (no system noise); native crashes show up as DEBUG/libc. */
    fun logcat(lines: Int = 2000): String {
        val tags = listOf(
            "CubexGame", "CubexCore", "CubexHost", "CubexInstall", "CubexRuntime", "CubexTweaks", "CubexRelay", "CubexDev",
            "CubexUpdate", "CubexLogUpload", "jrelog", "LIBGL", "NativeInput", "DynamicLoader", "EGLBridge", "MobileGlues", "ExitTrap",
        ).map { "$it:V" }
        val cmd = arrayOf("logcat", "-d", "-v", "time", "-t", lines.toString()) + tags + arrayOf("AndroidRuntime:E", "DEBUG:V", "libc:F", "*:S")
        return runCatching {
            Runtime.getRuntime().exec(cmd).inputStream.bufferedReader().readText()
        }.getOrElse { "logcat: $it\n" }
    }

    /** Last [n] lines of a text file, or "" when it does not exist. */
    fun tail(f: File, n: Int): String = runCatching {
        if (!f.isFile) "" else "--- ${f.name}\n" + f.readLines().takeLast(n).joinToString("\n") + "\n"
    }.getOrDefault("")
}
