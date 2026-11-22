# ARMAMUS

Single-file music player (`index.html`) for the browser and Android WebView/APK.

## Dolby Atmos playback

| Environment | Path |
|---|---|
| Android APK with the `ArmamusNative` bridge | Media3 (ExoPlayer) plays Atmos / E-AC-3 / AC-4 directly |
| Media3 fails on a file | the file is retried via FFmpeg.wasm, silently |
| Browser without the codec | FFmpeg.wasm immediately, no warning toast |
| No bridge | previous behaviour (browser, then FFmpeg) |

## Setup

1. Put `index.html` into `app/src/main/assets/`.
2. Copy `android/ArmamusMedia3Bridge.kt` into your project (fix the `package` line).
3. Add the dependency from `android/gradle-snippet.txt`.
4. Wire it up as in `android/MainActivityExample.kt`.

## Notes

- Real Atmos objects need a Dolby-capable device or headphones; otherwise Android downmixes.
- Files are sent to native code in 1 MB chunks and cached (last ~6 tracks).
- The Android part has not been build-tested on a device yet.
