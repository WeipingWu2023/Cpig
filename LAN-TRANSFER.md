# Copig phone-to-Windows transfers

## First test, step by step

1. Copy **Copig-debug.apk** to your Redmi, tap the file and install it as an update. Open Copig and check that your earlier clips are still listed. Do not uninstall the old app first.
2. On Windows 10/11 x64, extract **Copig-Receiver-Windows-x64.zip**. Open the extracted folder and double-click **Copig Receiver.exe**. Keep the app running; it includes its runtime, so you do not need to install .NET.
3. If Windows asks about network access, allow **Private networks** for your home Wi-Fi. The receiver window shows your computer's name, IPv4 addresses, PIN and receive folder.
4. Connect your phone to the same router as the computer. The computer can use Wi-Fi or Ethernet. A cloud account and Internet connection are unnecessary.
5. In Copig, choose a small saved clip and tap **Send to computer**. Wait for the computer to appear, select it, enter its six-digit PIN and tap **Send**.
6. Wait for **Sent to…**. On Windows, click **Open folder** and play the received file. The default folder is **Downloads\Copig**. **Change folder** lets you choose another location.

After this first test, try a larger clip, cancel it partway through, and send it again. The phone's original remains saved. Interrupted data is removed; only a validated complete file appears in the receive folder. Sending the same filename again creates another copy instead of overwriting the earlier file.

The Android transfer notification shows progress and offers **Cancel transfer**. You can close the transfer panel or switch apps while sending. Closing/minimizing the Windows window keeps the receiver available in the taskbar tray; double-click its pig icon to reopen it. **Quit** stops it. **Receive files** pauses or resumes reception.

## If the computer does not appear

- Check that **Receive files** is enabled and the Windows status says it is ready.
- Tap **Refresh** on Android after changing Wi-Fi. This restarts discovery on the current network.
- Tap **Add computer by IP address**, then enter the local IPv4 address shown on Windows, such as `192.168.1.20`. If you changed the receiver port, include it: `192.168.1.20:53318`.
- Avoid guest Wi-Fi or router client/AP isolation. These can block communication even when both devices show the same Wi-Fi name. Temporarily disconnect a VPN if it routes local traffic elsewhere.
- In Windows Security → Firewall & network protection → Allow an app through firewall, check the receiver's access on your private network. Both **TCP 53317** (encrypted uploads) and **UDP 53317** (discovery) must be allowed. Allow the port you selected if you changed the TCP port. Manual IP entry bypasses multicast discovery, but cannot bypass a blocked TCP connection.
- If another LocalSend receiver uses TCP 53317, quit it or choose another port in Copig Receiver and click **Restart**. Enter the new port manually on the phone.

If the receiver is busy after an interrupted setup, click **Cancel transfer** on Windows and retry. Unused upload reservations expire after one minute. If the PIN is wrong, use the PIN currently displayed on Windows; it changes when you quit and restart the app. After five incorrect attempts, wait one minute before retrying.

## Implementation

Copig implements the discovery and upload portion of [LocalSend protocol v2.2](https://github.com/localsend/protocol). It uses IPv4 multicast `224.0.0.167:53317`, HTTPS register/prepare-upload/upload/cancel routes, and a manual IPv4 fallback. Browser download sharing, Windows-to-phone reception and the full LocalSend feature set are outside this release.

Discovery sends device metadata only. A transfer starts after you explicitly choose a saved clip and a receiver. Android pins the receiver's TLS certificate to its advertised SHA-256 fingerprint; manual discovery checks the certificate before sending a PIN or file. Discovery is not an authenticated identity directory, so choose the computer whose name/IP matches your receiver.

The Windows receiver requires its PIN before preparing an upload, then creates random per-file tokens bound to the sender IP. Files are streamed to unique temporary files, checked against length and the sender's SHA-256, and renamed only after validation. It accepts at most 16 files per request and 8 GiB per file. Copig sends one clip at a time. Android and Windows both stream chunks instead of loading a clip into memory.

Source boundaries: Android Compose/MVVM in `app`, transfer contracts/models in `domain`, platform discovery/sending/foreground lifetime in `data`, and the independent native Windows UI/Kestrel receiver in `windows`. See [ARCHITECTURE.md](ARCHITECTURE.md) and [windows/README.md](windows/README.md).
