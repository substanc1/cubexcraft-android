// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.install

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The build card of the main screen (03): size and mods of the installed build, written after each install from the real
 * manifest. The big mods are named from their file names (a known list); the rest is «ещё +N».
 */
object BuildInfo {
    class Info(val totalBytes: Long, val mods: List<String>, val version: String)

    private fun file(ctx: Context) = File(ctx.filesDir, "build-info.json")

    fun save(ctx: Context, plan: Installer.Plan) {
        val j = JSONObject()
            .put("totalBytes", plan.totalBytes)
            .put("mods", JSONArray(plan.mods))
            .put("version", plan.profile.optString("version"))
        runCatching { file(ctx).writeText(j.toString()) }
    }

    fun load(ctx: Context): Info? = runCatching {
        val j = JSONObject(file(ctx).readText())
        val a = j.optJSONArray("mods") ?: JSONArray()
        Info(j.optLong("totalBytes"), (0 until a.length()).map { a.getString(it) }, j.optString("version"))
    }.getOrNull()

    private val KNOWN = listOf(
        "gtceu-" to "GregTech", "appliedenergistics2-" to "Applied Energistics 2", "create-" to "Create", "mekanism-" to "Mekanism",
        "ExtendedAE-" to "ExtendedAE", "ftb-quests-" to "FTB Quests", "functionalstorage-" to "Functional Storage",
        "jei-" to "JEI", "emi-" to "EMI", "iris-" to "Iris", "sodium-" to "Sodium", "curios-" to "Curios",
    )

    /** Up to [n] known big mods present in the build, in the order of the list above. */
    fun highlights(mods: List<String>, n: Int = 5): List<String> =
        KNOWN.filter { (prefix, _) -> mods.any { it.startsWith(prefix, ignoreCase = true) } }.map { it.second }.take(n)
}
