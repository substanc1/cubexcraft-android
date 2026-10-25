// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request
import site.cubexcraft.mobile.net.Api
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * News covers: fetched once (through the RU relay when it is on), kept on disk, decoded already scaled down to the card's
 * width (a full-size picture would cost 10+ MB of memory on a weak phone).
 */
object Images {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "cubex-img").apply { isDaemon = true } }
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(ctx: Context, url: String, into: ImageView, widthPx: Int) {
        into.tag = url
        memory.get(url)?.let { into.setImageBitmap(it); return }
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        pool.execute {
            val f = File(dir, sha1(url))
            val bmp = runCatching {
                if (!f.isFile) {
                    Api.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching null
                        val tmp = File(f.path + ".part")
                        r.body!!.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        tmp.renameTo(f)
                    }
                }
                decode(f, widthPx)
            }.getOrNull() ?: return@execute
            memory.put(url, bmp)
            main.post { if (into.tag == url) into.setImageBitmap(bmp) }
        }
    }

    private fun decode(f: File, widthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
