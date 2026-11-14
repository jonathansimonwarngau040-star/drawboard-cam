package de.jonathan.drawboardcam

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Verbindung zur TUHH-Cloud (Nextcloud) über WebDAV.
 * Anmeldung per Nextcloud "Login Flow v2": Der normale TUHH-Login läuft im Browser,
 * die App bekommt danach automatisch ein eigenes App-Passwort (kann in der Cloud
 * unter Einstellungen → Sicherheit jederzeit widerrufen werden).
 */
object Cloud {
    const val SERVER = "https://cloud.tuhh.de"
    const val FOLDER = "DrawboardCam"
    private const val USER_AGENT = "DrawboardCam (Android)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Creds(val server: String, val loginName: String, val appPassword: String, val userId: String)
    data class LoginStart(val loginUrl: String, val pollEndpoint: String, val token: String)

    private fun prefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "cloud_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun creds(ctx: Context): Creds? {
        val p = prefs(ctx)
        val server = p.getString("server", null) ?: return null
        val login = p.getString("login", null) ?: return null
        val pw = p.getString("pw", null) ?: return null
        val uid = p.getString("uid", null) ?: return null
        return Creds(server, login, pw, uid)
    }

    fun logout(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** Schritt 1: Login starten → URL für den Browser. */
    fun startLogin(): LoginStart {
        val req = Request.Builder()
            .url("$SERVER/index.php/login/v2")
            .header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Cloud antwortet mit ${r.code}")
            val j = JSONObject(r.body!!.string())
            val poll = j.getJSONObject("poll")
            return LoginStart(j.getString("login"), poll.getString("endpoint"), poll.getString("token"))
        }
    }

    /** Schritt 2: Fragen, ob der Login im Browser fertig ist. null = noch nicht. */
    fun pollLogin(ls: LoginStart): Triple<String, String, String>? {
        val req = Request.Builder()
            .url(ls.pollEndpoint)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("token", ls.token).build())
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) error("Cloud antwortet mit ${r.code}")
            val j = JSONObject(r.body!!.string())
            return Triple(j.getString("server").trimEnd('/'), j.getString("loginName"), j.getString("appPassword"))
        }
    }

    /** Schritt 3: Benutzer-ID holen, Ordner anlegen, Zugangsdaten verschlüsselt speichern. */
    fun finishLogin(ctx: Context, server: String, loginName: String, appPassword: String) {
        val auth = Credentials.basic(loginName, appPassword)
        val req = Request.Builder()
            .url("$server/ocs/v1.php/cloud/user?format=json")
            .header("OCS-APIRequest", "true")
            .header("Authorization", auth)
            .header("User-Agent", USER_AGENT)
            .build()
        val uid = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Benutzerdaten nicht lesbar (${r.code})")
            JSONObject(r.body!!.string()).getJSONObject("ocs").getJSONObject("data").getString("id")
        }
        val c = Creds(server, loginName, appPassword, uid)
        ensureFolder(c)
        prefs(ctx).edit()
            .putString("server", server).putString("login", loginName)
            .putString("pw", appPassword).putString("uid", uid)
            .apply()
    }

    private fun folderUrl(c: Creds): HttpUrl.Builder =
        c.server.toHttpUrl().newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(c.userId)
            .addPathSegment(FOLDER)

    private fun ensureFolder(c: Creds) {
        val req = Request.Builder()
            .url(folderUrl(c).addPathSegment("").build())
            .header("Authorization", Credentials.basic(c.loginName, c.appPassword))
            .header("User-Agent", USER_AGENT)
            .method("MKCOL", null)
            .build()
        http.newCall(req).execute().use { r ->
            // 201 = angelegt, 405 = gibt es schon
            if (r.code != 201 && r.code != 405) error("Ordner konnte nicht angelegt werden (${r.code})")
        }
    }

    /** Lädt ein Foto in den Cloud-Ordner. Kein automatischer zweiter Versuch. */
    fun upload(ctx: Context, file: File): Result<Unit> = runCatching {
        val c = creds(ctx) ?: error("nicht mit Cloud verbunden")
        fun put(): Int {
            val req = Request.Builder()
                .url(folderUrl(c).addPathSegment(file.name).build())
                .header("Authorization", Credentials.basic(c.loginName, c.appPassword))
                .header("User-Agent", USER_AGENT)
                .put(file.asRequestBody("image/jpeg".toMediaType()))
                .build()
            return http.newCall(req).execute().use { it.code }
        }
        var code = put()
        if (code == 404 || code == 409) { // Ordner wurde gelöscht → neu anlegen
            ensureFolder(c)
            code = put()
        }
        if (code == 401) error("Cloud-Login abgelaufen – in den Einstellungen neu verbinden")
        if (code !in 200..299) error("Cloud-Fehler $code")
    }
}
