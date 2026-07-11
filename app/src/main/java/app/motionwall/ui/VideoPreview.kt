package app.motionwall.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Muted, looping, controls-free autoplay preview — the hero. */
@Composable
fun VideoPreview(uri: Uri, modifier: Modifier = Modifier, crop: Boolean = true) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(uri) { onDispose { player.release() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        update = { it.player = player }
    )
}
