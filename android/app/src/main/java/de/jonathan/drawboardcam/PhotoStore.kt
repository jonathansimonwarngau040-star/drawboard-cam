package de.jonathan.drawboardcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fotos liegen nur im privaten App-Speicher – keine Galerie, kein Google Fotos. */
object PhotoStore {
    /** Läuft weiter, auch wenn der Bildschirm gewechselt wird. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun newFile(ctx: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.GERMANY).format(Date())
        return File(dir(ctx), "IMG_$stamp.jpg")
    }

    fun all(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.name } ?: emptyList()

    private fun sentPrefs(ctx: Context) = ctx.getSharedPreferences("sent", Context.MODE_PRIVATE)
    fun markSent(ctx: Context, f: File) = sentPrefs(ctx).edit().putBoolean(f.name, true).apply()
    fun isSent(ctx: Context, f: File) = sentPrefs(ctx).getBoolean(f.name, false)

    fun delete(ctx: Context, f: File) {
        f.delete()
        sentPrefs(ctx).edit().remove(f.name).apply()
    }

    /** Verkleinertes, richtig gedrehtes Vorschaubild. */
    fun thumbnail(f: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }
}
