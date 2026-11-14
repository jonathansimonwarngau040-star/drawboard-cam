package de.jonathan.drawboardcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var thumb: ImageView
    private lateinit var shutter: ImageButton
    private var imageCapture: ImageCapture? = null
    private var orientationListener: OrientationEventListener? = null

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else status.text = "Kamera-Erlaubnis fehlt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)

        shutter.setOnClickListener { takePhoto() }
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        thumb.setOnClickListener { startActivity(Intent(this, PhotosActivity::class.java)) }

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
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
                imageCapture = ic
            } catch (e: Exception) {
                status.text = "Kamera-Fehler: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val file = PhotoStore.newFile(this)
        shutter.isEnabled = false
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shutter.isEnabled = true
                    preview.animate().alpha(0.3f).setDuration(60).withEndAction {
                        preview.animate().alpha(1f).setDuration(120).start()
                    }.start()
                    refreshThumb()
                    send(file)
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
        val app = applicationContext
        PhotoStore.scope.launch {
            val bmp = PhotoStore.thumbnail(last, 200)
            withContext(Dispatchers.Main) { if (!isDestroyed) thumb.setImageBitmap(bmp) }
        }
    }

    // Lautstärketasten als Auslöser
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
