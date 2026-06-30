package com.taqin.frialauncher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream

class FridaManager(private val context: Context) {

    companion object {
        private const val TAG = "FridaManager"
        private const val PREFS = "frida_launcher"
        private const val KEY_BIN_NAME = "bin_name"
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
        const val DEFAULT_PORT = 27042

        // Detectable ASCII signatures inside frida-server. Replaced in-place with
        // same-length random tokens so process/thread/maps scans miss them.
        // (Bare "frida" inside the D-Bus address "re.frida.server" is intentionally
        // left untouched to preserve client-attach compatibility.)
        private val DETECT_SIGNATURES = listOf(
            "frida-server",
            "frida-agent",
            "frida-gadget",
            "gum-js-loop",
            "gmain",
            "gdbus",
            "pool-frida",
            "linjector"
        )
    }

    private val fridaDir = File(context.filesDir, "frida")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Randomized, persisted binary filename (hidden). Avoids the literal
    // "frida-server" path that detectors grep for.
    private val binName: String = prefs.getString(KEY_BIN_NAME, null) ?: run {
        val generated = "." + randomToken(8)
        prefs.edit().putString(KEY_BIN_NAME, generated).apply()
        generated
    }
    private val fridaBinary = File(fridaDir, binName)

    // Cache expensive checks; root state & arch don't change at runtime.
    private val cachedArch: String by lazy { resolveArchitecture() }
    @Volatile private var cachedRoot: Boolean? = null

    init {
        fridaDir.mkdirs()
        Log.d(TAG, "Frida directory: ${fridaDir.absolutePath}, binary: $binName")
    }

    // ---- Helpers --------------------------------------------------------

    private fun randomToken(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        // java.util.Random is fine here; this is obfuscation, not crypto.
        val r = java.util.Random()
        return buildString { repeat(len) { append(chars[r.nextInt(chars.length)]) } }
    }

