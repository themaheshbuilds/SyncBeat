package com.syncbeats.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.syncbeats.app.network.AudioHttpServer
import com.syncbeats.app.network.ClockSyncManager
import com.syncbeats.app.network.ConnectedClientInfo
import com.syncbeats.app.network.DiscoveredHost
import com.syncbeats.app.network.NetworkUtils
import com.syncbeats.app.network.NsdDiscoveryManager
import com.syncbeats.app.network.SyncClient
import com.syncbeats.app.network.SyncMessage
import com.syncbeats.app.network.SyncWebSocketServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class PartyRole { NONE, HOST, CLIENT }

data class SyncServiceState(
    val role: PartyRole = PartyRole.NONE,
    val hostIp: String = "",
    val wsPort: Int = NetworkUtils.WS_PORT,
    val trackTitle: String = "No track selected",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlayerReady: Boolean = false,
    val connectedClients: List<ConnectedClientInfo> = emptyList(),
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val clientConnectionState: SyncClient.ConnectionState = SyncClient.ConnectionState.DISCONNECTED,
    val clientError: String? = null,
    val liveDriftMs: Long = 0L,
    val medianRttMs: Long = 0L,
    val clockOffsetMs: Long = 0L,
    val manualLatencyOffsetMs: Long = 0L,
    val playbackSpeed: Float = 1.0f
)

class SyncAudioService : Service() {

    companion object {
        private const val CHANNEL_ID = "syncbeats_playback_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SCHEDULE_LEAD_MS = 1500L
        private const val SYNC_PULSE_INTERVAL_MS = 2000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): SyncAudioService = this@SyncAudioService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var audioHttpServer: AudioHttpServer
    private lateinit var wsServer: SyncWebSocketServer
    private lateinit var nsdManager: NsdDiscoveryManager
    private lateinit var clockSyncManager: ClockSyncManager
    private lateinit var syncClient: SyncClient

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var currentStreamUrl: String? = null
    private var pendingHostPlayPositionMs: Long? = null
    private var scheduledPlayRunnable: Runnable? = null
    private var hostPulseJob: Job? = null
    private var uiTickerJob: Job? = null
    private var speedResetJob: Job? = null

