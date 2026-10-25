// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.install

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.Download
import site.cubexcraft.mobile.net.FileEntry
import site.cubexcraft.mobile.net.HashCache
import site.cubexcraft.mobile.net.Manifest
import site.cubexcraft.mobile.net.Sync
import site.cubexcraft.mobile.net.SyncProgress
import java.io.File

/**
 * Where the game lives on the phone (app-specific external storage, no permission needed):
 *   game/<filesDir>    the client directory of the mobile profile (mods, config, options.txt…)
 *   game/libraries     the profile's libraries and the patched Minecraft/NeoForge jars FML finds by -DlibraryDirectory
 *   game/cxl           the launcher's own jars (@cxl/libs, e.g. vanilla authlib)
 *   game/assets        Minecraft assets
 */
class GameLayout(ctx: Context, filesDir: String) {
    val root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "game")
    val client = File(root, filesDir)
    val libraries = File(root, "libraries")
    val cxl = File(root, "cxl")
    val assets = File(root, "assets")
    val profileFile = File(client, ".cubex-profile.json")
}

/**
 * Installs and updates the mobile CubeXTech build, the same plan as CubeXCraft for iPhone (CXCInstaller.m) with what it learned
 * on real phones: libraries of the mobile profile come from the desktop distribution, Sodium/Iris/our Sodium screen and the shader
 * packs too; LWJGL is the Android build in the APK, never the desktop jars of the profile.
 */
class Installer(private val ctx: Context, private val token: String) {
    private val tag = "CubexInstall"

    /** Phase text for the UI, and file/byte progress of the download. */
    var onStatus: (String) -> Unit = {}
    var onProgress: SyncProgress? = null
    @Volatile var cancelled = false

    class Result(val profile: JSONObject, val layout: GameLayout)

    /**
     * What a start needs: the files to fetch ([jobs], [bytes]) out of the whole build ([totalBytes]); [mods] — the mods' file
     * names, for the build card. Checking is fast after the first time (hash cache).
     */
    class Plan(
        val profile: JSONObject, val layout: GameLayout, val manifest: Manifest, val jobs: List<Download>, val keep: Set<String>,
        val cache: HashCache, val totalBytes: Long, val mods: List<String>,
    ) {
        val bytes get() = jobs.sumOf { it.size }
    }

    private var total = 0L

    fun plan(): Plan {
        onStatus("Получаю сборку CubeXTech…")
        val profile = mobileProfile()
        val filesDir = profile.optJSONObject("mobile")?.optString("filesDir")?.ifEmpty { null } ?: profile.getString("dir")
        val layout = GameLayout(ctx, filesDir)
        listOf(layout.client, layout.libraries, layout.cxl, layout.assets, File(layout.client, "logs")).forEach { it.mkdirs() }

        val manifest = Api.manifest(token, filesDir)
        val libManifest = Api.manifest(token, LIBRARIES_DIR)
        val cxlManifest = Api.manifest(token, "@cxl")
        val assetManifest = Api.manifest(token, profile.optString("assetDir", "assets"))
        val cache = HashCache(File(layout.root, ".cubex-hashes.json"))
        val jobs = ArrayList<Download>()
        total = 0L

        onStatus("Проверяю файлы сборки (${manifest.files.size})…")
        planClient(manifest, profile, layout, cache, jobs)
        planLibraries(profile, manifest, libManifest, cxlManifest, layout, cache, jobs)
        val desktopMods = planDesktopMods(libManifest, layout, cache, jobs)
        onStatus("Проверяю ресурсы игры (${assetManifest.files.size})…")
        for (e in assetManifest.files.values) add(assetManifest, e, Sync.safeFile(layout.assets, e.path), cache, jobs)
        cache.save()
        val mods = (manifest.files.keys + desktopMods).filter { it.startsWith("mods/") && it.endsWith(".jar") }.map { it.substringAfterLast('/') }.sorted()
        return Plan(profile, layout, manifest, jobs, desktopMods, cache, total, mods)
    }

    fun run(plan: Plan = plan()): Result {
        if (cancelled) throw InterruptedException("cancelled")
        if (plan.jobs.isNotEmpty()) {
            onStatus("Скачиваю ${plan.jobs.size} файлов…")
            Sync.fetchAll(Api.http, plan.jobs, onProgress) { cancelled }
            for (j in plan.jobs) plan.cache.remember(j.dest, j.sha1)
            plan.cache.save()
        }
        if (cancelled) throw InterruptedException("cancelled")

        removeExtras(plan.manifest, plan.profile, plan.layout, plan.keep)
        Tweaks.apply(plan.layout.client, plan.manifest)
        plan.layout.profileFile.writeText(plan.profile.toString())
        BuildInfo.save(ctx, plan)
        onStatus("Сборка готова")
        return Result(plan.profile, plan.layout)
    }

