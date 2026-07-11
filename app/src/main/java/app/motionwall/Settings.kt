package app.motionwall

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/** One place for the pref schema shared by the wallpaper engine and the UI. */
object Settings {
    const val PREFS = "motionwall"

    const val KEY_VIDEO = "video"                 // absolute file path, or "" => bundled sample
    const val KEY_GRAYSCALE = "grayscale"         // Boolean
    const val KEY_FIT = "fit_mode"                // Boolean: true = fit whole video, false = fill/crop
    const val KEY_FPS = "import_fps"              // Int target fps at import; 0 = keep original
    const val KEY_PAUSE_ON_LOW_POWER = "pause_low_power"  // Boolean
    const val KEY_PAUSE_ON_BATTERY = "pause_on_battery"   // Boolean
    const val KEY_LOW_BATTERY_FREEZE = "low_batt_freeze"  // Boolean (freeze below threshold)
    const val LOW_BATTERY_PCT = 15

    /** Folder holding processed wallpaper clips — its contents ARE the library (no DB). */
    fun libraryDir(ctx: Context): File =
        File(ctx.filesDir, "wallpapers").apply { mkdirs() }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current video as a Uri; falls back to the bundled sample when nothing is set. */
    fun videoUri(ctx: Context): Uri {
        val path = prefs(ctx).getString(KEY_VIDEO, "").orEmpty()
        return if (path.isNotEmpty() && File(path).exists()) File(path).toUri()
        else "asset:///test.mp4".toUri()
    }
}
