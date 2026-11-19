package de.jonathan.drawboardcam

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Erkennt große viereckige Flächen (Tafel, Beamer-Folie, Whiteboard, Blatt) und entzerrt sie.
 * Alles lokal mit OpenCV – kein Google-Dokumentenscanner.
 *
 * Ecken werden immer als FloatArray(8) übergeben: x/y von oben-links, oben-rechts, unten-rechts, unten-links.
 */
object DocScanner {

    /** Fläche muss mindestens so viel vom Bild ausmachen. */
    private const val MIN_AREA = 0.12
    /** …aber nicht praktisch das ganze Bild (sonst ist es meist nur der Bildrand). */
    private const val MAX_AREA = 0.97

    private var state = 0 // 0 = noch nicht geladen, 1 = ok, -1 = geht nicht

    @Synchronized
    fun ready(): Boolean {
        if (state == 0) state = try { if (OpenCVLoader.initLocal()) 1 else -1 } catch (t: Throwable) { -1 }
        return state == 1
    }

    // ---------------------------------------------------------------- Erkennung

    /** Sucht das größte plausible Viereck in einem Graustufenbild. Koordinaten im System von [gray]. */
    fun detectGray(gray: Mat): FloatArray? {
        val longSide = max(gray.cols(), gray.rows())
        val s = if (longSide > 480) 480.0 / longSide else 1.0
        val small = Mat()
        if (s < 1.0) Imgproc.resize(gray, small, Size(), s, s, Imgproc.INTER_AREA) else gray.copyTo(small)
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        val total = small.rows().toDouble() * small.cols()

        var best: Array<Point>? = null
        var bestArea = 0.0
        fun consider(bin: Mat) {
            val q = largestQuad(bin, total) ?: return
            val a = abs(area(q))
            if (a > bestArea) { bestArea = a; best = q }
        }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val work = Mat()

        // 1) Kanten (Rahmen von Tafel / Leinwand / Bildschirm)
        val med = median(small)
        val thresholds = listOf(
            max(10.0, 0.66 * med) to min(255.0, max(30.0, 1.33 * med)),
            20.0 to 60.0,
        )
        for ((lo, hi) in thresholds) {
            Imgproc.Canny(small, work, lo, hi)
            Imgproc.dilate(work, work, kernel, Point(-1.0, -1.0), 2)
            consider(work)
        }

        // 2) Helle Fläche (Folie, Whiteboard) bzw. dunkle Fläche (Kreidetafel)
        Imgproc.threshold(small, work, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(work, work, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 2)
        consider(work)
        Core.bitwise_not(work, work)
        consider(work)

        work.release(); kernel.release(); small.release()

        val b = best ?: return null
        val inv = 1.0 / s
        val out = FloatArray(8)
        order(b).forEachIndexed { i, p ->
            out[2 * i] = (p.x * inv).toFloat()
            out[2 * i + 1] = (p.y * inv).toFloat()
        }
        return out
    }

    /** Erkennung auf einem fertigen Foto. Koordinaten in Pixeln von [bmp]. */
    fun detectBitmap(bmp: Bitmap): FloatArray? {
        val s = min(1.0, 1000.0 / max(bmp.width, bmp.height))
        val scaled = if (s < 1.0)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
        else bmp
        val rgba = Mat()
        val gray = Mat()
        Utils.bitmapToMat(scaled, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val q = detectGray(gray)
        rgba.release(); gray.release()
        if (scaled !== bmp) scaled.recycle()
        return q?.let { arr -> FloatArray(8) { (arr[it] / s).toFloat() } }
    }

    private fun largestQuad(bin: Mat, total: Double): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        // RETR_LIST: auch die Innenkante eines Rahmens zählt – hilfreich, wenn außen Wand/Kram dranhängt.
        Imgproc.findContours(bin, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hier.release()
        var best: Array<Point>? = null
        var bestA = 0.0
        for (c in contours) {
            if (Imgproc.contourArea(c) >= total * MIN_AREA) {
                val pts = c.toArray()
                val idx = MatOfInt()
                Imgproc.convexHull(c, idx)
                val hull = idx.toArray().map { pts[it] }.toTypedArray()
                idx.release()
                val q = approxQuad(hull)
                if (q != null) {
                    val a = abs(area(q))
                    if (a >= total * MIN_AREA && a <= total * MAX_AREA && a > bestA && goodShape(q, total)) {
                        bestA = a; best = q
                    }
                }
            }
            c.release()
        }
        return best
    }

    private fun approxQuad(hull: Array<Point>): Array<Point>? {
        if (hull.size < 4) return null
        val m = MatOfPoint2f(*hull)
        val out = MatOfPoint2f()
        val peri = Imgproc.arcLength(m, true)
        var eps = 0.015
        var result: Array<Point>? = null
        while (eps <= 0.08) {
            Imgproc.approxPolyDP(m, out, eps * peri, true)
            val n = out.rows()
            if (n == 4) { result = out.toArray(); break }
            if (n < 4) break
            eps += 0.01
        }
        m.release(); out.release()
        return result
    }

    /** Keine extrem spitzen Ecken, keine winzigen Seiten. */
    private fun goodShape(q: Array<Point>, total: Double): Boolean {
        val minSide = 0.08 * sqrt(total)
        for (i in 0 until 4) {
            val p = q[i]; val a = q[(i + 3) % 4]; val b = q[(i + 1) % 4]
            val ax = a.x - p.x; val ay = a.y - p.y
            val bx = b.x - p.x; val by = b.y - p.y
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < minSide || lb < minSide) return false
            val cos = (ax * bx + ay * by) / (la * lb)
            if (abs(cos) > 0.75) return false // Winkel außerhalb ca. 41°…139°
        }
        return true
    }

    private fun area(q: Array<Point>): Double {
        var s = 0.0
        for (i in q.indices) {
            val a = q[i]; val b = q[(i + 1) % q.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2
    }

    /** Reihenfolge: oben-links, oben-rechts, unten-rechts, unten-links. */
    private fun order(q: Array<Point>): List<Point> {
        val cx = q.sumOf { it.x } / 4
        val cy = q.sumOf { it.y } / 4
        val sorted = q.sortedBy { atan2(it.y - cy, it.x - cx) } // im Uhrzeigersinn (y zeigt nach unten)
        val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        return List(4) { sorted[(start + it) % 4] }
    }

    private fun median(m: Mat): Double {
        val buf = ByteArray(m.total().toInt())
        m.get(0, 0, buf)
        val hist = IntArray(256)
        for (b in buf) hist[b.toInt() and 0xFF]++
        var acc = 0
        val half = buf.size / 2
        for (i in 0..255) { acc += hist[i]; if (acc >= half) return i.toDouble() }
        return 128.0
    }

    // ---------------------------------------------------------------- Entzerren + Aufbereiten

    /**
     * Schneidet das Viereck aus, stellt es gerade und verbessert Kontrast/Schärfe.
     * [zoom] = Zoomstufe bei der Aufnahme (für die Schätzung des echten Seitenverhältnisses).
     */
    fun warp(bmp: Bitmap, q: FloatArray, zoom: Float, enhance: Boolean): Bitmap {
        val tl = Point(q[0].toDouble(), q[1].toDouble())
        val tr = Point(q[2].toDouble(), q[3].toDouble())
        val br = Point(q[4].toDouble(), q[5].toDouble())
        val bl = Point(q[6].toDouble(), q[7].toDouble())

        val w0 = max(dist(tl, tr), dist(bl, br))
        val h0 = max(dist(tl, bl), dist(tr, br))
        val longSide = max(bmp.width, bmp.height).toDouble()
        // Grobe Brennweite in Pixeln (Pixel-8-Hauptkamera ≈ 0,74 × lange Bildseite bei 1×)
        val fExpected = 0.74 * longSide * zoom.coerceAtLeast(0.3f)
        var ratio = aspectRatio(tl, tr, bl, br, bmp.width / 2.0, bmp.height / 2.0, fExpected) ?: (w0 / h0)
        ratio = snap(ratio)

        var outW = sqrt(w0 * h0 * ratio)
        var outH = outW / ratio
        val k = min(1.0, 3200.0 / max(outW, outH))
        outW *= k; outH *= k
        val w = outW.roundToInt().coerceAtLeast(16)
        val h = outH.roundToInt().coerceAtLeast(16)

        val src = MatOfPoint2f(tl, tr, br, bl)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0)
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val warped = Mat()
        Imgproc.warpPerspective(rgba, warped, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        rgba.release(); m.release(); src.release(); dst.release()

        if (enhance) enhance(warped)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, out)
        warped.release()
        return out
    }

    /** Kontrast lokal anheben (CLAHE auf Helligkeit, Farben bleiben) + Schärfen. Klappt bei hellen und dunklen Tafeln. */
    private fun enhance(rgba: Mat) {
        val rgb = Mat()
        val lab = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(ch[0], ch[0])
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)

        val sigma = max(1.0, max(rgb.cols(), rgb.rows()) / 1600.0)
        val blur = Mat()
        Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(rgb, 1.7, blur, -0.7, 0.0, rgb)
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        blur.release(); lab.release(); rgb.release()
    }

    /**
     * Echtes Seitenverhältnis (Breite/Höhe) des fotografierten Rechtecks trotz schräger Perspektive
     * (Zhang & He, „Whiteboard scanning and image enhancement“). null = nicht bestimmbar.
     */
    private fun aspectRatio(tl: Point, tr: Point, bl: Point, br: Point, u0: Double, v0: Double, fExpected: Double): Double? {
        fun v(p: Point) = doubleArrayOf(p.x - u0, p.y - v0, 1.0)
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        )
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        val m1 = v(tl); val m2 = v(tr); val m3 = v(bl); val m4 = v(br)
        val d2 = dot(cross(m2, m4), m3)
        val d3 = dot(cross(m3, m4), m2)
        if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return null
        val k2 = dot(cross(m1, m4), m3) / d2
        val k3 = dot(cross(m1, m4), m2) / d3
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }

        var f = fExpected
        val dz = n2[2] * n3[2]
        if (abs(dz) > 1e-12) {
            val f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / dz
            if (f2 > 0) {
                val fEst = sqrt(f2)
                if (fEst > 0.5 * fExpected && fEst < 2.0 * fExpected) f = fEst
            }
        }
        val num = (n2[0] * n2[0] + n2[1] * n2[1]) / (f * f) + n2[2] * n2[2]
        val den = (n3[0] * n3[0] + n3[1] * n3[1]) / (f * f) + n3[2] * n3[2]
        if (num <= 0 || den <= 0) return null
        val r = sqrt(num / den)
        return if (r.isFinite() && r in 0.2..5.0) r else null
    }

    /** Fast 16:9 / 4:3 / 16:10 (typisch für Folien) → genau darauf einrasten. */
    private fun snap(r: Double): Double {
        val common = listOf(16.0 / 9, 4.0 / 3, 16.0 / 10, 9.0 / 16, 3.0 / 4, 10.0 / 16)
        val c = common.minByOrNull { abs(it - r) / it } ?: return r
        return if (abs(c - r) / c < 0.04) c else r
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}
