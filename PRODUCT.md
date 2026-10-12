# Copig

Capture what interests you. Understand what you saved.

## Mission

Turn interesting audio or video playing anywhere on the phone into personal learning material, where Android permits capture. This includes Douyin, WeChat Channels, browser media, and other playback apps. Example: save a NAS tutorial, understand the vocabulary, and learn the prerequisites needed to follow it.

## Current capture workflow

Native Android app, Android 10 or newer. Device compatibility must be verified on the user's phone.

1. Open Copig and enable the small pink pig overlay with Android overlay permission. It docks at the screen edge; tapping expands the recording controls.
2. Browse the source app, tap floating Record, and approve Android's fresh capture consent. Setup returns to the video without recording yet.
3. Tap floating Record now at the content you want; stop using floating Stop or the recording notification.
4. Preview the saved clip and check that audio is present.
5. Send the recording directly to the Windows receiver on the same local network, share it to a compatible destination, or export it for use with an AI workflow. Receiving apps determine which file types they accept.

Also accept shared video/audio files and PNG images. The floating Screenshot action prepares consent, then Take screenshot saves one full-resolution PNG with the overlay briefly hidden. Screenshots use the same private library, export/share and Windows transfer flow as clips. Shared URLs are not downloaded or bookmarked in this release.

Keep recordings local until the user explicitly chooses a clip and a receiver/share destination. Windows LAN transfers use encrypted streaming and a receiver PIN, with automatic discovery and a manual-IP fallback. Support preview and deletion. Clearly report failed captures and audio that appears silent; silence alone cannot establish that an app blocked capture.

## Android constraints

- Screen capture uses MediaProjection with user consent for each session and a foreground service.
- Internal playback capture uses AudioPlaybackCaptureConfiguration and AudioRecord on Android 10+; the source app's audio policy and audio usage must allow it.
- Secure/protected surfaces may be absent from recordings. Copig cannot promise to capture every app.
- Android 14 single-app sharing is preferred for excluding other app/system UI. Full-screen capture can include the floating recorder. Unused Ready setup expires after four minutes; fresh consent is required for each new clip or screenshot. Select Entire screen for screenshots across apps.
- A new recording does not include content played before recording started. Replay the video first. A rolling buffer is a possible later feature and requires an already active recording session.
- Native app save/share actions or importing an existing recording are fallbacks when direct capture fails.
- Capture support for Douyin, WeChat Channels, and other source apps is unverified until tested on the actual device and content.

## Learning output

For each clip, aim to produce:

- A plain-language explanation of the main idea.
- A glossary with simple definitions and examples.
- Prerequisites in the order a beginner should learn them.
- A step-by-step walkthrough anchored to timestamps when the input permits.
- A distinction between claims made in the clip and additional AI explanation.
- A few questions to check understanding.

Suggested prompt:

> Teach me this content as a beginner. Explain the main idea, define technical terms using examples, identify prerequisites in learning order, and walk through the content step by step. Reference timestamps where available. Separate the creator's statements from your added explanations, flag uncertainty, and finish with three comprehension questions.

## Implementation direction

Use Kotlin, Jetpack Compose and MVVM with separate `app`, `domain` and `data` modules. Encode screen frames as H.264 and eligible playback audio as AAC, then synchronize and mux them into MP4. Store clips locally and share using content URIs with temporary read permissions. Do not embed service API secrets in the APK. An integrated AI version needs a separately selected provider and credential architecture. See ARCHITECTURE.md for the implemented boundaries.

First technical milestone: capture one short video with audible internal playback on the user's phone, stop cleanly, play the output, and export it. Establish this before investing in an integrated AI interface or an always-on buffer.

## First release decisions

- Test phone: Redmi Note 13, Android 14.
- First release: capture clips and share/export to AI workflows. No integrated AI explanation.

## Official references

- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE

Implementation and validation status are documented in README.md. Physical-device capture compatibility still needs verification.
