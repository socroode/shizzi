package dev.shizzi.conso

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local-only Shizzi call screen.
 *
 * Signalling stays on the Shizzi captive-portal address and media uses WebRTC
 * with no STUN/TURN servers. 1:1 video captures at 854x480; group video at
 * 640x360. Group calls use a bounded local mesh coordinated by the router.
 */
class MessengerCallActivity : Activity() {

    private lateinit var connectivity: ConnectivityManager
    private var wifiNetwork: Network? = null
    private lateinit var audioManager: AudioManager

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var remoteGrid: GridLayout
    private var localRenderer: SurfaceViewRenderer? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val remoteRenderers = ConcurrentHashMap<String, SurfaceViewRenderer>()
    private val pendingIce = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    private val running = AtomicBoolean(false)
    private var lastEventId = 0L

    private var myAccount = ""
    private var targetAccount = ""
    private var groupId = ""
    private var callId = ""
    private var incoming = false
    private var groupCall = false
    private var video = false
    private var inviter = ""
    private var callStarted = false
    private var finishingCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = getSystemService(ConnectivityManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)

        val uri = intent?.data
        val mode = uri?.host.orEmpty()
        targetAccount = uri?.getQueryParameter("to").orEmpty()
        groupId = uri?.getQueryParameter("group").orEmpty()
        callId = uri?.getQueryParameter("callId").orEmpty()
        inviter = uri?.getQueryParameter("from").orEmpty()
        video = uri?.getQueryParameter("video") == "1"
        incoming = mode == "incoming"
        groupCall = mode == "group" || groupId.isNotBlank()

        if (callId.isBlank()) callId = UUID.randomUUID().toString()

        buildUi()

