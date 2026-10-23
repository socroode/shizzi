# ShizziCam 0.4.3.5.13 A/V

Update branch derived from ShizziCam .12; original Shizzi .11 untouched.

- Shizzi+Cam 0.3.2-cam-av preserves legacy portal, vouchers, media, messages, calls. Camera is gated by HTTPS /api/v1/cam/capabilities and is disabled on original Shizzi .11.
- Native WebRTC H264-preferred video and Opus audio using libwebrtc, encrypted by DTLS-SRTP.
- 360p 15fps / 480p 24fps / 720p 30fps; auto quality uses WebRTC bandwidth estimates plus hysteresis.
- User-initiated camera/microphone foreground service, no auto start or recording.
- Router has HTTPS signaling, authenticated by Shizzi account and admin token, single viewer per camera, state expires after 20 seconds.
- Admin APK uses the original Admin credentials, TLS pinning, speaker audio and quality selector.

**Limitation:** the media path is WebRTC P2P, not yet TURN-relayed. Some Android hotspots prevent Wi-Fi client-to-client UDP; if client isolation exists the stream will not connect. Physical client testing required. No claim of 24/7 reliability, production readiness, or 6 concurrent cameras before real tests.