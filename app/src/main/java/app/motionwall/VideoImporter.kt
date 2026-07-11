package app.motionwall

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Import a source video into the library: strip audio + cap resolution (compress) via
 * Media3 Transformer. No crop (the engine center-crops at render) and no fps cap yet.
 * ponytail: fps-cap + trim UI deferred — add when a clip is too heavy or too long.
 */
@UnstableApi
object VideoImporter {

    private const val MAX_HEIGHT = 1080   // downscale ceiling: smaller file, lighter decode

    fun import(
        ctx: Context,
        source: Uri,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        val out = File(Settings.libraryDir(ctx), "wp_${System.currentTimeMillis()}.mp4")

        val fps = Settings.prefs(ctx).getInt(Settings.KEY_FPS, 0)
        val videoEffects = buildList<Effect> {
            add(Presentation.createForHeight(MAX_HEIGHT))                 // downscale = smaller file
            if (fps > 0) add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))  // fewer frames = less decode
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    onDone(out)
                }
                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    out.delete()
                    onError(exception.message ?: "Import failed")
                }
            })
            .build()
            .start(edited, out.absolutePath)
    }
}
