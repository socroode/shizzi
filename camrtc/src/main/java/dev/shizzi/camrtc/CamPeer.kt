package dev.shizzi.camrtc

import android.content.Context
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** H264-preferred / Opus peer; DTLS-SRTP media goes directly over the local Wi-Fi. */
class CamPeer(
    private val context: Context,
    private val sink: SurfaceViewRenderer? = null,
    private val signal: (JSONObject) -> Unit,
    private val status: (String) -> Unit
) {
    private val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var localAudio: AudioTrack? = null
    private var session = ""
    private var isSender = false
    private var requested = "auto"
    private var currentQuality = "480p"
    private var stableGoodChecks = 0
    private var stableBadChecks = 0

    init {
        synchronized(Companion) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        sink?.init(egl.eglBaseContext,null)
        sink?.setEnableHardwareScaler(true)
    }

    /** Only call after the user explicitly started an Android camera+microphone FGS. */
    fun prepareSender() {
        if (capturer!=null) return
        val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context)
                         else Camera1Enumerator(false)
        val device = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("Aucune caméra Android détectée")
        val cam = enumerator.createCapturer(device, null)
            ?: throw IllegalStateException("Capture vidéo non disponible")
        capturer = cam
        captureHelper = SurfaceTextureHelper.create("ShizziCam-Video",egl.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        cam.initialize(captureHelper,context.applicationContext,videoSource!!.capturerObserver)
        cam.startCapture(854,480,20)
        localVideo = factory.createVideoTrack("shizzicam-video",videoSource)
        audioSource = factory.createAudioSource(MediaConstraints())
        localAudio = factory.createAudioTrack("shizzicam-audio",audioSource)
        localAudio?.setEnabled(true)
        status("Caméra + micro démarrés · 480p")
    }

    fun beginSender(newSession: String, quality: String) {
        isSender=true
        session=newSession
        requested=quality.takeIf(::validQuality) ?: "auto"
        createPeer()
        pc?.addTrack(localVideo ?: error("Capture vidéo absente"),listOf("shizzicam"))
        pc?.addTrack(localAudio ?: error("Capture audio absente"),listOf("shizzicam"))
        preferH264()
        applyQuality(if (requested=="auto") "480p" else requested)
        pc?.createOffer(offerObserver(),MediaConstraints())
    }

    fun beginViewer(newSession: String) {
        isSender=false
        session=newSession
        createPeer()
        status("Connexion à la caméra…")
    }

    private fun createPeer() {
        pc?.close()
        pc?.dispose()
        val config=PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc=factory.createPeerConnection(config,object:PeerConnection.Observer {
            override fun onSignalingChange(newState:PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState:PeerConnection.IceConnectionState) {
                status("Wi-Fi caméra : "+newState.name)
            }
            override fun onIceConnectionReceivingChange(receiving:Boolean) {}
            override fun onIceGatheringChange(state:PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(ice:IceCandidate) {
                if (session.isNotEmpty()) signal(JSONObject().put("type","ice")
                    .put("session",session).put("mid",ice.sdpMid)
                    .put("mline",ice.sdpMLineIndex).put("candidate",ice.sdp))
            }
            override fun onIceCandidatesRemoved(candidates:Array<IceCandidate>) {}
            override fun onAddStream(stream:MediaStream) {
                stream.videoTracks.firstOrNull()?.addSink(sink ?: return)
            }
            override fun onRemoveStream(stream:MediaStream) {}
            override fun onDataChannel(channel:DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver:RtpReceiver,streams:Array<MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { track -> sink?.let(track::addSink) }
                (receiver.track() as? AudioTrack)?.setEnabled(true)
            }
            override fun onTrack(transceiver:RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.let { track ->
                    sink?.let(track::addSink)
                }
                (transceiver.receiver.track() as? AudioTrack)?.setEnabled(true)
            }
        }) ?: error("Impossible de créer une session WebRTC")
    }

    private fun preferH264() {
        val connection=pc ?: return
        val caps = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        val h264=caps.codecs.filter { it.mimeType.equals("video/H264",true) }
        if (h264.isEmpty()) {status("Codec H264 indisponible sur cet appareil");return}
        connection.transceivers.filter { it.mediaType==MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            .forEach { it.setCodecPreferences(h264) }
    }

    private fun offerObserver():SdpObserver = observer(onCreate={ desc ->
        pc?.setLocalDescription(observer(onSet={ signal(JSONObject().put("type","offer")
            .put("session",session).put("sdp",desc.description)) }),desc)
    })

    private fun answerObserver():SdpObserver=observer(onCreate={desc ->
        pc?.setLocalDescription(observer(onSet={signal(JSONObject().put("type","answer")
            .put("session",session).put("sdp",desc.description))}),desc)
    })

    private fun observer(onCreate:(SessionDescription)->Unit={},onSet:()->Unit={}):SdpObserver =
        object:SdpObserver{
            override fun onCreateSuccess(desc:SessionDescription){onCreate(desc)}
            override fun onSetSuccess(){onSet()}
            override fun onCreateFailure(error:String){status("SDP : "+error)}
            override fun onSetFailure(error:String){status("SDP distant : "+error)}
        }

    fun accept(event: JSONObject) {
        if (event.optString("session")!=session) return
        when (event.optString("type")) {
            "offer" -> if (!isSender) {
                val desc=SessionDescription(SessionDescription.Type.OFFER,event.getString("sdp"))
                pc?.setRemoteDescription(observer(onSet={
                    pc?.createAnswer(answerObserver(),MediaConstraints())
                }),desc)
            }
            "answer" -> if (isSender) {
                val desc=SessionDescription(SessionDescription.Type.ANSWER,event.getString("sdp"))
                pc?.setRemoteDescription(observer(),desc)
            }
            "ice" -> pc?.addIceCandidate(IceCandidate(event.optString("mid"),
                event.optInt("mline"),event.optString("candidate")))
            "quality" -> if (isSender) setQuality(event.optString("quality"))
            "stop" -> {pc?.close();status("Surveillance terminée")}
        }
    }

    fun setQuality(quality:String) {
        if (!validQuality(quality)) return
        requested=quality
        if (isSender) applyQuality(if (quality=="auto") "480p" else quality)
    }

    private fun applyQuality(quality:String) {
        if (quality==currentQuality && pc==null) return
        val (width,height,fps,bitrate)=when (quality) {
            "360p" -> listOf(640,360,15,700000)
            "720p" -> listOf(1280,720,30,2500000)
            else -> listOf(854,480,24,1200000)
        }
        try {
            capturer?.changeCaptureFormat(width,height,fps)
            pc?.senders?.filter { it.track()?.kind()=="video" }?.forEach { sender ->
                val parameters=sender.parameters
                parameters.encodings.forEach { it.maxBitrateBps=bitrate;it.maxFramerate=fps }
                sender.setParameters(parameters)
            }
            currentQuality=quality
            status("Vidéo "+quality+" · son Opus")
        }catch (e:Exception){status("Qualité limitée par le téléphone : "+e.javaClass.simpleName)}
    }

    /** WebRTC already controls RTP bitrate; hysteresis adds resolution adaptation when stats allow it. */
    fun adjustAutomatic() {
        if (!isSender || requested!="auto") return
        val connection=pc ?:return
        connection.getStats(RTCStatsCollectorCallback { report ->
            val estimates=report.statsMap.values
                .filter { it.type=="candidate-pair" && it.members["state"]=="succeeded" }
                .mapNotNull { (it.members["availableOutgoingBitrate"] as? Number)?.toDouble() }
            val bps=estimates.maxOrNull() ?: return@RTCStatsCollectorCallback
            val down=when(currentQuality){"720p"->bps<1400000;"480p"->bps<650000;else->false}
            val up=when(currentQuality){"360p"->bps>1300000;"480p"->bps>3500000;else->false}
            if(down){stableBadChecks++;stableGoodChecks=0}
            else if(up){stableGoodChecks++;stableBadChecks=0}
            else {stableBadChecks=0;stableGoodChecks=0}
            if(stableBadChecks>=2){
                stableBadChecks=0
                applyQuality(if(currentQuality=="720p")"480p" else "360p")
            } else if (stableGoodChecks>=4) {
                stableGoodChecks=0
                applyQuality(if(currentQuality=="360p")"480p" else "720p")
            }
        })
    }

    fun close() {
        session=""
        pc?.close();pc?.dispose();pc=null
        try{capturer?.stopCapture()}catch (_:InterruptedException){}
        capturer?.dispose();capturer=null
        localVideo?.dispose();localAudio?.dispose()
        videoSource?.dispose();audioSource?.dispose()
        captureHelper?.dispose()
        sink?.release()
        factory.dispose();egl.release()
    }
    companion object {
        private var initialized=false
        fun validQuality(value:String)=value in setOf("auto","360p","480p","720p")
    }
}