    /** Run a root shell command, return exit code + combined stdout. */
    private fun runAsRoot(cmd: String, timeoutMs: Long = 10_000): Pair<Int, String> {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor()
            Pair(finished, out.trim())
        } catch (e: Exception) {
            Log.e(TAG, "root cmd failed: $cmd", e)
            Pair(-1, "")
        }
    }

    fun getArchitecture(): String = cachedArch

    private fun resolveArchitecture(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        Log.d(TAG, "ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
        return when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> {
                Log.w(TAG, "Unknown ABI: $abi, defaulting to arm64")
                "arm64"
            }
        }
    }

    // ---- Root detection -------------------------------------------------

    /** Cached. First call may block briefly; call off the main thread. */
    fun isRooted(): Boolean {
        cachedRoot?.let { return it }
        val result = checkRootBinaryPaths() || checkSuExecutable()
        cachedRoot = result
        Log.d(TAG, "Root: $result")
        return result
    }

    /** Force re-evaluation (e.g. after user grants su). */
    fun refreshRoot(): Boolean {
        cachedRoot = null
        return isRooted()
    }

    private fun checkRootBinaryPaths(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su", "/data/adb/magisk", "/data/adb/ksu"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkSuExecutable(): Boolean {
        val (code, out) = runAsRoot("id", timeoutMs = 5_000)
        return code == 0 && out.contains("uid=0")
    }

    // ---- Port helpers ---------------------------------------------------

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    /** True if some process is already listening on [port]. Needs root. */
    fun isPortInUse(port: Int): Boolean {
        if (!isValidPort(port)) return false
        // Prefer ss (present in toybox); fall back to /proc/net/tcp parsing.
        val (ssCode, ssOut) = runAsRoot("ss -ltn 2>/dev/null", timeoutMs = 5_000)
        if (ssCode == 0 && ssOut.isNotEmpty()) {
            return ssOut.lineSequence().any { it.contains(":$port ") || it.endsWith(":$port") }
        }
        return procNetListening(port)
    }

    /** Parse /proc/net/tcp{,6} for a LISTEN socket on [port]. */
    private fun procNetListening(port: Int): Boolean {
        val hexPort = "%04X".format(port)
        for (path in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val (code, out) = runAsRoot("cat $path 2>/dev/null", timeoutMs = 5_000)
            if (code != 0) continue
            // columns: sl local_address rem_address st ...  st 0A == LISTEN
            val hit = out.lineSequence().drop(1).any { line ->
                val cols = line.trim().split(Regex("\\s+"))
                cols.size > 3 && cols[1].endsWith(":$hexPort") && cols[3] == "0A"
            }
            if (hit) return true
        }
        return false
    }

    // ---- Binary validation & anti-detect patching ----------------------

    private fun validateBinary(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                if (fis.read(header) < 4) return false
                header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                        header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateBinary failed", e)
            false
        }
    }

    /**
     * Rewrite detectable ASCII signatures in the ELF with same-length random
     * tokens. Defeats common Frida detectors that scan process cmdline,
     * per-thread comm names under /proc, and loaded library names.
     * Same-length, same-position edits keep the ELF structurally valid.
     */
    private fun patchAntiDetect(file: File): Int {
        return try {
            val data = file.readBytes()
            var patched = 0
            for (sig in DETECT_SIGNATURES) {
                val pattern = sig.toByteArray(Charsets.US_ASCII)
                val replacement = randomToken(pattern.size).toByteArray(Charsets.US_ASCII)
                var idx = indexOf(data, pattern, 0)
                while (idx >= 0) {
                    System.arraycopy(replacement, 0, data, idx, replacement.size)
                    patched++
                    idx = indexOf(data, pattern, idx + pattern.size)
                }
            }
            if (patched > 0) {
                file.writeBytes(data)
                Log.d(TAG, "Anti-detect: patched $patched signature occurrence(s)")
            } else {
                Log.d(TAG, "Anti-detect: no known signatures found")
            }
            patched
        } catch (e: Exception) {
            Log.e(TAG, "Anti-detect patch failed", e)
            0
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty()) return -1
        var i = from
        val last = data.size - pattern.size
        while (i <= last) {
            var j = 0
            while (j < pattern.size && data[i + j] == pattern[j]) j++
            if (j == pattern.size) return i
            i++
        }
        return -1
    }

    // ---- Download -------------------------------------------------------

    private fun decompressXZ(inputFile: File, outputFile: File, onProgress: (Float) -> Unit): Boolean {
        return try {
            val inputSize = inputFile.length()
            XZInputStream(FileInputStream(inputFile)).use { xz ->
                FileOutputStream(outputFile).use { out ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var bytesRead: Int
                    while (xz.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (inputSize > 0) {
                            onProgress((totalRead.toFloat() / (inputSize * 3.5f)).coerceAtMost(1f))
                        }
                    }
                }
            }
            outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "XZ decompression failed", e)
            false
        }
    }

    suspend fun downloadLatestFrida(
        onProgress: (Float) -> Unit,
        onFileInfo: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val tempDownloadFile = File(fridaDir, "download.tmp")
        try {
            val arch = getArchitecture()
            Log.d(TAG, "Download start, arch=$arch")

            val apiUrl = "https://api.github.com/repos/frida/frida/releases/latest"
            val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "FridaLauncher")
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            if (connection.responseCode != 200) {
                Log.e(TAG, "GitHub API HTTP ${connection.responseCode}")
                return@withContext false
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")

            var downloadUrl: String? = null
            var fileName: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.startsWith("frida-server") && name.contains("android-$arch")) {
                    downloadUrl = asset.getString("browser_download_url")
                    fileName = name
                    break
                }
            }

            if (downloadUrl == null) {
                Log.e(TAG, "No frida-server asset for android-$arch")
                return@withContext false
            }
            fileName?.let { onFileInfo(it) }

            if (fridaBinary.exists()) fridaBinary.delete()

            val dl = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "FridaLauncher")
                connectTimeout = 10_000
                readTimeout = 60_000
            }
            val totalSize = dl.contentLength

            dl.inputStream.use { input ->
                FileOutputStream(tempDownloadFile).use { tempOutput ->
                    val buffer = ByteArray(8192)
                    var downloadedSize = 0
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        tempOutput.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead
                        if (totalSize > 0) onProgress((downloadedSize.toFloat() / totalSize) * 0.7f)
                    }
                }
            }
            Log.d(TAG, "Downloaded ${tempDownloadFile.length()} bytes")

            val headerBytes = ByteArray(4)
            FileInputStream(tempDownloadFile).use { it.read(headerBytes) }

            val extracted = when {
                headerBytes[0] == 0xFD.toByte() && headerBytes[1] == 0x37.toByte() -> {
                    decompressXZ(tempDownloadFile, fridaBinary) { p -> onProgress(0.7f + p * 0.3f) }
                }
                headerBytes[0] == 0x1F.toByte() && headerBytes[1] == 0x8B.toByte() -> {
                    GZIPInputStream(FileInputStream(tempDownloadFile)).use { gz ->
                        FileOutputStream(fridaBinary).use { gz.copyTo(it) }
                    }
                    onProgress(1.0f); true
                }
                headerBytes[0] == 0x7F.toByte() && headerBytes[1] == 'E'.code.toByte() -> {
                    tempDownloadFile.copyTo(fridaBinary, overwrite = true)
                    onProgress(1.0f); true
                }
                else -> {
                    Log.e(TAG, "Unknown file format")
                    false
                }
            }

            if (!extracted) return@withContext false

            if (!validateBinary(fridaBinary)) {
                Log.e(TAG, "Final binary not a valid ELF")
                fridaBinary.delete()
                return@withContext false
            }

            // Anti-detect: scrub signatures before installing.
            patchAntiDetect(fridaBinary)

            val execSuccess = makeExecutable(fridaBinary.absolutePath)
            Log.d(TAG, "makeExecutable=$execSuccess, size=${fridaBinary.length()}")
            execSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        } finally {
            if (tempDownloadFile.exists()) tempDownloadFile.delete()
        }
    }

    private fun makeExecutable(filePath: String): Boolean {
        val (chmod, _) = runAsRoot("chmod 755 '$filePath'")
        runAsRoot("chown root:root '$filePath'")
        return chmod == 0
    }

    fun isBinaryInstalled(): Boolean = fridaBinary.exists() && validateBinary(fridaBinary)

    // ---- Server lifecycle ----------------------------------------------

    /** True if our frida binary process is currently alive AND its port listens. */
    fun isServerRunning(port: Int): Boolean {
        val (code, out) = runAsRoot("pgrep -f '$binName'", timeoutMs = 5_000)
        val proc = code == 0 && out.isNotBlank()
        if (!proc) return false
        return isPortInUse(port)
    }

    /**
     * Start the server. Returns a [StartResult] describing the outcome so the UI
     * can show a precise message instead of a generic failure.
     */
    suspend fun startFrida(port: Int): StartResult = withContext(Dispatchers.IO) {
        try {
            if (!isValidPort(port)) return@withContext StartResult.INVALID_PORT
            if (!isRooted()) return@withContext StartResult.NO_ROOT
            if (!isBinaryInstalled()) return@withContext StartResult.NO_BINARY

            if (isServerRunning(port)) {
                Log.d(TAG, "Server already running on $port")
                return@withContext StartResult.ALREADY_RUNNING
            }
            if (isPortInUse(port)) {
                Log.e(TAG, "Port $port in use by another process")
                return@withContext StartResult.PORT_IN_USE
            }

            stopFrida()

            val command = "cd '${fridaDir.absolutePath}' && nohup '${fridaBinary.absolutePath}' " +
                    "-l 0.0.0.0:$port -D >/dev/null 2>&1 &"
            Log.d(TAG, "Launching: $command")
            runAsRoot(command, timeoutMs = 5_000)

            // Poll instead of a fixed sleep: succeed as soon as the port listens.
            repeat(10) {
                delay(500)
                if (isServerRunning(port)) {
                    Log.d(TAG, "Server up on $port")
                    return@withContext StartResult.SUCCESS
                }
            }
            Log.e(TAG, "Server did not come up within timeout")
            StartResult.FAILED
        } catch (e: Exception) {
            Log.e(TAG, "startFrida failed", e)
            StartResult.FAILED
        }
    }

    suspend fun stopFrida(): Boolean = withContext(Dispatchers.IO) {
        try {
            runAsRoot("pkill -f '$binName'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopFrida failed", e)
            false
        }
    }

    /** Read the server version via the installed binary (best-effort log line). */
    fun getServerInfo(): String {
        if (!isBinaryInstalled()) return "Binary not installed"
        val (code, out) = runAsRoot("'${fridaBinary.absolutePath}' --version", timeoutMs = 5_000)
        return if (code == 0 && out.isNotBlank()) "frida-server $out" else "Version unavailable"
    }

    enum class StartResult {
        SUCCESS, ALREADY_RUNNING, INVALID_PORT, PORT_IN_USE, NO_ROOT, NO_BINARY, FAILED;

        val message: String
            get() = when (this) {
                SUCCESS -> "Server online"
                ALREADY_RUNNING -> "Server already running on that port"
                INVALID_PORT -> "Invalid port (1-65535)"
                PORT_IN_USE -> "Port already in use by another process"
                NO_ROOT -> "Root access denied"
                NO_BINARY -> "Frida binary not installed - download first"
                FAILED -> "Failed to start server"
            }
    }
}
