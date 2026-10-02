package dev.shizzi

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFolderAccessTest {

    @Test
    fun supportsTenCustomFoldersAndEightConcurrentClients() {
        assertEquals(10, MediaFolderStore.MAX_FOLDERS)
        assertTrue(MediaHttpServer.MAX_CLIENTS >= 8)
    }

    @Test
    fun publicFolderIsVisibleToEveryAccount() {
        val folder = MediaFolderConfig(
            id = "public",
            name = "Films",
            kind = MediaKind.FILMS,
            treeUri = "content://test/public",
        )
        for (user in 1..8) {
            assertTrue(MediaFolderStore.canAccess(folder, "USER$user"))
        }
    }

    @Test
    fun privateFolderOnlyAllowsSelectedAccounts() {
        val folder = MediaFolderConfig(
            id = "private",
            name = "Privé",
            kind = MediaKind.FILMS,
            treeUri = "content://test/private",
            allowedAccounts = setOf("USER2", "USER5", "USER8"),
        )

        for (user in 1..8) {
            val expected = user in setOf(2, 5, 8)
            assertEquals(expected, MediaFolderStore.canAccess(folder, "USER$user"))
        }
        assertFalse(MediaFolderStore.canAccess(folder, null))
    }

    @Test
    fun mediaSelectorShowsExistingAccountsWithNamesAndNumbers() {
        val accounts = (1..8).associate { index ->
            val number = "10%02d".format(index)
            number to PrepaidAccount(
                number = number,
                name = "Client $index",
                pinSalt = "salt-$index",
                pinHash = "hash-$index",
                enabled = index != 4,
            )
        }
        val options = mediaAccountOptions(
            CybercafeState(accounts = accounts),
        )

        assertEquals(8, options.size)
        assertEquals("Client 1 · 1001", options.first().label)
        assertEquals("1004", options.first { !it.enabled }.number)
        assertEquals(
            accounts.keys.sorted(),
            options.map { it.number }.sorted(),
        )
    }

    @Test
    fun selectorPermissionChoiceStillIsolatesEightClients() {
        val selected = setOf("1002", "1005", "1008")
        val folder = MediaFolderConfig(
            id = "selector-private",
            name = "Privé",
            kind = MediaKind.FILMS,
            treeUri = "content://test/private",
            allowedAccounts = selected,
        )

        for (user in 1..8) {
            val number = "10%02d".format(user)
            assertEquals(
                number in selected,
                MediaFolderStore.canAccess(folder, number),
            )
        }
    }

    @Test
    fun virtualRouterAndOneToEightAndroidClientsStayIsolated() {
        val folders = (1..10).map { index ->
            MediaFolderConfig(
                id = "folder-$index",
                name = "Dossier $index",
                kind = MediaKind.FILMS,
                treeUri = "content://virtual/folder-$index",
                allowedAccounts = when (index) {
                    1 -> emptySet()
                    else -> setOf("USER${((index - 2) % 8) + 1}")
                },
            )
        }

        for (userCount in 1..8) {
            val executor = Executors.newFixedThreadPool(userCount)
            try {
                val tasks = (1..userCount).map { user ->
                    Callable {
                        val account = "USER$user"
                        val visible = folders.filter { MediaFolderStore.canAccess(it, account) }
                        val expectedPrivate = folders.filter {
                            it.allowedAccounts.isNotEmpty() && account in it.allowedAccounts
                        }
                        assertTrue(visible.any { it.id == "folder-1" })
                        assertEquals(1 + expectedPrivate.size, visible.size)
                        folders
                            .filter { it.allowedAccounts.isNotEmpty() && account !in it.allowedAccounts }
                            .forEach { denied ->
                                assertFalse(
                                    "USER$user must not see ${denied.id} with $userCount virtual clients",
                                    MediaFolderStore.canAccess(denied, account),
                                )
                            }
                        true
                    }
                }
                val results = executor.invokeAll(tasks)
                assertTrue(
                    "virtual $userCount-client run failed",
                    results.all { it.get() },
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
