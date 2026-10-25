// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.install

import android.util.Log
import site.cubexcraft.mobile.net.Manifest
import java.io.File

/** Config fixes for phones, found on CubeX Mobile 2.7.x and CubeXCraft for iPhone. */
object Tweaks {
    private const val TAG = "CubexTweaks"

    /**
     * First-start options for phones (CubeX Mobile 2.7.x): no 30 FPS cap, moderate distances. Written only when options.txt does
     * not exist yet, so the player's later choices are never overwritten. Without "version:" as the first line Minecraft treats
     * the file as ancient and ignores all of it.
     */
    private val PHONE_OPTIONS = listOf(
        "version:3955",
        "renderDistance:8",
        "simulationDistance:5",
        "entityDistanceScaling:0.75",
        "graphicsMode:1",
        "mipmapLevels:4",
        "biomeBlendRadius:1",
        "enableVsync:false",
        "maxFps:260",
        "bobView:false",
        "autoJump:true",
        "attackIndicator:2",
    )

    fun apply(client: File, manifest: Manifest) {
        val options = File(client, "options.txt")
        if (!options.exists() && !manifest.files.containsKey("options.txt")) {
            options.writeText(PHONE_OPTIONS.joinToString("\n", postfix = "\n"))
            Log.i(TAG, "phone options.txt written")
        }
        // ModernFix: its narrator patch looks for com.mojang.text2speech.OperatingSystem, which the Amethyst text2speech stub
        // lacks → MixinTransformerError when the window is created (iPhone 16 Pro, 24.10)
        setProperty(File(client, "config/modernfix-mixins.properties"), "mixin.feature.suppress_narrator_stacktrace", "false")
        disableGtBloom(File(client, "config/gtceu.yaml"))
    }

    private fun setProperty(file: File, key: String, value: String) {
        val want = "$key=$value"
        val lines = if (file.isFile) file.readLines().filter { it.isNotEmpty() }.toMutableList() else mutableListOf()
        val i = lines.indexOfFirst { it.startsWith("$key=") }
        if (i >= 0 && lines[i] == want) return
        if (i >= 0) lines[i] = want else lines += want
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        Log.i(TAG, "${file.name}: $want")
    }

    /**
     * GregTech bloom off: without Sodium's path GTCEu 8.0.0 crashed with «Not building!» on glowing blocks (iPhone, 24.10), and
     * bloom is extra GPU work on a phone anyway. With no file yet only this key is written; the mod adds its defaults.
     */
    private fun disableGtBloom(file: File) {
        val text = if (file.isFile) file.readText() else ""
        if (text.isEmpty()) {
            file.parentFile?.mkdirs()
            file.writeText("client:\n  bloom:\n    type: DISABLED\n")
            return
        }
        val lines = text.split("\n").toMutableList()
        var section: String? = null
        var sub: String? = null
        for (i in lines.indices) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (!line.startsWith(" ")) {
                section = line
                sub = null
            } else if (!line.startsWith("   ")) {
                sub = line
            } else if (section == "client:" && sub == "  bloom:" && line.startsWith("    type:")) {
                if (t == "type: DISABLED") return
                lines[i] = "    type: DISABLED"
                file.writeText(lines.joinToString("\n"))
                Log.i(TAG, "gtceu.yaml: client.bloom.type: DISABLED")
                return
            }
        }
        Log.w(TAG, "gtceu.yaml: client.bloom.type not found")
    }
}
