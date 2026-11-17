# SteamHapticsSinger for Android

Android adaptation of [SteamHapticsSinger](https://github.com/CrazyCritic89/SteamHapticsSinger), maintained by Galax71.

This repository is currently a standalone GitHub repository, not a GitHub-network fork of upstream. The Android port incorporates upstream BSD-3-Clause-licensed frequency tables and protocol information, with attribution and original license preserved in [LICENSE](LICENSE).

## Android device compatibility

- **Minimum:** Android 6.0 (API 23); **target:** Android 15 (API 35). The project compiles against Android SDK 35; this does not imply it has been tested on every OS release.
- **Device types:** Android phones, tablets, foldables, and larger-screen devices. The app uses scrollable Android framework layouts and the system document picker; landscape, unusual screen sizes and accessibility settings still need testing.
- **USB:** Steam Controller 2026 direct USB-C (Valve 28DE:1302) or Steam Puck (28DE:1304) requires the device to support USB Host/OTG, a suitable data cable/adapter, and user-granted USB permission. Some devices cannot power the accessory reliably.
- **Bluetooth:** Optional device capability; paired-device discovery only. Android 12+ requires Bluetooth Connect permission to read paired-device names, and this app does not yet request it automatically. Bluetooth musical haptic playback is not available.
- **No controller:** MIDI file import and phone-speaker preview can be used without USB or Bluetooth controller hardware.
- **Android 6–7:** The gamepad vibration test uses a legacy vibration fallback; newer Android uses VibrationEffect.
- **Android 12+:** USB permission PendingIntent uses the modern mutability flag; older versions omit that flag.
- **Android 13+:** USB permission broadcast receiver uses the modern receiver registration flags.

**Compatibility is source-level, not device-certified.** The current USB implementation requires an accessible HID interrupt OUT endpoint and has not been validated on a broad range of Android hardware. Android TV, ChromeOS, devices without Google services, or custom ROMs may need additional validation.

## Features

- Standard MIDI File (.mid / .midi) player, library, live note display, simple phone speaker synthesis
- Experimental Steam Controller (2026) musical haptics through Steam Puck USB HID
- Controller output selected by default; phone and combined modes
- Gain slider and direct MIDI velocity gain option
- Repeat playback, two-channel limit, trackpad/rumble swap
- Detection of Valve devices on direct USB and paired Steam Bluetooth controllers (detection only)

## Known limitations

Direct USB-C (28DE:1302) and Steam Puck (28DE:1304) both have experimental HID note sending, but neither is verified on Android hardware. Bluetooth musical haptics remain **unimplemented**: Android's normal Bluetooth gamepad input/vibration APIs do not expose a proven frequency-note HID output channel. A paired device does not necessarily indicate an active Bluetooth connection. Hardware sound fidelity and compatibility have not been verified. This app is not affiliated with Valve.

## Build

From Windows PowerShell with Git and GitHub CLI authenticated:

```powershell
git clone https://github.com/Galax71/SteamHapticsSinger-Android.git
cd SteamHapticsSinger-Android
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\build-and-publish-android.ps1" -Version "1.6-device-compat" -SkipPublish
```

Build requires Android SDK 35 and JDK 17; the build script uses the bundled setup helper to install missing dependencies. When ready to make a **public** prerelease, omit `-SkipPublish`. APK builds and hardware playback have not been verified.

## Upstream credit

[CrazyCritic89/SteamHapticsSinger](https://github.com/CrazyCritic89/SteamHapticsSinger) — original code and haptic frequency tables, BSD-3-Clause. Copyright (c) 2015–2016 Pila; (c) 2022–2026 Crazy, AAGaming. See [LICENSE](LICENSE).