    private val _state = MutableStateFlow(SyncServiceState())
    val state: StateFlow<SyncServiceState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        initPlayer()
        initNetworking()
        startUiPositionTicker()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground("SyncBeats active")
        return START_STICKY
    }

    private fun promoteToForeground(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SyncBeats Audio Sync",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncBeats Party")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SyncBeats::SyncWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(lockMode, "SyncBeats::LowLatencyWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val ready = playbackState == Player.STATE_READY
                    _state.update {
                        it.copy(
                            isPlayerReady = ready,
                            durationMs = duration.coerceAtLeast(0L)
                        )
                    }
                    if (ready && _state.value.role == PartyRole.CLIENT) {
                        serviceScope.launch {
                            syncClient.sendMessage(SyncMessage.ClientReady(syncClient.clientId))
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }
            })
        }
    }

    private fun initNetworking() {
        audioHttpServer = AudioHttpServer(this)
        nsdManager = NsdDiscoveryManager(this)
        clockSyncManager = ClockSyncManager()

        wsServer = SyncWebSocketServer(
            onClientReadyCallback = {
                val pendingPos = pendingHostPlayPositionMs
                if (pendingPos != null && wsServer.areAllClientsReady()) {
                    pendingHostPlayPositionMs = null
                    serviceScope.launch { executeHostPlayAt(pendingPos) }
                }
            },
            onClientConnectedCallback = { session ->
                val url = currentStreamUrl
                if (url != null) {
                    wsServer.sendToSession(
                        session,
                        SyncMessage.LoadTrack(
                            streamUrl = url,
                            title = _state.value.trackTitle,
                            durationMs = _state.value.durationMs
                        )
                    )
                }
            }
        )

        syncClient = SyncClient(
            scope = serviceScope,
            clockSyncManager = clockSyncManager,
            onMessage = { msg -> handleClientMessage(msg) }
        )

        serviceScope.launch {
            wsServer.clients.collect { list ->
                _state.update { it.copy(connectedClients = list) }
            }
        }
        serviceScope.launch {
            nsdManager.discoveredHosts.collect { hosts ->
                _state.update { it.copy(discoveredHosts = hosts) }
            }
        }
        serviceScope.launch {
            syncClient.connectionState.collect { connState ->
                _state.update { it.copy(clientConnectionState = connState) }
            }
        }
        serviceScope.launch {
            syncClient.errorMessage.collect { err ->
                _state.update { it.copy(clientError = err) }
            }
        }
        serviceScope.launch {
            clockSyncManager.syncResult.collect { sync ->
                _state.update {
                    it.copy(
                        medianRttMs = sync.medianRttMs,
                        clockOffsetMs = sync.clockOffsetMs
                    )
                }
            }
        }
    }

    private fun startUiPositionTicker() {
        uiTickerJob = serviceScope.launch {
            while (isActive) {
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    _state.update {
                        it.copy(
                            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                            durationMs = exoPlayer.duration.coerceAtLeast(0L)
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    fun startHostMode() {
        stopAllRoles()
        val ip = NetworkUtils.getLocalIpAddress()
        audioHttpServer.start(NetworkUtils.HTTP_PORT)
        wsServer.start(NetworkUtils.WS_PORT)
        nsdManager.registerHostService(NetworkUtils.WS_PORT)

        _state.update {
            it.copy(
                role = PartyRole.HOST,
                hostIp = ip,
                wsPort = NetworkUtils.WS_PORT
            )
        }
        promoteToForeground("Hosting on $ip:${NetworkUtils.WS_PORT}")
        startHostSyncPulseLoop()
    }

    fun hostLoadTrack(uri: Uri) {
        val (title, _) = audioHttpServer.setAudioTrack(uri)
        val ip = NetworkUtils.getLocalIpAddress()
        val streamUrl = "http://$ip:${NetworkUtils.HTTP_PORT}/stream?v=${System.currentTimeMillis()}"
        currentStreamUrl = streamUrl
        pendingHostPlayPositionMs = null

        cancelScheduledPlay()
        wsServer.resetClientsReadyState()

        exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.playWhenReady = false
        exoPlayer.prepare()

        _state.update { it.copy(hostIp = ip, trackTitle = title, isPlaying = false) }

        serviceScope.launch {
            wsServer.broadcast(
                SyncMessage.LoadTrack(
                    streamUrl = streamUrl,
                    title = title,
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                )
            )
        }
    }

    fun hostPlay() {
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        if (!wsServer.areAllClientsReady()) {
            pendingHostPlayPositionMs = pos
            serviceScope.launch {
                delay(3000L)
                if (pendingHostPlayPositionMs != null) {
                    val p = pendingHostPlayPositionMs!!
                    pendingHostPlayPositionMs = null
                    executeHostPlayAt(p)
                }
            }
            return
        }
        serviceScope.launch { executeHostPlayAt(pos) }
    }

    private suspend fun executeHostPlayAt(positionMs: Long) {
        val startAtHostElapsed = SystemClock.elapsedRealtime() + SCHEDULE_LEAD_MS
        wsServer.broadcast(
            SyncMessage.PlayAt(
                trackPositionMs = positionMs,
                startAtHostElapsedMs = startAtHostElapsed
            )
        )
        scheduleLocalPlayAt(
            trackPositionMs = positionMs,
            startAtHostElapsedMs = startAtHostElapsed,
            clockOffsetMs = 0L
        )
    }

    fun hostPause() {
        cancelScheduledPlay()
        pendingHostPlayPositionMs = null
        exoPlayer.pause()
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        serviceScope.launch {
            wsServer.broadcast(SyncMessage.Pause(trackPositionMs = pos))
        }
    }

    fun hostSeekTo(positionMs: Long) {
        val wasPlaying = exoPlayer.isPlaying
        cancelScheduledPlay()
        exoPlayer.seekTo(positionMs)
        serviceScope.launch {
            if (wasPlaying) {
                val resumeAt = SystemClock.elapsedRealtime() + SCHEDULE_LEAD_MS
                wsServer.broadcast(
                    SyncMessage.Seek(
                        trackPositionMs = positionMs,
                        resumeAtHostElapsedMs = resumeAt
                    )
                )
                scheduleLocalPlayAt(positionMs, resumeAt, clockOffsetMs = 0L)
            } else {
                wsServer.broadcast(SyncMessage.Seek(trackPositionMs = positionMs, resumeAtHostElapsedMs = null))
            }
        }
    }

    private fun startHostSyncPulseLoop() {
        hostPulseJob?.cancel()
        hostPulseJob = serviceScope.launch {
            while (isActive) {
                delay(SYNC_PULSE_INTERVAL_MS)
                if (exoPlayer.isPlaying) {
                    val pulse = SyncMessage.SyncPulse(
                        trackPositionMs = exoPlayer.currentPosition,
                        hostTimestampMs = SystemClock.elapsedRealtime(),
                        isPlaying = true
                    )
                    wsServer.broadcast(pulse)
                }
            }
        }
    }

    fun startClientMode() {
        stopAllRoles()
        _state.update { it.copy(role = PartyRole.CLIENT) }
        nsdManager.startDiscovery()
        promoteToForeground("Searching for SyncBeats Host")
    }

    fun connectToHost(ip: String, port: Int = NetworkUtils.WS_PORT) {
        _state.update { it.copy(hostIp = ip, wsPort = port) }
        syncClient.connect(ip, port)
        promoteToForeground("Connected to $ip")
    }

    fun setManualLatencyOffset(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-200L, 200L)
        _state.update { it.copy(manualLatencyOffsetMs = clamped) }
    }

    private suspend fun handleClientMessage(msg: SyncMessage) = withContext(Dispatchers.Main.immediate) {
        when (msg) {
            is SyncMessage.LoadTrack -> {
                cancelScheduledPlay()
                speedResetJob?.cancel()
                exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
                exoPlayer.setMediaItem(MediaItem.fromUri(msg.streamUrl))
                exoPlayer.playWhenReady = false
                exoPlayer.prepare()
                _state.update {
                    it.copy(
                        trackTitle = msg.title,
                        durationMs = msg.durationMs,
                        playbackSpeed = 1.0f,
                        liveDriftMs = 0L
                    )
                }
            }

            is SyncMessage.PlayAt -> {
                scheduleLocalPlayAt(
                    trackPositionMs = msg.trackPositionMs,
                    startAtHostElapsedMs = msg.startAtHostElapsedMs,
                    clockOffsetMs = clockSyncManager.syncResult.value.clockOffsetMs
                )
            }

            is SyncMessage.Pause -> {
                cancelScheduledPlay()
                speedResetJob?.cancel()
                exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
                exoPlayer.pause()
                exoPlayer.seekTo(msg.trackPositionMs)
                _state.update { it.copy(playbackSpeed = 1.0f) }
            }

            is SyncMessage.Seek -> {
                cancelScheduledPlay()
                speedResetJob?.cancel()
                exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
                if (msg.resumeAtHostElapsedMs != null) {
                    scheduleLocalPlayAt(
                        trackPositionMs = msg.trackPositionMs,
                        startAtHostElapsedMs = msg.resumeAtHostElapsedMs,
                        clockOffsetMs = clockSyncManager.syncResult.value.clockOffsetMs
                    )
                } else {
                    exoPlayer.pause()
                    exoPlayer.seekTo(msg.trackPositionMs)
                }
            }

            is SyncMessage.SyncPulse -> {
                if (msg.isPlaying && exoPlayer.isPlaying) {
                    applyMicroDriftCorrection(msg)
                }
            }

            else -> Unit
        }
    }

    private fun scheduleLocalPlayAt(
        trackPositionMs: Long,
        startAtHostElapsedMs: Long,
        clockOffsetMs: Long
    ) {
        cancelScheduledPlay()
        speedResetJob?.cancel()
        exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
        exoPlayer.pause()

        val localNow = SystemClock.elapsedRealtime()
        val manualOffset = _state.value.manualLatencyOffsetMs
        val delayMs = (startAtHostElapsedMs - (localNow + clockOffsetMs)) + manualOffset

        if (delayMs <= 0L) {
            val catchUpPos = (trackPositionMs + abs(delayMs)).coerceAtLeast(0L)
            exoPlayer.seekTo(catchUpPos)
            exoPlayer.play()
            return
        }

        exoPlayer.seekTo(trackPositionMs)
        val runnable = Runnable {
            exoPlayer.play()
            scheduledPlayRunnable = null
        }
        scheduledPlayRunnable = runnable
        val targetUptimeMs = SystemClock.uptimeMillis() + delayMs
        mainHandler.postAtTime(runnable, targetUptimeMs)
    }

    private fun applyMicroDriftCorrection(pulse: SyncMessage.SyncPulse) {
        val estimatedHostNow = clockSyncManager.estimatedHostElapsedMs()
        val manualOffset = _state.value.manualLatencyOffsetMs
        val expectedPos = pulse.trackPositionMs + (estimatedHostNow - pulse.hostTimestampMs) - manualOffset
        val actualPos = exoPlayer.currentPosition
        val drift = expectedPos - actualPos
        val absDrift = abs(drift)

        _state.update { it.copy(liveDriftMs = drift) }

        serviceScope.launch {
            syncClient.sendMessage(
                SyncMessage.ClientStats(
                    clientId = syncClient.clientId,
                    rttMs = clockSyncManager.syncResult.value.medianRttMs,
                    driftMs = drift,
                    isReady = exoPlayer.playbackState == Player.STATE_READY
                )
            )
        }

        when {
            absDrift > 120L -> {
                speedResetJob?.cancel()
                exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
                exoPlayer.seekTo(expectedPos.coerceAtLeast(0L))
                _state.update { it.copy(playbackSpeed = 1.0f) }
            }

            absDrift > 15L -> {
                val targetSpeed = if (drift > 0) 1.03f else 0.97f
                exoPlayer.setPlaybackParameters(PlaybackParameters(targetSpeed))
                _state.update { it.copy(playbackSpeed = targetSpeed) }

                val correctionNeededMs = (absDrift - 4L).coerceAtLeast(0L)
                val durationToResetMs = (correctionNeededMs / 0.03f).toLong().coerceIn(50L, 1900L)

                speedResetJob?.cancel()
                speedResetJob = serviceScope.launch {
                    delay(durationToResetMs)
                    exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
                    _state.update { it.copy(playbackSpeed = 1.0f) }
                }
            }

            absDrift <= 5L -> {
                speedResetJob?.cancel()
                if (_state.value.playbackSpeed != 1.0f) {
                    exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
                    _state.update { it.copy(playbackSpeed = 1.0f) }
                }
            }
        }
    }

    private fun cancelScheduledPlay() {
        scheduledPlayRunnable?.let { mainHandler.removeCallbacks(it) }
        scheduledPlayRunnable = null
    }

    fun stopAllRoles() {
        cancelScheduledPlay()
        hostPulseJob?.cancel()
        speedResetJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
        audioHttpServer.stop()
        wsServer.stop()
        nsdManager.unregisterHostService()
        nsdManager.stopDiscovery()
        syncClient.disconnect()
        currentStreamUrl = null
        pendingHostPlayPositionMs = null
        _state.value = SyncServiceState()
    }

    override fun onDestroy() {
        stopAllRoles()
        syncClient.close()
        exoPlayer.release()
        uiTickerJob?.cancel()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }
}
