// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import site.cubexcraft.mobile.game.Launch
import java.io.File
import java.util.Locale

/** The app's language: Russian or English, by the phone until the player picks one in the settings. */
object L {
    @Volatile var ru = Locale.getDefault().language == "ru"
    fun t(ru: String, en: String) = if (this.ru) ru else en
}

/**
 * What the player can choose (screen 08): quality, shaders, language, «Сразу на сервер», and which server. Everything else is
 * ours and closed. Applied to the game on every start; options the player changed in the game stay until the quality changes.
 */
object Settings {
    enum class Quality { AUTO, MAX, BALANCE, ECO }
    enum class Shaders { AUTO, ON, OFF }

    private const val TAG = "CubexSettings"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.getString("lang", null)?.let { L.ru = it == "ru" }
    }

    var quality: Quality
        get() = runCatching { Quality.valueOf(prefs.getString("quality", null)!!) }.getOrDefault(Quality.AUTO)
        set(v) = prefs.edit().putString("quality", v.name).apply()
    var shaders: Shaders
        get() = runCatching { Shaders.valueOf(prefs.getString("shaders", null)!!) }.getOrDefault(Shaders.AUTO)
        set(v) = prefs.edit().putString("shaders", v.name).apply()
    var lang: String
        get() = if (L.ru) "ru" else "en"
        set(v) { L.ru = v == "ru"; prefs.edit().putString("lang", v).apply() }
    var joinServer: Boolean
        get() = prefs.getBoolean("join", true)
        set(v) = prefs.edit().putBoolean("join", v).apply()
    var server: String?
        get() = prefs.getString("server", null)
        set(v) = prefs.edit().putString("server", v).apply()
    var deviceChecked: Boolean
        get() = prefs.getBoolean("deviceChecked", false)
        set(v) = prefs.edit().putBoolean("deviceChecked", v).apply()

    /** «Авто» by the phone's class: memory, the fastest CPU core and the GPU (DeviceTier). */
    fun effective(ctx: Context): Quality = when (val q = quality) {
        Quality.AUTO -> when (site.cubexcraft.mobile.data.DeviceTier.of(ctx)) {
            site.cubexcraft.mobile.data.DeviceTier.LOW -> Quality.ECO
            site.cubexcraft.mobile.data.DeviceTier.MID -> Quality.BALANCE
            site.cubexcraft.mobile.data.DeviceTier.HIGH -> Quality.MAX
        }
        else -> q
    }

    /**
     * The game's options of each quality, and the bounds the in-game helper (cubexcraft_mobileperf) moves the fog (minRd: the
     * closest it comes, in chunks) and the entity distance in to keep ~60 FPS. Aimed at 60 FPS on a mid phone (Galaxy A35) with
     * as little loss as possible: the distance gives way only where the world is heavy; particles «decreased», fast clouds and
     * no entity shadows are hardly seen on a phone screen and cost the CPU every frame. maxRd is the render distance itself.
     */
    private class Profile(val options: Map<String, String>, val minRd: Int, val maxRd: Int, val minEnt: Double, val maxEnt: Double)

    private fun profile(q: Quality) = when (q) {
        Quality.MAX -> Profile(mapOf(
            "renderDistance" to "12", "simulationDistance" to "8", "entityDistanceScaling" to "1.0", "graphicsMode" to "1",
            "particles" to "0", "renderClouds" to "\"fast\"", "biomeBlendRadius" to "2", "mipmapLevels" to "4", "entityShadows" to "true",
        ), minRd = 7, maxRd = 12, minEnt = 0.75, maxEnt = 1.0)
        Quality.ECO -> Profile(mapOf(
            "renderDistance" to "6", "simulationDistance" to "5", "entityDistanceScaling" to "0.5", "graphicsMode" to "0",
            "particles" to "2", "renderClouds" to "\"false\"", "biomeBlendRadius" to "0", "mipmapLevels" to "2", "entityShadows" to "false",
        ), minRd = 4, maxRd = 6, minEnt = 0.5, maxEnt = 0.5)
        else -> Profile(mapOf(
            "renderDistance" to "8", "simulationDistance" to "5", "entityDistanceScaling" to "0.75", "graphicsMode" to "1",
            "particles" to "1", "renderClouds" to "\"fast\"", "biomeBlendRadius" to "1", "mipmapLevels" to "4", "entityShadows" to "false",
        ), minRd = 5, maxRd = 8, minEnt = 0.5, maxEnt = 0.75)
    }

    /** Bumped when the profiles change: the new values reach games set up with the old ones (once). */
    private const val PROFILE_REVISION = 3

    /** The screen's fastest refresh rate at its resolution (the game asks for it): the frame rate can never be above it. */
    private fun maxRefresh(ctx: Context): Int = runCatching {
        val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val cur = d.mode
        d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxOf { it.refreshRate }.toInt()
    }.getOrDefault(60)

    /**
     * The app's own client mods (assets/mods, built from mod/ in this repository) into the game's mods folder: copied when
     * missing or different, older versions of them removed. The installer's clean-up may remove them; this runs before every start.
     */
    private fun installMod(ctx: Context, client: File) {
        val names = ctx.assets.list("mods").orEmpty().filter { it.endsWith(".jar") }
        val mods = File(client, "mods").apply { mkdirs() }
        for (name in names) {
            val dest = File(mods, name)
            val size = ctx.assets.openFd("mods/$name").use { it.length }
            if (dest.isFile && dest.length() == size) continue
            ctx.assets.open("mods/$name").use { i -> File(mods, "$name.part").outputStream().use { o -> i.copyTo(o) } }
            File(mods, "$name.part").renameTo(dest)
            Log.i(TAG, "mod $name installed")
        }
        // an older jar of the same mod (cubexcraft_mobileperf-1.0.0.jar when 1.0.1 ships)
        val ids = names.map { it.substringBeforeLast('-') }.toSet()
        mods.listFiles()?.filter { f -> f.name.endsWith(".jar") && f.name !in names && f.name.substringBeforeLast('-') in ids }?.forEach { it.delete() }
    }

    /** Shaders by the phone: off until the device profiles (SCRUM-390) say which phones carry them. */
    fun shadersOn(): Boolean = shaders == Shaders.ON

    fun qualityName(q: Quality) = when (q) {
        Quality.AUTO -> L.t("Авто", "Auto")
        Quality.MAX -> L.t("Максимум", "Maximum")
        Quality.BALANCE -> L.t("Баланс", "Balanced")
        Quality.ECO -> L.t("Экономия", "Economy")
    }

    /** Render scale of the game picture: fewer pixels on weaker settings, the compositor scales them up for free. */
    fun renderScale(ctx: Context) = when (effective(ctx)) {
        Quality.MAX -> 0.9f
        Quality.ECO -> 0.6f
        else -> 0.75f
    }

    /**
     * Writes the chosen quality and shaders into the game's config before a start. Distances only when the quality changed
     * since the last start, so what the player set in the game's own options is kept.
     */
    fun applyToGame(ctx: Context, client: File) {
        val q = effective(ctx)
        val p = profile(q)
        val options = File(client, "options.txt")
        val applied = "${q.name}@$PROFILE_REVISION"
        if (prefs.getString("appliedQuality", null) != applied && options.isFile) {
            runCatching {
                val lines = options.readLines().toMutableList()
                // no frame cap and no vsync: the helper keeps 60 by what it draws, a cap would hide what the phone can do
                for ((k, v) in p.options + mapOf("maxFps" to "260", "enableVsync" to "false")) {
                    val i = lines.indexOfFirst { it.startsWith("$k:") }
                    if (i >= 0) lines[i] = "$k:$v" else lines += "$k:$v"
                }
                options.writeText(lines.joinToString("\n", postfix = "\n"))
                prefs.edit().putString("appliedQuality", applied).apply()
                Log.i(TAG, "quality $applied written to options.txt")
            }.onFailure { Log.w(TAG, "options.txt: $it") }
        }
        // the in-game helper and its bounds (every start: the bounds follow the quality)
        runCatching {
            val cfg = File(client, "config/cubexcraft-mobileperf.properties")
            cfg.parentFile?.mkdirs()
            cfg.writeText(
                "# CubeX Mobile (written by the app at every start)\n" +
                    "minRenderDistance=${p.minRd}\n" +
                    "maxEntityDistance=${p.maxEnt}\nminEntityDistance=${p.minEnt}\nlowFps=60\nhighFps=76\n" +
                    "refreshRate=${maxRefresh(ctx)}\n",
            )
            installMod(ctx, client)
        }.onFailure { Log.w(TAG, "mobileperf: $it") }
        // the game's language follows the app's
        runCatching {
            if (options.isFile) {
                val want = if (L.ru) "ru_ru" else "en_us"
                val lines = options.readLines().toMutableList()
                val i = lines.indexOfFirst { it.startsWith("lang:") }
                if (i < 0 || lines[i] != "lang:$want") {
                    if (i >= 0) lines[i] = "lang:$want" else lines += "lang:$want"
                    options.writeText(lines.joinToString("\n", postfix = "\n"))
                }
            }
        }
        // Iris: shaders on or off; on — the first pack of the build when none is chosen yet
        runCatching {
            val f = File(client, "config/iris.properties")
            val props = java.util.Properties()
            if (f.isFile) f.inputStream().use { props.load(it) }
            val on = shadersOn()
            props.setProperty("enableShaders", on.toString())
            if (on && props.getProperty("shaderPack").isNullOrEmpty()) {
                File(client, "shaderpacks").list()?.sorted()?.firstOrNull()?.let { props.setProperty("shaderPack", it) }
            }
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "CubeX Mobile") }
        }.onFailure { Log.w(TAG, "iris.properties: $it") }
    }
}
