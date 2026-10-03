package dev.shizzi

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ShizziCallKind {
    AUDIO,
    VIDEO;

    companion object {
        fun from(raw: String): ShizziCallKind? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

private enum class ShizziCallStatus {
    RINGING,
    ACTIVE,
    ENDED,
}

private data class ShizziCallSession(
    val id: String,
    val caller: String,
    val callee: String,
    val kind: ShizziCallKind,
    val offerSdp: String,
    val createdAtMillis: Long,
    var updatedAtMillis: Long,
    var status: ShizziCallStatus = ShizziCallStatus.RINGING,
)

private data class ShizziCallEvent(
    val sequence: Long,
    val recipient: String,
    val type: String,
    val callId: String,
    val from: String,
    val kind: ShizziCallKind,
    val sdpType: String = "",
    val sdp: String = "",
    val candidateJson: String = "",
    val createdAtMillis: Long,
)

class CallSignalingHub {
    private val calls = linkedMapOf<String, ShizziCallSession>()
    private val events = ArrayDeque<ShizziCallEvent>()
    private var nextSequence = 1L

    @Synchronized
    fun start(
        callerRaw: String,
        calleeRaw: String,
        kindRaw: String,
        offerTypeRaw: String,
        offerSdpRaw: String,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        cleanup(nowMillis)

        val caller = MessagingStore.normalizeAccount(callerRaw)
        val callee = MessagingStore.normalizeAccount(calleeRaw)
        val callerAccount = accounts[caller]?.takeIf { it.enabled }
            ?: return error("Compte appelant invalide.")
        val calleeAccount = accounts[callee]?.takeIf { it.enabled }
            ?: return error("Destinataire introuvable.")

        if (caller == callee) return error("Impossible de s'appeler soi-même.")

        val kind = ShizziCallKind.from(kindRaw)
            ?: return error("Type d'appel invalide.")
        val offerType = offerTypeRaw.trim().lowercase()
        // SDP is syntax-sensitive: preserve it exactly as produced by WebRTC.
        val offerSdp = offerSdpRaw
        if (offerType != "offer" || offerSdp.isBlank() || offerSdp.length > MAX_SDP_CHARS) {
            return error("Offre WebRTC invalide.")
        }

        if (liveCallFor(caller) != null) return error("Tu es déjà en appel.")
        if (liveCallFor(callee) != null) return error("Ce contact est déjà en appel.")

        val call = ShizziCallSession(
            id = "call:" + UUID.randomUUID().toString(),
            caller = callerAccount.number,
            callee = calleeAccount.number,
            kind = kind,
            offerSdp = offerSdp,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
        calls[call.id] = call
        emit(
            recipient = call.callee,
            type = "incoming",
            call = call,
            from = call.caller,
            sdpType = "offer",
            sdp = call.offerSdp,
            nowMillis = nowMillis,
        )
        trimEvents(nowMillis)

        return JSONObject()
            .put("ok", true)
            .put("callId", call.id)
            .put("kind", call.kind.name.lowercase())
            .put("peer", call.callee)
            .put("peerName", calleeAccount.name.ifBlank { call.callee })
    }

    @Synchronized
    fun answer(
        accountRaw: String,
        callIdRaw: String,
        answerTypeRaw: String,
        answerSdpRaw: String,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        cleanup(nowMillis)
        val account = enabledAccount(accountRaw, accounts)
            ?: return error("Compte invalide.")
        val call = calls[callIdRaw.trim()]
            ?: return error("Appel introuvable.")

        if (call.callee != account.number) return error("Seul le destinataire peut décrocher.")
        if (call.status != ShizziCallStatus.RINGING) return error("Cet appel n'est plus disponible.")

        val answerType = answerTypeRaw.trim().lowercase()
        // Keep CRLF and the final line ending intact for Android WebRTC/WebView.
        val answerSdp = answerSdpRaw
        if (answerType != "answer" || answerSdp.isBlank() || answerSdp.length > MAX_SDP_CHARS) {
            return error("Réponse WebRTC invalide.")
        }

        call.status = ShizziCallStatus.ACTIVE
        call.updatedAtMillis = nowMillis
        emit(
            recipient = call.caller,
            type = "answer",
            call = call,
            from = call.callee,
            sdpType = "answer",
            sdp = answerSdp,
            nowMillis = nowMillis,
        )
        trimEvents(nowMillis)
        return ok("Appel accepté.")
    }

    @Synchronized
    fun ice(
        accountRaw: String,
        callIdRaw: String,
        candidate: JSONObject?,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        cleanup(nowMillis)
        val account = enabledAccount(accountRaw, accounts)
            ?: return error("Compte invalide.")
        val call = calls[callIdRaw.trim()]
            ?: return error("Appel introuvable.")

        if (!isParticipant(call, account.number)) return error("Accès refusé.")
        if (call.status == ShizziCallStatus.ENDED) return error("Appel terminé.")

        val candidateObject = candidate ?: return error("Candidat ICE invalide.")
        val candidateLine = candidateObject.optString("candidate").trim()
        if (candidateLine.isBlank()) return error("Candidat ICE vide.")

        val safe = JSONObject()
            .put("candidate", candidateLine.take(MAX_CANDIDATE_CHARS))
            .put("sdpMid", candidateObject.optString("sdpMid").take(128))
            .put("sdpMLineIndex", candidateObject.optInt("sdpMLineIndex", 0))
        candidateObject.optString("usernameFragment")
            .takeIf { it.isNotBlank() }
            ?.let { safe.put("usernameFragment", it.take(256)) }

        val peer = peerOf(call, account.number)
        emit(
            recipient = peer,
            type = "ice",
            call = call,
            from = account.number,
            candidateJson = safe.toString(),
            nowMillis = nowMillis,
        )
        call.updatedAtMillis = nowMillis
        trimEvents(nowMillis)
        return ok("ICE transmis.")
    }

    @Synchronized
    fun reject(
        accountRaw: String,
        callIdRaw: String,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        cleanup(nowMillis)
        val account = enabledAccount(accountRaw, accounts)
            ?: return error("Compte invalide.")
        val call = calls[callIdRaw.trim()]
            ?: return error("Appel introuvable.")

        if (call.callee != account.number) return error("Seul le destinataire peut refuser.")
        if (call.status != ShizziCallStatus.RINGING) return error("Cet appel n'est plus disponible.")

        call.status = ShizziCallStatus.ENDED
        call.updatedAtMillis = nowMillis
        emit(
            recipient = call.caller,
            type = "rejected",
            call = call,
            from = call.callee,
            nowMillis = nowMillis,
        )
        trimEvents(nowMillis)
        return ok("Appel refusé.")
    }

    @Synchronized
    fun end(
        accountRaw: String,
        callIdRaw: String,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        cleanup(nowMillis)
        val account = enabledAccount(accountRaw, accounts)
            ?: return error("Compte invalide.")
        val call = calls[callIdRaw.trim()]
            ?: return ok("Appel déjà terminé.")

        if (!isParticipant(call, account.number)) return error("Accès refusé.")
        if (call.status == ShizziCallStatus.ENDED) return ok("Appel déjà terminé.")

        call.status = ShizziCallStatus.ENDED
        call.updatedAtMillis = nowMillis
        emit(
            recipient = peerOf(call, account.number),
            type = "ended",
            call = call,
            from = account.number,
            nowMillis = nowMillis,
        )
        trimEvents(nowMillis)
        return ok("Appel terminé.")
    }

    @Synchronized
    fun poll(
        accountRaw: String,
        afterSequence: Long,
        accounts: Map<String, MessagingAccount>,
        nowMillis: Long,
    ): JSONObject {
        cleanup(nowMillis)
        val account = enabledAccount(accountRaw, accounts)
            ?: return error("Compte invalide.")

        val after = afterSequence.coerceAtLeast(0L)
        val visible = events
            .asSequence()
            .filter { it.recipient == account.number && it.sequence > after }
            .take(MAX_EVENTS_PER_POLL)
            .toList()

        val cursor = visible.lastOrNull()?.sequence
            ?: maxOf(after, nextSequence - 1L)

        return JSONObject()
            .put("ok", true)
            .put("cursor", cursor)
            .put(
                "events",
                JSONArray().apply {
                    visible.forEach { event ->
                        put(eventJson(event, accounts))
                    }
                },
            )
            .put("serverTimeMillis", nowMillis)
    }

    @Synchronized
    internal fun activeCallForAccount(accountRaw: String, nowMillis: Long): String? {
        cleanup(nowMillis)
        return liveCallFor(MessagingStore.normalizeAccount(accountRaw))?.id
    }

    private fun cleanup(nowMillis: Long) {
        val timedOut = calls.values.filter { call ->
            when (call.status) {
                ShizziCallStatus.RINGING ->
                    nowMillis - call.createdAtMillis > RING_TIMEOUT_MILLIS

                ShizziCallStatus.ACTIVE ->
                    nowMillis - call.updatedAtMillis > ACTIVE_MAX_MILLIS

                ShizziCallStatus.ENDED -> false
            }
        }

        timedOut.forEach { call ->
            if (call.status == ShizziCallStatus.ENDED) return@forEach
            call.status = ShizziCallStatus.ENDED
            call.updatedAtMillis = nowMillis
            emit(call.caller, "timeout", call, call.callee, nowMillis = nowMillis)
            emit(call.callee, "timeout", call, call.caller, nowMillis = nowMillis)
        }

        calls.entries.removeIf { (_, call) ->
            call.status == ShizziCallStatus.ENDED &&
                nowMillis - call.updatedAtMillis > ENDED_RETENTION_MILLIS
        }
        trimEvents(nowMillis)
    }

    private fun trimEvents(nowMillis: Long) {
        while (events.isNotEmpty()) {
            val first = events.first()
            if (events.size <= MAX_RETAINED_EVENTS &&
                nowMillis - first.createdAtMillis <= EVENT_RETENTION_MILLIS) {
                break
            }
            events.removeFirst()
        }
    }

    private fun emit(
        recipient: String,
        type: String,
        call: ShizziCallSession,
        from: String,
        sdpType: String = "",
        sdp: String = "",
        candidateJson: String = "",
        nowMillis: Long,
    ) {
        events.addLast(
            ShizziCallEvent(
                sequence = nextSequence++,
                recipient = recipient,
                type = type,
                callId = call.id,
                from = from,
                kind = call.kind,
                sdpType = sdpType,
                sdp = sdp,
                candidateJson = candidateJson,
                createdAtMillis = nowMillis,
            ),
        )
    }

    private fun eventJson(
        event: ShizziCallEvent,
        accounts: Map<String, MessagingAccount>,
    ): JSONObject =
        JSONObject()
            .put("sequence", event.sequence)
            .put("type", event.type)
            .put("callId", event.callId)
            .put("from", event.from)
            .put("fromName", accounts[event.from]?.name?.ifBlank { event.from } ?: event.from)
            .put("kind", event.kind.name.lowercase())
            .put("createdAtMillis", event.createdAtMillis)
            .apply {
                if (event.sdp.isNotBlank()) {
                    put(
                        "description",
                        JSONObject()
                            .put("type", event.sdpType)
                            .put("sdp", event.sdp),
                    )
                }
                if (event.candidateJson.isNotBlank()) {
                    put("candidate", JSONObject(event.candidateJson))
                }
            }

    private fun enabledAccount(
        raw: String,
        accounts: Map<String, MessagingAccount>,
    ): MessagingAccount? {
        val number = MessagingStore.normalizeAccount(raw)
        return accounts[number]?.takeIf { it.enabled }
    }

    private fun liveCallFor(account: String): ShizziCallSession? =
        calls.values.firstOrNull {
            it.status != ShizziCallStatus.ENDED &&
                (it.caller == account || it.callee == account)
        }

    private fun isParticipant(call: ShizziCallSession, account: String): Boolean =
        call.caller == account || call.callee == account

    private fun peerOf(call: ShizziCallSession, account: String): String =
        if (call.caller == account) call.callee else call.caller

    private fun ok(message: String): JSONObject =
        JSONObject().put("ok", true).put("message", message)

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("message", message)

    companion object {
        internal const val RING_TIMEOUT_MILLIS = 45_000L
        internal const val ACTIVE_MAX_MILLIS = 6L * 60L * 60L * 1_000L
        internal const val ENDED_RETENTION_MILLIS = 60_000L
        internal const val EVENT_RETENTION_MILLIS = 5L * 60L * 1_000L
        internal const val MAX_RETAINED_EVENTS = 2_000
        internal const val MAX_EVENTS_PER_POLL = 100
        internal const val MAX_SDP_CHARS = 128_000
        internal const val MAX_CANDIDATE_CHARS = 8_192
    }
}
