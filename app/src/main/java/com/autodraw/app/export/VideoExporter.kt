package com.autodraw.app.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import com.autodraw.app.drawing.DrawingEngine
import com.autodraw.app.drawing.SceneRenderer
import com.autodraw.app.drawing.Stroke
import java.io.File
import java.nio.ByteBuffer

/**
 * Records the full drawing animation to an MP4 (H.264) file in the gallery.
 *
 * Uses an offscreen [DrawingEngine] + [SceneRenderer] so the export is
 * deterministic and independent of the on-screen view: we step the engine
 * from 0 → 1 at a fixed frame rate and render each frame to the encoder's
 * input [Surface].
 *
 * Must be called off the main thread (it blocks for the duration of the
 * encode). Reports progress via [onProgress] (0..1).
 */
class VideoExporter(private val context: Context) {

    private val mime = MediaFormat.MIMETYPE_VIDEO_AVC

    data class Request(
        val strokes: List<Stroke>,
        val imgWidth: Int,
        val imgHeight: Int,
        val outWidth: Int = 1080,
        val outHeight: Int = 1080,
        val fps: Int = 30,
        val durationSec: Float = 12f,
        val bitrate: Int = 6_000_000,
        val brushScale: Float = 1f
    )

    fun render(req: Request, onProgress: (Float) -> Unit = {}): Uri {
        require(req.strokes.isNotEmpty()) { "No strokes to render" }

        val engine = DrawingEngine().apply {
            setStrokes(req.strokes, req.imgWidth, req.imgHeight)
        }
        val renderer = SceneRenderer().apply { brushScale = req.brushScale }

        val format = MediaFormat.createVideoFormat(mime, req.outWidth, req.outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, req.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, req.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val muxerSink = openMuxer(req.outWidth, req.outHeight)
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false

        val totalFrames = (req.fps * req.durationSec).toInt().coerceAtLeast(1)
        try {
            for (frame in 0..totalFrames) {
                val frac = frame.toFloat() / totalFrames
                engine.seekTo(frac)
                drawFrame(inputSurface, renderer, engine, req.outWidth, req.outHeight)
                drain(codec, bufferInfo, muxerSink, started = { muxerStarted = true })
                onProgress(frac)
            }
            codec.signalEndOfInputStream()
            drain(codec, bufferInfo, muxerSink, started = { muxerStarted = true }, eos = true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            inputSurface.release()
            try { muxerSink.muxer.stop() } catch (_: Exception) {}
            muxerSink.muxer.release()
            muxerSink.pfd?.close()
        }
        // Mark the video as no longer pending (scoped storage bookkeeping).
        finalizeUri(muxerSink.uri)
        return muxerSink.uri
    }

    private fun drawFrame(
        surface: Surface, renderer: SceneRenderer,
        engine: DrawingEngine, w: Int, h: Int
    ) {
        val canvas = surface.lockHardwareCanvas()
        try {
            renderer.render(engine, canvas, w, h, drawBorder = false)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drain(
        codec: MediaCodec, info: MediaCodec.BufferInfo,
        sink: MuxerSink, started: () -> Unit, eos: Boolean = false
    ) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    // in EOS mode keep looping until we get the EOS buffer
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    sink.track = sink.muxer.addTrack(newFormat)
                    sink.muxer.start()
                    started()
                }
                index >= 0 -> {
                    val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: continue
                    if (info.size > 0 && sink.track >= 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.muxer.writeSampleData(sink.track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private data class MuxerSink(
        val muxer: MediaMuxer,
        var track: Int,
        val uri: Uri,
        val pfd: android.os.ParcelFileDescriptor? = null
    )

    private fun openMuxer(w: Int, h: Int): MuxerSink {
        val name = "autodraw_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/AutoDraw")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Cannot open output fd")
            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            MuxerSink(muxer, -1, uri, pfd)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "AutoDraw"
            ).apply { mkdirs() }
            val file = File(dir, name)
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            MuxerSink(muxer, -1, Uri.fromFile(file))
        }
    }

    private fun finalizeUri(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }
}
