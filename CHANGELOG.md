# Changelog

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.4.1.1] - 2026-10-06

### Fixed

- User accounts are portable and are never permanently bound to one phone, IP address or MAC address.
- A valid login on another phone takes over the single active account session instead of leaving the account locked to the previous device.
- A phone that stays on the Shizzi Wi-Fi keeps its authenticated session through sleep, Wi-Fi reassociation and DHCP IPv4 changes when Android exposes the same device MAC.
- Temporary disappearance from Android's tethering client list no longer logs a sleeping phone out.
- DHCP address reuse by a different MAC does not inherit the previous user's session.

### Changed

- Shizzi Hotspot is version 0.4.4.1.1 (versionCode 28).
- The Hotspot APK embeds both the compatible Shizzi+ APK and the compatible Shizzi Admin APK as signed release assets.

## [0.4.3.9] - 2026-09-30

### Security

- Shizzi Media now requires an active Shizzi account session on the requesting device.
- Unauthenticated hotspot clients cannot open /media/, browse the catalog, open player pages or request /media/stream URLs.
- The Media backend on port 8088 now binds to loopback only, preventing LAN clients from bypassing the captive-portal account check.
- The Media card is hidden until a Shizzi account is connected.

### Changed

- Shizzi+ 0.2.4 routes Media through the secured captive-portal /media/ path instead of connecting directly to port 8088.
- Media relative links remain under the /media/ proxy so catalog, player and stream requests all pass through the account gate.
- Shizzi Admin remains 0.2.3.


## [0.4.3.8] - 2026-09-30

### Added

- Browser-only local Wi-Fi speed test at /speedtest/ with 10, 25, 50 and 100 MiB test sizes.
- Five-sample local latency measurement and Reno9-to-client throughput measurement with live progress.
- Conservative estimate of simultaneous 1080p streams at 3 Mbps using a 30% Wi-Fi safety margin.
- Portal card linking directly to the speed test before or after account login.
- Bounded server-generated speed-test payloads; no media file, Internet download or persistent storage is required.

### Changed

- The local speed test uses the captive-portal path itself so the measurement reflects the Shizzi router-to-client Wi-Fi path.
- Shizzi+ remains 0.2.3 and Shizzi Admin remains 0.2.3; only Shizzi Hotspot changes in this test build.


## [0.4.3.7] - 2026-09-30

### Added

- Local browser access to Shizzi Media at /media/ through the captive portal.
- Portal Media card available to PCs, phones and tablets connected to the Shizzi Wi-Fi.
- Local reverse proxy preserves HTTP Range/206 responses so browser seeking works for large videos.
- Regression test for /media/ route rewriting.

### Changed

- Media catalog links are relative, so the same Films/Séries/Musique interface works both directly in Shizzi+ and behind the captive-portal /media/ path.
- Shizzi+ remains 0.2.3 and Shizzi Admin remains 0.2.3; only Shizzi Hotspot changes in this test build.


## [0.4.3.6] - 2026-09-30

### Added

- Captive portal download card for Shizzi+ before or after account login.
- Local /download/shizzi-plus.apk route that stays entirely on the hotspot and does not consume Internet quota.
- Loopback-only Android distribution bridge so the Shizuku datapath can safely proxy the embedded client APK.
- Shizzi+ 0.2.3 is embedded in the Hotspot build with version, size and SHA-256 metadata shown by the portal.
- CI verifies the embedded APK package, version, permanent certificate and exact SHA-256.

### Changed

- Clients no longer need the Shizzi+ APK to be sent manually; they can install it from the captive portal.
- No hotspot subnet is hard-coded for the download path: clients use the same captive-portal address they already reached.


## [0.4.3.5] - 2026-09-29

### Added

- Fast Media scanner using batched Android DocumentsContract queries instead of repeated per-file DocumentFile calls.
- Live scan progress with file and folder counts on the router phone.
- Shizzi+ 0.2.3 HTML5 video fullscreen with landscape orientation, system-bar hiding and Back-to-exit-fullscreen behavior.

### Changed

- SAF remains the source of access permission; no broad storage permission is required.
- The legacy DocumentFile scanner remains as a compatibility fallback for unusual Android document providers.
- Shizzi Admin 0.2.3 is version-aligned with the 0.4.3.5 test suite.


## [0.4.3.4] - 2026-09-29

### Added

