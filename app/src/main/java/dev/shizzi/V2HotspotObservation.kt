package dev.shizzi

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/**
 * Conservative parser for shell-visible CURRENT SoftAP clients and DHCP
 * leases. A Wi-Fi client count alone is NOT proof of a specific device.
 *
 * Some Android vendors suppress the required MAC list. In that case this
 * adapter returns null (unsupported) rather than authorizing a stranger or
 * recording a phantom disconnect. Android's supported source of truth is
 * WifiManager.SoftApCallback + TetheringEventCallback; this is a diagnostic
 * fallback until privileged callbacks can be verified per OEM.
 */
internal object V2HotspotObservation {
    private val tetheredInterface = Regex(
        """^\s*([a-zA-Z0-9_.:-]+)\s+-\s+TetheredState\s+-""",
    )
    private val wifiClients = Regex(
        """(?:getConnectedClientList\(\):|Connected clients\s*:)\s*\[(.*)]""",
        RegexOption.IGNORE_CASE,
    )
    private val wifiMac = Regex(
        """(?:WifiClient\s*\{[^}]*?(?:mMacAddress\s*=\s*|macAddress\s*=\s*)|\b)([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""",
    )
    private val macPattern = Regex("""[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}""")
    private val tetheredClient = Regex(
        """TetheredClient\s*\{hwAddr\s+(""" + macPattern.pattern + """),\s*addresses\s+\[([^]]*)]""",
        RegexOption.IGNORE_CASE,
    )
    private val ipPattern = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")

    /** null = no authoritative current client list. Empty set = proven empty. */
    fun associatedMacs(wifiDump: String): Set<String>? {
        // Explicit present-tense list only. Do not scan old event logs or
        // infer identity from getConnectedClientList().size().
        val rows = wifiDump.lineSequence()
            .mapNotNull { line -> wifiClients.find(line)?.groupValues?.getOrNull(1) }
            .toList()
        if (rows.size != 1) return null // ambiguous/missing active SoftAP
        val row = rows.single()
        if (row.isBlank()) return emptySet()
        val parsed = wifiMac.findAll(row)
            .map { it.groupValues[1].lowercase(Locale.US) }
            .toSet()
        if (parsed.isEmpty() || !row.contains("WifiClient", ignoreCase = true)) return null
        return parsed
    }

    /** Known DHCP lease MAC+IP; not by itself proof of Wi-Fi association. */
    fun physicalLeases(tetheringDump: String): Map<String, String>? {
        if (!tetheringDump.contains("Client Information:", ignoreCase = true)) return null
        val result = linkedMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        for (match in tetheredClient.findAll(tetheringDump)) {
            val mac = match.groupValues[1].lowercase(Locale.US)
            val ip = ipPattern.findAll(match.groupValues[2])
                .mapNotNull { candidate ->
                    val address = runCatching {
                        InetAddress.getByName(candidate.value)
                    }.getOrNull()
                    (address as? Inet4Address)?.takeIf {
                        it.isSiteLocalAddress && !it.isLoopbackAddress
                    }?.hostAddress
                }
                .firstOrNull() ?: continue
            if (result[mac] != null && result[mac] != ip) {
                ambiguous += mac
            } else {
                result[mac] = ip
            }
        }
        ambiguous.forEach(result::remove)
        return result
    }

    /** Only trust a TetheredState interface name, never the upstream Wi-Fi. */
    fun gatewayAddress(
        tetheringDump: String,
        addressForInterface: (String) -> List<InetAddress>,
    ): String? {
        val names = tetheringDump.lineSequence()
            .mapNotNull { tetheredInterface.find(it)?.groupValues?.getOrNull(1) }
            .distinct()
            .toList()
            .filter { name ->
                name.startsWith("ap") || name.startsWith("wlan") ||
                    name.startsWith("swlan")
            }
        if (names.size != 1) return null
        val ips = addressForInterface(names.single()).filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
        return ips.singleOrNull()
    }

    fun systemGatewayFor(tetheringDump: String): String? =
        gatewayAddress(tetheringDump) { name ->
            val iface = NetworkInterface.getByName(name)
            iface?.inetAddresses?.toList().orEmpty()
        }
}
