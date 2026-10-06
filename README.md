# DiPlay

**Modified for own head unit (Android 8.1 and using XUDA car adapter as hotspot to make audio consistent)**

**CarPlay for compatible Android head units.** Wired and wireless.

[Original Author](https://shihabal3amri.github.io/DiPlay/) · [Release]((https://github.com/YHang777/DiPlay-Android-8.1-CarPlay-adapter-Wired-to-Wireless-can-use/releases/tag/v0.2.11-xuda)) · 

**This fork** — [YHang777/DiPlay-Android-9](https://github.com/YHang777/DiPlay-Android-9) — carries 0.2.11 plus [wireless CarPlay on an external Wi-Fi network](#wireless-carplay-on-an-external-wi-fi-network) and the [audio corrections](#audio-over-a-client-wi-fi-link) that mode needs.

![DiPlay home](site/assets/home.png)

## 0.2.11 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. Wireless supports Wi-Fi Direct, the car’s existing hotspot, or any access point this device can join; Wi-Fi Direct requires Android 10+; the APK supports Android 8.1+ for wired use.

- Wired USB and wireless CarPlay with local authentication.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

## Wireless CarPlay on an external Wi-Fi network

DiPlay normally opens its own hotspot and waits for the iPhone to join it. Some setups already have a network in place — a car hotspot, or a CarPlay-bridging dongle that brings up its own Wi-Fi and Bluetooth for the phone. In that case the network can be supplied from outside and DiPlay only has to run the session on it.

- **DiPlay joins the network as a client.** The configured wireless network may be an access point that already exists instead of DiPlay’s own SoftAP. DiPlay associates to it through Android’s peer-network request, remembers it as a network suggestion, and reports the SSIDs it can actually see when association fails. It never demands a local hotspot while it is associated, because on single-radio firmware the client interface and the SoftAP are mutually exclusive.
- **The session is advertised on the address DiPlay really holds.** A client address is read from the live link and used for the AirPlay advertisement instead of the SoftAP default, so the iPhone’s connection to the listener actually arrives.
- **The outside device only supplies the network.** Pairing, AirPlay, RTP, media and the UI all stay in DiPlay; the bridge has to do nothing but carry the traffic. Nothing about the bridge itself is modified or reconfigured.
- **`CHANGE_NETWORK_STATE` is declared** for the peer-network request — Android refuses `requestNetwork` without it. It is a normal-level permission, granted at install and never prompted for.

Wi-Fi Direct, the car hotspot and the wired USB path are unchanged, and a self-hosted hotspot never takes a Wi-Fi lock.

## Audio over a client Wi-Fi link

Two defects show up only when DiPlay is a Wi-Fi client rather than an access point, and both are fixed here:

- **Audio packets are reordered before they are decoded.** RTP can be delivered out of order; playing in arrival order makes the audio lurch forward and then rewind, which is what “the audio speeds up” sounds like. Each stream is now held and dispatched in RTP timestamp order across the 32-bit wrap point, duplicates and already-played packets are dropped, and the frame step is corrected only downward so a genuine hole can never be mistaken for the frame size. A stream that keeps hitting unfilled holes gives up and returns to arrival order rather than stalling, and variable-frame codecs bypass ordering entirely. Reordering is confirmed rather than assumed: a forward-gap event that never arrives is a loss, and equality between the forward-gap and late/duplicate counters is a reorder.
- **A Wi-Fi lock is held for the whole session** — `WIFI_MODE_FULL_LOW_LATENCY` on Android 10+, `WIFI_MODE_FULL_HIGH_PERF` before that — so the client radio stays awake instead of caching delivery between beacon wake-ups. The lock is acquired when a sink is created and released in `close()`.

A live session reports both under `Audio: media stats`: `orderOn`, `orderStep`, `held`, `heldMax`, `stale`, `gaveUp`.

## What else this build carries

- **minSdk lowered to 27** (Android 8.1) in `common`, `mobile` and `shared`, with `APP_PLATFORM` matched in the NDK build.
- **Wired USB interface discovery** gained fallbacks (Apple Ethernet and NCM paths) when bringing up the iPhone’s tethered network.
- **`gradlew.bat`** no longer passes an empty `-classpath` argument.

## Documentation from original author

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses from Original Author](docs/THIRD_PARTY_NOTICES.md)

The website is available in English and Simplified Chinese. The app interface supports those same six languages. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
