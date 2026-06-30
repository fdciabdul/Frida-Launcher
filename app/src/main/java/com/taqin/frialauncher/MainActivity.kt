package com.taqin.frialauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taqin.frialauncher.ui.theme.FridaLauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- Palette --------------------------------------------------------------
private val Matrix = Color(0xFF00FF9C)
private val Amber = Color(0xFFFFB300)
private val Danger = Color(0xFFFF3B3B)
private val Purple = Color(0xFFBB86FC)
private val Panel = Color(0xFF0B0F14)
private val PanelHi = Color(0xFF0E141B)
private val TextDim = Color(0xFF5C6B73)
private val Ink = Color(0xFF05070A)

private val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    private lateinit var fridaManager: FridaManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fridaManager = FridaManager(this)
        enableEdgeToEdge()
        setContent {
            FridaLauncherTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Ink, Color(0xFF0A0E14)))
                        )
                ) {
                    FridaLauncherScreen(fridaManager, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridaLauncherScreen(
    fridaManager: FridaManager,
    modifier: Modifier = Modifier
) {
    // ---- State ----
    var arch by remember { mutableStateOf("…") }
    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var installed by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf<String?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadingFile by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(FridaManager.DEFAULT_PORT.toString()) }
    var status by remember { mutableStateOf("BOOTING") }
    var logs by remember {
        mutableStateOf(
            "[*] frida-launcher online\n" +
            "[*] awaiting operator input…"
        )
    }

    val scope = rememberCoroutineScope()
    fun log(line: String) { logs = "$line\n$logs" }

    // ---- One-shot init ----
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            arch = fridaManager.getArchitecture()
            rooted = fridaManager.isRooted()
            installed = fridaManager.isBinaryInstalled()
            version = fridaManager.getInstalledVersion()
            val p = port.toIntOrNull() ?: FridaManager.DEFAULT_PORT
            isRunning = installed && fridaManager.isServerRunning(p)
        }
        status = when {
            isRunning -> "SERVER ACTIVE"
            installed -> "READY"
            else -> "NO BINARY"
        }
        log("[+] arch=$arch root=${rooted == true} installed=$installed")
        // Update check is best-effort, never blocks the UI.
        val upd = fridaManager.isUpdateAvailable()
        if (upd) {
            updateAvailable = true
            log("[!] update available — press START to update")
        }
    }

    // ---- Actions ----
    suspend fun doDownload(): Boolean {
        isDownloading = true
        downloadProgress = 0f
        status = "DOWNLOADING"
        log("[>] fetching latest frida-server…")
        val ok = fridaManager.downloadLatestFrida(
            onProgress = { downloadProgress = it },
            onFileInfo = { downloadingFile = it; log("[>] $it") }
        )
        isDownloading = false
        downloadingFile = ""
        if (ok) {
            installed = true
            updateAvailable = false
            version = fridaManager.getInstalledVersion()
            status = "READY"
            log("[+] download complete — v${version ?: "?"}")
        } else {
            status = "DL FAILED"
            log("[x] download failed")
        }
        return ok
    }

    suspend fun doStart() {
        val portNum = port.toIntOrNull()
        if (portNum == null || !fridaManager.isValidPort(portNum)) {
            status = "BAD PORT"; log("[x] invalid port (1-65535)"); return
        }
        status = "STARTING"
        log("[>] launching server :$portNum")
        val r = fridaManager.startFrida(portNum)
        if (r == FridaManager.StartResult.SUCCESS || r == FridaManager.StartResult.ALREADY_RUNNING) {
            isRunning = true; status = "SERVER ACTIVE"
            log("[+] ${r.message} :$portNum")
        } else {
            isRunning = false; status = "START FAILED"
            log("[x] ${r.message}")
        }
    }

    suspend fun doStop() {
        fridaManager.stopFrida()
        isRunning = false; status = "STOPPED"
        log("[*] server terminated")
    }

    // ---- Adaptive primary action ----
    data class Action(val label: String, val color: Color)
    val action: Action = when {
        isDownloading -> Action("DOWNLOADING…", TextDim)
        isRunning -> Action("■  STOP SERVER", Danger)
        !installed -> Action("▼  DOWNLOAD FRIDA", Purple)
        updateAvailable -> Action("⟳  UPDATE & START", Amber)
        else -> Action("▶  START SERVER", Matrix)
    }
    fun onPrimary() {
        scope.launch {
            when {
                isDownloading -> {}
                isRunning -> doStop()
                !installed -> doDownload()
                updateAvailable -> { if (doDownload()) doStart() }
                else -> doStart()
            }
        }
    }

    // ---- Layout ----
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeaderBar(status = status, online = isRunning)

        // Stats grid
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("ARCH", arch, Matrix, Modifier.weight(1f))
            Stat(
                "ROOT",
                when (rooted) { true -> "GRANTED"; false -> "DENIED"; null -> "…" },
                when (rooted) { true -> Matrix; false -> Danger; null -> TextDim },
                Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("VERSION", version ?: "—", if (updateAvailable) Amber else Matrix, Modifier.weight(1f))
            Stat("SERVER", if (isRunning) "ONLINE" else "OFFLINE", if (isRunning) Matrix else TextDim, Modifier.weight(1f))
        }

        // Control panel
        Panel(border = Matrix) {
            SectionLabel("// CONTROL", Matrix)
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("PORT", fontFamily = Mono, fontSize = 11.sp) },
                singleLine = true,
                enabled = !isRunning && !isDownloading,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Matrix,
                    unfocusedBorderColor = Color(0xFF24323A),
                    focusedLabelColor = Matrix,
                    unfocusedLabelColor = TextDim,
                    focusedTextColor = Matrix,
                    unfocusedTextColor = Color(0xFFB9C6CC),
                    disabledBorderColor = Color(0xFF1A2329),
                    disabledTextColor = TextDim,
                    disabledLabelColor = TextDim
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 15.sp)
            )

            // Single adaptive action button — replaces the old DL/START pair.
            Button(
                onClick = { onPrimary() },
                enabled = !isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = action.color,
                    contentColor = Ink,
                    disabledContainerColor = Color(0xFF16202A),
                    disabledContentColor = TextDim
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(action.label, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            if (rooted == false) {
                Text(
                    "[!] root denied — server cannot launch",
                    fontFamily = Mono, fontSize = 10.sp, color = Danger
                )
            }
        }

        // Download progress
        if (isDownloading) {
            Panel(border = Purple, fill = PanelHi) {
                SectionLabel("// TRANSFER", Purple)
                if (downloadingFile.isNotEmpty()) {
                    Text(downloadingFile, fontFamily = Mono, fontSize = 10.sp, color = TextDim)
                }
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Matrix,
                    trackColor = Color(0xFF1A2329)
                )
                Text(
                    "${(downloadProgress * 100).toInt()}%  ::  ${progressBar(downloadProgress)}",
                    fontFamily = Mono, fontSize = 12.sp, color = Matrix
                )
            }
        }

        // Terminal
        Panel(border = Matrix, fill = Color(0xFF06090C)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("// TERMINAL", Matrix)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    GhostButton("SYNC", Matrix) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val p = port.toIntOrNull() ?: FridaManager.DEFAULT_PORT
                                isRunning = fridaManager.isServerRunning(p)
                                installed = fridaManager.isBinaryInstalled()
                                val info = fridaManager.getServerInfo()
                                status = if (isRunning) "SERVER ACTIVE" else if (installed) "READY" else "NO BINARY"
                                log("[*] sync: ${if (isRunning) "RUNNING" else "STOPPED"} | $info")
                            }
                        }
                    }
                    GhostButton("CLEAR", Danger) { logs = "[*] cleared" }
                }
            }
            TerminalView(logs)
        }
    }
}