    /** The CubeXTech profile with a "mobile" block: on an unknown platform the server would hand out the desktop ones. */
    private fun mobileProfile(): JSONObject {
        val list = Api.profiles(token)
        var first: JSONObject? = null
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i)
            if (p.optJSONObject("mobile") == null) continue
            if (p.optString("title") == "CubeXTech") return p
            if (first == null) first = p
        }
        return first ?: error("На сервере нет мобильной сборки CubeXTech")
    }

    private fun add(m: Manifest, e: FileEntry, dest: File, cache: HashCache, jobs: MutableList<Download>) {
        total += e.size
        if (!cache.matches(dest, e.size, e.sha1)) jobs += Download(m.base + Sync.encodePath(e.path), dest, e.size, e.sha1)
    }

    private fun matchesAny(path: String, prefixes: List<String>) = prefixes.any { path == it || path.startsWith("$it/") }

    private fun planClient(m: Manifest, profile: JSONObject, layout: GameLayout, cache: HashCache, jobs: MutableList<Download>) {
        val exclusions = strings(profile.optJSONArray("updateExclusions"))
        for (e in m.files.values) {
            if (e.path.startsWith("libraries/")) continue   // go to the shared libraries folder, see planLibraries
            val dest = Sync.safeFile(layout.client, e.path)
            // the player's own files (options.txt and the like): only when they do not exist yet
            if (dest.exists() && (matchesAny(e.path, exclusions) || e.path == "options.txt")) continue
            add(m, e, dest, cache, jobs)
        }
    }

    private fun planLibraries(
        profile: JSONObject, manifest: Manifest, libManifest: Manifest, cxlManifest: Manifest,
        layout: GameLayout, cache: HashCache, jobs: MutableList<Download>,
    ) {
        val wanted = LinkedHashSet<String>()
        wanted += strings(profile.optJSONArray("classPath"))
        wanted += strings(profile.optJSONObject("moduleConf")?.optJSONArray("modulePath"))
        wanted += strings(profile.optJSONArray("altClassPath"))
        for (entry in wanted) {
            if (entry.startsWith("@cxl/")) {
                // the launcher's own folder (/cxl/libs, its manifest has no "libs/"): vanilla authlib instead of Gravit's
                val key = if (entry.startsWith("@cxl/libs/")) entry.removePrefix("@cxl/libs/") else entry.substringAfterLast('/')
                val e = cxlManifest.files[key] ?: error("На сервере нет библиотеки $entry")
                add(cxlManifest, e, Sync.safeFile(layout.cxl, key), cache, jobs)
            } else if (entry.startsWith("libraries/")) {
                if (entry.startsWith("libraries/org/lwjgl/")) continue   // the Android build of LWJGL ships in the APK
                val src = if (manifest.files.containsKey(entry)) manifest else libManifest
                val e = src.files[entry] ?: error("На сервере нет библиотеки $entry")
                add(src, e, Sync.safeFile(layout.libraries, entry.removePrefix("libraries/")), cache, jobs)
            } else {
                Log.w(tag, "path outside libraries skipped: $entry")
            }
        }
        // The game itself is not on the classpath: FML finds the patched Minecraft and NeoForge jars (client-…-srg/-extra,
        // neoforge-…-client/-universal) by -DlibraryDirectory. Without them «minecraft/neoforge [MISSING]» (iPhone, 24.10).
        for (e in libManifest.files.values) {
            val p = e.path
            if (!p.startsWith("libraries/net/minecraft/") && !p.startsWith("libraries/net/neoforged/neoforge/")) continue
            if (p.endsWith(".cache") || p.endsWith("/minecraft.jar") || p in wanted) continue
            add(libManifest, e, Sync.safeFile(layout.libraries, p.removePrefix("libraries/")), cache, jobs)
        }
    }

    /**
     * Sodium, our Sodium settings screen and Iris with the shader packs come from the desktop pack (owner's call, 24.10: without
     * Sodium chunk building stuttered; Sodium gave 120 FPS on iPhone). commons-collections4 breaks Iris — the mobile profile has none.
     */
    private fun planDesktopMods(libManifest: Manifest, layout: GameLayout, cache: HashCache, jobs: MutableList<Download>): Set<String> {
        val kept = HashSet<String>()
        for (e in libManifest.files.values) {
            val name = e.path.substringAfterLast('/')
            val mod = e.path.startsWith("mods/") &&
                (name.startsWith("sodium-neoforge-") || name.startsWith("cubexcraft-sodiumui-") || name.startsWith("iris-neoforge-"))
            if (!mod && !e.path.startsWith("shaderpacks/")) continue
            kept += e.path
            add(libManifest, e, Sync.safeFile(layout.client, e.path), cache, jobs)
        }
        return kept
    }

    /** Files the server no longer has, inside the folders the profile verifies (mods by default). */
    private fun removeExtras(m: Manifest, profile: JSONObject, layout: GameLayout, keep: Set<String>) {
        val dirs = strings(profile.optJSONArray("updateVerify")).ifEmpty { listOf("mods") }
        for (d in dirs) {
            val dir = File(layout.client, d)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { f ->
                val rel = f.relativeTo(layout.client).invariantSeparatorsPath
                if (!m.files.containsKey(rel) && rel !in keep) {
                    Log.i(tag, "extra file removed: $rel")
                    f.delete()
                }
            }
        }
    }

    companion object {
        /** NeoForge libraries are not in the mobile distribution: the desktop one has the same jars. */
        const val LIBRARIES_DIR = "CubeXCraft"

        fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

        /** The installed profile, or null when the build was never installed. */
        fun installed(ctx: Context): Pair<JSONObject, GameLayout>? {
            for (dir in listOf("CubeXCraft-mobile")) {
                val layout = GameLayout(ctx, dir)
                if (layout.profileFile.isFile) return runCatching { JSONObject(layout.profileFile.readText()) to layout }.getOrNull()
            }
            return null
        }
    }
}
