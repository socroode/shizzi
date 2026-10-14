package dev.shizzi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Event-based association identity; MACs belong to sessions, never to accounts. */
class ClientPresenceMonitor(private val context: Context, private val publish: (String) -> Unit) {
    private var wifiKnown = false
    private var leasesKnown = false
    private var stations = emptySet<String>()
    private var leases = emptyList<Pair<String, String>>()
    private val registrations = mutableListOf<() -> Unit>()
    private val executor = Executor { it.run() }

    fun start() {
        register("wifi", "android.net.wifi.WifiManager\$SoftApCallback", "registerSoftApCallback", "unregisterSoftApCallback") { name, args ->
            synchronized(this) {
                when (name) {
                    "onConnectedClientsChanged" -> {
                        val clients = args?.lastOrNull { it is Collection<*> } as? Collection<*>
                        if (clients != null && args.size == 1) {
                            stations = clients.mapNotNull { macOf(it) }.toSet()
                            wifiKnown = true
                            emit()
                        }
                    }
                    "onStateChanged" -> if ((args?.firstOrNull() as? Int) in listOf(11, 14)) {
                        stations = emptySet(); wifiKnown = true; emit()
                    }
                }
            }
        }
        register("tethering", "android.net.TetheringManager\$TetheringEventCallback", "registerTetheringEventCallback", "unregisterTetheringEventCallback") { name, args ->
            if (name == "onClientsChanged") synchronized(this) {
                val clients = args?.firstOrNull() as? Collection<*>
                if (clients != null) {
                    leases = clients.flatMap { client ->
                        val mac = macOf(client) ?: return@flatMap emptyList()
                        val type = call(client, "getTetheringType") as? Int
                        if (type != null && type != 0) return@flatMap emptyList()
                        val addresses = call(client, "getAddresses") as? Collection<*> ?: return@flatMap emptyList()
                        addresses.mapNotNull { info ->
                            val link = call(info, "getAddress") ?: return@mapNotNull null
                            val ip = (call(link, "getAddress") as? java.net.Inet4Address)?.hostAddress
                            ip?.let { it to mac }
                        }
                    }
                    leasesKnown = true; emit()
                }
            }
        }
    }

    private fun register(service: String, callbackName: String, add: String, remove: String, event: (String, Array<Any?>?) -> Unit) {
        runCatching {
            val manager = context.getSystemService(service) ?: error("$service unavailable")
            val type = Class.forName(callbackName)
            check(type.isInterface) { "callback is not an interface" }
            val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "ShizziPresenceCallback"
                    else -> { event(method.name, args); null }
                }
            }
            manager.javaClass.getMethod(add, Executor::class.java, type).invoke(manager, executor, callback)
            registrations += { manager.javaClass.getMethod(remove, type).invoke(manager, callback) }
        }.onFailure { SessionLog.warn("presence $service callback unavailable: ${it.cause?.message ?: it.message}") }
    }

    private fun call(value: Any?, method: String): Any? =
        value?.let { runCatching { it.javaClass.getMethod(method).invoke(it) }.getOrNull() }

    private fun macOf(value: Any?): String? = call(value, "getMacAddress")?.toString()?.lowercase()
        ?.takeIf { it.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) && it != "00:00:00:00:00:00" }

    private fun emit() {
        publish(JSONObject().apply {
            put("wifiKnown", wifiKnown); put("leasesKnown", leasesKnown)
            put("associatedMacs", JSONArray(stations.toList()))
            put("leases", JSONArray().apply {
                leases.forEach { (ip, mac) -> put(JSONObject().put("ip", ip).put("mac", mac)) }
            })
        }.toString())
    }

    fun stop() {
        registrations.forEach { runCatching(it) }; registrations.clear()
        synchronized(this) { wifiKnown = false; leasesKnown = false; emit() }
    }
}
