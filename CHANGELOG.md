# Changelog

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.7.3] - 2026-09-25

### Fixed

- Switched the Shizzi test network and TUN to IPv4-only after device testing showed that the previous dual-stack path could leave YouTube and Messenger waiting tens of seconds before falling back to IPv4.
- Removed the IPv6 TUN address and IPv6 DNS advertisement from the active tethering path while keeping TCP, UDP/QUIC, MTU 1500, VPN handling, vouchers, quotas, prepaid accounts and traffic shaping unchanged.
- Updated the compatibility probe so Q6 verifies that the published Shizzi test network has no routable IPv6 address or IPv6 DNS server.

### Compatibility

- Shizzi Conso remains **v1.2.0** and requires no update.
- Existing prepaid accounts, vouchers, balances, validity and DataStore records remain unchanged from Shizzi 1.7.2.

## [1.7.2] - 2026-09-24

### Fixed

- The TUN/tethering compatibility probe now follows the same startup order as
  the production session: stop the downstream, clear stale test-network state,
  create and publish the new TUN, attach the datapath, prefer test networks,
  then restart Wi-Fi tethering.
- Stale Shizzi `testtunN` networks are torn down before a new probe TUN is
  created, preventing Android from remaining attached to the previous test
  network (for example `testtun8` while the current run owns `testtun9`).
- Q5 now evaluates the owned TUN after the downstream restart instead of
  intentionally testing the obsolete pre-TUN restart sequence.

### Shizzi Conso 1.2.0

- Simplified the app to a single **OUVRIR LA CONNEXION COMPTE** action.
- Removed the direct **MA CONSOMMATION** and **RECHARGER MON COMPTE** shortcuts.
- The account page remains the single customer entry point for login, active
  plan, balance, speed, validity, recharge and live consumption.
- The launcher still opens `http://192.0.2.1/`, so existing Shizzi account
  and recharge routes remain unchanged.

### Compatibility

- Shizzi Conso **v1.2.0** is designed for Shizzi Hotspot 1.7.2.
- Prepaid accounts, vouchers, balances, validity and existing DataStore records
  remain unchanged from Shizzi 1.6.0/1.7.1.

## [1.7.1] - 2026-09-24

### Fixed

- Prepaid account login now creates a local browser/captive-session token in
  addition to the existing client-IP authorization.
- Account recharge can recover the authenticated account when Android changes
  the portal request address between login and `/account/recharge`.
- The recharge form carries the local session token, and the portal also keeps
  it in a local HTTP cookie for normal browser navigation.
- A new login for the same account invalidates the previous browser session,
  preserving the one-active-device account rule.

### Compatibility

- Shizzi Conso remains **v1.1.0** and requires no update.
- Existing Conso routes are unchanged: `/`, `/status`, `/status.json`,
  `/account/login`, and `/account/recharge`.
- Shizzi 1.6.0/1.7.0 prepaid accounts, vouchers, balances, validity and settings
  remain stored in the same DataStore format.

## [1.7.0] - 2026-09-24

### Added

- A richer prepaid account panel in the captive portal showing the active plan,
  remaining balance, allocated download/upload rate, and remaining validity.
- A more colorful live consumption page while keeping the existing local
  `/status` and `/status.json` routes used by Shizzi Conso 1.1.0.
- Android unit tests that decode Shizzi 1.6 prepaid-account and redeemed-voucher
  records before every build.

### Changed

- The default captive portal now uses a colorful responsive card layout with
  clearer account, recharge, status, speed, and validity information.
- Shizzi Conso remains at 1.1.0; the customer pages are still served by Shizzi
  Hotspot, so no Conso update is required for these visual changes.
- Shizzi 1.7.0 keeps the 1.6.0 DataStore keys and serialized prepaid/voucher
  fields unchanged, so existing 1.6.0 records remain readable.


### Shizzi Conso 1.1.0

