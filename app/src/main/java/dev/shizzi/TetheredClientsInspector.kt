package dev.shizzi

import android.content.Context
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

data class TetheredDevice(
    val macAddress: String,
    val addresses: Set<String>,
) {
    val deviceId: String get() = macAddress.lowercase()
}

class TetheredClientsInspector(
    private val context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    fun snapshot(): List<TetheredDevice> = runCatching {
        val manager = context.getSystemService("tethering")
            ?: return emptyList()
        val managerClass = Class.forName("android.net.TetheringManager")
        val callbackClass =
            Class.forName("android.net.TetheringManager\$TetheringEventCallback")

        if (!callbackClass.isInterface) {
            SessionLog.warn("tethered client callback is not proxyable on this Android version")
            return emptyList()
        }

        val latch = CountDownLatch(1)
        val holder = arrayOf<List<TetheredDevice>>(emptyList())

        lateinit var callback: Any
        callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ShizziTetheringEventCallback"
                "onClientsChanged" -> {
                    holder[0] = parseClients(args?.firstOrNull())
                    latch.countDown()
                    null
                }
                else -> null
            }
        }

        val register = managerClass.getMethod(
            "registerTetheringEventCallback",
            Executor::class.java,
            callbackClass,
        )
        val unregister = managerClass.getMethod(
            "unregisterTetheringEventCallback",
            callbackClass,
        )

        try {
            register.invoke(manager, Executor { command -> command.run() }, callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            holder[0]
        } finally {
            runCatching { unregister.invoke(manager, callback) }
        }
    }.getOrElse { failure ->
        SessionLog.warn(
            "tethered client snapshot unavailable: " +
                "${failure.cause?.message ?: failure.message}",
        )
        emptyList()
    }

    private fun parseClients(raw: Any?): List<TetheredDevice> {
        val clients = raw as? Collection<*> ?: return emptyList()

        return clients.mapNotNull { client ->
            if (client == null) return@mapNotNull null

            val tetheringType = runCatching {
                client.javaClass.getMethod("getTetheringType").invoke(client) as Int
            }.getOrNull()
            if (tetheringType != null && tetheringType != WIFI_TETHERING_TYPE) {
                return@mapNotNull null
            }

            val mac = runCatching {
                client.javaClass.getMethod("getMacAddress").invoke(client).toString()
            }.getOrNull()?.lowercase() ?: return@mapNotNull null

            val addresses = runCatching {
                val infos = client.javaClass.getMethod("getAddresses").invoke(client)
                    as? Collection<*> ?: emptyList<Any>()
                infos.mapNotNull(::addressOf).toSet()
            }.getOrDefault(emptySet())

            TetheredDevice(macAddress = mac, addresses = addresses)
        }
    }

    private fun addressOf(info: Any?): String? = runCatching {
        if (info == null) return@runCatching null
        val linkAddress = info.javaClass.getMethod("getAddress").invoke(info)
            ?: return@runCatching null
        val inet = linkAddress.javaClass.getMethod("getAddress").invoke(linkAddress)
            as? InetAddress
        inet?.hostAddress?.substringBefore('%')
    }.getOrNull()

    private companion object {
        const val WIFI_TETHERING_TYPE = 0
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
