package dev.shizzi.accounts

/** This client presents account management only; router credentials keep their original permissions. */
internal object AccountCommandScope {
    private val actions = setOf("account.create", "account.enable", "account.disconnect", "account.rename", "account.pin", "account.delete")
    fun supports(action: String): Boolean = action in actions
}
