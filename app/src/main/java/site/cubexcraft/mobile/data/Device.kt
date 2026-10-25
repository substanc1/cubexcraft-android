// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import site.cubexcraft.mobile.game.Launch

/**
 * Screen 01: will the game run on this phone. Needs 64-bit Android, 3 GB RAM, OpenGL ES 3.2 (MobileGlues translates desktop
 * OpenGL to it) and 1 GB free; under 5 GB RAM it runs, but on «Экономия».
 */
object Device {
    enum class Verdict { OK, LIMITED, NO }

    class Report(val ramMb: Int, val gles: Int, val arm64: Boolean, val freeBytes: Long) {
        val ramOk get() = ramMb >= 2800
        val glesOk get() = gles >= 0x30002
        val freeOk get() = freeBytes >= 1L shl 30
        val ramComfort get() = ramMb >= 5000
        val verdict: Verdict
            get() = when {
                !arm64 || !ramOk || !glesOk || !freeOk -> Verdict.NO
                !ramComfort -> Verdict.LIMITED
                else -> Verdict.OK
            }
        val glesName get() = "OpenGL ES ${gles shr 16}.${gles and 0xFFFF}"
    }

    fun check(ctx: Context): Report {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return Report(
            ramMb = Launch.totalRamMb(ctx),
            gles = am.deviceConfigurationInfo.reqGlEsVersion,
            arm64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
            freeBytes = freeBytes(ctx),
        )
    }

    /** Free space where the game is kept (the app's folder on the shared storage). */
    fun freeBytes(ctx: Context): Long = runCatching { StatFs((ctx.getExternalFilesDir(null) ?: ctx.filesDir).path).availableBytes }.getOrDefault(0L)

    fun availRamMb(ctx: Context): Int {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return (info.availMem / (1024 * 1024)).toInt()
    }
}

/** «6 ГБ», «8,4 ГБ», «340 МБ» — as in the design (comma, no trailing zero). */
fun sizeText(bytes: Long, ru: Boolean = site.cubexcraft.mobile.ui.L.ru): String {
    val gb = bytes / 1073741824.0
    val mbUnit = if (ru) "МБ" else "MB"
    val gbUnit = if (ru) "ГБ" else "GB"
    return if (gb >= 1) {
        val s = if (gb >= 10 || gb == Math.floor(gb)) "%.0f".format(java.util.Locale.ROOT, gb) else "%.1f".format(java.util.Locale.ROOT, gb)
        (if (ru) s.replace('.', ',') else s) + " " + gbUnit
    } else "${(bytes / 1048576.0).toLong().coerceAtLeast(if (bytes > 0) 1 else 0)} $mbUnit"
}

/** «1 240»: thin groups as in the design. */
fun groups(n: Long): String = "%,d".format(java.util.Locale.ROOT, n).replace(',', ' ')
