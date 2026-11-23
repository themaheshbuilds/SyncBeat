package com.syncbeats.app.network

import android.os.SystemClock
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SyncWebSocketServer(
    private val onClientReadyCallback: (String) -> Unit,
    private val onClientConnectedCallback: suspend (DefaultWebSocketServerSession) -> Unit
) {
    private data class ClientSession(
        val id: String,
        val session: DefaultWebSocketServerSession,
        val sendMutex: Mutex = Mutex(),
        var info: ConnectedClientInfo
    )

    private var server: CIOApplicationEngine? = null
    private val sessions = ConcurrentHashMap<String, ClientSession>()

    private val _clients = MutableStateFlow<List<ConnectedClientInfo>>(emptyList())
    val clients: StateFlow<List<ConnectedClientInfo>> = _clients.asStateFlow()

    fun start(port: Int = NetworkUtils.WS_PORT) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                webSocket("/sync") {
                    val tempId = UUID.randomUUID().toString()
                    val remoteIp = call.request.local.remoteHost
                    val clientSession = ClientSession(
                        id = tempId,
                        session = this,
                        info = ConnectedClientInfo(
                            clientId = tempId,
                            deviceName = "Android Device",
                            ipAddress = remoteIp
                        )
                    )
                    sessions[tempId] = clientSession
                    publishClients()
                    onClientConnectedCallback(this)

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val receiveTime = SystemClock.elapsedRealtime()
                            val msg = runCatching {
                                SyncJson.decodeFromString(SyncMessage.serializer(), frame.readText())
                            }.getOrNull() ?: continue

                            when (msg) {
                                is SyncMessage.Ping -> {
                                    val pong = SyncMessage.Pong(
                                        seq = msg.seq,
                                        t0 = msg.t0,
                                        t1 = receiveTime,
                                        t2 = SystemClock.elapsedRealtime()
                                    )
                                    sendToClient(clientSession, pong)
                                }
                                is SyncMessage.Hello -> {
                                    clientSession.info = clientSession.info.copy(
                                        clientId = msg.clientId,
                                        deviceName = msg.deviceName
                                    )
                                    publishClients()
                                }
                                is SyncMessage.ClientReady -> {
                                    clientSession.info = clientSession.info.copy(isReady = true)
                                    publishClients()
                                    onClientReadyCallback(msg.clientId)
                                }
                                is SyncMessage.ClientStats -> {
                                    clientSession.info = clientSession.info.copy(
                                        rttMs = msg.rttMs,
                                        driftMs = msg.driftMs,
                                        isReady = msg.isReady
                                    )
                                    publishClients()
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        sessions.remove(tempId)
                        publishClients()
                    }
                }
            }
        }.start(wait = false)
    }

    fun resetClientsReadyState() {
        sessions.values.forEach { it.info = it.info.copy(isReady = false) }
        publishClients()
    }

    fun areAllClientsReady(): Boolean {
        val all = sessions.values
        return all.isEmpty() || all.all { it.info.isReady }
    }

    suspend fun broadcast(message: SyncMessage) {
        val json = SyncJson.encodeToString(SyncMessage.serializer(), message)
        sessions.values.forEach { client ->
            runCatching {
                client.sendMutex.withLock {
                    client.session.send(Frame.Text(json))
                }
            }
        }
    }

    suspend fun sendToSession(session: DefaultWebSocketServerSession, message: SyncMessage) {
        val json = SyncJson.encodeToString(SyncMessage.serializer(), message)
        runCatching { session.send(Frame.Text(json)) }
    }

    private suspend fun sendToClient(client: ClientSession, message: SyncMessage) {
        val json = SyncJson.encodeToString(SyncMessage.serializer(), message)
        runCatching {
            client.sendMutex.withLock {
                client.session.send(Frame.Text(json))
            }
        }
    }

    private fun publishClients() {
        _clients.value = sessions.values.map { it.info }.sortedBy { it.deviceName }
    }

    fun stop() {
        sessions.clear()
        publishClients()
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
        server = null
    }
}
