# Copig verification

## Android and Windows 0.4.2 update

Verified on 2026-10-12. The supplied 144.89-second H.264 clip measured 576 × 1280, about 4.05 Mbps. Frames sampled at 10, 45 and 100 seconds had strong blur behind a sharp pig overlay. The user confirmed the phone display was clear during recording. Lower resolution is a confirmed quality limitation; system/source capture masking is a suspected separate cause, not proven fixed by the encoding change. No private clip or sampled frame is published.

The Android build succeeded: **18 JVM tests passed**, including portrait/landscape, high-resolution limits, even dimensions and fallback-quality candidates. App and data lint finished with zero errors. The new APK is version 0.4.2, code 5, and passed signature and alignment verification with the same signing certificate as the previous APK. MediaRecorder preparation now tries screen resolution up to a 2560-pixel long edge before smaller sizes, and uses a size-dependent bitrate. Actual encoder preparation and recording on Redmi remain unverified; no phone was connected over USB.

The Windows build succeeded. **28 receiver checks and five Android/Windows HTTPS interoperability checks passed**. UDP discovery was explicitly skipped to avoid interference from the existing receiver. The EXE pig icon was extracted and visually checked, and the actual desktop shortcut target, working folder and EXE icon were verified. The ZIP includes a reusable desktop-shortcut script. Higher-quality capture and the privacy-blur comparison test still need the physical phone steps in the beginner guide.


## Windows 0.4.1 update

The receiver now prevents a second graphical instance and explains how to reopen the existing pig in the Windows tray. Port-conflict guidance distinguishes another Copig from another app such as LocalSend. The Android app is unchanged at 0.4.

The Windows 0.4.1 build succeeded. **28 receiver checks and five Android/Windows HTTPS interoperability checks passed**. The full discovery test was attempted, but timed out while the user's existing Copig receiver owned the shared UDP discovery port. The repeat used `windows/test-receiver.ps1 -SkipDiscovery`; this explicitly skips discovery without stopping the user's receiver. Automatic discovery and the new duplicate-window message still need a desktop acceptance check after quitting all old receivers. No user's running receiver was stopped.

## Android 0.4 and Windows 0.4.0 baseline

Verified again on 2026-10-12 in the Windows development workspace. These checks establish build and protocol behavior; they do not replace a Redmi-to-Windows physical Wi-Fi test.

## Android

The final build succeeded using the repository's JDK 17 / Gradle 8.11.1 / Android SDK 35 toolchain:

```powershell
.\build-local.ps1 -Tasks @('-Pcopy.useRepositoryMirror=true', '--offline', ':app:assembleDebug', ':app:testDebugUnitTest', ':data:testDebugUnitTest', ':domain:test', ':app:lintDebug', ':data:lintDebug', '--rerun-tasks')
```

All 102 Gradle tasks were executed again successfully. All **17 JVM tests** passed: 11 ViewModel tests, one overlay docking/bounds test, three data/audio/storage tests and two domain policy/address tests. New checks cover transfer selection, foreground discovery lifetime, duplicate-send/deletion guards, disappearing receivers, refresh after network changes, stale restored clip selection and synchronous sender failure.

App lint: **0 errors, 30 warnings**. Data lint: **0 errors, 4 warnings**. Nonblocking warnings include available dependency upgrades, conservative storage-space checks, optional URI helpers and absolute overlay positioning. APK signing and 16 KB alignment checks passed. Application ID is `app.copy`, version code `4`, version name `0.4`, label **Copig**. The signing certificate matches the earlier Copy/Cpig builds, preserving update compatibility and private clip storage.

```powershell
& '.\.tools\sdk\build-tools\35.0.0\apksigner.bat' verify --print-certs Copig-debug.apk
```

Use `apksigner.bat` from your SDK's Build Tools directory, with JDK 17 available. The delivered APK is copied from `app/build/outputs/apk/debug/app-debug.apk`; the normal build script does not copy that output to the root automatically.

## Windows and Android/Windows protocol

The native Windows client builds and publishes with **0 warnings/errors**, using the official .NET 10.0.401 SDK. Its downloaded SDK archive was verified against Microsoft's SHA-512. The portable x64 package includes the .NET runtime.

**29 receiver integration checks** passed against the actual packaged receiver: startup, HTTPS protocol/certificate identity, registration, UDP discovery response, missing/wrong PIN, authenticated file transfer, exact bytes/SHA-256, duplicate-name preservation, token/session checks, malformed metadata/pathname rejection, checksum/length errors, cancellation and PIN rate limiting.

```powershell
.\windows\build-windows.ps1
.\windows\test-receiver.ps1
```

**Five cross-platform interoperability checks** also passed. The JVM harness loads the APK's compiled `LocalSendConnection` and domain classes, connects to the packaged Windows executable over loopback, verifies certificate pinning and wrong-PIN feedback, then uploads a 2 MiB file and compares the received bytes. This catches TLS/provider and wire-protocol problems that pure model tests do not cover.

```powershell
.\checks\test-android-lan.ps1
```

That check runs after building both platforms. It accepts `-JavaHome`, `-AndroidSdk` and `-GradleUserHome` for an installed JDK 17, Android SDK 35 and the Gradle cache used by the Android build; it also recognizes the prepared workspace's local toolchain. Fresh-clone examples are in [GETTING-STARTED.md](GETTING-STARTED.md). Test processes run hidden and stop in a `finally` block; output goes into ignored workspace test folders. The actual Windows form was rendered offscreen and visually reviewed for layout, pink branding, PIN, receive controls and destination/progress display.

## Still to verify on the phone

Install the release APK as an update on the Redmi Note 13 / Android 14. Check existing clips, compact overlay dragging/edge docking, expand/collapse behavior, recording and fresh consent, real Wi-Fi discovery, a complete received clip, incorrect PIN, cancellation, manual IP fallback and reconnecting Wi-Fi. Android codec/audio capture and source-app restrictions still depend on the phone/content. Follow the acceptance checklist in [GETTING-STARTED.md](GETTING-STARTED.md) and network instructions in [LAN-TRANSFER.md](LAN-TRANSFER.md).

## Delivered file checksums (SHA-256)

- `Copig-debug.apk`: `EA826FA7CF47BC282B3C3078B0DA88A233E5ECC927DB885F0E238FA4E808118F`
- `Copig-Receiver-Windows-x64.zip` (0.4.2): `691BEB3606E267B849AF093A445D3D41E717FDAD557FAB974F54CAB0744D52FC`

The current Android APK and Windows ZIP are in [v0.4.2](https://github.com/WeipingWu2023/Cpig/releases/tag/v0.4.2). Both checksums appear in [SHA256SUMS.txt](SHA256SUMS.txt). The APK is a debug test build and the Windows executable is unsigned. Private signing keys and generated test data are excluded from the repository.
