package com.taqin.frialauncher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream

class FridaManager(private val context: Context) {
    private val fridaDir = File(context.filesDir, "frida")
    private val fridaBinary = File(fridaDir, "frida-server")
    private var fridaProcess: Process? = null
    private val TAG = "FridaManager"

    init {
        fridaDir.mkdirs()
        Log.d(TAG, "Frida directory: ${fridaDir.absolutePath}")
    }

    fun getArchitecture(): String {
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        Log.d(TAG, "Primary ABI: $abi")
        Log.d(TAG, "All ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")

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

    fun isRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }

    private fun checkRootMethod1(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("which su")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            process.waitFor()
            val rooted = result != null && result.isNotEmpty()
            Log.d(TAG, "Root check method 1: $rooted")
            rooted
        } catch (e: Exception) {
            Log.e(TAG, "Root check method 1 failed", e)
            false
        }
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        val found = paths.any { File(it).exists() }
        Log.d(TAG, "Root check method 2: $found")
        return found
    }

    private fun checkRootMethod3(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val writer = DataOutputStream(process.outputStream)
            writer.writeBytes("id\n")
            writer.writeBytes("exit\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            process.waitFor()

            val rooted = result?.contains("uid=0") == true
            Log.d(TAG, "Root check method 3: $rooted, output: $result")
            rooted
        } catch (e: Exception) {
            Log.e(TAG, "Root check method 3 failed", e)
            false
        }
    }

    private fun validateBinary(file: File): Boolean {
        return try {
            val fis = FileInputStream(file)
            val header = ByteArray(4)
            fis.read(header)
            fis.close()

            val isELF = header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.toByte() &&
                    header[2] == 'L'.toByte() &&
                    header[3] == 'F'.toByte()

            Log.d(TAG, "Binary validation - ELF header: $isELF")
            Log.d(TAG, "Header bytes: ${header.joinToString(" ") { "%02X".format(it) }}")

            isELF
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate binary", e)
            false
        }
    }

    private fun decompressXZ(inputFile: File, outputFile: File, onProgress: (Float) -> Unit): Boolean {
        return try {
            Log.d(TAG, "Decompressing XZ file using Java XZ library...")

            val inputSize = inputFile.length()
            val xzInputStream = XZInputStream(FileInputStream(inputFile))
            val outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var totalRead = 0L
            var bytesRead: Int

            while (xzInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (inputSize > 0) {
                    val progress = (totalRead.toFloat() / (inputSize * 3.5f)).coerceAtMost(1f)
                    onProgress(progress)
                }
            }

            xzInputStream.close()
            outputStream.close()

            val success = outputFile.exists() && outputFile.length() > 0
            Log.d(TAG, "XZ decompression result: $success")
            Log.d(TAG, "Input size: $inputSize, Output size: ${outputFile.length()}")

            success
        } catch (e: Exception) {
            Log.e(TAG, "XZ decompression failed", e)
            false
        }
    }

    suspend fun downloadLatestFrida(
        onProgress: (Float) -> Unit,
        onFileInfo: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Frida download...")
            val arch = getArchitecture()
            Log.d(TAG, "Target architecture: $arch")

            val apiUrl = "https://api.github.com/repos/frida/frida/releases/latest"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "FridaLauncher")
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val assets = json.getJSONArray("assets")

            var downloadUrl: String? = null
            var fileName: String? = null

            Log.d(TAG, "Searching for frida-server-*-android-$arch asset...")

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")

                if (name.startsWith("frida-server") && name.contains("android-$arch")) {
                    downloadUrl = asset.getString("browser_download_url")
                    fileName = name
                    Log.d(TAG, "Selected asset: $name")
                    Log.d(TAG, "Download URL: $downloadUrl")
                    break
                }
            }

            if (downloadUrl == null) {
                Log.e(TAG, "No matching frida-server found for android-$arch")
                return@withContext false
            }

            fileName?.let { name ->
                onFileInfo(name)
            }

            if (fridaBinary.exists()) {
                fridaBinary.delete()
                Log.d(TAG, "Deleted existing binary")
            }

            val downloadConnection = URL(downloadUrl).openConnection() as HttpURLConnection
            downloadConnection.connectTimeout = 10000
            downloadConnection.readTimeout = 60000
            val totalSize = downloadConnection.contentLength
            Log.d(TAG, "Download size: $totalSize bytes")

            val inputStream = downloadConnection.inputStream
            val buffer = ByteArray(8192)
            var downloadedSize = 0
            var bytesRead: Int

            val tempDownloadFile = File(fridaDir, "download.tmp")
            val tempOutput = FileOutputStream(tempDownloadFile)

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                tempOutput.write(buffer, 0, bytesRead)
                downloadedSize += bytesRead
                if (totalSize > 0) {
                    onProgress((downloadedSize.toFloat() / totalSize) * 0.7f)
                }
            }

            tempOutput.close()
            inputStream.close()

            Log.d(TAG, "Download completed. Temp file size: ${tempDownloadFile.length()} bytes")

            val headerBytes = ByteArray(4)
            val headerStream = FileInputStream(tempDownloadFile)
            headerStream.read(headerBytes)
            headerStream.close()

            Log.d(TAG, "File header: ${headerBytes.joinToString(" ") { "%02X".format(it) }}")

            val success = when {
                headerBytes[0] == 0xFD.toByte() && headerBytes[1] == 0x37.toByte() -> {
                    Log.d(TAG, "Detected XZ compressed file")
                    decompressXZ(tempDownloadFile, fridaBinary) { progress ->
                        onProgress(0.7f + (progress * 0.3f))
                    }
                }
                headerBytes[0] == 0x1F.toByte() && headerBytes[1] == 0x8B.toByte() -> {
                    Log.d(TAG, "Detected GZIP compressed file")
                    val gzipStream = GZIPInputStream(FileInputStream(tempDownloadFile))
                    val output = FileOutputStream(fridaBinary)
                    gzipStream.copyTo(output)
                    gzipStream.close()
                    output.close()
                    onProgress(1.0f)
                    true
                }
                headerBytes[0] == 0x7F.toByte() && headerBytes[1] == 'E'.toByte() -> {
                    Log.d(TAG, "Detected raw ELF file")
                    tempDownloadFile.renameTo(fridaBinary)
                    onProgress(1.0f)
                    true
                }
                else -> {
                    Log.e(TAG, "Unknown file format")
                    false
                }
            }

            tempDownloadFile.delete()

            if (!success) {
                Log.e(TAG, "Failed to process downloaded file")
                return@withContext false
            }

            Log.d(TAG, "Final binary size: ${fridaBinary.length()} bytes")

            if (!validateBinary(fridaBinary)) {
                Log.e(TAG, "Final binary validation failed")
                fridaBinary.delete()
                return@withContext false
            }

            val execSuccess = makeExecutable(fridaBinary.absolutePath)
            Log.d(TAG, "Made executable: $execSuccess")

            if (execSuccess) {
                testBinary()
            }

            execSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }

    private fun testBinary(): Boolean {
        return try {
            Log.d(TAG, "Testing binary...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "${fridaBinary.absolutePath} --version"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = reader.readLine()
            val error = errorReader.readLine()
            val exitCode = process.waitFor()

            Log.d(TAG, "Binary test - Exit code: $exitCode")
            Log.d(TAG, "Binary test - Output: $output")
            Log.d(TAG, "Binary test - Error: $error")

            exitCode == 0 || output?.contains("frida") == true
        } catch (e: Exception) {
            Log.e(TAG, "Binary test failed", e)
            false
        }
    }

    private fun makeExecutable(filePath: String): Boolean {
        return try {
            Log.d(TAG, "Making file executable: $filePath")

            val chmodProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 755 $filePath"))
            val chmodResult = chmodProcess.waitFor()
            Log.d(TAG, "chmod 755 result: $chmodResult")

            val chownProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "chown root:root $filePath"))
            val chownResult = chownProcess.waitFor()
            Log.d(TAG, "chown result: $chownResult")

            chmodResult == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make executable", e)
            false
        }
    }

    suspend fun startFrida(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Frida server on port $port")

            if (!isRooted()) {
                Log.e(TAG, "Device not rooted")
                return@withContext false
            }

            if (!fridaBinary.exists()) {
                Log.e(TAG, "Frida binary not found at ${fridaBinary.absolutePath}")
                return@withContext false
            }

            if (!validateBinary(fridaBinary)) {
                Log.e(TAG, "Binary validation failed")
                return@withContext false
            }

            Log.d(TAG, "Binary exists and is valid, size: ${fridaBinary.length()}")

            stopFrida()

            val command = "cd ${fridaDir.absolutePath} && ${fridaBinary.absolutePath} -l 0.0.0.0:$port -D"
            Log.d(TAG, "Executing command: su -c \"$command\"")

            val processBuilder = ProcessBuilder("su", "-c", command)
            processBuilder.redirectErrorStream(true)
            fridaProcess = processBuilder.start()

            Thread.sleep(4000)

            val running = checkIfFridaRunning(port)
            Log.d(TAG, "Frida running check: $running")

            if (!running) {
                Log.d(TAG, "Reading process output for debugging...")
                val reader = BufferedReader(InputStreamReader(fridaProcess?.inputStream))
                var line: String?
                var lineCount = 0
                while (reader.readLine().also { line = it } != null && lineCount < 10) {
                    Log.d(TAG, "Process output: $line")
                    lineCount++
                }
            }

            running
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Frida", e)
            false
        }
    }

    private fun checkIfFridaRunning(port: Int): Boolean {
        return try {
            Log.d(TAG, "Checking if Frida is running...")

            val processCheck = Runtime.getRuntime().exec(arrayOf("su", "-c", "pgrep -f frida-server"))
            val reader = BufferedReader(InputStreamReader(processCheck.inputStream))
            val result = reader.readLine()
            processCheck.waitFor()

            Log.d(TAG, "pgrep result: $result")

            if (result != null && result.isNotEmpty()) {
                val portCheck = Runtime.getRuntime().exec(arrayOf("su", "-c", "netstat -tulpn | grep :$port"))
                val portReader = BufferedReader(InputStreamReader(portCheck.inputStream))
                val portResult = portReader.readLine()
                portCheck.waitFor()

                Log.d(TAG, "Port check result: $portResult")

                val isListening = portResult != null && portResult.contains(":$port")
                Log.d(TAG, "Port $port is listening: $isListening")

                return isListening
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Frida status", e)
            false
        }
    }

    fun stopFrida() {
        try {
            Log.d(TAG, "Stopping Frida server")
            fridaProcess?.destroy()
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f frida-server"))
            process.waitFor()
            Log.d(TAG, "Frida stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Frida", e)
        }
    }

    fun getLogOutput(): String {
        return try {
            fridaProcess?.let { process ->
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                val output = StringBuilder()

                var line: String?
                var lineCount = 0
                while (reader.readLine().also { line = it } != null && lineCount < 20) {
                    output.append("OUT: $line\n")
                    lineCount++
                }

                lineCount = 0
                while (errorReader.readLine().also { line = it } != null && lineCount < 20) {
                    output.append("ERR: $line\n")
                    lineCount++
                }

                output.toString()
            } ?: "No process running"
        } catch (e: Exception) {
            "Failed to read logs: ${e.message}"
        }
    }
}