        if (!bindWifi()) {
            fail("Aucun Wi-Fi Shizzi actif.")
            return
        }
        requestMediaPermissions()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(3, 7, 18))
            setPadding(dp(12), dp(18), dp(12), dp(12))
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            text = if (video) "Préparation de l'appel vidéo…" else "Préparation de l'appel audio…"
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(status)

        remoteGrid = GridLayout(this).apply {
            columnCount = if (groupCall) 2 else 1
            rowCount = if (groupCall) 3 else 1
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            remoteGrid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        controls.addView(Button(this).apply {
            text = "Raccrocher"
            isAllCaps = false
            setOnClickListener { endCall(true) }
        })
        root.addView(controls)

        setContentView(root)
    }

    private fun requestMediaPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) wanted += Manifest.permission.CAMERA
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            startCallEngine()
        } else {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            fail("Microphone/caméra refusé.")
            return
        }
        startCallEngine()
    }

    private fun bindWifi(): Boolean {
        val network = connectivity.activeNetwork?.takeIf {
            connectivity.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false

        if (!connectivity.bindProcessToNetwork(network)) return false
        wifiNetwork = network
        return true
    }

    private fun startCallEngine() {
        if (callStarted) return
        callStarted = true

        thread(name = "ShizziCallStart") {
            try {
                val me = apiGet("/me")
                myAccount = me.getJSONObject("account").getString("number")

                runOnUiThread { initWebRtc() }

                var attempts = 0
                while (factory == null && attempts++ < 50) Thread.sleep(50)
                if (factory == null) error("WebRTC indisponible.")

                running.set(true)
                startEventPolling()

                if (groupCall) {
                    startGroupMode()
                } else if (!incoming) {
                    if (targetAccount.isBlank()) error("Destinataire manquant.")
                    createOffer(targetAccount)
                } else {
                    runOnUiThread { status.text = "Connexion à l'appel entrant…" }
                }
            } catch (failure: Throwable) {
                runOnUiThread { fail(failure.message ?: "Impossible de démarrer l'appel.") }
            }
        }
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions(),
        )

        val egl = EglBase.create()
        eglBase = egl

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(egl.eglBaseContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        val pcFactory = factory ?: return
        audioSource = pcFactory.createAudioSource(MediaConstraints())
        audioTrack = pcFactory.createAudioTrack("shizzi-audio", audioSource).apply {
            setEnabled(true)
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = video || groupCall

        if (!video) {
            status.text = if (groupCall) {
                "Appel audio de groupe · réseau local"
            } else {
                "Appel audio local"
            }
            return
        }

        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(this)) Camera2Enumerator(this)
            else Camera1Enumerator(false)

        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("Aucune caméra disponible.")

        val videoCapturer = enumerator.createCapturer(cameraName, null)
            ?: throw IllegalStateException("Impossible d'ouvrir la caméra.")
        capturer = videoCapturer

        val source = pcFactory.createVideoSource(false)
        videoSource = source
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "ShizziCamera",
            egl.eglBaseContext,
        ).also {
            videoCapturer.initialize(it, applicationContext, source.capturerObserver)
        }

        val width = if (groupCall) 640 else 854
        val height = if (groupCall) 360 else 480
        val fps = if (groupCall) 15 else 20
        videoCapturer.startCapture(width, height, fps)

        videoTrack = pcFactory.createVideoTrack("shizzi-video", source).apply {
            setEnabled(true)
        }

        val preview = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setMirror(true)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
        localRenderer = preview
        videoTrack?.addSink(preview)

        root.addView(
            preview,
            1,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(if (groupCall) 120 else 150),
            ),
        )

        status.text = if (groupCall) {
            "Vidéo groupe · 360p max · réseau local"
        } else {
            "Vidéo 1↔1 · 480p max · réseau local"
        }
    }

    private fun startGroupMode() {
        if (groupId.isBlank()) throw IllegalStateException("Groupe manquant.")

        val join = apiPost(
            "/room",
            JSONObject()
                .put("action", "join")
                .put("callId", callId)
                .put("groupId", groupId)
                .put("media", if (video) "video" else "audio"),
        )

        val existing = join.optJSONArray("members") ?: JSONArray()

        if (!incoming) {
            val groups = apiGet("/groups").getJSONArray("groups")
            val group = (0 until groups.length())
                .map { groups.getJSONObject(it) }
                .firstOrNull { it.getString("id") == groupId }
                ?: throw IllegalStateException("Groupe introuvable.")
            val members = group.getJSONArray("members")
            for (index in 0 until members.length()) {
                val account = members.getString(index)
                if (account != myAccount) createOffer(account)
            }
        } else {
            for (index in 0 until existing.length()) {
                val account = existing.getString(index)
                if (account != myAccount && account != inviter) {
                    createOffer(account)
                }
            }
        }
    }

    private fun createPeer(remote: String): PeerConnection {
        peers[remote]?.let { return it }
        val pcFactory = factory ?: error("WebRTC non initialisé.")
        val config = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = pcFactory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                        newState == PeerConnection.IceConnectionState.COMPLETED
                    ) {
                        runOnUiThread {
                            status.text = if (groupCall) {
                                "Appel de groupe actif · ${peers.size} liaison(s)"
                            } else {
                                "Appel local connecté"
                            }
                        }
                    }
                    if (newState == PeerConnection.IceConnectionState.FAILED) {
                        runOnUiThread { status.text = "Connexion avec $remote interrompue." }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    signal(
                        remote,
                        "ice",
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid.orEmpty(),
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dataChannel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    mediaStreams: Array<out MediaStream>?,
                ) {
                    val remoteTrack = receiver?.track() as? VideoTrack ?: return
                    if (!video) return
                    runOnUiThread {
                        val renderer = ensureRemoteRenderer(remote)
                        remoteTrack.addSink(renderer)
                    }
                }
            },
        ) ?: error("Impossible de créer la connexion WebRTC.")

        audioTrack?.let { pc.addTrack(it, listOf("shizzi")) }
        videoTrack?.let { pc.addTrack(it, listOf("shizzi")) }

        peers[remote] = pc
        applyVideoBitrate(pc)

        pendingIce.remove(remote)?.forEach { pc.addIceCandidate(it) }
        return pc
    }

    private fun ensureRemoteRenderer(remote: String): SurfaceViewRenderer {
        remoteRenderers[remote]?.let { return it }
        val egl = eglBase ?: error("EGL absent.")
        val renderer = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setMirror(false)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setBackgroundColor(Color.BLACK)
        }
        remoteRenderers[remote] = renderer

        val columns = if (groupCall) 2 else 1
        val size = if (groupCall) dp(170) else dp(320)
        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = size
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        }
        remoteGrid.columnCount = columns
        remoteGrid.addView(renderer, params)
        return renderer
    }

    private fun applyVideoBitrate(pc: PeerConnection) {
        if (!video) return
        val maxBitrate = if (groupCall) GROUP_VIDEO_MAX_BPS else DIRECT_VIDEO_MAX_BPS
        pc.senders.forEach { sender ->
            if (sender.track()?.kind() != "video") return@forEach
            val parameters = sender.parameters
            parameters.encodings.forEach { encoding ->
                encoding.maxBitrateBps = maxBitrate
            }
            sender.setParameters(parameters)
        }
    }

    private fun createOffer(remote: String) {
        if (remote.isBlank() || remote == myAccount) return
        val pc = createPeer(remote)
        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    description ?: return
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                signal(remote, "offer", sdp = description.description)
                            }
                        },
                        description,
                    )
                }
            },
            MediaConstraints(),
        )
    }

    private fun acceptOffer(remote: String, sdp: String) {
        val pc = createPeer(remote)
        val description = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    flushPendingIce(remote, pc)
                    pc.createAnswer(
                        object : SimpleSdpObserver() {
                            override fun onCreateSuccess(answer: SessionDescription?) {
                                answer ?: return
                                pc.setLocalDescription(
                                    object : SimpleSdpObserver() {
                                        override fun onSetSuccess() {
                                            signal(remote, "answer", sdp = answer.description)
                                        }
                                    },
                                    answer,
                                )
                            }
                        },
                        MediaConstraints(),
                    )
                }
            },
            description,
        )
    }

    private fun acceptAnswer(remote: String, sdp: String) {
        val pc = peers[remote] ?: return
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    flushPendingIce(remote, pc)
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    private fun addIce(remote: String, candidate: IceCandidate) {
        val pc = peers[remote]
        if (pc == null || pc.remoteDescription == null) {
            pendingIce.compute(remote) { _, list ->
                (list ?: mutableListOf()).apply { add(candidate) }
            }
            return
        }
        pc.addIceCandidate(candidate)
    }

    private fun flushPendingIce(remote: String, pc: PeerConnection) {
        pendingIce.remove(remote)?.forEach { pc.addIceCandidate(it) }
    }

    private fun signal(
        to: String,
        kind: String,
        sdp: String = "",
        candidate: String = "",
        sdpMid: String = "",
        sdpMLineIndex: Int = 0,
    ) {
        thread(name = "ShizziSignal") {
            runCatching {
                apiPost(
                    "/signal",
                    JSONObject()
                        .put("to", to)
                        .put("kind", kind)
                        .put("callId", callId)
                        .put("media", if (video) "video" else "audio")
                        .put("groupId", groupId)
                        .put("sdp", sdp)
                        .put("candidate", candidate)
                        .put("sdpMid", sdpMid)
                        .put("sdpMLineIndex", sdpMLineIndex),
                )
            }
        }
    }

    private fun startEventPolling() {
        thread(name = "ShizziCallEvents") {
            while (running.get()) {
                try {
                    val response = apiGet("/events?after=$lastEventId")
                    val events = response.optJSONArray("events") ?: JSONArray()
                    for (index in 0 until events.length()) {
                        val event = events.getJSONObject(index)
                        lastEventId = maxOf(lastEventId, event.optLong("id"))
                        if (event.optString("callId") != callId) continue
                        handleEvent(event)
                    }
                } catch (_: Throwable) {
                }
                Thread.sleep(350)
            }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val from = event.optString("from")
        if (from.isBlank() || from == myAccount) return
        when (event.optString("kind")) {
            "offer" -> acceptOffer(from, event.optString("sdp"))
            "answer" -> acceptAnswer(from, event.optString("sdp"))
            "ice" -> {
                val raw = event.optString("candidate")
                if (raw.isNotBlank()) {
                    addIce(
                        from,
                        IceCandidate(
                            event.optString("sdpMid"),
                            event.optInt("sdpMLineIndex"),
                            raw,
                        ),
                    )
                }
            }
            "decline" -> {
                closePeer(from)
                if (!groupCall) runOnUiThread { fail("Appel refusé.") }
            }
            "hangup", "room-leave" -> {
                closePeer(from)
                if (!groupCall) runOnUiThread { endCall(false) }
            }
        }
    }

    private fun closePeer(remote: String) {
        peers.remove(remote)?.let {
            runCatching { it.close() }
            runCatching { it.dispose() }
        }
        pendingIce.remove(remote)
        val renderer = remoteRenderers.remove(remote)
        if (renderer != null) {
            runOnUiThread {
                remoteGrid.removeView(renderer)
                runCatching { renderer.release() }
            }
        }
    }

    private fun apiGet(path: String): JSONObject = request("GET", path, null)

    private fun apiPost(path: String, body: JSONObject): JSONObject =
        request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val network = wifiNetwork ?: error("Wi-Fi Shizzi indisponible.")
        val connection = network.openConnection(URL(API_BASE + path)) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 4_000
        connection.readTimeout = 5_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(bytes) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val json = JSONObject(text.ifBlank { "{}" })
        if (code !in 200..299 || !json.optBoolean("ok", true)) {
            error(json.optString("message", "Erreur Messenger ($code)."))
        }
        return json
    }

    private fun endCall(sendSignal: Boolean) {
        if (finishingCall) return
        finishingCall = true
        running.set(false)

        if (sendSignal) {
            val destinations = peers.keys.toList().ifEmpty {
                listOfNotNull(
                    targetAccount.takeIf { it.isNotBlank() },
                    inviter.takeIf { it.isNotBlank() },
                )
            }.distinct()
            destinations.forEach { signal(it, "hangup") }
        }

        if (groupCall && groupId.isNotBlank()) {
            thread {
                runCatching {
                    apiPost(
                        "/room",
                        JSONObject()
                            .put("action", "leave")
                            .put("callId", callId)
                            .put("groupId", groupId)
                            .put("media", if (video) "video" else "audio"),
                    )
                }
            }
        }

        peers.keys.toList().forEach(::closePeer)
        finish()
    }

    private fun fail(message: String) {
        status.text = message
        status.setTextColor(Color.rgb(248, 113, 113))
    }

    override fun onDestroy() {
        running.set(false)
        peers.keys.toList().forEach(::closePeer)

        videoTrack?.let { track ->
            localRenderer?.let { renderer -> runCatching { track.removeSink(renderer) } }
            runCatching { track.dispose() }
        }
        localRenderer?.let { runCatching { it.release() } }
        remoteRenderers.values.forEach { runCatching { it.release() } }
        remoteRenderers.clear()

        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { eglBase?.release() }

        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        connectivity.bindProcessToNetwork(null)

        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        endCall(true)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private companion object {
        const val PERMISSION_REQUEST = 410
        const val API_BASE = "http://192.0.2.1/api/v1/messenger"
        const val DIRECT_VIDEO_MAX_BPS = 900_000
        const val GROUP_VIDEO_MAX_BPS = 450_000
    }
}
