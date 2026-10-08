package dev.shizzi

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class MessagingAccount(
    val number: String,
    val name: String,
    val enabled: Boolean = true,
)

data class ChatMessage(
    val id: Long,
    val conversationId: String,
    val sender: String,
    val text: String,
    val createdAtMillis: Long,
)

data class ChatGroup(
    val id: String,
    val name: String,
    val members: Set<String>,
    val createdBy: String,
    val createdAtMillis: Long,
)

private data class MessagingState(
    val nextMessageId: Long = 1L,
    val messages: List<ChatMessage> = emptyList(),
    val groups: List<ChatGroup> = emptyList(),
    val readMarkers: Map<String, Long> = emptyMap(),
)

class MessagingStore(private val file: File) {
    private var state: MessagingState = load()

    @Synchronized
    fun createGroup(
        creatorRaw: String,
        nameRaw: String,
        membersRaw: Collection<String>,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        val creator = normalizeAccount(creatorRaw)
        val creatorAccount = accounts[creator]
            ?.takeIf { it.enabled }
            ?: return error("Compte invalide.")

        val name = nameRaw.trim().replace(Regex("""\s+"""), " ").take(MAX_GROUP_NAME)
        if (name.length < 2) return error("Nom du groupe trop court.")

        val members = (membersRaw.map(::normalizeAccount) + creator)
            .filter { number -> accounts[number]?.enabled == true }
            .toSet()

        if (members.size < 2) return error("Un groupe doit contenir au moins 2 comptes.")
        if (members.size > MAX_GROUP_MEMBERS) return error("Trop de membres dans le groupe.")

        val group = ChatGroup(
            id = "group:" + UUID.randomUUID().toString(),
            name = name,
            members = members,
            createdBy = creatorAccount.number,
            createdAtMillis = nowMillis,
        )
        state = state.copy(groups = state.groups + group)
        persist()
        return JSONObject()
            .put("ok", true)
            .put("conversationId", group.id)
            .put("message", "Groupe créé.")
    }

    @Synchronized
    fun send(
        senderRaw: String,
        conversationIdRaw: String,
        textRaw: String,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        val sender = normalizeAccount(senderRaw)
        if (accounts[sender]?.enabled != true) return error("Compte invalide.")

        val conversationId = conversationIdRaw.trim()
        if (!canAccess(conversationId, sender, accounts)) {
            return error("Conversation inaccessible.")
        }

        val text = textRaw
            .replace("\u0000", "")
            .trim()
            .take(MAX_MESSAGE_CHARS)
        if (text.isBlank()) return error("Message vide.")

        val message = ChatMessage(
            id = state.nextMessageId,
            conversationId = conversationId,
            sender = sender,
            text = text,
            createdAtMillis = nowMillis,
        )
        val messages = (state.messages + message).takeLast(MAX_STORED_MESSAGES)
        state = state.copy(
            nextMessageId = message.id + 1L,
            messages = messages,
            readMarkers = state.readMarkers + (markerKey(conversationId, sender) to message.id),
        )
        persist()
        return JSONObject()
            .put("ok", true)
            .put("id", message.id)
            .put("message", "Envoyé.")
    }

