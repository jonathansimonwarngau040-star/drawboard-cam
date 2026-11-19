package de.jonathan.drawboardcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var overlay: QuadOverlayView
    private lateinit var status: TextView
    private lateinit var thumb: ImageView
    private lateinit var shutter: ImageButton
    private lateinit var scanToggle: ImageButton
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomChips: LinearLayout
    private lateinit var review: View
    private lateinit var cropView: CropView
    private lateinit var reviewHint: TextView

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var orientationListener: OrientationEventListener? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Zoom
    private var minZoom = -1f
    private var maxZoom = 1f
    private var zoomPresets = listOf<Float>()
    private var sliderTouched = false

    // Scan
    @Volatile private var scanMode = false
    @Volatile private var reviewing = false
    private var lastAnalysis = 0L
    private var lastQuadSeen = 0L
    private var yBytes = ByteArray(0)

    private class PendingScan(val original: File, val bitmap: Bitmap, val zoom: Float)
    private var pending: PendingScan? = null
    private var countdown = 0

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else status.text = "Kamera-Erlaubnis fehlt"
    }

    private val backDuringReview = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = finishReview(send = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.quadOverlay)
        status = findViewById(R.id.status)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)
        scanToggle = findViewById(R.id.scanToggle)
        zoomBar = findViewById(R.id.zoomBar)
        zoomChips = findViewById(R.id.zoomChips)
        review = findViewById(R.id.review)
        cropView = findViewById(R.id.cropView)
        reviewHint = findViewById(R.id.reviewHint)

        // TextureView-Modus: nötig für die Umrechnung Analyse-Bild → Bildschirm und die Blitz-Animation
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        shutter.setOnClickListener { takePhoto() }
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        thumb.setOnClickListener { startActivity(Intent(this, PhotosActivity::class.java)) }

        setupZoomControls()
        setupScan()
        onBackPressedDispatcher.addCallback(this, backDuringReview)

        // Hochkant/quer richtig im Foto vermerken (EXIF), auch wenn der Bildschirm nicht mitdreht.
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        refreshThumb()
        val txt = status.text?.toString().orEmpty()
        if (Cloud.creds(this) == null) status.text = "Nicht mit TUHH-Cloud verbunden –\ntippe unten rechts auf das Zahnrad"
        else if (txt.isEmpty() || txt.startsWith("Nicht mit")) status.text = "Bereit"
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
    }

    // ================================================================ Kamera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            // ~5 MP reicht für Drawboard völlig und lädt schnell hoch.
            val res = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(2592, 1944), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(res)
                .setJpegQuality(88)
                .build()
            // Kleines Live-Bild für die Rahmen-Erkennung
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { img -> analyze(img) } }

            // DEFAULT_BACK_CAMERA ist auf dem Pixel die „logische“ Kamera aus Ultraweitwinkel + Hauptkamera:
            // beim Zoomen wechselt das Handy selbst die Linse (0,5× = Ultraweit, ab 1× Hauptkamera,
            // 2× = Sensor-Ausschnitt, darüber digital).
            try {
                provider.unbindAll()
                camera = try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic, analysis)
                } catch (e: Exception) {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
                }
                imageCapture = ic
                camera?.cameraInfo?.zoomState?.observe(this) { zs ->
                    if (zs.minZoomRatio != minZoom || zs.maxZoomRatio.coerceAtMost(MAX_ZOOM) != maxZoom) {
                        minZoom = zs.minZoomRatio
                        maxZoom = zs.maxZoomRatio.coerceAtMost(MAX_ZOOM)
                        buildZoomChips()
                    }
                    updateZoomUi(zs.zoomRatio)
                }
            } catch (e: Exception) {
                status.text = "Kamera-Fehler: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (reviewing) return
        val ic = imageCapture ?: return
        val file = PhotoStore.newFile(this)
        val zoomAtCapture = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val scanning = scanMode
        shutter.isEnabled = false
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    preview.animate().alpha(0.3f).setDuration(60).withEndAction {
                        preview.animate().alpha(1f).setDuration(120).start()
                    }.start()
                    if (scanning) {
                        startScan(file, zoomAtCapture)
                    } else {
                        shutter.isEnabled = true
                        refreshThumb()
                        send(file)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    shutter.isEnabled = true
                    Toast.makeText(this@MainActivity, "Foto fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun send(file: File) {
        if (Cloud.creds(this) == null) {
            status.text = "Gespeichert (nur auf dem Handy) – Cloud nicht verbunden"
            return
        }
        status.text = "Sende …"
        val app = applicationContext
        PhotoStore.scope.launch {
            val r = Cloud.upload(app, file)
            if (r.isSuccess) PhotoStore.markSent(app, file)
            withContext(Dispatchers.Main) {
                if (!isDestroyed) {
                    status.text = if (r.isSuccess) "✓ An PC gesendet"
                    else "Nicht gesendet – bleibt auf dem Handy\n(${r.exceptionOrNull()?.message})"
                }
            }
        }
    }

    private fun refreshThumb() {
        val last = PhotoStore.all(this).firstOrNull()
        if (last == null) { thumb.setImageDrawable(null); return }
        PhotoStore.scope.launch {
            val bmp = PhotoStore.thumbnail(last, 200)
            withContext(Dispatchers.Main) { if (!isDestroyed) thumb.setImageBitmap(bmp) }
        }
    }

    // ================================================================ Zoom

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomControls() {
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setZoom(progressToZoom(p))
            }
            override fun onStartTrackingTouch(sb: SeekBar) { sliderTouched = true }
            override fun onStopTrackingTouch(sb: SeekBar) { sliderTouched = false }
        })

        // Zwei Finger = zoomen, einmal tippen = dort scharfstellen
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return false
                setZoom(z * d.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val point = preview.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
                )
                return true
            }
        })
        preview.setOnTouchListener { _, ev ->
            pinch.onTouchEvent(ev)
            if (!pinch.isInProgress && ev.pointerCount == 1) tap.onTouchEvent(ev)
            true
        }
    }

    private fun setZoom(r: Float) {
        camera?.cameraControl?.setZoomRatio(r.coerceIn(minZoom, maxZoom))
    }

    // Logarithmischer Balken: 0,5→1 ist genauso lang wie 1→2 und 2→4
    private fun progressToZoom(p: Int): Float {
        if (maxZoom <= minZoom) return minZoom
        return minZoom * (maxZoom / minZoom).pow(p / 1000f)
    }

    private fun zoomToProgress(z: Float): Int {
        if (maxZoom <= minZoom) return 0
        return (1000 * ln(z / minZoom) / ln(maxZoom / minZoom)).toInt().coerceIn(0, 1000)
    }

    private fun buildZoomChips() {
        val presets = mutableListOf<Float>()
        if (minZoom < 0.95f) presets += minZoom
        presets += 1f
        if (maxZoom >= 2f) presets += 2f
        if (maxZoom >= 5f) presets += 5f
        zoomPresets = presets

        zoomChips.removeAllViews()
        val size = dp(40)
        presets.forEach { z ->
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6); marginEnd = dp(6) }
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundResource(R.drawable.zoom_chip_bg)
                text = fmtZoom(z)
                setOnClickListener { setZoom(z) }
            }
            zoomChips.addView(tv)
        }
        val canZoom = maxZoom > minZoom
        zoomBar.visibility = if (canZoom) View.VISIBLE else View.GONE
        zoomChips.visibility = if (canZoom) View.VISIBLE else View.GONE
    }

    private fun updateZoomUi(z: Float) {
        if (!sliderTouched) zoomBar.progress = zoomToProgress(z)
        // Wie in der Google-Kamera: die passende Schnelltaste zeigt den genauen Wert
        val active = zoomPresets.indices.lastOrNull { zoomPresets[it] <= z * 1.01f } ?: 0
        for (i in 0 until zoomChips.childCount) {
            val tv = zoomChips.getChildAt(i) as TextView
            val sel = i == active
            tv.isSelected = sel
            tv.setTextColor(if (sel) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            tv.text = if (sel) fmtZoom(z) else fmtZoom(zoomPresets[i])
        }
    }

    private fun fmtZoom(z: Float): String {
        val s = String.format(Locale.GERMANY, "%.1f", z).removeSuffix(",0")
        return "$s×"
    }

    // ================================================================ Scan: Live-Rahmen

    private fun setupScan() {
        // OpenCV im Hintergrund laden
        PhotoStore.scope.launch { DocScanner.ready() }
        scanMode = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("scan", false)
        scanToggle.isActivated = scanMode
        scanToggle.setOnClickListener {
            if (!scanMode && !DocScanner.ready()) {
                Toast.makeText(this, "Scanner konnte nicht geladen werden", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            scanMode = !scanMode
            scanToggle.isActivated = scanMode
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("scan", scanMode).apply()
            if (!scanMode) overlay.setQuad(null)
            status.text = if (scanMode) "Scan an – Tafel/Folie ins Bild nehmen" else "Scan aus"
        }
        cropView.onUserTouch = { stopCountdown() }
        findViewById<Button>(R.id.reviewSend).setOnClickListener { finishReview(send = true) }
        findViewById<Button>(R.id.reviewCancel).setOnClickListener { finishReview(send = false) }
    }

    /** Läuft auf dem Analyse-Thread. */
    private fun analyze(image: ImageProxy) {
        try {
            if (!scanMode || reviewing || !DocScanner.ready()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < 120) return // ~8 Bilder/s reichen und schonen den Akku
            lastAnalysis = now

            val w = image.width
            val h = image.height
            val plane = image.planes[0] // Y = Helligkeit
            val buf = plane.buffer
            val rowStride = plane.rowStride
            if (yBytes.size != w * h) yBytes = ByteArray(w * h)
            buf.rewind()
            if (rowStride == w) {
                buf.get(yBytes, 0, w * h)
            } else {
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(yBytes, row * w, w)
                }
            }
            val gray = Mat(h, w, CvType.CV_8UC1)
            gray.put(0, 0, yBytes)
            val quad = DocScanner.detectGray(gray)
            gray.release()
            val source = ImageProxyTransformFactory().getOutputTransform(image)
            main.post { showLiveQuad(quad, source) }
        } catch (_: Throwable) {
            // Erkennung darf die Kamera nie stören
        } finally {
            image.close()
        }
    }

    private fun showLiveQuad(quad: FloatArray?, source: OutputTransform) {
        if (!scanMode || reviewing) { overlay.setQuad(null); return }
        val now = SystemClock.elapsedRealtime()
        if (quad == null) {
            if (now - lastQuadSeen > 500) overlay.setQuad(null)
            return
        }
        val target = preview.outputTransform ?: return
        val pts = quad.copyOf()
        CoordinateTransform(source, target).mapPoints(pts)
        overlay.setQuad(pts)
        lastQuadSeen = now
    }

    // ================================================================ Scan: nach dem Foto

    private fun startScan(original: File, zoom: Float) {
        status.text = "Erkenne Rahmen …"
        reviewing = true
        overlay.setQuad(null)
        PhotoStore.scope.launch {
            val bmp = PhotoStore.thumbnail(original, Int.MAX_VALUE)
            val quad = bmp?.let { runCatching { DocScanner.detectBitmap(it) }.getOrNull() }
            withContext(Dispatchers.Main) {
                shutter.isEnabled = true
                if (isDestroyed) return@withContext
                if (bmp == null) {
                    reviewing = false
                    status.text = "Foto konnte nicht gelesen werden – bleibt auf dem Handy"
                    refreshThumb()
                    return@withContext
                }
                showReview(PendingScan(original, bmp, zoom), quad)
            }
        }
    }

    private fun showReview(p: PendingScan, quad: FloatArray?) {
        pending = p
        // Während der Prüfung nicht drehen (sonst ginge die Ansicht verloren)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val q = quad ?: run {
            val w = p.bitmap.width.toFloat(); val h = p.bitmap.height.toFloat()
            val ix = w * 0.04f; val iy = h * 0.04f
            floatArrayOf(ix, iy, w - ix, iy, w - ix, h - iy, ix, h - iy)
        }
        cropView.setImage(p.bitmap, q)
        review.visibility = View.VISIBLE
        backDuringReview.isEnabled = true
        status.text = if (quad == null) "Kein Rahmen erkannt" else "Rahmen erkannt"
        countdown = COUNTDOWN_S
        tick(quad == null)
    }

    private val tickRunnable = Runnable { tick(false) }
    private var noFrameHint = false

    private fun tick(noFrame: Boolean) {
        if (noFrame) noFrameHint = true
        if (pending == null) return
        if (countdown <= 0) { finishReview(send = true); return }
        val prefix = if (noFrameHint) "Kein Rahmen erkannt – ganzes Bild.\n" else ""
        reviewHint.text = "${prefix}Wird in $countdown s gesendet – Ecke antippen zum Anpassen"
        countdown--
        main.postDelayed(tickRunnable, 1000)
    }

    private fun stopCountdown() {
        if (countdown < 0) return
        main.removeCallbacks(tickRunnable)
        countdown = -1
        reviewHint.text = "Ecken an die richtige Stelle ziehen, dann „Jetzt senden“"
    }

    private fun finishReview(send: Boolean) {
        val p = pending ?: return
        pending = null
        main.removeCallbacks(tickRunnable)
        noFrameHint = false
        val quad = cropView.corners.copyOf()
        review.visibility = View.GONE
        cropView.clear()
        backDuringReview.isEnabled = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        reviewing = false

        if (!send) {
            status.text = "Nicht gesendet – Original bleibt auf dem Handy"
            refreshThumb()
            return
        }
        status.text = "Scan wird aufbereitet …"
        PhotoStore.scope.launch {
            val scanFile = File(p.original.parentFile, p.original.nameWithoutExtension + "_scan.jpg")
            val ok = runCatching {
                val out = DocScanner.warp(p.bitmap, quad, p.zoom, enhance = true)
                FileOutputStream(scanFile).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                out.recycle()
            }.isSuccess
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                refreshThumb()
                if (ok) send(scanFile)
                else status.text = "Scan fehlgeschlagen – Original bleibt auf dem Handy"
            }
        }
    }

    // ================================================================ Tasten

    // Lautstärketasten als Auslöser (bei der Scan-Prüfung: sofort senden)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) {
                if (pending != null) finishReview(send = true) else takePhoto()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_ZOOM = 10f
        private const val COUNTDOWN_S = 3
    }
}
