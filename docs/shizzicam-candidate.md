# ShizziCam candidate 0.4.3.5.12

This is an independent experimental branch built upon Shizzi 0.4.3.5.11 (versionCode 39).
It must not be deployed to paying customers without real device validation.

- Router: app v0.4.3.5.12 (versionCode 40), original package dev.shizzi and original voucher/portal functionality.
- Camera client: conso v0.3.1-cam (versionCode 18), original package dev.shizzi.conso.
- Viewer: camadmin v0.1.0, package dev.shizzi.camadmin. It shares read-only
  Kotlin source files from the existing admin module for pinned TLS and Admin API;
  it does not modify the original Admin APK.

## Experimental camera protocol

- App user first authenticates their account in the ordinary captive portal.
- Shizzi+Cam camera is started explicitly with CAMERA foreground-service permission.
- The app pairs router TLS certificate against router displayed fingerprint.
- JPEG stills are POSTed to /api/v1/cam/frame over pinned HTTPS by current
  authenticated account; the Go datapath only keeps 1 JPEG per account in memory.
- ShizziCam Admin logs in with existing remote-admin credentials using pinned HTTPS.
- GET /api/v1/cam/list and /api/v1/cam/frame?account=… require the admin token.
- Maximum 16 cameras, max frame 180KB, expired after 15 seconds.
- No permanent local recording, motion analysis, microphone capture, WebRTC or
  network-wide VPN video delivery in this candidate.
- Android Camera2 still-capture cadence is about 1 image per second and is not
  yet a smooth video stream. Device performance varies. This is a first technical
  candidate, **not the full live video system**.

## Security and operational caveats

- The Wi-Fi remains open, but frames require existing account login and HTTPS
  with a manually confirmed TLS fingerprint.
- Web clients cannot access frames without router Admin token.
- DO NOT auto-synchronize camera image data or usage with voucher quotas.
- The current client app delivery list embeds Shizzi+Cam as Shizzi-Plus.apk; the
  standalone ShizziCam Admin APK is distributed only through CI artifacts.
- Both router and Plus reuse the ORIGINAL Android package IDs, so installing
  these signed variants **updates/replaces** the installed originals.
- No public releases and no auto-updates.
