package com.example.sip.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Status of the MCPTT APN network binding.
 * Conforms to MCPTT-APN-SETUP.md (Path 2: Dedicated mcptt APN type=default).
 */
sealed class ApnNetworkStatus {
    data object Scanning : ApnNetworkStatus()
    data class Bound(val ip: String, val ifaceName: String, val apnName: String) : ApnNetworkStatus()
    data class NoMcpttPdn(val detectedIp: String?, val expectedPrefix: String, val reason: String) : ApnNetworkStatus()
    data object Disconnected : ApnNetworkStatus()

    fun displaySummary(): String = when (this) {
        is Bound -> "bound ($ip) [$apnName]"
        is NoMcpttPdn -> "No mcptt PDN (detected ${detectedIp ?: "none"}, expected $expectedPrefix)"
        is Scanning -> "Scanning networks for mcptt PDN..."
        is Disconnected -> "Cellular Disconnected"
    }
}

class McpttApnNetworkManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkStatus = MutableStateFlow<ApnNetworkStatus>(ApnNetworkStatus.Scanning)
    val networkStatus: StateFlow<ApnNetworkStatus> = _networkStatus.asStateFlow()

    var activeNetwork: Network? = null
        private set
    var boundIp: String = "192.168.102.6"
        private set

    private var configuredApnPrefix: String = "192.168.102."
    private var configuredApnName: String = "mcptt"

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun updateConfig(apnName: String, apnPrefix: String) {
        this.configuredApnName = apnName.ifBlank { "mcptt" }
        this.configuredApnPrefix = apnPrefix.ifBlank { "192.168.102." }
        refreshNetworkBinding()
    }

    fun startMonitoring() {
        if (cm == null) {
            _networkStatus.value = ApnNetworkStatus.Disconnected
            return
        }

        try {
            val request = NetworkRequest.Builder().build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    scope.launch { refreshNetworkBinding() }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    if (activeNetwork == network) {
                        activeNetwork = null
                    }
                    scope.launch { refreshNetworkBinding() }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                    Log.d(TAG, "Link properties changed for network $network: ${linkProperties.interfaceName}")
                    scope.launch { refreshNetworkBinding() }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    scope.launch { refreshNetworkBinding() }
                }
            }

            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}", e)
        }

        refreshNetworkBinding()
    }

    fun stopMonitoring() {
        networkCallback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    /**
     * Attempts to find the network matching the configured MCPTT APN IPv4 prefix.
     * Falls back gracefully to cellular mobile data interface or best IPv4 candidate.
     */
    @Synchronized
    fun refreshNetworkBinding() {
        if (cm == null) {
            _networkStatus.value = ApnNetworkStatus.Disconnected
            return
        }

        try {
            var matchedNetwork: Network? = null
            var matchedIp: String? = null
            var matchedIface = "unknown"
            var fallbackCellularNetwork: Network? = null
            var fallbackCellularIp: String? = null
            var fallbackCellularIface = "unknown"

            val networks = cm.allNetworks
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val linkProps = cm.getLinkProperties(net)
                val ifName = linkProps?.interfaceName ?: "unknown"

                linkProps?.linkAddresses?.forEach { linkAddr ->
                    val addr = linkAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostIp = addr.hostAddress ?: ""
                        if (hostIp.startsWith(configuredApnPrefix)) {
                            matchedNetwork = net
                            matchedIp = hostIp
                            matchedIface = ifName
                        } else if (isCellular && fallbackCellularIp == null) {
                            fallbackCellularNetwork = net
                            fallbackCellularIp = hostIp
                            fallbackCellularIface = ifName
                        }
                    }
                }
            }

            if (matchedNetwork != null && matchedIp != null) {
                activeNetwork = matchedNetwork
                boundIp = matchedIp!!
                _networkStatus.value = ApnNetworkStatus.Bound(
                    ip = boundIp,
                    ifaceName = matchedIface,
                    apnName = configuredApnName
                )
                Log.i(TAG, "Successfully bound to MCPTT APN network: $boundIp ($matchedIface) [$configuredApnName]")
            } else if (fallbackCellularNetwork != null && fallbackCellularIp != null) {
                // Cellular is active, but IP does not match expected prefix (e.g. 192.168.100.x instead of 192.168.102.x)
                activeNetwork = fallbackCellularNetwork
                boundIp = fallbackCellularIp!!
                _networkStatus.value = ApnNetworkStatus.NoMcpttPdn(
                    detectedIp = fallbackCellularIp,
                    expectedPrefix = configuredApnPrefix,
                    reason = "Preferred APN may be set to internet (192.168.100.x) instead of mcptt ($configuredApnPrefix)"
                )
                Log.w(TAG, "Cellular active but IP is $fallbackCellularIp, expected prefix $configuredApnPrefix")
            } else {
                // Check if running on emulator / WiFi / TUN interface
                val localSysIp = findLocalIpv4Matching(configuredApnPrefix)
                if (localSysIp != null) {
                    boundIp = localSysIp
                    _networkStatus.value = ApnNetworkStatus.Bound(
                        ip = boundIp,
                        ifaceName = "local",
                        apnName = configuredApnName
                    )
                } else {
                    val anyIpv4 = findFirstNonLoopbackIpv4()
                    if (anyIpv4 != null) {
                        boundIp = anyIpv4
                        _networkStatus.value = ApnNetworkStatus.NoMcpttPdn(
                            detectedIp = anyIpv4,
                            expectedPrefix = configuredApnPrefix,
                            reason = "No cellular network with prefix $configuredApnPrefix found"
                        )
                    } else {
                        _networkStatus.value = ApnNetworkStatus.Disconnected
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing network binding: ${e.message}", e)
        }
    }

    /**
     * Binds a DatagramSocket directly to the MCPTT APN network interface.
     * Prevents kernel from routing MCPTT SIP/RTP packets over the wrong PDN (e.g. internet or Wi-Fi).
     */
    fun bindSocket(socket: DatagramSocket) {
        val net = activeNetwork
        if (net != null) {
            try {
                net.bindSocket(socket)
                Log.i(TAG, "Bound DatagramSocket (${socket.localAddress?.hostAddress ?: "unbound"}:${socket.localPort}) to Network $net")
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind socket to network $net: ${e.message}")
            }
        }
    }

    private fun findLocalIpv4Matching(prefix: String): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith(prefix)) return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying network interfaces: ${e.message}")
        }
        return null
    }

    private fun findFirstNonLoopbackIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying network interfaces: ${e.message}")
        }
        return null
    }

    companion object {
        private const val TAG = "McpttApnNetMgr"
    }
}