- Persistent local Media index stored on the router phone.
- Background indexing when a Films, Séries or Musique folder is selected or changed.
- Manual **Scanner la médiathèque** control with indexed item counts.

### Fixed

- Opening Films, Séries, a player page or a stream no longer recursively scans SAF storage on the client request path.
- Media pages now read the persistent index, so category opening is immediate after indexing.
- Updating one Media folder rebuilds only that category and preserves the other indexed categories.


## [0.4.3.3] - 2026-09-29

### Fixed

- Shizzi+ now hides the previous account portal while a Media page is loading, preventing stale TEKOMOPAO/account content from remaining visible.
- Shizzi+ tracks the requested portal origin so stale WebView callbacks cannot replace the current Media navigation state.
- Main-frame network and HTTP failures now show the actual Shizzi+/Media error instead of leaving an old page visible.
- Shizzi Media landing page no longer scans every configured library before rendering; category scanning happens only when opened.


## [0.4.3.2] - 2026-09-29

### Fixed

- Media client discovery now uses the actual IPv4 Wi-Fi gateway supplied by Android instead of hard-coded hotspot subnets.
- Router Media address discovery excludes real upstream network addresses rather than relying on Oppo/Samsung interface names.
- The same Media discovery path is shared by Shizzi+ and Shizzi Admin for OEM-independent Android compatibility.


## [0.4.3.1] - 2026-09-29

### Fixed

- Runs Shizzi Media in a dedicated Android process so a media-client failure cannot terminate the hotspot/TUN process.
- Contains client socket errors such as connection reset, socket closed and broken pipe.
- Replaces the unbounded media thread pool with a bounded six-client worker pool.
- Synchronizes selected SAF media folders when the isolated media process starts or restarts.


## [0.4.0-rc.3] - 2026-09-13

Adds a quick settings tile and an intent API for starting and stopping sessions
from other apps. New permissions screen in onboarding. Adds accent and design
language pickers, and animates screen changes and controls throughout. Adds a
VPN setting, and stops a VPN in another Android user from being mistaken for
this one. Supersedes 0.4.0-rc.1 and 0.4.0-rc.2.

### Added

- **VPN setting.** `Auto` binds to an active VPN if there is one, `Always`
  refuses to start without one, and `Never` leaves the datapath unbound. A
  session that ignores a live VPN says so on the home screen and in the
  notification.
- **Quick settings tile.** Start and stop sharing from the notification shade.
- **Intent API.** Start, stop, toggle, and query a session from another app.
  Off by default, token-authenticated. See [automation](docs/automation.md).
- **Permissions screen** in onboarding, replacing the Shizuku step. Also in
  settings.
- **Accent and design language pickers** in settings.
- **Motion tokens.** Durations, springs, and easing are theme values, so
  Neobrutalism moves mechanically while Material Expressive settles with a
  bounce.
- **Screen transitions.** Navigation slides and fades by screen depth, and
  onboarding fades into the home screen instead of cutting to it.
- **Press feedback in Neobrutalism**, which had none. Surfaces settle onto
  their shadow when pressed, covering every button, card, toggle, and swatch.
- Appearance glyphs spin a full turn on each press.
- **Version tap easter egg.** Three taps on the version label open a
  full-screen tethering icon pattern.

### Changed

- Material Expressive is the new default design language. Neobrutalism is still
  available.
- The connect button, status icon, settings sections, log rows, toasts, accent
  swatches, and the onboarding wizard animate their state changes.
- The tethering glyph accepts a brush, so it can carry a gradient. Icon only
  takes a flat tint.
- Compose moved to a BOM carrying Material3 1.4.0.

### Fixed

