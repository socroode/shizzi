<p align="center">
  <img src="docs/assets/icon.png" width="96" alt="">
</p>

<h1 align="center">Shizzi</h1>

<p align="center">
  Wi-Fi tethering over a Shizuku-privileged test network.
</p>

Creates a test TUN interface, sets it as the preferred tethering upstream, and
forwards hotspot traffic through a Go datapath. Supports IPv4 and IPv6.

## Requirements

- Android 11 (API 30+), arm64
- [Shizuku](https://shizuku.rikka.app/) 13.6.0+

Android 13 and up work outright. On Android 11 and 12 it depends on the device's
tethering module; the app checks on first launch and offers to install the
module it needs. See [compatibility](docs/android-compatibility.md).

## Install

Download the APK from the
[latest release](https://github.com/carlelieser/shizzi/releases/latest).

## Features

- 🚀 **Unlimited hotspot.** Sharing draws on your regular data instead of your
  hotspot allowance.
- 🛡️ **VPN compatible.** Stay private on every connected device.
- ⚙️ **Uses your existing hotspot.** No extra configuration required.
- 🙌 **No root.** Shizuku is all it needs.
- 🎛️ **One tap away.** A quick settings tile starts and stops sharing from the
  notification shade.
- 🤖 **Automatable.** Start and stop from Tasker or MacroDroid. See
  [automation](docs/automation.md).
- 📶 **Hotspot Control.** Global and per-device speed limits, persistent
  monthly quotas, automatic quota blocking, immediate manual blocking,
  friendly device names, bandwidth presets, weighted dynamic sharing,
  Normal/Priority/VIP classes, active-client limits, temporary pauses,
  persistent known devices, per-device day/week/month/total usage, quick
  Guest/Family/VIP profiles, and connection history.
- 🎟️ **Voucher Studio + Captive Portal.** Build your own prepaid offers
  instead of using fixed presets: independent download/upload values in kbps
  or Mbps, custom data volume in MB/GB, validity in minutes/hours/days, and
  batch generation of 1–500 unique vouchers. Offers can be saved as reusable
  templates; generated vouchers keep their original rules even when templates
  change later. Inventory search/filtering tracks Available, Active, Expired,
  Exhausted and Disabled vouchers, with usage/remaining data, activation and
  expiry details plus CSV export. Unauthorised clients keep DNS access but
  ordinary Internet traffic stays blocked until a valid voucher is entered on
  the captive portal. The portal and usage popup continue to support custom
  HTML/CSS and show the exact voucher speed, quota and remaining validity.
- 🔐 **Stable update signing.** Release APKs can use one permanent signing
  identity so future versions install as updates instead of requiring an
  uninstall. See [release signing](docs/release-signing.md).

## Build

Needs the Android SDK with NDK, Go, and gomobile:

```
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
./gradlew assembleDebug
```
