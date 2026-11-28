package com.syncbeats.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.Executors

class NsdDiscoveryManager(context: Context) {

    companion object {
        private const val SERVICE_TYPE = "_syncbeats._tcp."
        private const val SERVICE_NAME_PREFIX = "SyncBeatsParty"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun registerHostService(port: Int = NetworkUtils.WS_PORT) {
        unregisterHostService()
        acquireMulticastLock()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX-${Build.MODEL.take(10)}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun unregisterHostService() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        releaseMulticastLock()
    }

    fun startDiscovery() {
        stopDiscovery()
        acquireMulticastLock()
        _discoveredHosts.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_syncbeats")) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discoveredHosts.value = _discoveredHosts.value.filterNot {
                    it.serviceName == serviceInfo.serviceName
                }
            }
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                nsdManager.registerServiceInfoCallback(
                    serviceInfo,
                    resolveExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                        override fun onServiceUpdated(updated: NsdServiceInfo) {
                            val ip = updated.hostAddresses
                                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                                ?.hostAddress ?: return
                            addHost(DiscoveredHost(updated.serviceName, ip, updated.port))
                        }
                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val address = resolved.host as? Inet4Address ?: return
                        val ip = address.hostAddress ?: return
                        addHost(DiscoveredHost(resolved.serviceName, ip, resolved.port))
                    }
                })
            }
        }
    }

    private fun addHost(host: DiscoveredHost) {
        val current = _discoveredHosts.value.filterNot { it.hostIp == host.hostIp }
        _discoveredHosts.value = current + host
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("SyncBeats:MulticastLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null
    }
}
