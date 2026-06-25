package com.p2p.monitor.util

import java.net.Inet4Address
import java.net.NetworkInterface

object IpUtils {
    private val VPN_PREFIXES = listOf(
        "100.",   // Tailscale
        "10.3.",  // ZeroTier common range
        "172.28.", // ZeroTier common range
        "198.18.", // ZeroTier common range
    )

    fun getTailscaleIp(): String? {
        return getVpnIp()
    }

    fun getVpnIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        if (VPN_PREFIXES.any { ip.startsWith(it) }) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("Error getting VPN IP", e)
        }
        return null
    }

    fun getAllIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        address.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("Error getting IP addresses", e)
        }
        return ips
    }
}
