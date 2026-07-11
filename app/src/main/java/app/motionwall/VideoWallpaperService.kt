package app.motionwall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "MotionWall"

/**
 * Battery-first video wallpaper. Ports the macOS Wallpaper-Sync DNA: a single policy
 * plays the muted, looping player IFF the home screen is visible AND no power rule pauses
 * it. Decoding fully stops otherwise — never holds a wake lock.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    @UnstableApi
    private inner class VideoEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private var player: ExoPlayer? = null
        private val prefs get() = Settings.prefs(this@VideoWallpaperService)
        private val handler = Handler(Looper.getMainLooper())

        // Single source of truth (macOS refreshAllPlayback): play iff visible && !paused.
        private var visible = false
        private var powerPaused = false
        private var surfaceW = 0
        private var surfaceH = 0

        private val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = applyPowerPolicy()
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            prefs.registerOnSharedPreferenceChangeListener(this)
            IntentFilter().apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }.let { registerReceiver(powerReceiver, it) }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            player = ExoPlayer.Builder(this@VideoWallpaperService).build().apply {
                setMediaItem(MediaItem.fromUri(Settings.videoUri(this@VideoWallpaperService)))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                setVideoSurface(holder.surface)
                prepare()
            }
            applyEffects()
            applyPowerPolicy()   // computes powerPaused + refreshes playback
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceW = width; surfaceH = height
            applyEffects()       // center-crop needs the surface size
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            // THE battery behavior: no visible home screen => stop decoding. Debounced to
            // avoid thrash on app-switch/transition animations (macOS uses ~0.4s).
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                visible = isVisible
                Log.i(TAG, "visible=$isVisible")
                refreshPlayback()
            }, 300)
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Settings.KEY_GRAYSCALE -> applyEffects()
                Settings.KEY_VIDEO -> player?.apply {
                    setMediaItem(MediaItem.fromUri(Settings.videoUri(this@VideoWallpaperService)))
                    prepare()
                    refreshPlayback()
                }
                else -> applyPowerPolicy()   // power toggles changed
            }
        }

        /**
         * Center-crop to fill, plus optional grayscale. The color path renders the decoder
         * straight to the surface (setVideoScalingMode) — the standard, guaranteed-to-work
         * live-wallpaper approach. Only grayscale engages Media3's GL effect pipeline, so a
         * pipeline quirk on the wallpaper surface degrades just grayscale, never the whole app.
         */
        private fun applyEffects() {
            val p = player ?: return
            if (prefs.getBoolean(Settings.KEY_GRAYSCALE, false)) {
                val effects = buildList<Effect> {
                    if (surfaceW > 0 && surfaceH > 0) {
                        add(Presentation.createForWidthAndHeight(
                            surfaceW, surfaceH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                    }
                    add(RgbFilter.createGrayscaleFilter())
                }
                p.setVideoEffects(effects)
            } else {
                p.setVideoEffects(emptyList())
                p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
        }

        /** OR the user's power rules with the live system state (macOS applyPowerPolicy). */
        private fun applyPowerPolicy() {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val lowPower = pm.isPowerSaveMode
            val plugged = isPlugged()
            val lowBatt = batteryPct() <= Settings.LOW_BATTERY_PCT

            powerPaused =
                (prefs.getBoolean(Settings.KEY_PAUSE_ON_LOW_POWER, false) && lowPower) ||
                (prefs.getBoolean(Settings.KEY_PAUSE_ON_BATTERY, false) && !plugged) ||
                (prefs.getBoolean(Settings.KEY_LOW_BATTERY_FREEZE, true) && lowBatt && !plugged)
            refreshPlayback()
        }

        // Pausing already holds the last decoded frame on the surface (== macOS freeze).
        private fun refreshPlayback() {
            player?.let { if (visible && !powerPaused) it.play() else it.pause() }
        }

        private fun isPlugged(): Boolean {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val s = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            return s != 0
        }

        private fun batteryPct(): Int {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            player?.release()
            player = null
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            runCatching { unregisterReceiver(powerReceiver) }
            player?.release()
            player = null
            super.onDestroy()
        }
    }
}