    @Synchronized
    fun snapshot(
        accountRaw: String,
        accounts: Map<String, MessagingAccount>,
        presence: Map<String, Long>,
        nowMillis: Long,
        conversationIdRaw: String? = null,
        markRead: Boolean = true,
    ): JSONObject {
        val account = normalizeAccount(accountRaw)
        val self = accounts[account]?.takeIf { it.enabled }
            ?: return error("Compte invalide.")

        val conversationId = conversationIdRaw?.trim().orEmpty()
        if (conversationId.isNotBlank() && !canAccess(conversationId, account, accounts)) {
            return error("Conversation inaccessible.")
        }

        if (conversationId.isNotBlank() && markRead) {
            val lastId = state.messages
                .asSequence()
                .filter { it.conversationId == conversationId }
                .maxOfOrNull(ChatMessage::id)
                ?: 0L
            val markerKey = markerKey(conversationId, account)
            val previous = state.readMarkers[markerKey] ?: 0L
            if (lastId > previous) {
                state = state.copy(
                    readMarkers = state.readMarkers + (markerKey to lastId),
                )
                persist()
            }
        }

        val contacts = accounts.values
            .filter { it.enabled && it.number != account }
            .sortedWith(compareBy<MessagingAccount>({ it.name.lowercase() }, { it.number }))

        val conversations = mutableListOf<JSONObject>()
        contacts.forEach { contact ->
            val id = directConversationId(account, contact.number)
            conversations += conversationJson(
                id = id,
                type = "direct",
                name = contact.name.ifBlank { contact.number },
                account = account,
                members = setOf(account, contact.number),
            )
        }
        state.groups
            .filter { account in it.members }
            .sortedBy { it.name.lowercase() }
            .forEach { group ->
                conversations += conversationJson(
                    id = group.id,
                    type = "group",
                    name = group.name,
                    account = account,
                    members = group.members,
                )
            }

        val selectedMessages = if (conversationId.isBlank()) {
            emptyList()
        } else {
            state.messages
                .filter { it.conversationId == conversationId }
                .takeLast(MAX_RETURNED_MESSAGES)
        }

        val usersJson = JSONArray().apply {
            (listOf(self) + contacts).forEach { user ->
                put(
                    JSONObject()
                        .put("number", user.number)
                        .put("name", user.name.ifBlank { user.number })
                        .put(
                            "online",
                            nowMillis - (presence[user.number] ?: 0L) <= PRESENCE_WINDOW_MILLIS,
                        ),
                )
            }
        }

        return JSONObject()
            .put("ok", true)
            .put(
                "self",
                JSONObject()
                    .put("number", self.number)
                    .put("name", self.name.ifBlank { self.number }),
            )
            .put("users", usersJson)
            .put("conversations", JSONArray(conversations))
            .put(
                "messages",
                JSONArray().apply {
                    selectedMessages.forEach { message ->
                        put(messageJson(message, accounts))
                    }
                },
            )
            .put("serverTimeMillis", nowMillis)
    }

    @Synchronized
    fun unreadTotal(
        accountRaw: String,
        accounts: Map<String, MessagingAccount>,
    ): Int {
        val account = normalizeAccount(accountRaw)
        if (accounts[account]?.enabled != true) return 0

        return allConversationIds(account, accounts).sumOf { conversationId ->
            unreadCount(conversationId, account)
        }
    }

    @Synchronized
    fun canAccess(
        conversationId: String,
        accountRaw: String,
        accounts: Map<String, MessagingAccount>,
    ): Boolean {
        val account = normalizeAccount(accountRaw)
        if (accounts[account]?.enabled != true) return false

        return when {
            conversationId.startsWith("dm:") -> {
                val members = parseDirectMembers(conversationId) ?: return false
                val canonical = directConversationId(
                    members.elementAt(0),
                    members.elementAt(1),
                )
                conversationId == canonical &&
                    account in members &&
                    members.all { accounts[it]?.enabled == true }
            }

            conversationId.startsWith("group:") ->
                state.groups.firstOrNull { it.id == conversationId }
                    ?.members
                    ?.contains(account) == true

            else -> false
        }
    }

    private fun conversationJson(
        id: String,
        type: String,
        name: String,
        account: String,
        members: Set<String>,
    ): JSONObject {
        val last = state.messages.lastOrNull { it.conversationId == id }
        return JSONObject()
            .put("id", id)
            .put("type", type)
            .put("name", name)
            .put("unread", unreadCount(id, account))
            .put("lastMessage", last?.text.orEmpty())
            .put("lastAtMillis", last?.createdAtMillis ?: 0L)
            .put("members", JSONArray(members.sorted()))
    }

    private fun unreadCount(conversationId: String, account: String): Int {
        val marker = state.readMarkers[markerKey(conversationId, account)] ?: 0L
        return state.messages.count {
            it.conversationId == conversationId &&
                it.sender != account &&
                it.id > marker
        }
    }

    private fun allConversationIds(
        account: String,
        accounts: Map<String, MessagingAccount>,
    ): List<String> {
        val directs = accounts.values
            .filter { it.enabled && it.number != account }
            .map { directConversationId(account, it.number) }
        val groups = state.groups
            .filter { account in it.members }
            .map(ChatGroup::id)
        return directs + groups
    }

    private fun messageJson(
        message: ChatMessage,
        accounts: Map<String, MessagingAccount>,
    ): JSONObject {
        val sender = accounts[message.sender]
        return JSONObject()
            .put("id", message.id)
            .put("conversationId", message.conversationId)
            .put("sender", message.sender)
            .put("senderName", sender?.name?.ifBlank { message.sender } ?: message.sender)
            .put("text", message.text)
            .put("createdAtMillis", message.createdAtMillis)
    }

