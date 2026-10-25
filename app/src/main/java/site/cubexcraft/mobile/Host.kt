// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import site.cubexcraft.core.CoreHost
import site.cubexcraft.mobile.ui.DevActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the game asks the app for: clipboard, links, the window scale and the exit screen. */
class Host(private val app: Context) : CoreHost {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var scale = 0.75f

    private fun clipboard() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun openLink(link: String) {
        main.post {
            runCatching {
                val uri = if (link.startsWith("file:") || link.startsWith("/")) null else Uri.parse(link)
                if (uri != null) app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w("CubexHost", "cannot open $link: $it") }
        }
    }

    override fun setClipboard(text: String) {
        main.post { clipboard().setPrimaryClip(ClipData.newPlainText("CubeXCraft", text)) }
    }

    /** The clipboard can be read on the main thread only; the game threads wait up to a second for it. */
    override fun getClipboard(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return readClip()
        var text = ""
        val done = CountDownLatch(1)
        main.post {
            text = readClip()
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
        return text
    }

    private fun readClip(): String = runCatching {
        clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
    }.getOrNull().orEmpty()

    override fun renderScale(): Float = scale

    /** The game JVM is exiting (its process ends right after): bring the launcher back with the exit code. */
    override fun onGameExit(context: Context, code: Int, isSignal: Boolean) {
        Log.i("CubexHost", "game exit code=$code signal=$isSignal")
        app.startActivity(
            Intent(app, DevActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(DevActivity.EXTRA_EXIT_CODE, code)
                .putExtra(DevActivity.EXTRA_EXIT_SIGNAL, isSignal)
        )
    }
}
