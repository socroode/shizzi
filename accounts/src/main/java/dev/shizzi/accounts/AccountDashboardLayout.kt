package dev.shizzi.accounts

import org.json.JSONArray
import org.json.JSONObject

/** Counter/credit changes update existing views, so live polling cannot destroy an edited form. */
internal object AccountDashboardLayout {
    fun key(response: JSONObject, accountsOnly: Boolean): String {
        if (!accountsOnly) return response.toString()
        val state = response.optJSONObject("state")
        val accounts = state?.optJSONArray("accounts")
        val structural = JSONArray()
        if (accounts != null) for (index in 0 until accounts.length()) {
            val account = accounts.optJSONObject(index) ?: continue
            structural.put(JSONObject().put("number", account.optString("number"))
                .put("name", account.optString("name")).put("enabled", account.optBoolean("enabled", true))
                .put("cameraMac", account.opt("cameraMac")))
        }
        return JSONObject().put("routerName", response.optString("routerName"))
            .put("accountsAvailable", accounts != null).put("accounts", structural)
            .put("associated", response.optJSONObject("traffic")?.optJSONArray("associatedClients")).toString()
    }
}
