# Copig: a beginner’s guide

Copig saves interesting screen video and eligible playback audio on your Android phone. Captures are saved as MP4, including when the source is audio-only. You can preview a clip, share or export it, and send it directly to a Windows computer over your local network. The Android app is version **0.4**; the Windows receiver is **0.4.1**, which prevents duplicate receiver windows. The GitHub repository still uses its earlier name, **Cpig**.

This guide starts with the ready-built downloads. Building from source is optional and is explained later.

## 1. Download the two apps

Use the download links below. The Android app comes from [v0.4.0](https://github.com/WeipingWu2023/Cpig/releases/tag/v0.4.0); the corrected Windows receiver comes from [v0.4.1](https://github.com/WeipingWu2023/Cpig/releases/tag/v0.4.1). On a release page, expand **Assets** if necessary.

| Device | Download | What it is |
| --- | --- | --- |
| Android phone | [Copig-debug.apk](https://github.com/WeipingWu2023/Cpig/releases/download/v0.4.0/Copig-debug.apk) | The Android installation file. Requires Android 10 or later. |
| Windows computer | [Copig-Receiver-Windows-x64.zip](https://github.com/WeipingWu2023/Cpig/releases/download/v0.4.1/Copig-Receiver-Windows-x64.zip) | The portable Windows 10/11 x64 receiver, including its runtime. |

Download the APK on the phone and the ZIP on the computer. **Source code (zip)** is for building the project; it is not the ready-to-run Windows receiver.

The first phone to test is a Redmi Note 13 running Android 14. Keep it unlocked and have at least **256 MB of free storage** before recording. Use a short video for the first test.

## 2. Install or update Copig on Android

1. On the phone, open **File Manager → Downloads** and tap `Copig-debug.apk`.
2. If Android asks, allow that File Manager to **install unknown apps**, then return to the installation screen. The setting applies to the app opening the APK; it might instead name your browser if you opened the download there.
3. Tap **Install**, or **Update** if Copy, Cpig, or Copig is already installed.
4. Tap **Open**. Look for **Copig** and the pink pig icon.
5. If you used an earlier version, check **Your clips**. Existing clips should remain after installing the published APK as an update.

**Keep the old app installed while updating.** Its private clips are deleted if you uninstall it or clear its app data. Before deliberately uninstalling, use **Save a copy to Files** on every clip you want to keep.

If the APK is on your computer, connect the phone by USB, choose **File transfer** in its USB notification, and copy the APK into the phone’s **Download** folder using Windows File Explorer. Then follow the phone steps above.

## 3. Enable the small floating pig

1. Open Copig and tap **Enable floating controls**.
2. Android opens the permission screen for displaying over other apps. Enable this for Copig.
3. Return to Copig. A small pink pig button should appear near a screen edge.
4. Open another app. The pig should remain available while you browse.
5. Drag the pig to move it. When you let go, it snaps to the nearer left or right edge.
6. Tap the pig to expand the recording buttons. Tap **×** or outside the panel to shrink it again. An unused idle panel also shrinks after a short wait.

The bubble has a **48 dp touch target** so it stays usable even though the pig’s face is small. Recording start and stop automatically shrink the panel. Tap the pig again whenever you need the buttons.

On Redmi/MIUI/HyperOS, permission names vary. If tapping **Record** does not open Android’s prompt, open the phone’s **Settings → Apps → Copig** or long-press its launcher icon and choose **App info**. Look in the app’s permissions for **Display over other apps** and, if present, **Display pop-up windows while running in the background** or a similarly named setting. Allow those for Copig. If the pig disappears while browsing, check the app’s battery/background restrictions and reopen Copig.

You can also use **Record a clip** inside Copig to set up capture if the phone blocks a prompt launched from the overlay.

## 4. Record a clean first clip

Choose a short video with clear speech and a visible speaker or another obvious sound/video synchronization cue. Start with a source that allows capture, such as an ordinary local or browser video. Stay in portrait orientation for this first test.

1. Open the source app and find the video. Pause it or position it at the beginning of the section you want.
2. Tap the floating pig, then **Record**.
3. Allow audio permission and notifications when asked. Android requires audio permission for internal playback capture; Copig does not record microphone input. Notifications give you an additional way to stop a recording.
4. Approve Android’s screen capture prompt. On Android 14, choose the source app’s window for cleaner output. Choose the entire screen if you need to capture while switching between apps; full-screen output can include the floating controls.
5. After approval, the controls show **Ready**. Return to the right point in your video. **Nothing is recorded during Ready**, so this navigation will be excluded.
6. Tap **Record now**. Wait for the **Recording** status, then play or replay the section. The panel shrinks to the pig bubble.
7. After about **20–30 seconds**, tap the pig to expand it and tap **Stop**. You can stay in the source app. The recording notification also offers **Stop and save**.
8. Wait while Copig saves the clip, then open Copig and find it under **Your clips**.
9. Tap **Preview clip** and select a video player if Android asks.

Check that the clip opens, shows the expected video, has audible speech, and that speech and mouth movements reasonably match. The Ready/navigation period should be absent. The few moments after **Record now**, including waiting to press Play, are part of the recording.

Every new capture session requires fresh Android approval. Unused Ready setup expires after **four minutes**; tap Record and approve again if that happens. Copig cannot recover content played before recording started, so replay the section you want.

Some apps or individual videos protect their picture or block playback audio. Douyin, WeChat Channels, and other sources must be tested with the actual content on the phone. Selecting a single app limits the captured picture; it does not automatically limit playback audio to that app. Other eligible sounds playing at the same time may also be captured.

Keep the phone unlocked while capturing. A clip can last up to **15 minutes**; low storage can stop it earlier. Allow saving to finish before starting the next clip.

## 5. Preview, export, share, or import media

The buttons on each saved clip have different uses:

| Button | What to do |
| --- | --- |
| **Preview clip** | Open it in a compatible video/audio player and check the result. |
| **Share file** | Choose an installed app that accepts the clip’s file type. |
| **Save a copy to Files** | Choose a destination such as Downloads and save an independent copy. |
| **Send to computer** | Transfer it to the Windows receiver; follow the next section. |
| **Delete** | Confirm deletion of the phone’s private copy. Export it first if you want a backup. |

To test export, save a copy to Downloads, open it in the phone’s File Manager, and play it there. The exported copy should work independently of the library.

Use **Copy learning prompt** to copy an explanation prompt separately. Paste it alongside your clip in an AI workflow that accepts that media. The receiving app decides which attachments it supports; Copig does not contain an AI model or upload clips automatically.

**Import video or audio** lets you add an existing MP4, WebM, M4A, MP3, WAV, or OGG file. You can also share an actual media file to Copig from another app. A shared web link is not downloaded in this version. If a source blocks recording but offers its own save/share feature, use that file and import it instead.

## 6. Start the Windows receiver

1. On Windows, find `Copig-Receiver-Windows-x64.zip` in Downloads.
2. Right-click it and choose **Extract All**. Choose a folder and complete extraction.
3. Open the extracted folder and double-click **Copig Receiver.exe**. Run it from the extracted folder, rather than from inside the ZIP.
4. If Windows Firewall asks for network access, allow the receiver on your **private home network**. Keep the firewall enabled.
5. Wait for the receiver to say it is ready. Confirm **Receive files** is checked.
6. Find the computer name, local IP address, and **six-digit PIN** shown in its window.
7. Note the **Saved to** folder. The default is `%USERPROFILE%\Downloads\Copig`. **Open folder** opens the exact location; **Change folder** lets you choose another location when no transfer is active.

The portable download includes .NET, so you do not need to install a separate runtime to use it. Starting the app does not require administrator privileges; changing a firewall rule may require them.

Closing the window with **×** or minimizing it keeps the receiver running in the taskbar tray. Click the small up arrow near the Windows clock if the tray icons are hidden, then double-click the pink pig to reopen it. Use **Quit** in the window or tray menu to stop receiving. Unchecking **Receive files** pauses reception.

If opening Copig says **Copig is already running**, reopen the existing pig near the clock. If an older version says **address already in use**, another receiver may already be running: click **Quit** in the window showing the error, then reopen the existing pig. Before replacing the receiver with version 0.4.1, use **Quit** in every older receiver window; × only hides it. If a different app such as LocalSend occupies the port, choose **53318** in Copig's **Port** box, click **Restart**, then use that port when adding the PC manually on Android.

The PIN stays the same while that app instance runs, including while it is in the tray. Quitting and launching the receiver again creates a new PIN. Start the receiver whenever you want to send files, or leave it available in the tray.

## 7. Send your first clip over the local network

1. Connect the phone and computer to the **same Wi-Fi/router**. The computer can also use Ethernet connected to that router. Internet access is not required for the transfer.
2. Keep the Windows receiver running with **Receive files** enabled.
3. Open Copig on the phone. Choose the short clip you previewed and tap **Send to computer**.
4. Wait for a nearby computer to appear. Check that its name and IP match the Windows receiver, then select it.
5. Enter the **current six-digit PIN** shown on Windows. Tap **Send**.
6. Expect stages such as **Checking file…**, **Waiting for…**, and **Sending to…**. Progress appears on Android and Windows.
7. Wait for **Sent to…** on Android. On Windows, click **Open folder** and play the received MP4. It should show the same content and sound as the phone’s preview.

Only the clip you selected is sent. Its original stays on the phone. Sending the same filename again creates another copy on Windows instead of overwriting an existing file. File length and SHA-256 are checked before a completed file is exposed.

You can close the phone’s transfer panel or switch apps while a transfer continues. Progress and **Cancel transfer** remain available in Copig and its transfer notification. Keep both devices connected and the receiver running; keep the phone awake during the first test.

If you want to stop a transfer, use **Cancel transfer** on either device. Wait for it to finish cancelling before retrying. The phone’s saved clip remains, and unfinished data should not appear as a completed file on Windows.

### Add the computer manually if discovery fails

1. Read the receiver’s local IPv4 address, for example `192.168.1.20`.
2. In Android’s transfer panel, tap **Add computer by IP address**.
3. Enter that address and tap **Add computer**.
4. Select the added computer, enter its PIN, and tap **Send**.

If the receiver uses a custom port, enter it after the IP: `192.168.1.20:53318`. Manual entry avoids multicast discovery; it still needs an open network path to the receiver. Use a numeric local IPv4 address, not a website address or `https://` URL.

## 8. Check that the main features work

Run these tests on the Redmi and your actual Wi-Fi. Automated checks already recorded in [TESTING.md](TESTING.md) verify builds and protocol behavior; they do not establish phone-specific capture compatibility or real Wi-Fi performance.

| Test | Steps | Expected result |
| --- | --- | --- |
| Update and branding | Install the published APK over your earlier app and open it. | Copig name, pink pig icon/theme, and your existing clips are present. |
| Compact controls | Leave Copig, drag the pig to both edges, tap it, and close with × or an outside tap. Rotate the phone while idle. | It stays reachable, snaps to an edge, expands, and shrinks without blocking unrelated screen taps. |
| Clean capture start | Approve capture, stay at Ready for 5–10 seconds while navigating, then Record now, play a 20-second video, and Stop. | Only the recording period is saved; picture, sound, and synchronization are acceptable. |
| Fresh consent | Finish saving one clip, then start another. Cancel the new consent prompt once. | Each new session asks for approval; cancelling creates no unwanted clip. |
| Source compatibility | Repeat with Douyin, WeChat Channels, and another playback app. | Supported content records; identify any source/content producing black picture or silent sound. |
| Export | Save a copy to Files and play it from File Manager. | The exported clip opens and plays. |
| Wi-Fi transfer | Send the short clip using the correct receiver PIN. | Android reports Sent; Windows contains a playable matching clip, with the phone’s original retained. |
| Wrong PIN | Change one digit, try Send, then retry with the correct PIN. | A clear PIN error; no completed file from the rejected attempt; the correct PIN succeeds. |
| Cancellation | Send a longer clip and cancel partway through. Resend after cancellation finishes. | No unfinished file is treated as completed, the phone’s original remains, and retry succeeds. |
| Manual address and reconnect | Add the receiver by IP, transfer, change/reconnect Wi-Fi, then Refresh or re-add the current IP. | The fallback connects when the network allows it; failures leave saved clips intact. |

For cancellation testing, use a larger clip if the short one finishes too quickly. Use your own test content and listen to the saved result before relying on a source app for later captures.

## 9. Troubleshooting

| Symptom | What to check |
| --- | --- |
| APK cannot be opened or installed | Confirm the download is `Copig-debug.apk`, allow the File Manager/browser opening it to install apps, and finish any system scan. Read the exact installation error if it still fails. |
| Update says the app conflicts or signatures differ | Use the published APK for updating the earlier delivered app. A fresh build from source normally has a different debug signing key; see the build section before uninstalling anything. |
| Floating pig is absent | Return to Copig, enable floating controls, and check display-over-other-apps permission. On Redmi, check background/pop-up permissions and battery restrictions. Android can hide overlays on sensitive system screens. |
| Floating Record does not open the prompt | Wait five seconds for Copig’s recovery message. Allow Redmi background pop-up windows, or use Record a clip inside Copig to prepare the session. |
| Ready expires or Record now fails after a long wait | Start a fresh setup, approve Android’s prompt, and Record now within four minutes. Never reuse a previous session’s approval. |
| Clip has black picture or silent sound | Preview it. Make sure the source is playing with sound, try another source, and check capture permissions. Protected content/source policies may prevent capture; use native save/share and import when available. |
| No navigation was removed | Position the video while Ready. Recording begins after Record now; actions after that point are included. |
| No notifications | Enable Copig notifications in Android app settings if you want notification controls. You can still stop from the floating panel or Copig. |
| Saving seems slow | Wait for audio encoding/finalization, check free storage, and try a short clip first. Avoid force-stopping the app while saving. |
| Receiver does not appear | Check same router, Receive files enabled, Windows private-network firewall access, and try Refresh or manual IP. Guest Wi-Fi/client isolation and some VPNs can block local connections. |
| Manual IP cannot connect | Use the receiver’s current local IPv4 address and port. Recheck network isolation and firewall access; manual entry cannot bypass a blocked connection. |
| Wrong PIN | Use all six digits currently shown on Windows, including leading zeroes. After five incorrect attempts, wait one minute. Quitting/relaunching the receiver changes its PIN. |
| Receiver says Busy after an interruption | Click Cancel transfer on Windows, then retry. An unused upload reservation expires after one minute. |
| Receiver reports that its port is occupied | Quit another LocalSend/receiver using that port, or choose a different port in Copig Receiver and click Restart. Enter the new IP:port manually on Android if needed. |
| Certificate verification fails | Refresh discovery or re-add the receiver’s IP, check the devices’ clocks, and select the matching name/IP again. Avoid sending to an unexpected receiver. |
| File did not appear where expected | Use Open folder on Windows and read its Saved to path. Confirm Android reported Sent, rather than Failed or Cancelled. |

For firewall troubleshooting, open **Windows Security → Firewall & network protection → Allow an app through firewall** and check Copig Receiver on the private network you use. **TCP 53317** carries uploads and **UDP 53317** carries discovery. A custom receiver port changes the TCP upload port; discovery still uses UDP 53317. Allow the relevant app/ports rather than turning off the firewall.

If a downloaded executable is flagged as an unknown publisher, confirm it came from this project’s release. This test release is not signed with a Windows publisher certificate. Keep system protection enabled and inspect the specific prompt before deciding whether to run that file.

## 10. Build the Android app from source

Use this section if you want to change code or reproduce the build. To just test the app, the release APK is enough.

### Prepare Android Studio and the project

1. Download Android Studio from [Android Developers](https://developer.android.com/studio) and complete its setup wizard.
2. Open the [GitHub repository](https://github.com/WeipingWu2023/Cpig), choose **Code → Download ZIP**, and extract it. Git users can clone the same repository instead.
3. In Android Studio, choose **Open** and select the extracted project folder containing `settings.gradle.kts` and `gradlew.bat`. Select the whole project rather than only its `app` folder.
4. Open **SDK Manager**. Install **Android SDK Platform 35**, **Android SDK Build-Tools 35.0.0**, and **Android SDK Platform-Tools**. In SDK Tools, **Show Package Details** may be needed to choose that Build Tools version.
5. Open **File → Settings → Build, Execution, Deployment → Build Tools → Gradle**. Select an installed **JDK 17**, or use **Download JDK** to obtain version 17. Note its exact installation folder. This project uses a Java 17 toolchain. [Android’s JDK configuration guide](https://developer.android.com/build/jdks).
6. Let Gradle sync complete. Keep the dependency/plugin versions defined by the repository while reproducing this build. The first sync downloads Gradle and libraries, so it needs Internet access.

Android Studio normally creates `local.properties` with the SDK location. If a build reports **SDK location not found**, check that file. A Windows example is:

```properties
sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk
```

Replace the example with your actual SDK location shown in SDK Manager. SDK 35 is a build dependency; the phone can still run Android 14 because the app’s minimum Android version is 10.

### Build an APK and run the checks

1. Open **Terminal** in Android Studio. Use a PowerShell terminal in the project’s main folder.
2. Set `JAVA_HOME` to the **same JDK 17 folder** selected above. Replace the example path below with the exact folder on your computer; use the JDK folder, not its `bin` subfolder.

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
   .\gradlew.bat --version
   ```

   Confirm that Gradle reports version **8.11.1** and uses Java 17. Terminal builds use `JAVA_HOME`; setting Android Studio’s Gradle JDK alone does not set this terminal variable.

3. Build and verify:

   ```powershell
   .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :data:testDebugUnitTest :domain:test :app:lintDebug :data:lintDebug
   ```

4. Wait for **BUILD SUCCESSFUL**. Find the new APK at:

   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

5. Copy it to the phone and install using section 2. Read the signing note below before trying to update an existing published APK.

The command builds the app, runs local JVM tests, and checks app/data lint. It does not operate your phone. A successful automated build still needs the physical acceptance checks in section 8. Android Studio can also deploy with its **Run** button after you configure and authorize a physical device; see [Android’s build/run instructions](https://developer.android.com/studio/run).

If repository downloads stall, retry with the optional mirror property:

```powershell
.\gradlew.bat -Pcopy.useRepositoryMirror=true :app:assembleDebug :app:testDebugUnitTest :data:testDebugUnitTest :domain:test :app:lintDebug :data:lintDebug
```

Official repositories are the default. The property opts into the public Aliyun Maven mirrors. Use `--offline` only after the required dependencies have been downloaded successfully into your current Gradle cache; a fresh clone does not include that cache.

### Important signing and local-toolchain details

The published APK uses the same signing key as the earlier delivered Copy/Cpig APKs. That is why installing it as an update preserves their private clips.

A fresh source checkout **does not contain that signing key**. Android normally creates or uses your computer’s own debug keystore, so your resulting APK may not be able to update the published one. If you possess the original development key, the build recognizes `.tools/copy-debug.keystore`. Keep it private. Otherwise, use the published APK to update existing installations. If you deliberately switch to a differently signed build, first export all clips, then uninstall the old app and install your own APK; uninstalling removes its private library.

`build-local.ps1` is a convenience for the original development workspace, where `.tools/` already contains JDK, SDK, Gradle, and caches. It does not install those tools, and `.tools/` is excluded from Git. For a fresh download or clone, use the Android Studio/wrapper steps above.

Neither a normal wrapper build nor `build-local.ps1` replaces a root-level `Copig-debug.apk` automatically. Install the newly generated `app/build/outputs/apk/debug/app-debug.apk` when testing your own changes.

## 11. Build the Windows receiver from source

1. On Windows, install the **.NET 10 SDK for x64** from [Microsoft’s .NET 10 download page](https://dotnet.microsoft.com/en-us/download/dotnet/10.0). Choose the SDK, not just a runtime.
2. Open a new PowerShell window and move to the extracted repository folder. These examples assume `C:\Projects\Cpig`; replace that folder with yours.

   ```powershell
   cd C:\Projects\Cpig
   dotnet --list-sdks
   ```

   Check that a `10.0.x` SDK is listed. A new terminal may be needed after SDK installation.

3. Build the portable receiver:

   ```powershell
   .\windows\build-windows.ps1
   ```

4. The output ZIP is `Copig-Receiver-Windows-x64.zip` in the project’s main folder. The runnable files also appear in `windows/publish`. This default build includes the runtime and publishes for Windows x64.
5. Close normal Copig/LocalSend receiver instances before the automated receiver checks, so discovery responses cannot come from the wrong instance. Run:

   ```powershell
   .\windows\test-receiver.ps1
   ```

6. Expect **PASS** output and a successful exit. These checks run a hidden headless receiver and exercise discovery, TLS identity, PIN checks, file integrity, duplicate-name preservation, error handling, and cancellation. Test data goes into `windows/test-output`, not your normal receive folder.
7. Extract your newly built ZIP and start **Copig Receiver.exe** to run the phone-to-Windows checks in section 8.

If PowerShell reports that script execution is blocked, you can allow these project scripts for the current terminal session, then retry:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

The helper uses `.tools/dotnet/dotnet.exe` if it exists in the workspace, otherwise the installed `dotnet` command. A fresh clone uses your installed SDK. The first build needs network access to restore .NET framework/runtime packs.

### Check Android/Windows interoperability on the computer

After completing the Android build and the Windows publish above, you can also run the interoperability harness. It loads Android’s compiled connection/domain classes into a JVM, connects to the packaged Windows receiver over loopback, verifies certificate pinning and PIN rejection, then uploads a test file and compares the received bytes. This checks the two implementations together; it does not operate the phone.

From the main project folder, run the following with your actual JDK 17 and Android SDK paths:

```powershell
.\checks\test-android-lan.ps1 -JavaHome 'C:\Program Files\Java\jdk-17' -AndroidSdk 'C:\Users\YourName\AppData\Local\Android\Sdk' -GradleUserHome "$env:USERPROFILE\.gradle"
```

Use the Gradle cache used for your Android build. A fresh wrapper build normally uses `%USERPROFILE%\.gradle`; if you set `GRADLE_USER_HOME`, pass that folder instead. The earlier Android `assembleDebug` step creates the required data classes JAR and domain JAR, and Windows publishing creates `windows/publish/Copig Receiver.exe`. Run both builds before the harness. Expect successful interoperability checks; its test output goes into `checks/build`.

You can omit the path parameters if `JAVA_HOME`, `ANDROID_HOME`, and `GRADLE_USER_HOME` already identify the correct tools/cache; the script also recognizes the original workspace’s `.tools/` layout. See [TESTING.md](TESTING.md) for the recorded results.

## 12. If you need help after testing

Record the exact error message and these details:

- Phone model and Android/MIUI/HyperOS version.
- Source app and whether picture, sound, or both failed.
- Whether the issue occurred during permission setup, Ready, recording, saving, or transfer.
- For transfer: automatic discovery or manual IP, the receiver’s status, and whether both devices were on the same router. Do not include your receiver PIN in a public issue.
- Whether the installed APK came from the release or your own build.

See [README.md](README.md) for project scope, [LAN-TRANSFER.md](LAN-TRANSFER.md) for protocol/network details, [windows/README.md](windows/README.md) for receiver details, and [TESTING.md](TESTING.md) for the recorded verification results.
