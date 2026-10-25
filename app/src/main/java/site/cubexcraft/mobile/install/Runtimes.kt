// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.install

import android.content.Context
import android.system.Os
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * What the game needs next to its own files: the Java 21 runtime and the Android build of LWJGL. Both ship in the APK and are
 * unpacked into the app's private storage once per version of the APK (a marker file holds the version).
 */
object Runtimes {
    private const val TAG = "CubexRuntime"
    private const val JRE = "runtime/jre-21"
    private const val LWJGL_JARS = "runtime/lwjgl3"
    private const val LWJGL_NATIVES = "components/lwjgl-3.3.3-natives/arm64-v8a"

    class Paths(val javaHome: File, val lwjglJars: List<File>, val lwjglNatives: File)

    /** Unpacks what is missing or outdated; a few seconds on the first start, instant afterwards. Call off the main thread. */
    fun ensure(ctx: Context): Paths {
        val root = File(ctx.filesDir, "runtime")
        val javaHome = File(root, "jre-21")
        // by content, not by app version: an update of the app code must not unpack the same Java again (10-20 s)
        val stamp = "${assetText(ctx, "$JRE/version")}/" + listOf("$JRE/universal.tar.xz", "$JRE/bin-arm64.tar.xz", "$LWJGL_JARS/version")
            .joinToString("/") { runCatching { ctx.assets.openFd(it).use { fd -> fd.length } }.getOrDefault(-1L).toString() } +
            "/" + (ctx.assets.list(LWJGL_JARS)?.size ?: 0)
        val marker = File(javaHome, ".cubex-version")
        if (!marker.isFile || marker.readText() != stamp) {
            val t = System.currentTimeMillis()
            javaHome.deleteRecursively()
            javaHome.mkdirs()
            untarXz(ctx.assets.open("$JRE/universal.tar.xz"), javaHome)
            untarXz(ctx.assets.open("$JRE/bin-arm64.tar.xz"), javaHome)
            marker.writeText(stamp)
            Log.i(TAG, "Java 21 unpacked in ${System.currentTimeMillis() - t} ms")
        }

        val jarsDir = File(root, "lwjgl3")
        val nativesDir = File(jarsDir, "natives")
        val lwjglMarker = File(jarsDir, ".cubex-version")
        if (!lwjglMarker.isFile || lwjglMarker.readText() != stamp) {
            jarsDir.deleteRecursively()
            nativesDir.mkdirs()
            for (name in ctx.assets.list(LWJGL_JARS).orEmpty()) {
                if (name.endsWith(".jar")) copyAsset(ctx, "$LWJGL_JARS/$name", File(jarsDir, name))
            }
            for (name in ctx.assets.list(LWJGL_NATIVES).orEmpty()) {
                copyAsset(ctx, "$LWJGL_NATIVES/$name", File(nativesDir, name))
            }
            lwjglMarker.writeText(stamp)
        }
        val jars = jarsDir.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }
        return Paths(javaHome, jars, nativesDir)
    }

    private fun assetText(ctx: Context, path: String) = ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private fun copyAsset(ctx: Context, path: String, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    }

    /** Minimal ustar/GNU tar reader: regular files, directories, symlinks and GNU long names. */
    private fun untarXz(input: InputStream, dest: File) {
        XZInputStream(BufferedInputStream(input, 1 shl 16)).use { s ->
            val header = ByteArray(512)
            var longName: String? = null
            val root = dest.canonicalPath + File.separator
            while (true) {
                if (!readFully(s, header)) break
                if (header.all { it.toInt() == 0 }) break
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                var name = longName ?: run {
                    val prefix = cstr(header, 345, 155)
                    val n = cstr(header, 0, 100)
                    if (prefix.isNotEmpty()) "$prefix/$n" else n
                }
                longName = null
                if (type == 'L') {   // GNU long name: the next entry's name is in this entry's data
                    val buf = ByteArray(size.toInt())
                    readFully(s, buf)
                    skip(s, pad(size))
                    longName = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                name = name.removePrefix("./")
                val out = File(dest, name)
                val canon = out.canonicalPath
                if (name.isEmpty() || !(canon + File.separator).startsWith(root)) {   // the root itself or outside it
                    skip(s, size + pad(size))
                    continue
                }
                when (type) {
                    '5' -> out.mkdirs()
                    '2' -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(cstr(header, 157, 100), out.path)
                    }
                    '0', '\u0000' -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16)
                            var left = size
                            while (left > 0) {
                                val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                o.write(buf, 0, n)
                                left -= n
                            }
                        }
                        if (octal(header, 100, 8) and 0b001_001_001 != 0L) out.setExecutable(true, false)
                        skip(s, pad(size))
                        continue
                    }
                }
                skip(s, size + pad(size))
            }
        }
    }

    private fun pad(size: Long) = (512 - size % 512) % 512

    private fun readFully(s: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = s.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skip(s: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val k = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (k < 0) return
            left -= k
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** Octal number of a tar header field: leading spaces/NULs skipped, ends at the first non-digit. */
    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        var started = false
        for (i in off until off + len) {
            val c = b[i].toInt().toChar()
            if (c in '0'..'7') {
                v = v * 8 + (c - '0')
                started = true
            } else if (started) {
                break
            }
        }
        return v
    }
}