    private fun persist() {
        val destination = file
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: File("."), destination.name + ".tmp")
        val raw = encode(state).toString()
        FileOutputStream(temporary).use { output ->
            output.write(raw.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun load(): MessagingState {
        if (!file.isFile) return MessagingState()
        return runCatching { decode(JSONObject(file.readText())) }
            .getOrDefault(MessagingState())
    }

    private fun encode(value: MessagingState): JSONObject =
        JSONObject()
            .put("version", FORMAT_VERSION)
            .put("nextMessageId", value.nextMessageId)
            .put(
                "messages",
                JSONArray().apply {
                    value.messages.forEach { message ->
                        put(
                            JSONObject()
                                .put("id", message.id)
                                .put("conversationId", message.conversationId)
                                .put("sender", message.sender)
                                .put("text", message.text)
                                .put("createdAtMillis", message.createdAtMillis),
                        )
                    }
                },
            )
            .put(
                "groups",
                JSONArray().apply {
                    value.groups.forEach { group ->
                        put(
                            JSONObject()
                                .put("id", group.id)
                                .put("name", group.name)
                                .put("members", JSONArray(group.members.sorted()))
                                .put("createdBy", group.createdBy)
                                .put("createdAtMillis", group.createdAtMillis),
                        )
                    }
                },
            )
            .put(
                "readMarkers",
                JSONObject().apply {
                    value.readMarkers.forEach { (key, marker) -> put(key, marker) }
                },
            )

    private fun decode(root: JSONObject): MessagingState {
        if (root.optInt("version", 0) !in 1..FORMAT_VERSION) return MessagingState()

        val messages = buildList {
            val array = root.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optLong("id")
                val conversation = item.optString("conversationId")
                val sender = normalizeAccount(item.optString("sender"))
                val text = item.optString("text")
                if (id <= 0L || conversation.isBlank() || sender.isBlank() || text.isBlank()) continue
                add(
                    ChatMessage(
                        id = id,
                        conversationId = conversation,
                        sender = sender,
                        text = text.take(MAX_MESSAGE_CHARS),
                        createdAtMillis = item.optLong("createdAtMillis"),
                    ),
                )
            }
        }.takeLast(MAX_STORED_MESSAGES)

        val groups = buildList {
            val array = root.optJSONArray("groups") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (!id.startsWith("group:") || name.isBlank()) continue
                val members = buildSet {
                    val memberArray = item.optJSONArray("members") ?: JSONArray()
                    for (memberIndex in 0 until memberArray.length()) {
                        normalizeAccount(memberArray.optString(memberIndex))
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
                if (members.size < 2) continue
                add(
                    ChatGroup(
                        id = id,
                        name = name.take(MAX_GROUP_NAME),
                        members = members,
                        createdBy = normalizeAccount(item.optString("createdBy")),
                        createdAtMillis = item.optLong("createdAtMillis"),
                    ),
                )
            }
        }

        val readMarkers = linkedMapOf<String, Long>()
        val markers = root.optJSONObject("readMarkers") ?: JSONObject()
        markers.keys().forEach { key ->
            val value = markers.optLong(key)
            if (value > 0L) readMarkers[key] = value
        }

        val maxId = messages.maxOfOrNull(ChatMessage::id) ?: 0L
        return MessagingState(
            nextMessageId = root.optLong("nextMessageId", maxId + 1L).coerceAtLeast(maxId + 1L),
            messages = messages,
            groups = groups,
            readMarkers = readMarkers,
        )
    }

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("message", message)

    companion object {
        internal const val FORMAT_VERSION = 1
        internal const val MAX_MESSAGE_CHARS = 2_000
        internal const val MAX_GROUP_NAME = 60
        internal const val MAX_GROUP_MEMBERS = 64
        internal const val MAX_STORED_MESSAGES = 20_000
        internal const val MAX_RETURNED_MESSAGES = 120
        internal const val PRESENCE_WINDOW_MILLIS = 15_000L

        fun normalizeAccount(raw: String): String = raw.filter(Char::isDigit)

        fun directConversationId(aRaw: String, bRaw: String): String {
            val a = normalizeAccount(aRaw)
            val b = normalizeAccount(bRaw)
            val pair = listOf(a, b).sorted()
            return "dm:" + pair.joinToString(":")
        }

        fun parseDirectMembers(id: String): Set<String>? {
            if (!id.startsWith("dm:")) return null
            val pieces = id.removePrefix("dm:").split(':')
            if (pieces.size != 2) return null
            val members = pieces.map(::normalizeAccount)
            if (members.any { it.isBlank() } || members[0] == members[1]) return null
            return members.toSet()
        }

        private fun markerKey(conversationId: String, account: String): String =
            conversationId + "|" + normalizeAccount(account)
    }
}
