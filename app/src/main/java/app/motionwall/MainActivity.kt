package app.motionwall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activePath.value = Settings.prefs(this).getString(Settings.KEY_VIDEO, "").orEmpty()
        handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { HomeScreen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Share-sheet entry: a video shared into MotionWall gets imported + activated. */
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
            onDone = { file ->
                setActive(file)
                Toast.makeText(this, "Added to library", Toast.LENGTH_SHORT).show()
                libraryVersion.intValue++   // trigger grid reload
            },
            onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() })
    }

    private fun setActive(file: File) {
        Settings.prefs(this).edit().putString(Settings.KEY_VIDEO, file.absolutePath).apply()
        activePath.value = file.absolutePath
    }

    private fun setAsWallpaper() {
        startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, VideoWallpaperService::class.java)))
    }

    // Simple app-scoped UI state (single screen; no ViewModel needed).
    private val libraryVersion = mutableIntStateOf(0)
    private val activePath = mutableStateOf("")

    @Composable
    private fun HomeScreen() {
        val ctx = LocalContext.current
        val prefs = Settings.prefs(ctx)
        val version by libraryVersion
        val library = remember(version) { Settings.libraryDir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
        val active by activePath

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { importAndUse(it) } }

        // Live engine status (written by the wallpaper service) — our eyes without adb.
        var status by remember { mutableStateOf(prefs.getString("engine_status", "—").orEmpty()) }
        var play by remember { mutableStateOf(prefs.getString("engine_play", "—").orEmpty()) }
        DisposableEffect(Unit) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
                when (k) {
                    "engine_status" -> status = p.getString(k, "—").orEmpty()
                    "engine_play" -> play = p.getString(k, "—").orEmpty()
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(l)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
        }

        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("MotionWall", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("playback: $play", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text("engine: $status", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

            Button(onClick = ::setAsWallpaper, modifier = Modifier.fillMaxWidth()) { Text("Set as wallpaper") }
            OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import a video") }

            PrefSwitch(prefs, Settings.KEY_FIT, "Fit whole video (no zoom/crop)", default = false)
            PrefSwitch(prefs, Settings.KEY_GRAYSCALE, "Grayscale", default = false)
            PrefSwitch(prefs, Settings.KEY_PAUSE_ON_LOW_POWER, "Pause in battery-saver", default = false)
            PrefSwitch(prefs, Settings.KEY_PAUSE_ON_BATTERY, "Pause on battery (unplugged)", default = false)
            PrefSwitch(prefs, Settings.KEY_LOW_BATTERY_FREEZE, "Freeze below ${Settings.LOW_BATTERY_PCT}%", default = true)

            Text("Library", color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (library.isEmpty()) {
                Text("No clips yet — import one above.", color = Color.Gray)
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(library, key = { it.absolutePath }) { file ->
                        LibraryCell(file, isActive = file.absolutePath == active,
                            onSelect = { setActive(file) },
                            onDelete = {
                                file.delete()
                                if (file.absolutePath == active) {
                                    prefs.edit().remove(Settings.KEY_VIDEO).apply()
                                    activePath.value = ""
                                }
                                libraryVersion.intValue++
                            })
                    }
                }
            }
        }
    }

    @Composable
    private fun PrefSwitch(prefs: android.content.SharedPreferences, key: String, label: String, default: Boolean) {
        var checked by remember { mutableStateOf(prefs.getBoolean(key, default)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = {
                checked = it
                prefs.edit().putBoolean(key, it).apply()   // engine's listener picks it up live
            })
        }
    }

    @Composable
    private fun LibraryCell(file: File, isActive: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
        val thumb by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
            value = withContext(Dispatchers.IO) { runCatching { videoThumb(file) }.getOrNull() }
        }
        Box(Modifier.aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)).background(Color.DarkGray).clickable(onClick = onSelect)) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (isActive) Box(Modifier.fillMaxSize().background(Color(0x3300E5FF)))
            Text(if (isActive) "● active" else "×",
                color = if (isActive) Color.Cyan else Color.White,
                modifier = Modifier.align(if (isActive) Alignment.BottomStart else Alignment.TopEnd)
                    .padding(6.dp).clickable(enabled = !isActive, onClick = onDelete))
        }
    }

    private fun videoThumb(file: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(file.absolutePath); r.frameAtTime } finally { r.release() }
    }
}
