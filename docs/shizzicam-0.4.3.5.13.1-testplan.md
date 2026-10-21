# ShizziCam 0.4.3.5.13.1 / Android 11+ correction candidate

## Scope
- Keep Shizzi production 0.4.3.5.11 unchanged.
- Camera telephone: Shizzi+Cam (package dev.shizzi.conso) Android 11+; account/PIN and historical chat/media/calls preserved.
- Router telephone: ShizziCam Hotspot Android 11+; separate from camera or viewer.
- Viewer telephone: ShizziCam Admin (package dev.shizzi.camadmin) Android 11+; Admin login required.
- Initial validation requires three **different** telephones (router, camera, viewer), then 2, 4, 6, up to 8 cameras progressively; no untested capacity guarantees.
- Capture options 360p, 480p, 720p, Auto; hardware encoders on Samsung, OPPO, other Android vendors must be tested, with Camera2->Camera1 fallback.
- WebRTC JNI keep rules added to conso, camadmin and camrtc library consumer rules; cause was JNI null class during JNI_OnLoad on Samsung A07 Android 16.
- Phone camera shows a live preview, service state and persistent camera/microphone FGS notification.
- Shizzi+Cam signed asset is ~48 MB. Verified portal download 32 MiB cap replaced by 128 MiB cap and integrity validation maintained. Error responses are plain text, not APK attachments; download anchor no longer forces *.apk extension on HTTP error.
- Android minSdk 30 (Android 11) remains for all 3 modules, with higher versionCode and existing permanent keystore, in-place client updates.

## Important limitations (do not misrepresent)
- **Media still uses encrypted local WebRTC direct peer transport. The router provides authentication and signaling, not a TURN media relay.** This is not yet the requested isolation-proof router media relay. On hotspots with Wi-Fi client isolation, direct transport may fail.
- A future isolated-client-capable router media relay requires separate architecture verification of NAT/TUN/AP UDP reachability (or another fully implemented media relay) before claiming 3-phone operation independent of P2P.
- JNI keep rules and Android builds are regression checks, not evidence of successful hardware video/audio on every Android brand.
- Conduct three-device hardware tests before any customer deployment. Verify no auto Wi-Fi reconnect, account portability, camera permissions, audio, automatic quality, and 24h capture with physical phones.
- No public release.
