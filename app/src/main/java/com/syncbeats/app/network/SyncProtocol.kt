package com.syncbeats.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
sealed interface SyncMessage {
    @Serializable
    @SerialName("HELLO")
    data class Hello(val clientId: String, val deviceName: String) : SyncMessage

    @Serializable
    @SerialName("PING")
    data class Ping(val seq: Int, val t0: Long) : SyncMessage

    @Serializable
    @SerialName("PONG")
    data class Pong(val seq: Int, val t0: Long, val t1: Long, val t2: Long) : SyncMessage

    @Serializable
    @SerialName("CLIENT_STATS")
    data class ClientStats(
        val clientId: String,
        val rttMs: Long,
        val driftMs: Long,
        val isReady: Boolean
    ) : SyncMessage

    @Serializable
    @SerialName("LOAD_TRACK")
    data class LoadTrack(
        val streamUrl: String,
        val title: String,
        val durationMs: Long
    ) : SyncMessage

    @Serializable
    @SerialName("CLIENT_READY")
    data class ClientReady(val clientId: String) : SyncMessage

    @Serializable
    @SerialName("PLAY_AT")
    data class PlayAt(
        val trackPositionMs: Long,
        val startAtHostElapsedMs: Long
    ) : SyncMessage

    @Serializable
    @SerialName("PAUSE")
    data class Pause(val trackPositionMs: Long) : SyncMessage

    @Serializable
    @SerialName("SEEK")
    data class Seek(
        val trackPositionMs: Long,
        val resumeAtHostElapsedMs: Long? = null
    ) : SyncMessage

    @Serializable
    @SerialName("SYNC_PULSE")
    data class SyncPulse(
        val trackPositionMs: Long,
        val hostTimestampMs: Long,
        val isPlaying: Boolean
    ) : SyncMessage
}

data class ConnectedClientInfo(
    val clientId: String,
    val deviceName: String,
    val ipAddress: String,
    val rttMs: Long = 0L,
    val driftMs: Long = 0L,
    val isReady: Boolean = false
)

data class DiscoveredHost(
    val serviceName: String,
    val hostIp: String,
    val wsPort: Int
)
