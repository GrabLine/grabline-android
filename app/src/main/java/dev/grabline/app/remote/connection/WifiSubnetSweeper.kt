package dev.grabline.app.remote.connection

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * PROTOCOL.md section 11 step 3: when no known address answers, try the other hosts of the phone's own subnet.
 * Uses the active Wi-Fi or Ethernet network's IPv4 address; networks wider than a /22 (1022 hosts) are not swept.
 * [ConnectionManager] runs it at most once per 60 s per PC, only while connections are wanted, 32 hosts at a time.
 */
class WifiSubnetSweeper(context: Context) : AddressSweeper {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override suspend fun candidates(knownAddresses: List<String>): List<String> {
        val cm = connectivity ?: return emptyList()
        return try {
            val network = cm.activeNetwork ?: return emptyList()
            val caps = cm.getNetworkCapabilities(network) ?: return emptyList()
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return emptyList()
            }
            val link = cm.getLinkProperties(network) ?: return emptyList()
            val own = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return emptyList()
            SubnetMath.hosts(own.address.address, own.prefixLength)
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}

/** Address arithmetic for the sweep, kept free of Android types so it can be unit tested. */
object SubnetMath {
    const val MIN_PREFIX = 22

    /**
     * Every host address of [ownAddress]/[prefixLength] except the phone's own, as dotted decimal.
     * The network and broadcast addresses are left out (except in /31, where both are hosts).
     * Empty for an address that is not 4 bytes, a prefix below [MIN_PREFIX] (too many hosts) or a /32.
     */
    fun hosts(ownAddress: ByteArray, prefixLength: Int): List<String> {
        if (ownAddress.size != 4 || prefixLength < MIN_PREFIX || prefixLength > 32) return emptyList()
        val own = ownAddress.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val hostBits = 32 - prefixLength
        val size = 1L shl hostBits
        val network = own and (size - 1).inv() and 0xffffffffL
        val first = if (hostBits >= 2) network + 1 else network
        val last = if (hostBits >= 2) network + size - 2 else network + size - 1
        val result = ArrayList<String>()
        for (a in first..last) if (a != own) result += dotted(a)
        return result
    }

    private fun dotted(a: Long) = "${(a shr 24) and 0xff}.${(a shr 16) and 0xff}.${(a shr 8) and 0xff}.${a and 0xff}"
}
