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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "MotionWall"

/**
 * Battery-first video wallpaper. Plays a muted, looping player IFF the home screen is
 * visible AND no power rule pauses it — decoding stops otherwise, no wake lock.
 *
 * Color (default) path renders the decoder STRAIGHT to the surface (no setVideoEffects at
 * all — even an empty effects list forces the GL graph, which renders black on a raw
 * wallpaper surface). Grayscale is the only path that engages Media3 effects; toggling it
 * rebuilds the player so effects mode is entered/left cleanly.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    @UnstableApi
    private inner class VideoEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val ctx get() = this@VideoWallpaperService
        private var player: ExoPlayer? = null
        private var holder: SurfaceHolder? = null
        private val prefs get() = Settings.prefs(ctx)
        private val handler = Handler(Looper.getMainLooper())

        private var visible = false
        private var powerPaused = false
        private var surfaceW = 0
        private var surfaceH = 0

        private val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = applyPowerPolicy()
        }

        private val statusListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                writeStatus("state=" + when (state) {
                    Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"; else -> "ENDED"
                } + " playing=${player?.isPlaying}")
            }
            override fun onVideoSizeChanged(size: VideoSize) =
                writeStatus("video ${size.width}x${size.height}")
            override fun onPlayerError(error: PlaybackException) =
                writeStatus("ERROR ${error.errorCodeName}: ${error.message}")
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

        override fun onSurfaceCreated(h: SurfaceHolder) {
            holder = h
            visible = isVisible          // seed, don't wait for the callback
            buildPlayer()
        }

        override fun onSurfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            val changed = width != surfaceW || height != surfaceH
            surfaceW = width; surfaceH = height
            // Grayscale crop needs the surface size; rebuild once we know it.
            if (changed && prefs.getBoolean(Settings.KEY_GRAYSCALE, false)) buildPlayer()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({           // debounce app-switch/transition thrash (~macOS 0.4s)
                visible = isVisible
                Log.i(TAG, "visible=$isVisible")
                refreshPlayback()
            }, 300)
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Settings.KEY_GRAYSCALE, Settings.KEY_VIDEO -> buildPlayer()   // clean mode swap
                Settings.KEY_PAUSE_ON_LOW_POWER,
                Settings.KEY_PAUSE_ON_BATTERY,
                Settings.KEY_LOW_BATTERY_FREEZE -> applyPowerPolicy()
                // ignore others (e.g. engine_status written by writeStatus)
            }
        }

        private fun buildPlayer() {
            val h = holder ?: return
            releasePlayer()
            applyPowerPolicy(refresh = false)
            val gray = prefs.getBoolean(Settings.KEY_GRAYSCALE, false)
            player = ExoPlayer.Builder(ctx).build().apply {
                addListener(statusListener)
                setMediaItem(MediaItem.fromUri(Settings.videoUri(ctx)))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                setVideoSurface(h.surface)
                if (gray) {
                    setVideoEffects(grayscaleEffects())
                } else {
                    // Direct decode-to-surface: the proven, always-renders path.
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                }
                playWhenReady = visible && !powerPaused
                prepare()
            }
            writeStatus("built gray=$gray visible=$visible paused=$powerPaused")
        }

        private fun grayscaleEffects(): List<Effect> = buildList {
            if (surfaceW > 0 && surfaceH > 0) {
                add(Presentation.createForWidthAndHeight(
                    surfaceW, surfaceH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            }
            add(RgbFilter.createGrayscaleFilter())
        }

        private fun applyPowerPolicy(refresh: Boolean = true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val plugged = isPlugged()
            powerPaused =
                (prefs.getBoolean(Settings.KEY_PAUSE_ON_LOW_POWER, false) && pm.isPowerSaveMode) ||
                (prefs.getBoolean(Settings.KEY_PAUSE_ON_BATTERY, false) && !plugged) ||
                (prefs.getBoolean(Settings.KEY_LOW_BATTERY_FREEZE, true) &&
                    batteryPct() <= Settings.LOW_BATTERY_PCT && !plugged)
            if (refresh) refreshPlayback()
        }

        // Pausing holds the last decoded frame on the surface (== macOS freeze).
        private fun refreshPlayback() {
            player?.playWhenReady = visible && !powerPaused
        }

        private fun isPlugged(): Boolean {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }

        private fun batteryPct(): Int =
            (getSystemService(BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        private fun writeStatus(msg: String) {
            Log.i(TAG, msg)
            prefs.edit().putString("engine_status", msg).apply()
        }

        private fun releasePlayer() {
            player?.release()
            player = null
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releasePlayer()
            this.holder = null
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            runCatching { unregisterReceiver(powerReceiver) }
            releasePlayer()
            super.onDestroy()
        }
    }
}
