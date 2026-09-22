package dev.shizzi

import java.util.concurrent.TimeUnit

data class ClientIdentity(
    val ip: String,
    val mac: String,
) {
    val deviceId: String get() = mac.lowercase()
}

class ClientIdentityInspector {

    fun byIp(): Map<String, ClientIdentity> {
        val output = runCommand(listOf("ip", "neigh", "show"))
        if (output.isBlank()) return emptyMap()

        return output.lineSequence()
            .mapNotNull(::parseLine)
            .associateBy(ClientIdentity::ip)
    }

    private fun parseLine(line: String): ClientIdentity? {
        val ip = IPV4.find(line)?.value ?: return null
        val mac = MAC.find(line)?.value?.lowercase() ?: return null
        return ClientIdentity(ip = ip, mac = mac)
    }

    private fun runCommand(command: List<String>): String = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching ""
        }
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private companion object {
        const val COMMAND_TIMEOUT_MS = 1_500L
        val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val MAC = Regex("""\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""")
    }
}
