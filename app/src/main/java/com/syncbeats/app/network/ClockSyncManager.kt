package com.syncbeats.app.network

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class ClockSyncManager {

    data class SyncSample(val rttMs: Long, val offsetMs: Long)

    data class ClockSyncResult(
        val clockOffsetMs: Long = 0L,
        val medianRttMs: Long = 0L,
        val isSynced: Boolean = false
    )

    private val _syncResult = MutableStateFlow(ClockSyncResult())
    val syncResult: StateFlow<ClockSyncResult> = _syncResult.asStateFlow()

    private val pendingPings = ConcurrentHashMap<Int, CompletableDeferred<SyncSample>>()

    suspend fun performHandshake(
        pingCount: Int = 8,
        sendPing: suspend (SyncMessage.Ping) -> Unit
    ): ClockSyncResult {
        val samples = mutableListOf<SyncSample>()

        for (seq in 1..pingCount) {
            val deferred = CompletableDeferred<SyncSample>()
            pendingPings[seq] = deferred

            val t0 = SystemClock.elapsedRealtime()
            sendPing(SyncMessage.Ping(seq = seq, t0 = t0))

            val sample = withTimeoutOrNull(1500L) { deferred.await() }
            pendingPings.remove(seq)
            if (sample != null) {
                samples.add(sample)
            }
            delay(40L)
        }

        if (samples.isEmpty()) return _syncResult.value

        val sortedByRtt = samples.sortedBy { it.rttMs }
        val keepCount = (sortedByRtt.size * 3 / 4).coerceAtLeast(1)
        val filtered = sortedByRtt.take(keepCount)

        val medianOffset = filtered.map { it.offsetMs }.sorted().let { list ->
            val mid = list.size / 2
            if (list.size % 2 == 0) (list[mid - 1] + list[mid]) / 2 else list[mid]
        }
        val medianRtt = filtered.map { it.rttMs }.sorted()[filtered.size / 2]

        val result = ClockSyncResult(
            clockOffsetMs = medianOffset,
            medianRttMs = medianRtt,
            isSynced = true
        )
        _syncResult.value = result
        return result
    }

    fun onPongReceived(pong: SyncMessage.Pong) {
        val t3 = SystemClock.elapsedRealtime()
        val rtt = ((t3 - pong.t0) - (pong.t2 - pong.t1)).coerceAtLeast(0L)
        val offset = ((pong.t1 - pong.t0) + (pong.t2 - t3)) / 2L
        pendingPings[pong.seq]?.complete(SyncSample(rttMs = rtt, offsetMs = offset))
    }

    fun estimatedHostElapsedMs(): Long {
        return SystemClock.elapsedRealtime() + _syncResult.value.clockOffsetMs
    }

    fun reset() {
        pendingPings.values.forEach { it.cancel() }
        pendingPings.clear()
        _syncResult.value = ClockSyncResult()
    }
}