- Added an account-oriented launcher for Shizzi 1.6 prepaid accounts.
- The normal Wi-Fi captive window remains the place where the customer signs in with account number + PIN.
- Added one-tap browser access to the live account usage page.
- Added one-tap browser access to the local account/recharge page, including when the account has 0 MB and Internet is blocked.
- Kept Shizzi Conso lightweight: it does not store balances, vouchers, or hotspot administration data.


## [1.6.0] - 2026-09-24

### Added

- Permanent prepaid accounts with an account number and PIN.
- Account Editor in Shizzi to create accounts, copy credentials, enable or disable
  accounts, and regenerate a PIN.
- Local account login and one-use coupon recharge endpoints for clients on the
  hotspot, including accounts that currently have no Internet balance.

### Changed

- Vouchers can now be redeemed as one-use account recharges instead of being the
  customer's permanent identity.
- A Data recharge adds the remaining Data balance, applies the new coupon's
  speed, and starts the new validity period according to the recharge.
- If a Data coupon is entered during an active unlimited period, the Data balance
  waits behind unlimited access and its validity begins after unlimited ends.
- Unlimited recharges accumulate validity. Existing queued Data is preserved
  while unlimited access remains active.
- The same prepaid account can move to another device; the previous live account
  session is replaced.
- Existing portable-voucher login remains available when no prepaid accounts
  have been created.


## [1.5.0] - 2026-09-24

### Changed

- Prepaid voucher codes are now portable balances rather than device-bound
  credentials. A code can move from a tablet to a phone without resetting its
  original activation time, remaining validity or cumulative data usage.
- Entering an already-active code on another client transfers the live
  authorization to the new client; the previous device returns to the captive
  portal.
- Voucher quota accounting is now stored on the voucher itself instead of being
  derived from a device's lifetime traffic counter.
- Existing device-bound vouchers are migrated automatically: activation time is
  preserved, legacy usage is carried forward when it can be derived, and the
  stored device identifier is cleared.

## [1.4.0] - 2026-09-24

### Fixed

- A valid prepaid voucher now stays provisionally authorized while Android
  resolves the physical hotspot client. Manager synchronization no longer
  overwrites a freshly accepted voucher with an immediate deny.
- Unresolved portal claims are retried for 30 seconds instead of being cleared
  on the first synchronization cycle.
- Portal authorizations now fail closed if the voucher disappears, is disabled,
  expires, exhausts its quota, or never finishes device assignment.
- After a voucher is accepted, the captive portal hands the client back to
  Android's connectivity check so the Wi-Fi network can become validated
  without choosing “Use this network as is”.

### Changed

- Voucher Studio remains complete: custom download/upload speeds, quota,
  validity, templates, batch generation, inventory states, CSV export and the
  live usage page are retained.
- Prepaid vouchers are now the authoritative per-device speed/data policy.
  Legacy manual per-device speed, data quota, quick profiles, pause controls
  and manual voucher assignment were removed from the hotspot UI.
- Dynamic bandwidth sharing is retained. The global share can reduce a
  client's instantaneous rate under congestion, but can never raise it above
  that voucher's configured maximum.
- Normal/Priority/VIP weighting, device naming, usage history, global hotspot
  limits, max-client control and immediate administrative blocking remain.

## [0.8.1] - 2026-09-23

### Fixed

- Startup now stops Wi-Fi tethering and clears the test-network preference before
  creating the new TUN, so Android cannot keep routing through an older Shizzi
  `testtunN` while the replacement is being prepared.
- Stale Shizzi test networks are identified by the TUN interface plus Shizzi's
  IPv4/IPv6 addresses, torn down, and waited out before startup continues.
- Startup recovery and the active-session watchdog also purge competing stale
  Shizzi TUNs before forcing tethering to reselect the owned interface.
- Startup remains fail-closed: if a competing Shizzi test network cannot be
  removed, the session refuses to become active instead of accepting the wrong
  tethering upstream.

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