- A VPN running in another Android user, such as Samsung's Secure Folder, no
  longer counts as this profile's VPN. It could pin the datapath to a network
  the hotspot never routed over, and end the session when that unrelated VPN
  disconnected.
  ([#32](https://github.com/carlelieser/shizzi/issues/32))
- A VPN reconnect or radio handoff left the tethering upstream empty for a few
  seconds, which killed the session. Only real drift onto another interface
  ends it now.
  ([#22](https://github.com/carlelieser/shizzi/issues/22))
- Stopping a session could leave the hotspot on, because stopTethering lands
  asynchronously. It now retries.
  ([#22](https://github.com/carlelieser/shizzi/issues/22))
- Intent-triggered starts silently did nothing on Android 12+, which blocks
  foreground service starts from the background. Battery optimization exemption
  is now requested as a permission, and an undeliverable start says why.
- An automation toggle sent mid-startup tore down the session it meant to leave
  alone, having read the service as running before it had connected.
- The notification permission dialog appeared over the welcome screen on first
  launch. It's asked for in the Permissions step now, and a denial is visible
  instead of silent.
- The onboarding wizard drew the incoming step in both transition layers, so
  the slide animated identical content.
- Settings rows that open a picker trailed the same arrow as rows that navigate
  or act in place. They take a chevron now.
- The Neobrutalism bottom sheet stopped above the navigation bar, leaving a band
  of scrim below it. It spans the full display now.

## [0.3.0] - 2026-08-22

Adds support for Android 11 and 12 (API 30-32) by providing a tethering module update if necessary. Also adds an onboarding flow. Minor updates to the UI and better logging.

### Added

- **Android 11 and 12 (API 30-32) Support.** Through tethering module update.
- **Onboarding** With welcome, shizuku setup, and compatibility check.

### Changed

- **Better logging.** Improved logging throughout the codebase.
- **UI** Moved logging into settings, updated setting item labels and descriptions.
- **Log Viewer** was rebuilt around a menu, jump bands, and an empty state.
- Toasts can be swiped away and rank by weight rather than colour.
- Compose moved to the 2025.08.00 BOM, the build to AGP 8.9.2.

### Fixed

- Release builds no longer require a debuggable shell process, which kept the
  privileged side from starting outside a debug build.
- A slow Shizuku start no longer fails to bind.
- The shell context is attributed correctly on API 30.
- A failed context rebase is reported instead of killing the process.
- Better timeout messaging.
- Automatically tear down hotspot after diagnostic run.
- Datapath tests can now run in CI.

## [0.2.0] - 2026-08-19

Tethered devices now get working IPv6, and a session tells you what it is
actually doing — how many devices are connected, how much has gone through,
and whether traffic is leaving over your VPN.

### Added

- **IPv6 for tethered clients.** Connected devices get a real IPv6 address and
  reach the v6 internet. Previously they were handed a v6 route that led
  nowhere, so sites that prefer IPv6 stalled before falling back to IPv4.
- **VPN status on the session.** The app shows whether tethered traffic is
  going out through your VPN, both in the app and on the notification, so you
  no longer have to take it on trust.
- **Device count and data used.** The session notification reports how many
  devices are on the hotspot and how much data the tunnel has carried,
  updated as you watch.

### Changed

- **A dropped VPN now stops the session.** If you started tethering through a
  VPN and it goes away, the hotspot stops instead of quietly continuing over
  your normal connection. Sessions started without a VPN are unaffected.
- **Clearer notification wording.** The notification no longer describes
  tethering as "protected" — it said the same thing whether or not a VPN was
  up. It now states plainly whether devices are going out through one.
- **Minimum Android version is now 13 (API 33), enforced at install.** Older
  devices could install 0.1.0 but never tether with it; the feature it depends
  on does not exist below Android 13. They are now told at install time
  instead of after setup.

### Fixed

- **IPv6 traffic no longer bypasses your VPN.** With a VPN up, IPv6 traffic
  from tethered devices previously escaped the tunnel. This was the known
  limitation noted in 0.1.0 and is now resolved.
  ([#5](https://github.com/carlelieser/shizzi/issues/5),
  [#6](https://github.com/carlelieser/shizzi/issues/6))

## [0.1.0] - 2026-08-06

First public build.

### Added

- Wi-Fi tethering over a Shizuku-privileged test network: creates a test TUN
  interface, sets it as the preferred tethering upstream, and forwards hotspot
  traffic through a Go datapath.
- Requires Android 13 (API 33+) on arm64 and Shizuku 13.6.0+.

### Known limitations

- IPv6 was not suppressed on the downstream; v6 traffic could bypass the
  tunnel. Fixed in 0.2.0.

[0.4.0-rc.3]: https://github.com/carlelieser/shizzi/releases/tag/v0.4.0-rc.3
[0.3.0]: https://github.com/carlelieser/shizzi/releases/tag/v0.3.0
[0.2.0]: https://github.com/carlelieser/shizzi/releases/tag/v0.2.0
[0.1.0]: https://github.com/carlelieser/shizzi/releases/tag/v0.1.0
