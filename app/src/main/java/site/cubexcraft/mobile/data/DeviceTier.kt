// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.data

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import site.cubexcraft.mobile.game.Launch
import java.io.File

/**
 * How strong the phone is, for «Авто» quality (from CubeX Mobile 2.7.x, our code). RAM alone misleads — Galaxy A16 and Redmi
 * Note 9 Pro both have ~5.6 GB and ran at 55 and 8–19 FPS — so the fastest CPU core and the GPU name count too.
 * Measured 2.7.x: Snapdragon 720G (big cores 2.3 GHz) gave 7–10 FPS at the spawn on 8 chunks; Galaxy A35 (Exynos 1380, 2.4 GHz,
 * Mali-G68) 30–40 — so the CPU line is 2.2 GHz (2.7.x had 2.6 and put the A35 below where it belongs).
 */
enum class DeviceTier { LOW, MID, HIGH;
    companion object {
        private const val TAG = "CubexTier"
        private const val LOW_RAM_MB = 5500
        private const val LOW_CPU_KHZ = 2_200_000L
        private const val HIGH_RAM_MB = 11_000
        private const val HIGH_CPU_KHZ = 3_000_000L

        @Volatile private var cached: DeviceTier? = null

        fun of(ctx: Context): DeviceTier = cached ?: run {
            val ram = Launch.totalRamMb(ctx)
            val cpu = maxCpuKhz()
            val gpu = Gpu.renderer(ctx)
            val weakGpu = Gpu.isWeak(gpu)
            val t = when {
                ram < LOW_RAM_MB || (cpu in 1 until LOW_CPU_KHZ) || weakGpu -> LOW
                ram >= HIGH_RAM_MB && cpu >= HIGH_CPU_KHZ -> HIGH
                else -> MID
            }
            val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
            Log.i(TAG, "tier=$t model=${Build.MODEL} soc=$soc ram=$ram MB maxCpu=$cpu kHz gpu=$gpu weakGpu=$weakGpu")
            t.also { cached = it }
        }

        /** Fastest CPU core in kHz (0 if unknown). */
        fun maxCpuKhz(): Long = generateSequence(0) { it + 1 }
            .map { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
            .takeWhile { it.exists() }
            .maxOfOrNull { f -> runCatching { f.readText().trim().toLong() }.getOrDefault(0L) } ?: 0L
    }
}

/** The GPU's name (GL_RENDERER), read once through a 1×1 EGL pbuffer and kept. */
object Gpu {
    private const val TAG = "CubexGpu"

    fun renderer(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)
        prefs.getString("renderer", null)?.let { return it }
        val name = runCatching { query() }.onFailure { Log.w(TAG, "GL_RENDERER: $it") }.getOrNull().orEmpty()
        if (name.isNotEmpty()) prefs.edit().putString("renderer", name).apply()
        return name
    }

    /** Weak by name: Mali-G5x and older (T-series), Adreno below 620. An unknown GPU is not treated as weak. */
    fun isWeak(renderer: String): Boolean {
        val r = renderer.lowercase()
        Regex("""adreno.*?(\d{3})""").find(r)?.let { return it.groupValues[1].toInt() < 620 }
        Regex("""mali-(g\d+|t\d+)""").find(r)?.let { m ->
            val g = m.groupValues[1]
            return g.startsWith("t") || g.drop(1).toInt() < 68
        }
        return false
    }

    private fun query(): String {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(d, v, 0, v, 1)) return ""
        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        try {
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(d, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return ""
            ctx = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreatePbufferSurface(d, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) return ""
            return GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        } finally {
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, ctx)
            EGL14.eglTerminate(d)
        }
    }
}
