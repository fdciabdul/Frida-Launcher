package com.taqin.frialauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch

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
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF0F0F23),
                                    Color(0xFF1A1A2E)
                                )
                            )
                        )
                ) {
                    FridaLauncherScreen(
                        fridaManager = fridaManager,
                        modifier = Modifier.fillMaxSize()
                    )
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
    var status by remember { mutableStateOf("SYSTEM READY") }
    var isRunning by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("27042") }
    var downloadProgress by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadingFile by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(">> Frida Launcher v1.0 initialized\n>> Awaiting commands...") }
    val coroutineScope = rememberCoroutineScope()

    val neonBlue = Color(0xFF64FFDA)
    val neonPurple = Color(0xFFBB86FC)
    val darkCard = Color(0xFF16213E)
    val darkerCard = Color(0xFF0E1628)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Status

        // Control Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = darkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "CONTROL PANEL",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = neonBlue
                )

                // Port Input
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = {
                        Text(
                            "PORT",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = neonBlue,
                        unfocusedBorderColor = Color(0xFF37474F),
                        focusedLabelColor = neonBlue,
                        unfocusedLabelColor = Color(0xFF78909C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color(0xFFB0BEC5)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                )

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isDownloading = true
                                status = "DOWNLOADING..."
                                logs = ">> Initiating download sequence\n$logs"
                                val success = fridaManager.downloadLatestFrida(
                                    onProgress = { progress ->
                                        downloadProgress = progress
                                    },
                                    onFileInfo = { fileName ->
                                        downloadingFile = fileName
                                        logs = ">> Downloading: $fileName\n$logs"
                                    }
                                )
                                isDownloading = false
                                downloadingFile = ""
                                if (success) {
                                    status = "DOWNLOAD COMPLETE"
                                    logs = ">> Download completed successfully\n$logs"
                                } else {
                                    status = "DOWNLOAD FAILED"
                                    logs = ">> ERROR: Download failed\n$logs"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isDownloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = neonPurple,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "DOWNLOAD",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                if (isRunning) {
                                    fridaManager.stopFrida()
                                    isRunning = false
                                    status = "SERVER STOPPED"
                                    logs = ">> Server terminated\n$logs"
                                } else {
                                    status = "INITIALIZING..."
                                    logs = ">> Starting server on port $port\n$logs"
                                    val success = fridaManager.startFrida(port.toIntOrNull() ?: 27042)
                                    if (success) {
                                        isRunning = true
                                        status = "SERVER ACTIVE :$port"
                                        logs = ">> Server online - Port $port\n$logs"
                                    } else {
                                        status = "START FAILED"
                                        logs = ">> ERROR: Failed to start server\n$logs"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Color(0xFFF44336) else neonBlue,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            if (isRunning) "STOP" else "START",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Download Progress
        if (isDownloading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = darkerCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "DOWNLOAD PROGRESS",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = neonPurple
                    )
                    if (downloadingFile.isNotEmpty()) {
                        Text(
                            text = "FILE: $downloadingFile",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF78909C)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = neonBlue,
                        trackColor = Color(0xFF263238)
                    )
                    Text(
                        text = "${(downloadProgress * 100).toInt()}% COMPLETE",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = neonBlue
                    )
                }
            }
        }

        // System Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = darkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "ARCH",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = neonBlue
                    )
                    Text(
                        text = fridaManager.getArchitecture(),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = darkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "ROOT",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = neonBlue
                    )
                    Text(
                        text = if (fridaManager.isRooted()) "GRANTED" else "DENIED",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (fridaManager.isRooted()) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                }
            }
        }

        // Terminal
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = darkerCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TERMINAL",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = neonBlue
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val processLogs = fridaManager.getLogOutput()
                                    logs = if (processLogs.isNotEmpty()) {
                                        ">> Process output refreshed\n$processLogs\n$logs"
                                    } else {
                                        ">> No process output\n$logs"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = neonPurple
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "REFRESH",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Button(
                            onClick = {
                                logs = ">> Terminal cleared\n>> Frida Launcher ready"
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color(0xFFF44336)
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "CLEAR",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0A0A))
                        .border(
                            1.dp,
                            neonBlue.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                ) {
                    val scrollState = rememberScrollState()

                    LaunchedEffect(logs) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }

                    Text(
                        text = logs,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .verticalScroll(scrollState),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = neonBlue,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}