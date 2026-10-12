# Copig verification

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

- `Copig-debug.apk`: `7FCEBCB73692652C27355990154EB95DEA9D0A8C95D616103687E702C4D2D128`
- `Copig-Receiver-Windows-x64.zip` (0.4.1): `C32FB4CFF8A7CA727269EBCD0CB73DEA1E0DAA1E159212A0AE7D0B10ED8CC661`

The Android APK is in [v0.4.0](https://github.com/WeipingWu2023/Cpig/releases/tag/v0.4.0); the corrected Windows ZIP is in [v0.4.1](https://github.com/WeipingWu2023/Cpig/releases/tag/v0.4.1). Both checksums appear in [SHA256SUMS.txt](SHA256SUMS.txt). The APK is a debug test build and the Windows executable is unsigned. Private signing keys and generated test data are excluded from the repository.
