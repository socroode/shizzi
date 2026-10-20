package dev.shizzi.accounts

import org.junit.Assert.*
import org.junit.Test

class AccountCommandScopeTest {
    @Test fun exposesAccountCommandsAndNoRouterOrMediaConfiguration() {
        assertTrue(AccountCommandScope.supports("account.create"))
        assertTrue(AccountCommandScope.supports("account.disconnect"))
        assertFalse(AccountCommandScope.supports("module.media.set"))
        assertFalse(AccountCommandScope.supports("admin.credentials"))
        assertFalse(AccountCommandScope.supports("portal.set"))
        assertFalse(AccountCommandScope.supports("offer.delete"))
    }
}
