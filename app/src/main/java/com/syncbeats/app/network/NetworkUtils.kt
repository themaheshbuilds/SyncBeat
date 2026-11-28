package com.syncbeats.app.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    const val HTTP_PORT = 8080
    const val WS_PORT = 8081

    fun getLocalIpAddress(): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val priority = when {
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("wlan") -> 2
                    name.startsWith("eth") -> 3
                    else -> continue
                }
                for (addr in intf.inetAddresses.toList()) {
                    if (addr is Inet4Address &&
                        !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        addr.isSiteLocalAddress
                    ) {
                        addr.hostAddress?.let { ip ->
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return candidates.minByOrNull { it.first }?.second.orEmpty()
    }
}
