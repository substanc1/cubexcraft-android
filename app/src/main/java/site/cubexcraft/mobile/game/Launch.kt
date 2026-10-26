// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.game

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import site.cubexcraft.core.LaunchConfig
import site.cubexcraft.mobile.install.GameLayout
import site.cubexcraft.mobile.install.Installer.Companion.strings
import site.cubexcraft.mobile.install.Runtimes
import site.cubexcraft.mobile.net.Relay
import java.io.File

/** The player of this launch: the game token from /game/session. */
class Session(val username: String, val uuid: String, val accessToken: String)

/**
 * Builds the game command line from the server's mobile profile, like the desktop launcher (launch.rs) does from the same profile:
 * jvmArgs, module path, classpath, main class, client arguments. The differences on a phone: the Android build of LWJGL instead of
 * the desktop jars of the profile, the heap from the phone's RAM, and the window size from the screen.
 */
object Launch {
    private const val SEP = ":"

    fun totalRamMb(ctx: Context): Int {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt()
    }

    /**
     * A quarter of the RAM, 1.75..4 GB, and not above the profile's mobile.xmx (CubeX Mobile 2.7.x, GC log 25.10): in the world
     * ~1.1 GB is live; a bigger heap only fills with garbage first and sits in swap, young pauses grew to 180-330 ms.
     */
    fun heapMb(ctx: Context, profile: JSONObject): Int {
        val byRam = (totalRamMb(ctx) * 0.25).toInt().coerceIn(1792, 4096)
        val cap = profile.optJSONObject("mobile")?.optString("xmx")?.toIntOrNull()
        return if (cap != null && cap > 0) minOf(byRam, cap) else byRam
    }

    fun config(
        ctx: Context, profile: JSONObject, layout: GameLayout, runtime: Runtimes.Paths, session: Session,
        windowWidth: Int, windowHeight: Int, joinServer: Boolean, server: String? = null,
    ): LaunchConfig {
        val c = LaunchConfig()
        val version = profile.optString("version", "1.21.1")
        val libDir = layout.libraries.absolutePath
        fun subst(s: String) = s.replace("\${version_name}", version).replace("\${library_directory}", libDir)
            .replace("\${classpath_separator}", SEP)
        fun resolve(p: String): String = when {
            p.startsWith("@cxl/libs/") -> File(layout.cxl, p.removePrefix("@cxl/libs/")).absolutePath
            p.startsWith("@cxl/") -> File(layout.cxl, p.substringAfterLast('/')).absolutePath
            p.startsWith("libraries/") -> File(layout.libraries, p.removePrefix("libraries/")).absolutePath
            else -> File(layout.client, p).absolutePath
        }

        c.javaHome = runtime.javaHome
        c.nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        c.lwjglNativesDir = runtime.lwjglNatives
        c.gameDir = layout.client
        c.homeDir = layout.client
        c.cacheDir = ctx.cacheDir
        c.logFile = File(layout.client, "logs/launcher-native.log")
        c.mobileGluesDir = File(ctx.filesDir, "MobileGlues").apply { mkdirs() }
        writeMobileGluesConfig(c.mobileGluesDir)
        c.windowWidth = windowWidth
        c.windowHeight = windowHeight
        c.heapMb = heapMb(ctx, profile)

        for (a in strings(profile.optJSONArray("jvmArgs"))) {
            if (a.startsWith("-Xmx") || a.startsWith("-Xms")) continue   // the heap is ours (heapMb)
            c.jvmArgs += if (a.startsWith("-DlibraryDirectory=")) "-DlibraryDirectory=$libDir" else subst(a)
        }
        val mc = profile.optJSONObject("moduleConf")
        val modulePath = strings(mc?.optJSONArray("modulePath"))
        if (modulePath.isNotEmpty()) {
            c.jvmArgs += listOf("-p", modulePath.joinToString(SEP) { resolve(it) })
            c.jvmArgs += listOf("--add-modules", strings(mc?.optJSONArray("modules")).ifEmpty { listOf("ALL-MODULE-PATH") }.joinToString(","))
        }
        for ((flag, key) in listOf("--add-exports" to "exports", "--add-opens" to "opens", "--add-reads" to "reads")) {
            val m = mc?.optJSONObject(key) ?: continue
            val keys = m.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                c.jvmArgs += listOf(flag, "$k=${m.getString(k)}")
            }
        }
        // short GC pauses (CubeXCraft for iPhone, 24.10: without them chunk loading stuttered); pauses go to the log
        c.jvmArgs += listOf(
            "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=37", "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=20", "-XX:G1HeapRegionSize=8M", "-XX:+ParallelRefProcEnabled", "-XX:+DisableExplicitGC",
            "-Xlog:gc:file=${File(layout.client, "logs/gc.log").absolutePath}:time,uptime:filecount=2,filesize=4M",
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            // our in-game helper (cubexcraft_mobileperf) works only on the phone
            "-Dcubex.mobile=true",
            "-Djava.library.path=${runtime.lwjglNatives.absolutePath}$SEP${ctx.applicationInfo.nativeLibraryDir}",
            "-Dorg.lwjgl.openal.libname=${ctx.applicationInfo.nativeLibraryDir}/libopenal.so",
        )
        c.jvmArgs += Relay.javaArgs()

