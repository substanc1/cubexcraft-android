// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One file to fetch into [dest]; [sha1] may be empty when only the size is known. */
data class Download(val url: String, val dest: File, val size: Long, val sha1: String)

/** Progress of [Sync.fetchAll]: files and bytes done of the totals. */
fun interface SyncProgress {
    fun onProgress(filesDone: Int, filesTotal: Int, bytesDone: Long, bytesTotal: Long)
}

/**
 * Downloads with verification, from CubeX Mobile 2.7.x (measured on phones, 25.10): one connection to the site gives ~1.5 MB/s,
 * eight give ~9 MB/s, so big files go in parallel and are split into ranges; the ~3900 small asset files of a first install are
 * bound by the round trip and get their own bigger pool.
 */
object Sync {
    private const val PARALLEL_BIG = 8
    private const val PARALLEL_SMALL = 24
    private const val SMALL_FILE = 1L shl 20
    private const val SPLIT_ABOVE = 8L shl 20
    private const val CHUNK = 4L shl 20
    private const val ATTEMPTS = 3

    fun encodePath(p: String) = p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Rejects entries that would escape [root] (path traversal). */
    fun safeFile(root: File, rel: String): File {
        val rootPath = root.canonicalPath
        val f = File(rootPath, rel).canonicalFile
        require(f.path.startsWith(rootPath + File.separator)) { "unsafe path: $rel" }
        return f
    }

    private fun isValid(f: File, size: Long, sha1: String): Boolean =
        f.isFile && (size <= 0 || f.length() == size) && (sha1.isEmpty() || sha1(f) == sha1)

    private fun fetch(http: OkHttpClient, d: Download, bytes: AtomicLong) {
        d.dest.parentFile?.mkdirs()
        val tmp = File(d.dest.path + ".part")
        http.newCall(Request.Builder().url(d.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${d.url}")
            r.body!!.byteStream().use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        bytes.addAndGet(n.toLong())
                    }
                }
            }
        }
        finish(tmp, d)
    }

    /** Bytes [start, end] of the file into [tmp] at the same offset; the server must answer 206. */
    private fun fetchRange(http: OkHttpClient, url: String, tmp: File, start: Long, end: Long, bytes: AtomicLong) {
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        http.newCall(request).execute().use { r ->
            if (r.code != 206) throw IOException("HTTP ${r.code} (no range support) for $url")
            RandomAccessFile(tmp, "rw").use { out ->
                out.seek(start)
                val buf = ByteArray(1 shl 16)
                var written = 0L
                r.body!!.byteStream().use { i ->
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        bytes.addAndGet(n.toLong())
                    }
                }
                if (written != end - start + 1) throw IOException("short range $start-$end for $url")
            }
        }
    }

    private fun finish(tmp: File, d: Download) {
        if (!isValid(tmp, d.size, d.sha1)) {
            tmp.delete()
            throw IOException("hash mismatch for ${d.url}")
        }
        if (d.dest.exists()) d.dest.delete()
        if (!tmp.renameTo(d.dest)) throw IOException("rename failed for ${d.dest}")
    }

    private fun withRetry(task: () -> Unit) {
        var last: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                task()
                return
            } catch (e: IOException) {
                last = e
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw last!!
    }

    fun fetchAll(http: OkHttpClient, downloads: List<Download>, progress: SyncProgress? = null) {
        val total = downloads.size
        if (total == 0) return
        val bytesTotal = downloads.sumOf { it.size }
        val done = AtomicInteger()
        val bytes = AtomicLong()
        val report = { progress?.onProgress(done.get(), total, bytes.get(), bytesTotal) }
        val big = mutableListOf<() -> Unit>()
        val small = mutableListOf<() -> Unit>()
        // biggest first, so a large file does not start last and hold up the end
        downloads.sortedByDescending { it.size }.forEach { d ->
            if (d.size > SPLIT_ABOVE) {
                d.dest.parentFile?.mkdirs()
                val tmp = File(d.dest.path + ".part")
                RandomAccessFile(tmp, "rw").use { it.setLength(d.size) }
                val parts = ((d.size + CHUNK - 1) / CHUNK).toInt()
                val left = AtomicInteger(parts)
                repeat(parts) { i ->
                    val start = i * CHUNK
                    val end = minOf(d.size, start + CHUNK) - 1
                    big += {
                        fetchRange(http, d.url, tmp, start, end, bytes)
                        if (left.decrementAndGet() == 0) {
                            finish(tmp, d)
                            done.incrementAndGet()
                        }
                        report()
                    }
                }
            } else {
                (if (d.size > SMALL_FILE) big else small) += {
                    fetch(http, d, bytes)
                    done.incrementAndGet()
                    report()
                }
            }
        }
        val pools = listOf(big to PARALLEL_BIG, small to PARALLEL_SMALL)
            .filter { it.first.isNotEmpty() }
            .map { (tasks, threads) -> Executors.newFixedThreadPool(minOf(threads, tasks.size)) to tasks }
        try {
            // the site sometimes stalls a connection for over a minute (seen on a phone in Moscow): retry each piece before giving up
            val jobs = pools.flatMap { (pool, tasks) -> tasks.map { task -> pool.submit { withRetry(task) } } }
            jobs.forEach { job ->
                try {
                    job.get()
                } catch (ex: ExecutionException) {
                    throw ex.cause ?: ex
                }
            }
        } finally {
            pools.forEach { it.first.shutdownNow() }
        }
    }
}

/**
 * sha1 of files already on disk, keyed by path and checked against size and mtime: re-hashing ~6000 files (2+ GB) on every start
 * took most of a minute on a phone; with the cache only new or changed files are read.
 */
class HashCache(private val file: File) {
    private val map = ConcurrentHashMap<String, String>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            val k = o.keys()
            while (k.hasNext()) {
                val key = k.next()
                map[key] = o.getString(key)
            }
        }
    }

    /** True when [f] exists with the expected size and sha1. */
    fun matches(f: File, size: Long, sha1: String): Boolean {
        if (!f.isFile || (size > 0 && f.length() != size)) return false
        if (sha1.isEmpty()) return true
        val stamp = "${f.length()}:${f.lastModified()}"
        val cached = map[f.path]
        if (cached != null && cached.substringBefore('|') == stamp) return cached.substringAfter('|') == sha1
        val actual = Sync.sha1(f)
        map[f.path] = "$stamp|$actual"
        return actual == sha1
    }

    /** A file we just wrote ourselves with known content. */
    fun remember(f: File, sha1: String) {
        if (f.isFile) map[f.path] = "${f.length()}:${f.lastModified()}|$sha1"
    }

    fun save() {
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }
}
