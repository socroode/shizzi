package dev.shizzi.accounts

import org.json.JSONObject

/** Detect optional state fields; an absent feature is never mistaken for an empty enabled feature. */
internal class AdminCapabilities(response: JSONObject) {
    private val state = response.optJSONObject("state") ?: JSONObject()
    private val traffic = response.optJSONObject("traffic") ?: JSONObject()
    val accounts = state.optJSONArray("accounts") != null
    val offers = state.optJSONArray("offers") != null && state.optJSONArray("vouchers") != null
    val mediaOffers = state.optJSONArray("mediaOffers") != null && state.optJSONArray("mediaVouchers") != null
    val media = state.optJSONObject("media") != null
    val modules = state.optJSONObject("modules") != null
    val battery = state.optJSONObject("battery") != null
    val portal = state.optJSONObject("portal") != null
    val credentials = state.optJSONObject("remoteAdmin") != null
    val devices = traffic.optJSONArray("portalAuthorizations") != null
    val diagnostics = traffic.optJSONObject("attribution") != null
    val mediaDiagnostics = traffic.optJSONArray("mediaDiagnostics") != null
    private val accountList = state.optJSONArray("accounts")
    val cameras = accountList != null && (0 until accountList.length()).any {
        accountList.optJSONObject(it)?.has("cameraMac") == true
    }
    private val folders = state.optJSONObject("media")?.optJSONArray("folders")
    val fileUpload = folders != null && (0 until folders.length()).any {
        folders.optJSONObject(it)?.has("writable") == true
    }

    fun supports(action: String): Boolean = when {
        action.startsWith("camera.") -> cameras
        action.startsWith("account.") -> accounts
        action.startsWith("offer.") || action.startsWith("voucher.") -> offers
        action.startsWith("media.offer.") || action.startsWith("media.voucher.") -> mediaOffers
        action.startsWith("media.") -> media
        action.startsWith("module.") -> modules
        action == "portal.set" -> portal
        action == "admin.credentials" -> credentials
        action == "session.disconnect" -> devices
        else -> false
    }
}