        // classpath: the Android LWJGL first, then the profile's (no LWJGL, no module-path jars, no duplicates: a jar twice
        // breaks BootstrapLauncher with «Duplicate key»)
        val cp = LinkedHashSet<String>()
        runtime.lwjglJars.forEach { cp += it.absolutePath }
        (strings(profile.optJSONArray("classPath")) + strings(profile.optJSONArray("altClassPath")))
            .filter { it !in modulePath && !it.startsWith("libraries/org/lwjgl/") }
            .forEach { cp += resolve(it) }
        c.programArgs += listOf("-cp", cp.joinToString(SEP))
        c.programArgs += profile.optString("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher")

        c.programArgs += listOf(
            "--username", session.username,
            "--uuid", session.uuid.replace("-", ""),
            "--accessToken", session.accessToken,
            "--userType", "mojang",
            "--userProperties", "{}",
            "--assetIndex", profile.optString("assetIndex", version),
            "--version", version,
            "--gameDir", layout.client.absolutePath,
            "--assetsDir", layout.assets.absolutePath,
            "--versionType", "CubeXCraft",
        )
        // the window size is the screen's (minus render scale), not the 1708x960 of the desktop profile
        val client = strings(profile.optJSONArray("clientArgs"))
        var i = 0
        while (i < client.size) {
            if (client[i] == "--width" || client[i] == "--height") { i += 2; continue }
            c.programArgs += client[i]
            i++
        }
        c.programArgs += listOf("--width", windowWidth.toString(), "--height", windowHeight.toString())
        // the server the player chose on the main screen; without one — the profile's first
        if (server != null) {
            c.programArgs += listOf("--quickPlayMultiplayer", server)
        } else if (joinServer) {
            val s = profile.optJSONArray("servers")?.optJSONObject(0)
            if (s != null) c.programArgs += listOf("--quickPlayMultiplayer", "${s.optString("serverAddress")}:${s.optInt("serverPort", 25565)}")
        }
        return c
    }

    /**
     * MobileGlues' config.json (read because MG_DIR_PATH points to our folder): the cache of translated shaders on — without it
     * every start translates hundreds of GLSL shaders to GLSL ES again on the phone's CPU (CubeXCraft for iPhone has 30 MB too).
     * The other keys are written as the values the game ran with so far (MobileGlues' defaults without a config).
     */
    private fun writeMobileGluesConfig(dir: File) {
        val json = """{"enableANGLE":0,"enableNoError":0,"enableExtComputeShader":0,"enableExtTimerQuery":0,""" +
            """"enableExtDirectStateAccess":0,"maxGlslCacheSize":30,"angleDepthClearFixMode":0,"customGLVersion":0,""" +
            """"fsr1Setting":0,"hideMGEnvLevel":0}"""
        val f = File(dir, "config.json")
        runCatching { if (!f.isFile || f.readText() != json) f.writeText(json) }
    }

    /** For the log: the command without the token. */
    fun redacted(c: LaunchConfig): String {
        val all = c.jvmArgs + c.programArgs
        val out = ArrayList<String>(all.size)
        var hide = false
        for (a in all) {
            out += if (hide) "***" else a
            hide = a == "--accessToken"
        }
        return out.joinToString(" ")
    }
}
