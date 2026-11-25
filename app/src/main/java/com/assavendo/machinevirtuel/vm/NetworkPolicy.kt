package com.assavendo.machinevirtuel.vm

import java.security.MessageDigest
import java.util.Locale

/**
 * Network policy shared by VM validation and backend command generation.
 *
 * The policy is deliberately backend-neutral: it validates the logical VM
 * network identity and constraints before QEMU/crosvm-specific translation.
 */
data class NetworkPolicy(
    val mode: NetworkMode,
    val macAddress: String,
    val mtu: Int = 1500,
    val portForwards: List<PortForward> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val hostname: String = "",
) {
    companion object {
        private val MAC_PATTERN = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
        private val HOSTNAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9.-]{0,62}$")
        private val IPV4_PATTERN = Regex(
            "^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$"
        )

        /**
         * Deterministic locally-administered unicast MAC address.
         *
         * The first octet is forced to 0x02 so the address cannot collide with
         * an OUI assigned to a physical vendor.
         */
        fun deterministicMac(vmId: String): String {
            require(vmId.isNotBlank()) { "VM ID must not be blank." }
            val digest = MessageDigest.getInstance("SHA-256").digest(vmId.toByteArray(Charsets.UTF_8))
            val bytes = digest.copyOfRange(0, 6)
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            return bytes.joinToString(":") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
        }
    }

    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        if (!MAC_PATTERN.matches(macAddress.lowercase(Locale.ROOT))) {
            issues += "MAC address invalide."
        } else {
            val first = macAddress.substring(0, 2).toIntOrNull(16)
            if (first == null || (first and 1) != 0) issues += "La MAC doit être unicast."
            if (first != null && (first and 2) == 0) issues += "La MAC doit être administrativement locale."
        }

        if (mtu !in 576..9000) {
            issues += "MTU doit être compris entre 576 et 9000."
        }

        val hostPorts = mutableSetOf<Int>()
        portForwards.forEachIndexed { index, forward ->
            if (forward.protocol.lowercase(Locale.ROOT) !in setOf("tcp", "udp")) {
                issues += "Port forward #$index utilise un protocole invalide."
            }
            if (forward.hostPort !in 1..65535 || forward.guestPort !in 1..65535) {
                issues += "Port forward #$index contient un port hors plage 1..65535."
            }
            if (forward.hostPort in 1..65535 && !hostPorts.add(forward.hostPort)) {
                issues += "Deux redirections utilisent le même port hôte."
            }
        }

        dnsServers.forEachIndexed { index, server ->
            require(server.isNotBlank()) { "DNS #$index est vide." }
            require(IPV4_PATTERN.matches(server) || server.contains(":")) {
                "DNS #$index est invalide."
            }
        }

        if (hostname.isNotBlank() && !HOSTNAME_PATTERN.matches(hostname)) {
            issues += "Nom d'hôte réseau invalide."
        }

        if (mode == NetworkMode.DISABLED && portForwards.isNotEmpty()) {
            issues += "Une VM sans réseau ne peut pas exposer de redirections de ports."
        }

        return issues
    }
}
