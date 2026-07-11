# MotionWall

Battery-first video **home-screen** wallpaper for Android. A looping, muted clip renders
behind your real launcher (icons/widgets untouched) via a system `WallpaperService` — and
**stops decoding the instant the home screen isn't visible**. Ported from the macOS
Wallpaper-Sync "moving wallpaper" project.

## What it does

- Real system wallpaper (`WallpaperService`) — not a launcher, not an overlay.
- Plays **only** while home is visible; pauses (holds last frame, 0 decode) on app
  foreground / screen off. No wake lock.
- Live **grayscale** toggle (Media3 `RgbFilter`) for the Nothing mono aesthetic.
- Battery rules: pause in battery-saver, pause on battery, freeze below 15%.
- Import local videos (picker or share-sheet) → muted + downscaled via Media3 Transformer.
- Library grid: pick / preview / delete clips.

Home-screen only by design — never animates the lock screen.

## Build (command-line, no Android Studio)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed with the stable MotionWall key
```

Signing: `keystore.properties` + `release.keystore` (both gitignored). A fresh clone
without them falls back to debug signing.

## Architecture

- `VideoWallpaperService.kt` — the engine. One `refreshPlayback()` policy: play iff
  `visible && !powerPaused`. Debounced `onVisibilityChanged`, power/battery receivers,
  center-crop fill + optional grayscale through the Media3 effect pipeline.
- `VideoImporter.kt` — Media3 Transformer: strip audio + cap to 1080p.
- `Settings.kt` — SharedPreferences schema; the wallpaper folder *is* the library (no DB).
- `MainActivity.kt` — Compose UI (set-flow, toggles, library grid).

## Not yet done

- **On-device validation** (decode-stop, lock-bleed on Nothing OS) — the make-or-break gate.
- Trim UI + true fps-cap on import (currently mute + downscale only).
- Shizuku auto-rotate / scheduling tier.
