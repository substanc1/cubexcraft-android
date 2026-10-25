// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.net

import android.content.Context
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Russian DPI cuts HTTPS to the VPS after ~16 KB. Same fix as the desktop launcher 1.3.x and CubeX Mobile 2.7.x: probe a 64 KB
 * file directly; if it does not arrive, send the site hosts to the Moscow relay (same SNI, certificate and ports). The choice is
 * remembered; the game JVM gets the same mapping through a hosts file (-Djdk.net.hosts.file).
 */
object Relay {
    private const val TAG = "CubexRelay"
    const val RELAY_IP = "89.125.214.40"
    private const val PROBE_URL = "https://www.cubexcraft.site/cxl/probe-64k.bin"
    private const val PROBE_BYTES = 65536L
    private val HOSTS = setOf("www.cubexcraft.site", "cubexcraft.site", "play.cubexcraft.site")

    @Volatile var enabled = false
        private set
    private var stateFile: File? = null
    private var hostsFile: File? = null

    fun init(context: Context) {
        stateFile = File(context.filesDir, "relay.txt")
        hostsFile = File(context.filesDir, "relay_hosts.txt")
        enabled = stateFile?.takeIf { it.exists() }?.readText()?.trim() == "relay"
        writeHosts()
    }

    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (enabled && hostname in HOSTS) listOf(InetAddress.getByName(RELAY_IP)) else Dns.SYSTEM.lookup(hostname)
    }

    /** Direct download of probe-64k.bin (8 s). Blocking: call off the main thread. */
    fun probe() {
        val direct = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
        val got = try {
            direct.newCall(Request.Builder().url(PROBE_URL).build()).execute().use { r ->
                var n = 0L
                val buf = ByteArray(16384)
                val s = r.body?.byteStream()
                while (s != null) {
                    val k = s.read(buf)
                    if (k < 0) break
                    n += k
                }
                n
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            0L
        }
        set(got < PROBE_BYTES)
        Log.i(TAG, "probe got=$got -> ${if (enabled) "relay $RELAY_IP" else "direct"}")
    }

    private fun set(relay: Boolean) {
        enabled = relay
        runCatching { stateFile?.writeText(if (relay) "relay" else "direct") }
        writeHosts()
    }

    private fun writeHosts() {
        val f = hostsFile ?: return
        runCatching { f.writeText(if (enabled) HOSTS.joinToString("\n") { "$RELAY_IP $it" } + "\n" else "") }
    }

    /** JVM options so the game itself (auth, server play.cubexcraft.site) goes through the relay too. */
    fun javaArgs(): List<String> {
        val f = hostsFile
        return if (enabled && f != null && f.exists()) listOf("-Djdk.net.hosts.file=${f.absolutePath}") else emptyList()
    }
}
