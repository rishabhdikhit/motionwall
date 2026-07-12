package app.motionwall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import app.motionwall.ui.AppRoot
import app.motionwall.ui.MotionWallTheme
import app.motionwall.ui.UiEnv
import java.io.File

@UnstableApi
class MainActivity : ComponentActivity() {

    private val libraryVersion = mutableIntStateOf(0)
    private val activePath = mutableStateOf("")
    private val applyActive = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Capture crashes to a pref (shown in Settings next launch) — diagnostics without adb.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Settings.prefs(this).edit().putString("last_crash", e.stackTraceToString().take(2000)).commit() }
            prev?.uncaughtException(t, e)
        }
        val splash = installSplashScreen()
        var keepSplash = true
        splash.setKeepOnScreenCondition { keepSplash }
        super.onCreate(savedInstanceState)
        window.decorView.postDelayed({ keepSplash = false }, 1000)

        activePath.value = Settings.prefs(this).getString(Settings.KEY_VIDEO, "").orEmpty()
        handleShare(intent)
        setContent { MotionWallTheme { AppRoot(rememberEnv()) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    @Composable
    private fun rememberEnv(): UiEnv {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importAndUse(it) }
        }
        return UiEnv(
            prefs = Settings.prefs(this),
            activePath = activePath.value,
            libraryVersion = libraryVersion.intValue,
            applyActive = applyActive.value,
            onImport = { picker.launch(arrayOf("video/*")) },
            onSetActive = ::setActive,
            onDelete = ::deleteClip,
            // Always open the system live-wallpaper preview so you see it and can choose the
            // target (home / lock / both — whatever Nothing OS offers). The selected clip is
            // already active, so the preview shows it.
            onApply = { startActivity(changeWallpaperIntent()) },
            onApplyComplete = { applyActive.value = false },
        )
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
            importAndUse(uri)
        }
    }

    private fun importAndUse(source: Uri) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        VideoImporter.import(this, source,
            onDone = { file -> setActive(file); libraryVersion.intValue++; Toast.makeText(this, "Added", Toast.LENGTH_SHORT).show() },
            onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() })
    }

    private fun setActive(file: File) {
        Settings.prefs(this).edit().putString(Settings.KEY_VIDEO, file.absolutePath).apply()
        activePath.value = file.absolutePath
    }

    private fun deleteClip(file: File) {
        file.delete()
        if (file.absolutePath == activePath.value) {
            Settings.prefs(this).edit().remove(Settings.KEY_VIDEO).apply()
            activePath.value = ""
        }
        libraryVersion.intValue++
    }

    private fun changeWallpaperIntent() = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, VideoWallpaperService::class.java))
}
