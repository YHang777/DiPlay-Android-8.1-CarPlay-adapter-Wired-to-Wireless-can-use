# DiPlay 0.2.8 — 2026-09-30

- Steering-wheel next, previous and play/pause now reach CarPlay on BYD firmware that sends vendor key codes instead of standard Android media keys. DiPlay reads `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`, `KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_HEADSETHOOK` and BYD's play/pause code 353; unrecognised codes are recorded once as `Wheel key discovery: …` in the diagnostic export so other firmware can be added later.
- Long-press Siri is unchanged and still works alongside the media keys.
- Connection loss recovers automatically in cases that previously needed an app restart: a timed-out control window, a failed teardown that left recovery stuck, and a scheduled retry that was quietly dropped.
- A picture that freezes while the connection still looks healthy now reconnects instead of sitting frozen. An idle CarPlay home screen is not treated as a failure.
- A short Wi-Fi blip is less likely to tear down the whole CarPlay session.
- Music on slow head units no longer cuts and jumps ahead. Audio now runs at real-time priority ahead of video, holds a packet instead of losing it when the decoder is momentarily busy, and asks the Wi-Fi socket to keep a larger backlog so bursts after a radio gap are not dropped before the app sees them.
- Music also costs less CPU per packet, which leaves more head-room for video on weak SoCs.
- "Open after the car starts" now opens CarPlay itself instead of leaving DiPlay on its home page, understands the vendor quickboot broadcast some head units send instead of the standard boot one, and offers the "Display over other apps" permission that Android 10 or newer requires before a boot can open an app.
- Media controls respond before the first track starts, not only after music is already playing.
- Steering-wheel and recovery behaviour has not yet been re-verified on a car; please test and report.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
