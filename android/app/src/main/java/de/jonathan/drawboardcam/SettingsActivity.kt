package de.jonathan.drawboardcam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var state: TextView
    private lateinit var connect: Button
    private lateinit var logout: Button
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        state = findViewById(R.id.cloudState)
        connect = findViewById(R.id.connect)
        logout = findViewById(R.id.logout)

        connect.setOnClickListener { startLogin() }
        logout.setOnClickListener {
            Cloud.logout(this)
            refresh()
        }
        refresh()
    }

    private fun refresh() {
        val c = Cloud.creds(this)
        state.text = if (c != null) "✓ Verbunden als ${c.loginName}\nOrdner: ${Cloud.FOLDER}"
        else "Nicht verbunden"
        logout.isEnabled = c != null
        connect.text = if (c != null) "Neu verbinden" else "Mit TUHH-Cloud verbinden"
    }

    private fun startLogin() {
        loginJob?.cancel()
        connect.isEnabled = false
        state.text = "Öffne TUHH-Login im Browser …"
        val app = applicationContext
        loginJob = PhotoStore.scope.launch {
            try {
                val ls = Cloud.startLogin()
                withContext(Dispatchers.Main) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ls.loginUrl)))
                    state.text = "Bitte im Browser anmelden und „Zugriff gewähren“ tippen,\ndann zurück zur App."
                }
                // bis zu 20 Minuten warten
                var result: Triple<String, String, String>? = null
                repeat(600) {
                    if (result == null) {
                        delay(2000)
                        result = runCatching { Cloud.pollLogin(ls) }.getOrNull()
                    }
                }
                val (server, login, pw) = result ?: error("Zeit abgelaufen – bitte nochmal versuchen")
                Cloud.finishLogin(app, server, login, pw)
                withContext(Dispatchers.Main) { if (!isDestroyed) refresh() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) state.text = "Verbinden fehlgeschlagen: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { if (!isDestroyed) connect.isEnabled = true }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (loginJob?.isActive != true) refresh()
    }
}
