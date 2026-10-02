package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MessagingStoreTest {

    private fun accounts(count: Int = 8): Map<String, MessagingAccount> =
        (1..count).associate { index ->
            val number = "100$index"
            number to MessagingAccount(
                number = number,
                name = "Client $index",
                enabled = true,
            )
        }

    @Test
    fun directConversationIsPrivateToItsTwoAccounts() {
        val dir = Files.createTempDirectory("shizzi-chat").toFile()
        try {
            val store = MessagingStore(dir.resolve("chat.json"))
            val directory = accounts()
            val conversation = MessagingStore.directConversationId("1001", "1002")

            val sent = store.send(
                senderRaw = "1001",
                conversationIdRaw = conversation,
                textRaw = "Bonjour",
                accounts = directory,
                nowMillis = 100L,
            )
            assertTrue(sent.getBoolean("ok"))

            val one = store.snapshot("1001", directory, emptyMap(), 200L, conversation)
            val two = store.snapshot("1002", directory, emptyMap(), 200L, conversation)
            val three = store.snapshot("1003", directory, emptyMap(), 200L, conversation)

            assertTrue(one.getBoolean("ok"))
            assertTrue(two.getBoolean("ok"))
            assertEquals(1, one.getJSONArray("messages").length())
            assertEquals(1, two.getJSONArray("messages").length())
            assertFalse(three.getBoolean("ok"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun groupMembershipIsolatedAcrossEightClients() {
        val dir = Files.createTempDirectory("shizzi-chat").toFile()
        try {
            val store = MessagingStore(dir.resolve("chat.json"))
            val directory = accounts()
            val created = store.createGroup(
                creatorRaw = "1001",
                nameRaw = "Équipe",
                membersRaw = listOf("1002", "1003", "1004"),
                accounts = directory,
                nowMillis = 100L,
            )
            assertTrue(created.getBoolean("ok"))
            val group = created.getString("conversationId")

            for (member in 1..4) {
                val account = "100$member"
                assertTrue(store.canAccess(group, account, directory))
                val sent = store.send(
                    senderRaw = account,
                    conversationIdRaw = group,
                    textRaw = "Message $member",
                    accounts = directory,
                    nowMillis = 100L + member,
                )
                assertTrue(sent.getBoolean("ok"))
            }

            for (outsider in 5..8) {
                val account = "100$outsider"
                assertFalse(store.canAccess(group, account, directory))
                val sent = store.send(
                    senderRaw = account,
                    conversationIdRaw = group,
                    textRaw = "Intrusion",
                    accounts = directory,
                    nowMillis = 200L,
                )
                assertFalse(sent.getBoolean("ok"))
            }

            val memberView = store.snapshot("1002", directory, emptyMap(), 300L, group)
            assertTrue(memberView.getBoolean("ok"))
            assertEquals(4, memberView.getJSONArray("messages").length())

            val outsiderView = store.snapshot("1008", directory, emptyMap(), 300L, group)
            assertFalse(outsiderView.getBoolean("ok"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unreadCountsClearWhenConversationIsOpened() {
        val dir = Files.createTempDirectory("shizzi-chat").toFile()
        try {
            val store = MessagingStore(dir.resolve("chat.json"))
            val directory = accounts(2)
            val conversation = MessagingStore.directConversationId("1001", "1002")

            store.send("1001", conversation, "Un", directory, 100L)
            store.send("1001", conversation, "Deux", directory, 101L)

            assertEquals(2, store.unreadTotal("1002", directory))

            store.snapshot(
                accountRaw = "1002",
                accounts = directory,
                presence = emptyMap(),
                nowMillis = 200L,
                conversationIdRaw = conversation,
                markRead = true,
            )
            assertEquals(0, store.unreadTotal("1002", directory))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun historyAndReadMarkersSurviveRouterStoreReload() {
        val dir = Files.createTempDirectory("shizzi-chat").toFile()
        try {
            val file = dir.resolve("chat.json")
            val directory = accounts(3)
            val first = MessagingStore(file)
            val conversation = MessagingStore.directConversationId("1001", "1002")

            first.send("1001", conversation, "Persistant", directory, 100L)
            assertEquals(1, first.unreadTotal("1002", directory))
            first.snapshot("1002", directory, emptyMap(), 200L, conversation, markRead = true)

            val reloaded = MessagingStore(file)
            val snapshot = reloaded.snapshot("1002", directory, emptyMap(), 300L, conversation)
            assertTrue(snapshot.getBoolean("ok"))
            assertEquals(1, snapshot.getJSONArray("messages").length())
            assertEquals("Persistant", snapshot.getJSONArray("messages").getJSONObject(0).getString("text"))
            assertEquals(0, reloaded.unreadTotal("1002", directory))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun presenceReflectsRecentHeartbeatOnly() {
        val dir = Files.createTempDirectory("shizzi-chat").toFile()
        try {
            val store = MessagingStore(dir.resolve("chat.json"))
            val directory = accounts(2)
            val now = 100_000L
            val snapshot = store.snapshot(
                accountRaw = "1001",
                accounts = directory,
                presence = mapOf(
                    "1001" to now,
                    "1002" to now - MessagingStore.PRESENCE_WINDOW_MILLIS - 1L,
                ),
                nowMillis = now,
            )

            val users = snapshot.getJSONArray("users")
            val user1 = users.getJSONObject(0)
            val user2 = users.getJSONObject(1)
            assertTrue(user1.getBoolean("online"))
            assertFalse(user2.getBoolean("online"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
