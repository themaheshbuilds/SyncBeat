package com.syncbeats.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncbeats.app.service.SyncAudioService
import com.syncbeats.app.service.SyncServiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SyncViewModel : ViewModel() {

    private var service: SyncAudioService? = null
    private var collectorJob: Job? = null

    private val _uiState = MutableStateFlow(SyncServiceState())
    val uiState: StateFlow<SyncServiceState> = _uiState.asStateFlow()

    fun attachService(syncService: SyncAudioService) {
        service = syncService
        collectorJob?.cancel()
        collectorJob = viewModelScope.launch {
            syncService.state.collect { _uiState.value = it }
        }
    }

    fun detachService() {
        collectorJob?.cancel()
        service = null
    }

    fun startHost() = service?.startHostMode()
    fun startClient() = service?.startClientMode()
    fun loadTrack(uri: Uri) = service?.hostLoadTrack(uri)
    fun play() = service?.hostPlay()
    fun pause() = service?.hostPause()
    fun seekTo(posMs: Long) = service?.hostSeekTo(posMs)
    fun connectToHost(ip: String, port: Int) = service?.connectToHost(ip, port)
    fun setManualLatency(offsetMs: Long) = service?.setManualLatencyOffset(offsetMs)
    fun leaveParty() = service?.stopAllRoles()
}