// ---- Components -----------------------------------------------------------

@Composable
private fun HeaderBar(status: String, online: Boolean) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a"
    )
    Panel(border = Matrix, fill = PanelHi) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "FRIDA::LAUNCHER",
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = Matrix, letterSpacing = 2.sp
                )
                Text(
                    "root toolkit // anti-detect",
                    fontFamily = Mono, fontSize = 10.sp, color = TextDim, letterSpacing = 1.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background((if (online) Matrix else Amber).copy(alpha = pulse))
                )
                Text(status, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (online) Matrix else Amber)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontFamily = Mono, fontSize = 9.sp, color = TextDim, letterSpacing = 1.sp)
            Text(value, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun Panel(
    border: Color,
    fill: Color = Panel,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .border(1.dp, border.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(text, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 1.sp)
}

@Composable
private fun GhostButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        elevation = null
    ) {
        Text(text, fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TerminalView(logs: String) {
    val cursor = rememberInfiniteTransition(label = "cursor")
    val blink by cursor.animateFloat(
        0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blink"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF04070A))
            .border(1.dp, Matrix.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
    ) {
        val scrollState = rememberScrollState()
        LaunchedEffect(logs) { scrollState.animateScrollTo(scrollState.maxValue) }
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                logs,
                fontFamily = Mono, fontSize = 11.sp, color = Matrix, lineHeight = 15.sp
            )
            Text(
                "operator@frida:~$ ▋",
                fontFamily = Mono, fontSize = 11.sp,
                color = Matrix.copy(alpha = blink)
            )
        }
    }
}

private fun progressBar(p: Float): String {
    val total = 14
    val filled = (p * total).toInt().coerceIn(0, total)
    return "[" + "█".repeat(filled) + "░".repeat(total - filled) + "]"
}
