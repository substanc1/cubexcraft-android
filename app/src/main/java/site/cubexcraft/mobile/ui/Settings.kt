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

    /** «Авто» by the phone's memory: under 3.5 GB economy, under 10 GB balance, more — maximum. */
    fun effective(ctx: Context): Quality = when (val q = quality) {
        Quality.AUTO -> {
            val ram = Launch.totalRamMb(ctx)
            when {
                ram < 3584 -> Quality.ECO
                ram < 10240 -> Quality.BALANCE
                else -> Quality.MAX
            }
        }
        else -> q
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
        val options = File(client, "options.txt")
        if (prefs.getString("appliedQuality", null) != q.name && options.isFile) {
            val values = when (q) {
                Quality.MAX -> mapOf("renderDistance" to "12", "simulationDistance" to "8", "graphicsMode" to "1", "entityDistanceScaling" to "1.0")
                Quality.ECO -> mapOf("renderDistance" to "5", "simulationDistance" to "5", "graphicsMode" to "0", "entityDistanceScaling" to "0.5")
                else -> mapOf("renderDistance" to "8", "simulationDistance" to "5", "graphicsMode" to "1", "entityDistanceScaling" to "0.75")
            }
            runCatching {
                val lines = options.readLines().toMutableList()
                for ((k, v) in values) {
                    val i = lines.indexOfFirst { it.startsWith("$k:") }
                    if (i >= 0) lines[i] = "$k:$v" else lines += "$k:$v"
                }
                options.writeText(lines.joinToString("\n", postfix = "\n"))
                prefs.edit().putString("appliedQuality", q.name).apply()
                Log.i(TAG, "quality ${q.name} written to options.txt")
            }.onFailure { Log.w(TAG, "options.txt: $it") }
        }
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
