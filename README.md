# SteamHapticsSinger for Android

Android adaptation of [SteamHapticsSinger](https://github.com/CrazyCritic89/SteamHapticsSinger), maintained by Galax71.

This repository is currently a standalone GitHub repository, not a GitHub-network fork of upstream. The Android port incorporates upstream BSD-3-Clause-licensed frequency tables and protocol information, with attribution and original license preserved in [LICENSE](LICENSE).

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
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\build-and-publish-android.ps1" -Version "1.4-android-port" -SkipPublish
```

Build requires Android SDK 35 and JDK 17; the build script uses the bundled setup helper to install missing dependencies. When ready to make a **public** prerelease, omit `-SkipPublish`. APK builds and hardware playback have not been verified.

## Upstream credit

[CrazyCritic89/SteamHapticsSinger](https://github.com/CrazyCritic89/SteamHapticsSinger) — original code and haptic frequency tables, BSD-3-Clause. Copyright (c) 2015–2016 Pila; (c) 2022–2026 Crazy, AAGaming. See [LICENSE](LICENSE).
