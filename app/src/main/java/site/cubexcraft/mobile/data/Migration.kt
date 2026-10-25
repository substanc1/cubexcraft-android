// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import site.cubexcraft.mobile.install.GameLayout
import java.io.File

/**
 * 3.0 installed over CubeX Mobile 2.7.x (same package and key): once, the player stays logged in and the game already
 * downloaded is reused. 2.7.x kept the launcher token in its Room database (databases/launcher_data.db, table accounts,
 * the CubeX account's refreshToken) and the game in <external files>/.minecraft (versions/CubeXTech, libraries, assets).
 * Folders are moved by renaming (same storage, instant); the installer then checks them by hash and fetches only what differs.
 */
object Migration {
    private const val TAG = "CubexMigrate"

    fun run(ctx: Context) {
        val prefs = ctx.getSharedPreferences("migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("from27", false)) return
        runCatching { account(ctx) }.onFailure { Log.w(TAG, "account: $it") }
        runCatching { files(ctx) }.onFailure { Log.w(TAG, "files: $it") }
        prefs.edit().putBoolean("from27", true).apply()
    }

    private fun account(ctx: Context) {
        val auth = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        if (auth.getString("token", null) != null) return
        val db = ctx.getDatabasePath("launcher_data.db")
        if (!db.isFile) return
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery(
                "SELECT refreshToken, username, profileId FROM accounts WHERE accountType = ? AND refreshToken <> '0' LIMIT 1",
                arrayOf("CubeX"),
            ).use { c ->
                if (!c.moveToFirst()) return
                auth.edit().putString("token", c.getString(0)).putString("username", c.getString(1)).putString("uuid", c.getString(2)).apply()
                Log.i(TAG, "login of 2.7.x kept: ${c.getString(1)}")
            }
        }
    }

    private fun files(ctx: Context) {
        val ext = ctx.getExternalFilesDir(null) ?: return
        val old = File(ext, ".minecraft")
        if (!old.isDirectory) return
        val layout = GameLayout(ctx, "CubeXCraft-mobile")
        layout.root.mkdirs()
        move(File(old, "libraries"), layout.libraries)
        move(File(old, "assets"), layout.assets)
        // the game folder of 2.7.x (mods, config, options, worlds, screenshots); its own launch files stay behind
        val version = File(old, "versions/CubeXTech")
        val skip = setOf("CubeXTech.json", "CubeXTech.jar", "natives", "ZalithVersion.cfg")
        version.listFiles()?.forEach { f -> if (f.name !in skip) move(f, File(layout.client, f.name)) }
        Log.i(TAG, "game files of 2.7.x moved to ${layout.root}")
    }

    /** Rename; when the target exists, merge folder by folder (what is already there wins). */
    private fun move(from: File, to: File) {
        if (!from.exists()) return
        if (!to.exists()) {
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) return
        }
        if (from.isDirectory && to.isDirectory) from.listFiles()?.forEach { move(it, File(to, it.name)) }
    }
}
