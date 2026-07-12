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
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "MotionWall"

/**
 * Battery-first video wallpaper. Renders through our own GL pipeline (GLVideoRenderer) so
 * the video shows at its native aspect (sharp, no auto-zoom), with a live grayscale shader
 * and a fill option. If GL init fails on a device, falls back to the plain crop path so the
 * wallpaper still works (no black-screen regression). Plays only while home is visible and
 * no power rule pauses it.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    @UnstableApi
    private inner class VideoEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val ctx get() = this@VideoWallpaperService
        private var player: ExoPlayer? = null
        private var renderer: GLVideoRenderer? = null
        private var holder: SurfaceHolder? = null
        private var decided = false          // pipeline chosen (GL or fallback) — build once
        private val prefs get() = Settings.prefs(ctx)
        private val main = Handler(Looper.getMainLooper())

        private var visible = false
        private var powerPaused = false
        private var zoomedAway = false
        private var surfaceW = 0
        private var surfaceH = 0

        private val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = applyPowerPolicy()
        }

        private val playerListener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                renderer?.setVideoSize(size.width, size.height)
                writeStatus("video ${size.width}x${size.height} surf ${surfaceW}x$surfaceH")
            }
            override fun onPlayerError(e: PlaybackException) = writeStatus("ERROR ${e.errorCodeName}: ${e.message}")
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                prefs.edit().putString("engine_play",
                    if (isPlaying) "▶ playing (home visible)"
                    else "⏸ paused — 0 decode (off-home / drawer / screen off / low batt)").apply()
            }
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
            visible = isVisible
            decided = false
            applyPowerPolicy(refresh = false)
            // Try the GL pipeline; it calls back with an input surface, or reports failure.
            val gl = GLVideoRenderer(
                output = h.surface,
                onInputSurface = { surf -> main.post { if (!decided) { decided = true; buildPlayer(surf) } } },
                onStatus = { s ->
                    writeStatus(s)
                    if (s.contains("FAILED") && !decided) main.post { if (!decided) { decided = true; buildFallback() } }
                },
            ).apply {
                grayscale = prefs.getBoolean(Settings.KEY_GRAYSCALE, false)
                scale = prefs.getString(Settings.KEY_SCALE, "fit") ?: "fit"
                resize(surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1))
            }
            renderer = gl
            gl.start()
        }

        override fun onSurfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceW = width; surfaceH = height
            renderer?.resize(width, height)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ visible = isVisible; Log.i(TAG, "visible=$isVisible"); refreshPlayback() }, 300)
        }

        // App drawer / recents / shade zoom the wallpaper out — pause for those too.
        override fun onZoomChanged(zoom: Float) {
            val away = zoom >= 0.5f
            if (away != zoomedAway) { zoomedAway = away; refreshPlayback() }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Settings.KEY_GRAYSCALE -> renderer?.grayscale = prefs.getBoolean(key, false)   // live, no rebuild
                Settings.KEY_SCALE -> renderer?.scale = prefs.getString(key, "fit") ?: "fit"
                Settings.KEY_VIDEO -> player?.apply {
                    setMediaItem(MediaItem.fromUri(Settings.videoUri(ctx))); prepare(); refreshPlayback()
                }
                Settings.KEY_PAUSE_ON_LOW_POWER, Settings.KEY_PAUSE_ON_BATTERY, Settings.KEY_LOW_BATTERY_FREEZE ->
                    applyPowerPolicy()
            }
        }

        private fun buildPlayer(inputSurface: Surface) {
            player = ExoPlayer.Builder(ctx).build().apply {
                addListener(playerListener)
                setMediaItem(MediaItem.fromUri(Settings.videoUri(ctx)))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                setVideoSurface(inputSurface)
                playWhenReady = visible && !powerPaused && !zoomedAway
                prepare()
            }
            writeStatus("GL pipeline · visible=$visible")
        }

        // GL unavailable → plain decoder-to-surface, cropped. No grayscale/letterbox, but works.
        private fun buildFallback() {
            val h = holder ?: return
            renderer?.release(); renderer = null
            player = ExoPlayer.Builder(ctx).build().apply {
                addListener(playerListener)
                setMediaItem(MediaItem.fromUri(Settings.videoUri(ctx)))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                setVideoSurface(h.surface)
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                playWhenReady = visible && !powerPaused && !zoomedAway
                prepare()
            }
            writeStatus("fallback (crop) · GL failed")
        }

        private fun applyPowerPolicy(refresh: Boolean = true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val plugged = isPlugged()
            powerPaused =
                (prefs.getBoolean(Settings.KEY_PAUSE_ON_LOW_POWER, false) && pm.isPowerSaveMode) ||
                (prefs.getBoolean(Settings.KEY_PAUSE_ON_BATTERY, false) && !plugged) ||
                (prefs.getBoolean(Settings.KEY_LOW_BATTERY_FREEZE, true) && batteryPct() <= Settings.LOW_BATTERY_PCT && !plugged)
            if (refresh) refreshPlayback()
        }

        private fun refreshPlayback() {
            player?.playWhenReady = visible && !powerPaused && !zoomedAway
        }

        private fun isPlugged(): Boolean {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }

        private fun batteryPct(): Int =
            (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        private fun writeStatus(msg: String) {
            Log.i(TAG, msg)
            prefs.edit().putString("engine_status", msg).apply()
        }

        private fun releaseAll() {
            player?.release(); player = null
            renderer?.release(); renderer = null
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releaseAll()
            this.holder = null
        }

        override fun onDestroy() {
            main.removeCallbacksAndMessages(null)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            runCatching { unregisterReceiver(powerReceiver) }
            releaseAll()
            super.onDestroy()
        }
    }
}
