# MotionWall

**Battery-first video home-screen wallpaper for Android.** A looping, muted clip renders
behind your real launcher — icons, widgets and layout untouched — via a system
`WallpaperService`, and **stops decoding the instant the home screen isn't visible.**
Ported from the macOS Wallpaper-Sync "moving wallpaper" project.

Not a launcher. Not an overlay. The actual system wallpaper, so your home screen is
exactly as you left it — just alive.

## Download

<a href="https://github.com/rishabhdikhit/motionwall/releases/latest/download/MotionWall.apk">
  <img src="docs/download-qr.png" width="200" alt="Scan to download the latest MotionWall APK">
</a>

**[⬇ Download the latest APK](https://github.com/rishabhdikhit/motionwall/releases/latest/download/MotionWall.apk)**
— or scan the QR. Open it on your phone, allow install from unknown sources. Android 8+.

## What it does

- **Real system wallpaper** — renders behind your launcher; nothing gets replaced or re-laid-out.
- **Battery-honest** — plays only while home is visible; pauses (holds the last frame, zero
  decode) when any app is foreground or the screen is off. Never holds a wake lock.
- **Grayscale** — live mono toggle (Media3 `RgbFilter`) for the Nothing aesthetic.
- **Fit / Fill** — show the whole clip or crop-to-fill.
- **Import FPS** — re-encode imported clips to 30 / 24 / 15 fps: fewer frames, less decode,
  better battery.
- **Import** local videos via picker or share-sheet (muted + downscaled by Media3 Transformer).
- **Library** — pick / preview / delete clips.

Home-screen only by design — never animates the lock screen.

## Build (command-line, no Android Studio)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed with the stable MotionWall key
```

Signing uses `keystore.properties` + `release.keystore` (both gitignored). A fresh clone
without them falls back to debug signing.

## Architecture

| File | Role |
|------|------|
| `VideoWallpaperService.kt` | The engine. One `refreshPlayback()` policy: play iff `visible && !powerPaused`. Debounced `onVisibilityChanged`, power/battery receivers, direct decode-to-surface (color) or Media3 effect pipeline (grayscale). |
| `VideoImporter.kt` | Media3 Transformer: strip audio, cap resolution, optional FPS cap. |
| `Settings.kt` | SharedPreferences schema; the wallpaper folder *is* the library (no DB). |
| `MainActivity.kt` | Compose UI — set-flow, toggles, FPS selector, library grid, live engine status. |

## Status

Working test build (v0.5). Verified on a Nothing Phone 3: renders behind the launcher,
plays, scales correctly. **Battery pause-on-invisible** is wired and self-reports in-app
(the `playback:` line) but not yet measured over a long session.

**Not yet done:** trim UI, Shizuku auto-rotate / scheduling tier, cleanup of the debug
status lines before a 1.0.
