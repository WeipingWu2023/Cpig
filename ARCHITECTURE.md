# Copig architecture

Copig uses Kotlin, Jetpack Compose, MVVM, coroutines, and three Gradle modules. Dependencies are enforced by the build, rather than being folders inside one activity. Internal `app.copy` package/provider/storage identities are retained for compatible updates.

| Module | Responsibility | Depends on |
| --- | --- | --- |
| `app` | Compose UI, ViewModel, permissions and Android activity-result launchers, application composition root | `domain`, `data` |
| `domain` | Immutable clip/LAN models, capture and transfer phases, import policy, repository/controller contracts, learning prompt | Kotlin and coroutines only; no Android APIs |
| `data` | Repository implementation, local files, read-only content provider, foreground capture and transfer services, LAN discovery/HTTPS sender, codecs and muxing | `domain`, Android APIs |

## UI and state

`CopyScreen` takes immutable `CopyUiState` and callbacks. It does not access storage, launch recording, or hold a context. It renders a keyed lazy clip list, progress state, and a saveable deletion dialog. It follows system light/dark appearance and respects system-bar insets.

`CopyViewModel` depends on domain interfaces, exposes a single `StateFlow<CopyUiState>`, and receives actions through methods. The screen collects state with `collectAsStateWithLifecycle`. The ViewModel performs asynchronous repository operations through `viewModelScope`, preserves a pending export selection with `SavedStateHandle`, and exposes failures as visible state. Cancellation is propagated rather than displayed as an error.

`MainActivity` handles overlay settings, document pickers, share/view intents, and clipboard access. `CapturePermissionActivity` is a transparent, isolated task that requests permissions/consent and finishes back to the source app. Both use Activity Result contracts and neither owns files or native recorder resources. A launch watchdog releases the consent guard if a phone silently blocks background popups.

`FloatingControlsService` hosts a small ComposeView in a supported `TYPE_APPLICATION_OVERLAY` window. It owns lifecycle/saved-state tree owners, a 48 dp collapsed pig button, expanded controls, dragging/edge docking and UI state collection. Window positions are clamped when the panel resizes or the display changes. Recording start/stop and idle/outside taps collapse the controls. An explicit, user-enabled `specialUse` foreground notification maintains visible controls while browsing. The overlay permission and foreground service subtype are declared in the app module. Hiding controls cancels unused setup, while an active recording remains owned by the separate capture service and can still be stopped from its notification.

## Data and recording

`AndroidClipRepository` owns the clip directory and metadata. File and media operations run on `Dispatchers.IO`. Imported files are checked for supported MIME types and readable media tracks, bounded by size/storage limits, written to a unique temporary file, and promoted only when complete. Its import mutex prevents overlapping imports. It retains the previous clip directory and silent-audio metadata so version 0.1 clips remain available after updating.

`ClipProvider` exposes only completed clips through temporary URI permissions and read-only descriptors. Canonical paths prevent traversal outside the clip directory. Neither activities nor ViewModels can mutate files through this provider.

`CaptureService` owns the projection token, recording coroutine and Android projection callback, independently of the ViewModel and overlay. Setup progresses Idle → Preparing → Ready without creating CaptureEngine, audio input, virtual display or recording files. Explicit Record progresses Ready → Preparing → Recording → Saving → Idle. Explicit Screenshot progresses Ready → Preparing → Saving → Idle. Ready cancellation/revocation releases the unused token without a clip; Ready expires after four minutes. Each new clip obtains fresh consent and uses its token only once. The controller reserves transitions before sending service actions and shares consent state so duplicate taps across the library and overlay do not open overlapping dialogs.

`CaptureEngine` owns native media resources, captures video and eligible playback PCM, and encodes/muxes the completed MP4. Stop requests and projection revocation initiate saving. Resource cleanup executes on failure and cancellation. Capture phases and completion messages reach UI state flows; there is no polling or static activity state.

`CopyApplication` is the composition root. A small `CopyContainer` shares the repository and capture controller with the activity and service. Manual constructor injection makes the ViewModel testable without adding a dependency injection framework.

## LAN transfers and Windows receiver

`LanTransferRepository` exposes discovered devices, discovery feedback and transfer progress as domain state flows. `CopyViewModel` preserves the selected clip ID, routes discovery/send/cancel actions, and prevents deleting clips during a transfer. `LanTransferDialog` renders this state with computer selection, PIN input and a manual-IP fallback; it has no networking or filesystem access. Discovery stops when the screen leaves the foreground. Upload lifetime belongs to the data layer's foreground transfer service and survives activity recreation.

The Android data implementation uses platform sockets and HTTPS connections, with bounded streaming from read-only clip URIs, incremental SHA-256 calculation and cancellation. Certificate checks are scoped to the chosen receiver and verify the advertised certificate fingerprint. Discovery accepts local IPv4 endpoints; it never sends clips automatically.

`windows/` contains a separate native C# WinForms client and an ASP.NET Core Kestrel receiver. LocalSend v2.2 multicast discovery, register/prepare-upload/upload/cancel routes connect the two platforms. A generated six-digit receiver PIN gates preparation; expiring per-file tokens and sender-address checks gate streaming. Downloads use safe unique filenames and temporary files, then validate length/hash before promotion. See the Windows source/readme for exact limits and portable publishing instructions.

## Tests

- Domain: supported/unsupported import formats and unfinished-file policy.
- Data: signed PCM amplitude, long audio timestamps, canonical-path boundaries, incomplete/missing clip rejection.
- UI state: recording propagation, consent state/duplicate request prevention, Ready waiting for explicit Record, restored/cancelled exports, import failure while preserving the library.

These are JVM tests. They do not establish that Redmi's media codecs or a particular source app allow playback capture. Physical-device acceptance tests are listed in README.md.

## Scope

Native capture remains subject to Android consent and source-app protections. The current encoder uses a fixed canvas; orientation changes may be letterboxed. Capture has a 15-minute limit. No integrated AI, cloud upload, rolling buffer, or automatic link downloader is included. LAN transmission is explicit and receiver-directed.

File-backed storage is adequate for this clip library. Room, Hilt, Navigation and pass-through use-case classes can be introduced when the app has requirements that justify them; module boundaries already provide places for those changes.

References: [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations), [Compose compiler setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).

## Screenshots

`ScreenshotEngine` uses one MediaProjection VirtualDisplay and an RGBA ImageReader at the current screen size. The floating service hides its overlay before dispatch; the engine waits briefly for the compositor, handles row/pixel stride padding, and saves lossless PNG through a private `.part` file renamed only after completion. Timeout, revocation and cancellation close image/display/reader resources. CaptureService refreshes the existing repository after saving, then ends the projection session. Screenshot setup requires no audio permission; switching a prepared session to video requests audio permission without acquiring a second projection token.

`ClipPolicy` includes PNG; the existing library, read-only content provider, export/share intents and LAN sender preserve `image/png`. PNG imports validate the image with bounded native decoding. The Windows receiver accepts the image through its existing binary transfer route. The Windows-only rounded rectangle icon is stored separately from Android artwork and embedded into the EXE; desktop shortcuts can use the bundled ICO directly.
