package com.autodraw.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autodraw.app.drawing.DrawingEngine
import com.autodraw.app.export.ImageExporter
import com.autodraw.app.export.VideoExporter
import com.autodraw.app.image.SketchConfig
import com.autodraw.app.image.SketchExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: com.autodraw.app.drawing.DrawingView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var hintText: android.widget.TextView
    private lateinit var progressSeek: android.widget.SeekBar
    private lateinit var speedSeek: android.widget.SeekBar
    private lateinit var brushSeek: android.widget.SeekBar
    private lateinit var shadeSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var btnPick: com.google.android.material.button.MaterialButton
    private lateinit var btnStart: com.google.android.material.button.MaterialButton
    private lateinit var btnReset: com.google.android.material.button.MaterialButton
    private lateinit var btnExportImage: com.google.android.material.button.MaterialButton
    private lateinit var btnExportVideo: com.google.android.material.button.MaterialButton

    private val extractor = SketchExtractor(SketchConfig())
    private var sourceBitmap: Bitmap? = null
    private var lastResult: SketchExtractor.Result? = null
    private var seekDragging = false
    private var playing = false

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) loadAndProcess(uri) }

    private val writePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doExportImage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        progressBar = findViewById(R.id.progressBar)
        hintText = findViewById(R.id.hintText)
        progressSeek = findViewById(R.id.progressSeek)
        speedSeek = findViewById(R.id.speedSeek)
        brushSeek = findViewById(R.id.brushSeek)
        shadeSwitch = findViewById(R.id.shadeSwitch)
        btnPick = findViewById(R.id.btnPick)
        btnStart = findViewById(R.id.btnStart)
        btnReset = findViewById(R.id.btnReset)
        btnExportImage = findViewById(R.id.btnExportImage)
        btnExportVideo = findViewById(R.id.btnExportVideo)

        wireControls()
        wireEngine()
    }

    private fun wireControls() {
        btnPick.setOnClickListener { pickMedia.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        ) }

        btnStart.setOnClickListener {
            if (!playing) {
                drawingView.startAnimation()
                playing = true
                btnStart.text = getString(R.string.pause)
            } else {
                drawingView.pauseAnimation()
                playing = false
                btnStart.text = getString(R.string.resume)
            }
        }

        btnReset.setOnClickListener {
            drawingView.resetAnimation()
            playing = false
            btnStart.text = getString(R.string.start_draw)
            progressSeek.progress = 0
        }

        btnExportImage.setOnClickListener { tryExportImage() }

        btnExportVideo.setOnClickListener { exportVideo() }

        speedSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
            // 0..100 -> 120..2400 points/sec
            val v = 120f + (progress / 100f) * (2400f - 120f)
            drawingView.setSpeed(v)
        })
        drawingView.setSpeed(1200f)

        brushSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
            // 0..100 -> 0.4..2.6
            val s = 0.4f + (progress / 100f) * (2.6f - 0.4f)
            drawingView.setBrushScale(s)
        })
        drawingView.setBrushScale(1.4f)

        progressSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && !seekDragging && lastResult != null) {
                    // tap-to-seek (optional, lightweight)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
                if (lastResult != null) {
                    drawingView.engine.seekTo(progressSeek.progress / 1000f)
                    drawingView.invalidate()
                }
            }
        })

        shadeSwitch.setOnCheckedChangeListener { _, _ -> reprocess() }
    }

    private fun wireEngine() {
        drawingView.engine.listener = object : DrawingEngine.Listener {
            override fun onProgress(fraction: Float) {
                runOnUiThread {
                    if (!seekDragging) progressSeek.progress = (fraction * 1000).toInt()
                }
            }
            override fun onComplete() {
                runOnUiThread {
                    playing = false
                    btnStart.text = getString(R.string.start_draw)
                    progressSeek.progress = 1000
                }
            }
        }
    }

    private fun loadAndProcess(uri: Uri) {
        hintText.visibility = android.view.View.GONE
        progressBar.visibility = android.view.View.VISIBLE
        btnStart.isEnabled = false
        btnReset.isEnabled = false
        btnExportImage.isEnabled = false
        btnExportVideo.isEnabled = false

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeSampled(uri, 1024) }
            sourceBitmap = bmp
            if (bmp == null) {
                runOnUiThread {
                    progressBar.visibility = android.view.View.GONE
                    hintText.visibility = android.view.View.VISIBLE
                    hintText.text = getString(R.string.no_image)
                    Toast.makeText(this@MainActivity, "无法加载图片", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            processInternal(bmp)
        }
    }

    private fun reprocess() {
        val src = sourceBitmap ?: return
        progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch { processInternal(src) }
    }

    private suspend fun processInternal(src: Bitmap) {
        val config = SketchConfig(
            shadingLevels = if (shadeSwitch.isChecked) listOf(0.32f, 0.55f) else emptyList()
        )
        val result = withContext(Dispatchers.IO) {
            SketchExtractor(config).extract(src)
        }
        lastResult = result
        runOnUiThread {
            drawingView.engine.setStrokes(result.strokes, result.width, result.height)
            drawingView.resetAnimation()
            progressBar.visibility = android.view.View.GONE
            btnStart.isEnabled = true
            btnReset.isEnabled = true
            btnExportImage.isEnabled = true
            btnExportVideo.isEnabled = true
            progressSeek.progress = 0
            Toast.makeText(
                this@MainActivity,
                "已生成 ${result.strokes.size} 条笔触",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun tryExportImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            doExportImage()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) doExportImage()
            else writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun doExportImage() {
        val bmp = drawingView.renderToBitmap()
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                ImageExporter.savePng(this@MainActivity, bmp)
            }
            announceSaved(getString(R.string.saved), uri)
        }
    }

    private fun exportVideo() {
        val result = lastResult ?: return
        if (playing) { drawingView.pauseAnimation(); playing = false; btnStart.text = getString(R.string.resume) }
        progressBar.visibility = android.view.View.VISIBLE
        progressBar.isIndeterminate = true
        Toast.makeText(this, getString(R.string.recording_started), Toast.LENGTH_SHORT).show()
        val a = result.width.toFloat() / result.height
        var outW = if (a >= 1f) 1080 else (1080 * a).toInt()
        var outH = if (a >= 1f) (1080 / a).toInt() else 1080
        // H.264 dimensions must be even
        outW = outW and 0x7FFFFFFE
        outH = outH and 0x7FFFFFFE

        lifecycleScope.launch {
            val brushScale = if (brushSeek.progress == 0) 1.4f
                             else 0.4f + (brushSeek.progress / 100f) * (2.6f - 0.4f)
            val req = VideoExporter.Request(
                strokes = result.strokes,
                imgWidth = result.width,
                imgHeight = result.height,
                outWidth = outW.coerceAtLeast(2),
                outHeight = outH.coerceAtLeast(2),
                brushScale = brushScale,
                durationSec = 14f
            )
            val ok = try {
                val uri = withContext(Dispatchers.IO) {
                    VideoExporter(this@MainActivity).render(req)
                }
                announceSaved(getString(R.string.recording_stopped), uri)
                true
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "录制失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
                false
            }
            runOnUiThread { progressBar.isIndeterminate = false; progressBar.visibility = android.view.View.GONE }
            // no-op on ok flag to keep compiler happy
            if (!ok) Unit
        }
    }

    private fun announceSaved(message: String, uri: Uri) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        // Make the saved media visible in the gallery
        sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
    }

    private fun decodeSampled(uri: Uri, reqLongSide: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight)
            while (longest / sample > reqLongSide) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } catch (e: Exception) { null }
    }

    private fun simpleSeek(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onProgress(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
}
