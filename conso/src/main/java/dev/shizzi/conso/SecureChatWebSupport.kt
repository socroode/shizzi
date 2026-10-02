package dev.shizzi.conso

internal object SecureChatWebSupport {
    const val SECURE_HOST = "shizzi.local"
    const val SECURE_BASE_URL = "https://shizzi.local/chat/"
    const val PORTAL_CHAT_URL = "http://192.0.2.1/chat/"
    private const val PORTAL_CHAT_API_URL = "http://192.0.2.1/chat/api/"

    fun isTrustedMediaOrigin(scheme: String?, host: String?): Boolean =
        scheme.equals("https", ignoreCase = true) &&
            host.equals(SECURE_HOST, ignoreCase = true)

    fun normalizeApiPath(raw: String): String? {
        val value = raw.trim().removePrefix("/")
        if (value.isBlank()) return null
        if (value.contains("..") || value.contains("://") || value.contains('\\')) return null
        if (!value.matches(Regex("[A-Za-z0-9_./?=&%+:-]+"))) return null
        return value
    }

    fun apiUrl(raw: String): String? =
        normalizeApiPath(raw)?.let { PORTAL_CHAT_API_URL + it }
}
