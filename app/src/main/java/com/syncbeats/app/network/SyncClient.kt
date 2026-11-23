package com.syncbeats.app.network

import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SyncClient(
    private val scope: CoroutineScope,
    val clockSyncManager: ClockSyncManager,
    private val onMessage: suspend (SyncMessage) -> Unit
) {
    enum class ConnectionState { DISCONNECTED, CONNECTING, SYNCING_CLOCK, CONNECTED, ERROR }

    val clientId: String = UUID.randomUUID().toString().take(8)
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var connectionJob: Job? = null
    private var activeSession: DefaultClientWebSocketSession? = null
    private val sendMutex = Mutex()

    fun connect(hostIp: String, wsPort: Int = NetworkUtils.WS_PORT) {
        disconnect()
        val normalizedIp = hostIp.trim()
        val isValidIpv4 = normalizedIp.split('.').let { octets ->
            octets.size == 4 && octets.all { octet ->
                octet.isNotEmpty() && octet.all(Char::isDigit) &&
                    octet.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
        if (!isValidIpv4) {
            _errorMessage.value = "Enter a valid host IPv4 address"
            _connectionState.value = ConnectionState.ERROR
            return
        }
        _errorMessage.value = null
        _connectionState.value = ConnectionState.CONNECTING

        connectionJob = scope.launch {
            try {
                httpClient.webSocket(host = normalizedIp, port = wsPort, path = "/sync") {
                    activeSession = this
                    sendMessage(SyncMessage.Hello(clientId = clientId, deviceName = deviceName))

                    val readerJob = launch {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val msg = runCatching {
                                SyncJson.decodeFromString(SyncMessage.serializer(), frame.readText())
                            }.getOrNull() ?: continue

                            if (msg is SyncMessage.Pong) {
                                clockSyncManager.onPongReceived(msg)
                            } else {
                                onMessage(msg)
                            }
                        }
                    }

                    _connectionState.value = ConnectionState.SYNCING_CLOCK
                    val syncRes = clockSyncManager.performHandshake(pingCount = 8) { ping ->
                        sendMessage(ping)
                    }
                    _connectionState.value = ConnectionState.CONNECTED
                    sendMessage(
                        SyncMessage.ClientStats(
                            clientId = clientId,
                            rttMs = syncRes.medianRttMs,
                            driftMs = 0L,
                            isReady = false
                        )
                    )

                    readerJob.join()
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connection failed"
                _connectionState.value = ConnectionState.ERROR
            } finally {
                activeSession = null
                if (_connectionState.value != ConnectionState.ERROR) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    suspend fun sendMessage(message: SyncMessage) {
        val session = activeSession ?: return
        val json = SyncJson.encodeToString(SyncMessage.serializer(), message)
        runCatching {
            sendMutex.withLock {
                session.send(Frame.Text(json))
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        activeSession = null
        clockSyncManager.reset()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun close() {
        disconnect()
        httpClient.close()
    }
}
