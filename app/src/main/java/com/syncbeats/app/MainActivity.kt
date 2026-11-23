package com.syncbeats.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syncbeats.app.network.NetworkUtils
import com.syncbeats.app.service.PartyRole
import com.syncbeats.app.service.SyncAudioService
import com.syncbeats.app.service.SyncServiceState
import com.syncbeats.app.ui.SyncViewModel
import kotlin.math.abs
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private val viewModel: SyncViewModel by viewModels()
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as SyncAudioService.LocalBinder).getService()
            viewModel.attachService(service)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.detachService()
            isBound = false
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val nearbyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val serviceIntent = Intent(this, SyncAudioService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncBeatsApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun SyncBeatsApp(viewModel: SyncViewModel) {
    val state by viewModel.uiState.collectAsState()

    when (state.role) {
        PartyRole.NONE -> HomeScreen(
            onHostClick = { viewModel.startHost() },
            onJoinClick = { viewModel.startClient() }
        )
        PartyRole.HOST -> HostScreen(
            state = state,
            onPickFile = { viewModel.loadTrack(it) },
            onPlay = { viewModel.play() },
            onPause = { viewModel.pause() },
            onSeek = { viewModel.seekTo(it) },
            onBack = { viewModel.leaveParty() }
        )
        PartyRole.CLIENT -> ClientScreen(
            state = state,
            onConnect = { ip, port -> viewModel.connectToHost(ip, port) },
            onLatencyChange = { viewModel.setManualLatency(it) },
            onBack = { viewModel.leaveParty() }
        )
    }
}

@Composable
fun HomeScreen(
    onHostClick: () -> Unit,
    onJoinClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.SpeakerGroup,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "SyncBeats LAN",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Offline Multi-Device Audio Sync (<30ms)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))

        Button(
            onClick = onHostClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.SpeakerGroup, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("Host a Party")
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onJoinClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Headphones, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("Join a Party")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    state: SyncServiceState,
    onPickFile: (android.net.Uri) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit
) {
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPickFile(uri)
    }

    var sliderPosition by remember(state.currentPositionMs) {
        mutableFloatStateOf(state.currentPositionMs.toFloat())
    }
    var isDragging by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Host Party") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Manual Connect IP (Wi-Fi / Hotspot)", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = "${state.hostIp}:${state.wsPort}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Now Playing", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = state.trackTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                        }
                        FilledTonalButton(
                            onClick = { filePicker.launch(arrayOf("audio/*")) }
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Pick Audio")
                        }
                    }

                    val maxDur = state.durationMs.coerceAtLeast(1L).toFloat()
                    Slider(
                        value = if (isDragging) sliderPosition else state.currentPositionMs.toFloat().coerceIn(0f, maxDur),
                        onValueChange = {
                            isDragging = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            onSeek(sliderPosition.roundToLong())
                        },
                        valueRange = 0f..maxDur,
                        enabled = state.isPlayerReady
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(state.currentPositionMs), style = MaterialTheme.typography.labelSmall)
                        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        FilledIconButton(
                            onClick = { if (state.isPlaying) onPause() else onPlay() },
                            enabled = state.isPlayerReady,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = "Connected Clients (${state.connectedClients.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(state.connectedClients, key = { it.clientId }) { client ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(client.deviceName, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "RTT: ${client.rttMs}ms • Drift: ${client.driftMs}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(if (client.isReady) "READY" else "BUFFERING") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    state: SyncServiceState,
    onConnect: (String, Int) -> Unit,
    onLatencyChange: (Long) -> Unit,
    onBack: () -> Unit
) {
    var manualIp by remember { mutableStateOf("192.168.43.1") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join Party") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text("Host IP Address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onConnect(manualIp, NetworkUtils.WS_PORT) },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("Connect")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Status: ${state.clientConnectionState.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    state.clientError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Track: ${state.trackTitle}", style = MaterialTheme.typography.bodyMedium)

                    val driftColor = when {
                        abs(state.liveDriftMs) <= 15L -> Color(0xFF4CAF50)
                        abs(state.liveDriftMs) <= 50L -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Drift: ${state.liveDriftMs}ms",
                            color = driftColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text("RTT: ${state.medianRttMs}ms")
                        Text("Speed: ${state.playbackSpeed}x")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Hardware Latency Offset", fontWeight = FontWeight.Medium)
                        Text(
                            text = "${if (state.manualLatencyOffsetMs >= 0) "+" else ""}${state.manualLatencyOffsetMs} ms",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = state.manualLatencyOffsetMs.toFloat(),
                        onValueChange = { onLatencyChange(it.roundToLong()) },
                        valueRange = -200f..200f,
                        steps = 79
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("-200ms (Earlier)", style = MaterialTheme.typography.labelSmall)
                        Text("0ms", style = MaterialTheme.typography.labelSmall)
                        Text("+200ms (Later)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text(
                text = "Auto-Discovered Hosts (${state.discoveredHosts.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(state.discoveredHosts, key = { it.hostIp }) { host ->
                    Card(
                        onClick = { onConnect(host.hostIp, host.wsPort) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(host.serviceName, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${host.hostIp}:${host.wsPort}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("JOIN", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
