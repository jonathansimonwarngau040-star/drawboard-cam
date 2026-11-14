package de.jonathan.drawboardcam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Fotos, die nur in der App liegen. Antippen: ansehen, erneut senden oder löschen. */
class PhotosActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photos)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = Adapter()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        files = PhotoStore.all(this)
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        grid.adapter?.notifyDataSetChanged()
    }

    private fun open(f: File) {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        PhotoStore.scope.launch {
            val bmp = PhotoStore.thumbnail(f, 1600)
            withContext(Dispatchers.Main) { iv.setImageBitmap(bmp) }
        }
        AlertDialog.Builder(this)
            .setView(iv)
            .setPositiveButton("An PC senden") { _, _ -> resend(f) }
            .setNegativeButton("Löschen") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Foto wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ -> PhotoStore.delete(this, f); reload() }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNeutralButton("Schließen", null)
            .show()
    }

    private fun resend(f: File) {
        val app = applicationContext
        Toast.makeText(this, "Sende …", Toast.LENGTH_SHORT).show()
        PhotoStore.scope.launch {
            val r = Cloud.upload(app, f)
            if (r.isSuccess) PhotoStore.markSent(app, f)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    app,
                    if (r.isSuccess) "✓ An PC gesendet" else "Nicht gesendet: ${r.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
                if (!isDestroyed) reload()
            }
        }
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

        override fun getItemCount() = files.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val f = files[pos]
            h.img.setImageDrawable(null)
            h.img.tag = f.name
            h.badge.text = if (PhotoStore.isSent(this@PhotosActivity, f)) "✓" else "nur Handy"
            h.itemView.setOnClickListener { open(f) }
            PhotoStore.scope.launch {
                val bmp = PhotoStore.thumbnail(f, 300)
                withContext(Dispatchers.Main) { if (h.img.tag == f.name) h.img.setImageBitmap(bmp) }
            }
        }
    }
}
