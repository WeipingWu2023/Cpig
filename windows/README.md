# Copig Receiver for Windows

Copig transfers saved clips directly from your Android phone to your Windows computer on the same local network. Files stay on your devices. The receiver implements LocalSend's HTTPS upload protocol v2.2 and multicast discovery; a six-digit PIN is always required.

## Run

Download the Windows ZIP from [Copig Receiver v0.4.2](https://github.com/WeipingWu2023/Cpig/releases/tag/v0.4.2).

1. Extract **Copig-Receiver-Windows-x64.zip** into a folder, then double-click **Copig Receiver.exe**. This portable x64 build includes .NET; there is no installer.
2. Connect the phone and computer to the same Wi-Fi. Ethernet on the same router works too.
3. If Windows Firewall asks, allow the receiver on **Private networks**. The receiver uses TCP and UDP port **53317**. No administrator access is required by the app.
4. In Android Copig, choose **Send to computer** on a saved clip. Select the computer and enter the six-digit PIN displayed in the Windows app. The PIN changes after **Quit** and relaunch; the **Restart** button keeps the current PIN.
5. Received files appear in **Downloads\Copig**. Use **Open folder** to see them or **Change folder** to choose another destination.

Closing or minimizing the window keeps Copig receiving in the taskbar tray. Double-click the pink pig to reopen it. Use **Quit** to stop the receiver, or uncheck **Receive files** to pause it. **Cancel transfer** removes unfinished data and keeps completed files.

Version 0.4.2 prevents a second graphical receiver from starting while one is already running. If you see **Copig is already running**, click the small arrow near the Windows clock and double-click the pink pig. When replacing an older version, first use **Quit** in each old Copig receiver window. The older version did not have this safeguard.

If discovery fails, enter the Windows app's IPv4 address and port manually in Android. Guest Wi-Fi, router client isolation, VPNs and a blocked firewall can prevent local transfers. If LocalSend already occupies port 53317, close it or choose another receiver port and press **Restart**, then enter that port on Android.

## Build and verify

Requires the .NET 10 SDK on Windows. This repository's local build script uses `.tools\dotnet\dotnet.exe` when present, otherwise `dotnet` from PATH. No third-party NuGet packages are used.

For a fresh clone, install the official .NET 10 SDK, open PowerShell at the repository root, and run the commands below. The first build downloads Microsoft's .NET runtime packs through NuGet. The default build produces a self-contained ZIP, including runtime licenses and third-party notices, at the repository root.

```powershell
.\windows\build-windows.ps1
.\windows\test-receiver.ps1
```

Close normal receiver instances before the full test. To keep an existing receiver running, use `test-receiver.ps1 -SkipDiscovery`: this runs 28 file-transfer/security checks and explicitly skips the shared UDP discovery check. The default full test runs all 29 checks.

`-PackageOnly` updates the ZIP from an existing successful publish without rebuilding the executable. `-FrameworkDependent` creates a development build that requires the .NET 10 Windows Desktop and ASP.NET Core runtimes on the receiving computer; use the default build for the portable release.

The Windows executable is not code-signed. The release lists SHA-256 checksums for verifying downloaded files.

The checks run the same receiver backend in a hidden headless process, verify discovery responses and certificate identity, then exercise PIN rejection, exact-byte transfer, duplicate file preservation, invalid tokens/names, checksum and length mismatch, cancellation and PIN guessing limits. They write only inside `windows\test-output`.

Advanced headless mode:

```powershell
& '.\windows\publish\Copig Receiver.exe' --headless --output 'D:\Clips' --port 53317 --pin 123456
```

Use the graphical app for normal use; it chooses a cryptographically random PIN. A persistent local self-signed certificate is saved in `%LOCALAPPDATA%\Copig\receiver.pfx`, and the Android sender verifies the SHA-256 fingerprint advertised during discovery. In addition to TLS, transfers have per-file random tokens, sender IP binding, advertised length and optional SHA-256 validation. Metadata accepts at most 16 files and 8 GiB per file. Files are first streamed to an exclusive `.part` file and renamed only after validation; existing files are never overwritten.

If preparing a transfer is interrupted before the phone receives the response, that unused reservation expires within one minute. **Cancel transfer** on Windows releases it immediately. An interrupted upload can be sent again; completed clips stay intact.

Protocol reference: [LocalSend protocol v2.2](https://github.com/localsend/protocol). Copig is an independent implementation and is not affiliated with LocalSend. File download / browser sharing endpoints are not implemented (`download: false`).

The executable has the pink pig file icon. To add a desktop shortcut, keep the extracted folder in place, right-click Copig Receiver.exe and choose Send to → Desktop (create shortcut), or run the included Create-Desktop-Shortcut.ps1 with PowerShell.
