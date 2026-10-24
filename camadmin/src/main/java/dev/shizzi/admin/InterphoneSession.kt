package dev.shizzi.admin

/** Each attempt owns a generation so a stopped worker cannot change a later conversation. */
internal class InterphoneSession {
    enum class Phase { OFF, PERMISSION, CONNECTING, ACCEPTED, FAILED }
    data class State(
        val generation: Long = 0,
        val target: String = "",
        val phase: Phase = Phase.OFF,
        val startedRouterUs: Long = 0,
        val acceptedRouterUs: Long = 0,
        val playbackConfirmed: Boolean = false,
        val message: String = "Mon micro : coupé",
        val capturedSamples: Long = 0,
        val encodedPackets: Long = 0,
        val sentPackets: Long = 0,
        val confirmationError: String = "",
    ) {
        val streaming get() = phase == Phase.CONNECTING || phase == Phase.ACCEPTED
    }
    @Volatile var state = State()
        private set

    @Synchronized fun request(target: String, permissionGranted: Boolean, nowUs: Long): State? {
        if (state.streaming || state.phase == Phase.PERMISSION) return null
        state = State(state.generation + 1, target,
            if (permissionGranted) Phase.CONNECTING else Phase.PERMISSION,
            if (permissionGranted) nowUs else 0,
            message = if (permissionGranted) "Mon micro : connexion au relais…" else "Mon micro : autorisation Android requise")
        return state
    }
    @Synchronized fun permit(generation: Long, nowUs: Long): Boolean {
        if (state.generation != generation || state.phase != Phase.PERMISSION) return false
        state = state.copy(phase = Phase.CONNECTING, startedRouterUs = nowUs,
            message = "Mon micro : connexion au relais…")
        return true
    }
    fun streaming(generation: Long): Boolean = state.let { it.generation == generation && it.streaming }

    @Synchronized fun captured(generation: Long, samples: Int) {
        if(streaming(generation))state=state.copy(capturedSamples=state.capturedSamples+samples)
    }
    @Synchronized fun encoded(generation: Long) {
        if(streaming(generation))state=state.copy(encodedPackets=state.encodedPackets+1)
    }
    @Synchronized fun sent(generation: Long) {
        if(streaming(generation))state=state.copy(sentPackets=state.sentPackets+1)
    }
    @Synchronized fun confirmationError(generation: Long, detail: String) {
        if(streaming(generation))state=state.copy(confirmationError=detail.take(120))
    }

    @Synchronized fun observe(generation: Long, acceptedUs: Long, playbackUs: Long, playbackVerified: Boolean) {
        if (!streaming(generation) || acceptedUs < state.startedRouterUs) return
        val played = playbackVerified && playbackUs >= state.startedRouterUs
        state = state.copy(phase = Phase.ACCEPTED, acceptedRouterUs = acceptedUs,
            playbackConfirmed = played,
            message = if (played) "Mon micro : relais accepté · lecture signalée par la caméra"
                else "Mon micro : relais accepté · lecture caméra non confirmée")
    }
    @Synchronized fun observeLegacy(generation: Long, nowUs: Long, incomingKbps: Double,
        playbackUs: Long, playbackVerified: Boolean) {
        // Old v3 routers report two complete seconds of traffic, but no receipt timestamp.
        // Wait until their whole sample window is newer than this attempt, or use a fresh playback ACK.
        val acceptedUs = when {
            playbackVerified && playbackUs >= state.startedRouterUs -> playbackUs
            incomingKbps > 0 && nowUs - state.startedRouterUs >= 3_000_000 -> nowUs - 3_000_000
            else -> 0
        }
        observe(generation, acceptedUs, playbackUs, playbackVerified)
    }
    @Synchronized fun fail(generation: Long, message: String): Boolean {
        if (state.generation != generation || (!state.streaming && state.phase != Phase.PERMISSION)) return false
        state = state.copy(phase = Phase.FAILED, message = "Mon micro : $message")
        return true
    }
    @Synchronized fun expire(nowUs: Long, generation: Long = state.generation): Boolean {
        val s = state
        if (s.generation != generation) return false
        if (s.phase == Phase.CONNECTING && nowUs - s.startedRouterUs > 10_000_000) {
            val detail=when {
                s.capturedSamples==0L -> "aucune donnée reçue du microphone"
                s.encodedPackets==0L -> "micro capturé · aucun paquet Opus produit"
                s.sentPackets==0L -> "Opus produit · envoi au relais bloqué"
                s.confirmationError.isNotEmpty() -> "confirmation inaccessible : ${s.confirmationError}"
                else -> "${s.sentPackets} paquets envoyés · réception par le relais non confirmée"
            }
            return fail(s.generation, detail)
        }
        if (s.phase == Phase.ACCEPTED && nowUs - s.acceptedRouterUs > 5_000_000)
            return fail(s.generation, "transmission interrompue")
        return false
    }
    @Synchronized fun stop() { state = State(generation = state.generation + 1) }
}
