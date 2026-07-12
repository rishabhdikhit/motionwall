package app.motionwall.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.motionwall.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UiEnv(
    val prefs: SharedPreferences,
    val activePath: String,
    val libraryVersion: Int,
    val applyActive: Boolean,
    val onImport: () -> Unit,
    val onSetActive: (File) -> Unit,
    val onDelete: (File) -> Unit,
    val onApply: () -> Unit,
    val onApplyComplete: () -> Unit,
)

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AppRoot(env: UiEnv) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val library = remember(env.libraryVersion) {
        Settings.libraryDir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    var fullscreen by remember { mutableStateOf<File?>(null) }

    val tabs = listOf(
        Tab("library", "Library") { Icon(Icons.Rounded.PhotoLibrary, null) },
        Tab("settings", "Settings") { Icon(Icons.Rounded.Settings, null) },
    )

    Box(Modifier.fillMaxSize().background(Bg)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                val current = nav.currentBackStackEntryAsState().value?.destination?.route
                NavigationBar(containerColor = Card, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { if (current != t.route) nav.navigate(t.route) { launchSingleTop = true } },
                            icon = t.icon,
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Indigo, selectedTextColor = Indigo,
                                indicatorColor = Color(0x1F4F46E5),
                                unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary,
                            )
                        )
                    }
                }
            }
        ) { pad ->
            NavHost(nav, startDestination = "library", modifier = Modifier.padding(pad)) {
                composable("library") { LibraryScreen(env, library, onOpen = { fullscreen = it }) }
                composable("settings") { SettingsScreen(env) }
            }
        }

        // Long-press fullscreen preview — fade in, tap to dismiss.
        AnimatedVisibility(fullscreen != null, enter = fadeIn(), exit = fadeOut()) {
            fullscreen?.let { f ->
                Box(Modifier.fillMaxSize().background(Color.Black).clickable { fullscreen = null }) {
                    VideoPreview(f.toUri(), Modifier.fillMaxSize())
                }
            }
        }

        ApplyOverlay(active = env.applyActive, onComplete = env.onApplyComplete)
    }
}

/* --------------------------------- Library ---------------------------------- */

@Composable
private fun LibraryScreen(env: UiEnv, library: List<File>, onOpen: (File) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Library", color = TextPrimary, style = MaterialTheme.typography.headlineMedium)
            FilledIconButton(onClick = env.onImport, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Indigo)) {
                Icon(Icons.Rounded.Add, "Import", tint = Color.White)
            }
        }
        if (library.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No wallpapers yet — tap + to import.", color = TextSecondary)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(library, key = { it.absolutePath }) { f ->
                    LibraryCard(f, active = f.absolutePath == env.activePath,
                        onTap = { env.onSetActive(f); env.onApply() },
                        onLong = { onOpen(f) },
                        onDelete = { env.onDelete(f) })
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(file: File, active: Boolean, onTap: () -> Unit, onLong: () -> Unit, onDelete: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.03f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "lift")
    val meta = remember(file.absolutePath) { videoMeta(file) }

    Column(Modifier.scale(scale)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(20.dp)).background(Card)
        ) {
            Thumb(file, active = active, modifier = Modifier.fillMaxSize(), onClick = onTap,
                onLong = onLong, interaction = press, showPlay = true)
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                    .clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Delete, "Delete", tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(meta, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/* --------------------------------- Settings --------------------------------- */

@Composable
private fun SettingsScreen(env: UiEnv) {
    val prefs = env.prefs
    var play by remember { mutableStateOf(prefs.getString("engine_play", "—").orEmpty()) }
    var status by remember { mutableStateOf(prefs.getString("engine_status", "—").orEmpty()) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
            when (k) { "engine_play" -> play = p.getString(k, "—").orEmpty(); "engine_status" -> status = p.getString(k, "—").orEmpty() }
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Settings", color = TextPrimary, style = MaterialTheme.typography.headlineMedium)

        val crash = remember { prefs.getString("last_crash", "").orEmpty() }
        if (crash.isNotEmpty()) {
            Surface(color = Color(0x33FF3B30), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last crash", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(crash, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    Text("Tap to clear", color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { prefs.edit().remove("last_crash").apply() })
                }
            }
        }

        SettingsGroup("Playback") {
            SwitchRow(prefs, Settings.KEY_GRAYSCALE, "Grayscale", "Baked at import — applies to next import", false)
        }
        SettingsGroup("Battery") {
            SwitchRow(prefs, Settings.KEY_PAUSE_ON_LOW_POWER, "Pause in battery-saver", null, false)
            SwitchRow(prefs, Settings.KEY_PAUSE_ON_BATTERY, "Pause on battery", "When unplugged", false)
            SwitchRow(prefs, Settings.KEY_LOW_BATTERY_FREEZE, "Freeze below ${Settings.LOW_BATTERY_PCT}%", null, true)
        }
        SettingsGroup("Import quality") {
            FpsRow(prefs)
        }
        SettingsGroup("Engine") {
            Text("playback: $play", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(status, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text("Wally · battery-first video wallpaper", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = TextSecondary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Surface(color = Card, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun SwitchRow(prefs: SharedPreferences, key: String, title: String, subtitle: String?, default: Boolean) {
    var checked by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = { checked = it; prefs.edit().putBoolean(key, it).apply() },
            colors = SwitchDefaults.colors(checkedTrackColor = Indigo, checkedThumbColor = Color.White))
    }
}

@Composable
private fun FpsRow(prefs: SharedPreferences) {
    var fps by remember { mutableIntStateOf(prefs.getInt(Settings.KEY_FPS, 0)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FPS — lower = better battery (next import)", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Original", 30 to "30", 24 to "24", 15 to "15").forEach { (v, label) ->
                val sel = v == fps
                Text(label, color = if (sel) Color.White else TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Indigo else Color(0x14FFFFFF))
                        .clickable { fps = v; prefs.edit().putInt(Settings.KEY_FPS, v).apply() }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/* -------------------------------- components -------------------------------- */

@Composable
private fun Thumb(
    file: File, active: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit, onLong: (() -> Unit)? = null,
    interaction: MutableInteractionSource? = null, showPlay: Boolean = false,
) {
    val bmp by produceState<Bitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { runCatching { loadThumb(file) }.getOrNull() }
    }
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(Card)
            .then(if (active) Modifier.border(2.dp, Indigo, RoundedCornerShape(16.dp)) else Modifier)
            .combinedTouch(onClick, onLong, interaction),
        contentAlignment = Alignment.Center
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (showPlay) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(34.dp))
    }
}

// combinedClickable with an optional interaction source for the press-lift.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedTouch(
    onClick: () -> Unit, onLong: (() -> Unit)?, interaction: MutableInteractionSource?,
): Modifier {
    val src = interaction ?: remember { MutableInteractionSource() }
    return this.combinedClickable(
        interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong
    )
}

private fun loadThumb(file: File): Bitmap? {
    val r = MediaMetadataRetriever()
    return try { r.setDataSource(file.absolutePath); r.frameAtTime } finally { r.release() }
}

private fun videoMeta(file: File): String {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(file.absolutePath)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val mb = file.length() / 1_000_000.0
        val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
        val fps = if (frames != null && ms > 0) " · %.0ffps".format(frames * 1000.0 / ms) else ""
        "%.0fs · %.1f MB%s".format(ms / 1000.0, mb, fps)
    } catch (e: Exception) { "" } finally { r.release() }
}
