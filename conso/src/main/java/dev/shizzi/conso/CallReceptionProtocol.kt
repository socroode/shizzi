package dev.shizzi.conso

import org.json.JSONObject

internal data class IncomingCall(val id: String, val caller: String, val name: String, val video: Boolean)

/** The router's live snapshot is authoritative, independently of the WebView event cursor. */
internal class CallReceptionProtocol {
    var account: String = ""; private set
    var incoming: IncomingCall? = null; private set
    private var silenced = ""

    fun update(json: JSONObject): IncomingCall? {
        check(json.optBoolean("ok")) { json.optString("message", "Connecte ton compte Shizzi.") }
        check(json.has("incomingCall")) { "Mets le routeur Shizzi à jour pour recevoir les appels en arrière-plan." }
        val nextAccount = json.optString("accountNumber")
        check(nextAccount.isNotBlank()) { "Compte Shizzi non identifié." }
        if (nextAccount != account) { reset(); account = nextAccount }
        val item = json.optJSONObject("incomingCall")
        val next = item?.takeIf {
            it.optString("callId").isNotBlank() && it.optString("from").isNotBlank() &&
                it.optLong("expiresAtMillis") > json.optLong("serverTimeMillis")
        }?.let { IncomingCall(it.getString("callId"), it.getString("from"),
            it.optString("fromName").ifBlank { it.getString("from") }, it.optString("kind") == "video") }
        if (next?.id != incoming?.id) silenced = ""
        incoming = next
        return next?.takeUnless { it.id == silenced }
    }

    fun silence() { silenced = incoming?.id.orEmpty() }
    fun reset() { account = ""; incoming = null; silenced = "" }
}

internal interface CallReceptionTransport {
    fun poll(): JSONObject
    fun reject(callId: String): JSONObject
}
