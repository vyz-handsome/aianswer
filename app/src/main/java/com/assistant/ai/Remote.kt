package com.assistant.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Penghubung ke server Termux (login kode, heartbeat, perintah jarak jauh) untuk dua mode app:
 *  - owner: app yang dibuka dengan sidik jari, mengelola app user lewat server.
 *  - user : app turunan (package/nama custom) yang dibuka dengan kode 4 digit dari owner.
 * Mode dibaca dari meta-data manifest "assistant.mode"; patcher di server mengubahnya jadi mode user.
 */
object Remote {
    private const val MODE_USER = "assistant_mode_user"
    private const val DISCOVERY_PLACEHOLDER = "DISCOVERY_URL_PLACEHOLDER_ASSISTANT_V1"
    private const val GRACE_MS = 10 * 60 * 1000L
    private const val BEAT_MS = 15_000L
    private const val PREFS = "remote"

    class LoginResult(val ok: Boolean, val error: String, val retryAfter: Int)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var appCtx: Context

    @Volatile private var loaded = false
    @Volatile private var started = false
    @Volatile private var modeUser: Boolean? = null
    @Volatile private var token = ""
    @Volatile private var kind = ""
    @Volatile private var expiresAt = 0L
    @Volatile private var offsetSec = 0L
    @Volatile private var lastOkElapsed = 0L
    @Volatile private var lastAllowed = false
    @Volatile private var lastReason = ""
    @Volatile var lockListener: ((String) -> Unit)? = null

    private val uses = ArrayList<JSONObject>()

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ mode

    private fun meta(c: Context, key: String): String = try {
        val ai = c.packageManager.getApplicationInfo(c.packageName, PackageManager.GET_META_DATA)
        ai.metaData?.getString(key).orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun isUser(c: Context): Boolean {
        modeUser?.let { return it }
        val v = meta(c, "assistant.mode") == MODE_USER
        modeUser = v
        return v
    }

    fun isOwner(c: Context): Boolean = !isUser(c)

    @Synchronized
    private fun ensureLoaded(c: Context) {
        if (loaded) return
        appCtx = c.applicationContext
        val p = sp(c)
        token = p.getString("token", "").orEmpty()
        kind = p.getString("kind", "").orEmpty()
        expiresAt = p.getLong("expires", 0L)
        offsetSec = p.getLong("offset", 0L)
        // Sesi yang tersimpan dianggap sah sampai heartbeat pertama (masa tenggang 10 menit).
        lastOkElapsed = if (token.isNotEmpty()) SystemClock.elapsedRealtime() else 0L
        lastAllowed = isUser(c) && token.isNotEmpty()
        loaded = true
    }

    private fun nowServer(): Long = System.currentTimeMillis() / 1000 + offsetSec

    /** true kalau app boleh dipakai (mode owner selalu true). */
    fun allowed(c: Context): Boolean {
        if (!isUser(c)) return true
        ensureLoaded(c)
        if (token.isEmpty()) return false
        if (expiresAt > 0 && nowServer() >= expiresAt) return false
        return SystemClock.elapsedRealtime() - lastOkElapsed <= GRACE_MS
    }

    fun requireAllowed(c: Context) {
        if (!allowed(c)) throw GeminiException(403, "Akses terkunci. Buka app lalu masukkan kode dari owner.")
    }

    fun kindLabel(): String = when (kind) {
        "once" -> "Sekali pakai"
        "timed" -> "Berbatas waktu"
        "perm" -> "Permanen"
        else -> ""
    }

    // ------------------------------------------------------------------ jaringan

    private fun discoveryUrl(c: Context): String {
        val v = if (isUser(c)) meta(c, "assistant.discovery") else sp(c).getString("discovery", "").orEmpty()
        return if (v == DISCOVERY_PLACEHOLDER) "" else v
    }

    private fun cachedUrl(c: Context): String = sp(c).getString("server_url", "").orEmpty()

    private fun discover(c: Context): String {
        val d = discoveryUrl(c)
        if (d.isEmpty()) throw IOException("Alamat server belum diatur.")
        val sep = if (d.contains("?")) "&" else "?"
        val conn = URL(d + sep + "t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode != 200) throw IOException("Discovery HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val url = JSONObject(text).optString("url", "").trimEnd('/')
            if (!url.startsWith("https://")) throw IOException("Discovery tidak valid")
            sp(c).edit().putString("server_url", url).apply()
            return url
        } finally {
            conn.disconnect()
        }
    }

    private fun http(url: String, body: JSONObject?, ownerToken: String?, file: File?, deviceId: String? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = if (file != null) 120_000 else 20_000
            conn.doOutput = true
            if (ownerToken != null) conn.setRequestProperty("X-Owner-Token", ownerToken)
            if (deviceId != null) conn.setRequestProperty("X-Device-Id", deviceId)
            if (file != null) {
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(file.length())
                conn.outputStream.use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                }
            } else {
                val bytes = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            try {
                return JSONObject(text)
            } catch (e: Exception) {
                throw IOException("Server tidak menjawab dengan benar (HTTP $code)")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Panggil API. Kalau alamat tersimpan gagal (tunnel ganti URL), cari alamat baru lewat discovery lalu ulangi. */
    private fun call(c: Context, path: String, body: JSONObject?, ownerToken: String? = null, file: File? = null): JSONObject {
        var err: Exception? = null
        for (attempt in 0..1) {
            try {
                val base = if (attempt == 0) cachedUrl(c).ifEmpty { discover(c) } else discover(c)
                return http(base + path, body, ownerToken, file, if (ownerToken != null) androidId(c) else null)
            } catch (e: Exception) {
                err = e
            }
        }
        throw err ?: IOException("Gagal terhubung")
    }

    // ------------------------------------------------------------------ mode user: login + heartbeat

    private fun androidId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    private fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun login(c: Context, code: String, cb: (LoginResult) -> Unit) {
        val app = c.applicationContext
        ensureLoaded(app)
        Thread {
            val r = try {
                doLogin(app, code)
            } catch (e: Exception) {
                LoginResult(false, "Tidak bisa terhubung ke server. Cek internet lalu coba lagi.", 0)
            }
            main.post { cb(r) }
        }.start()
    }

    private fun doLogin(c: Context, code: String): LoginResult {
        val body = JSONObject()
            .put("code", code)
            .put("package", c.packageName)
            .put("android_id", androidId(c))
            .put("brand", Build.BRAND.orEmpty())
            .put("model", Build.MODEL.orEmpty())
            .put("android_version", Build.VERSION.RELEASE.orEmpty())
            .put("app_version", versionName(c))
        val res = call(c, "/v1/login", body)
        if (!res.optBoolean("ok")) {
            return LoginResult(false, res.optString("error", "Gagal masuk."), res.optInt("retry_after", 0))
        }
        token = res.getString("token")
        kind = res.optString("kind", "")
        expiresAt = res.optLong("expires_at", 0L)
        offsetSec = res.optDouble("server_time", System.currentTimeMillis() / 1000.0).toLong() - System.currentTimeMillis() / 1000
        lastOkElapsed = SystemClock.elapsedRealtime()
        sp(c).edit().putString("token", token).putString("kind", kind).putLong("expires", expiresAt)
            .putLong("offset", offsetSec).apply()
        applySettings(c, res.optJSONObject("settings"), res.optInt("settings_v", 0))
        lastAllowed = true
        start(c)
        main.post { onGranted(c) }
        return LoginResult(true, "", 0)
    }

    /** Mulai loop heartbeat (sekali per proses). Aman dipanggil berkali-kali. */
    fun start(c: Context) {
        if (!isUser(c)) return
        ensureLoaded(c)
        if (started) return
        started = true
        Thread {
            while (true) {
                try {
                    tick()
                } catch (e: Throwable) {
                    // loop tidak boleh mati
                }
                try {
                    Thread.sleep(BEAT_MS)
                } catch (e: InterruptedException) {
                }
            }
        }.apply { isDaemon = true; name = "remote-heartbeat"; start() }
    }

    private fun tick() {
        val c = appCtx
        if (token.isNotEmpty()) heartbeat(c)
        checkTransition(c)
    }

    private fun heartbeat(c: Context) {
        val body = JSONObject().put("token", token)
            .put("app_version", versionName(c))
            .put("bubble_on", Prefs.bubbleOn(c))
            .put("model_id", Prefs.modelId(c))
        val batch: List<JSONObject> = synchronized(uses) {
            val l = ArrayList(uses)
            uses.clear()
            l
        }
        if (batch.isNotEmpty()) body.put("uses", JSONArray(batch))
        val res = try {
            call(c, "/v1/heartbeat", body)
        } catch (e: Exception) {
            synchronized(uses) {
                uses.addAll(0, batch)
                while (uses.size > 300) uses.removeAt(uses.size - 1)
            }
            return
        }
        offsetSec = res.optDouble("server_time", System.currentTimeMillis() / 1000.0).toLong() - System.currentTimeMillis() / 1000
        if (!res.optBoolean("valid")) {
            clearSession(c, res.optString("reason", "Sesi berakhir. Masukkan kode lagi."))
            return
        }
        lastOkElapsed = SystemClock.elapsedRealtime()
        expiresAt = res.optLong("expires_at", 0L)
        sp(c).edit().putLong("expires", expiresAt).putLong("offset", offsetSec).apply()
        applySettings(c, res.optJSONObject("settings"), res.optInt("settings_v", 0))
        val cmds = res.optJSONArray("commands")
        if (cmds != null) {
            for (i in 0 until cmds.length()) {
                val cmd = cmds.optJSONObject(i) ?: continue
                runCommand(c, cmd.optString("type"), cmd.optJSONObject("payload") ?: JSONObject())
            }
        }
    }

    private fun applySettings(c: Context, s: JSONObject?, version: Int) {
        if (s == null) return
        val p = sp(c)
        if (version == p.getInt("settings_applied", -1)) return
        val model = s.optString("model", "")
        if (model.isNotEmpty() && Models.ALL.any { it.id == model }) Prefs.setModelId(c, model)
        if (s.has("accurate")) Prefs.setAccurate(c, s.optBoolean("accurate"))
        if (s.has("fallback")) Prefs.setFallback(c, s.optBoolean("fallback"))
        p.edit().putInt("settings_applied", version).apply()
    }

    private fun runCommand(c: Context, type: String, payload: JSONObject) {
        when (type) {
            "stop_ai" -> main.post {
                Prefs.setBubbleOn(c, false)
                OverlayService.instance?.stopBubble()
            }
            "hide_bubble" -> OverlayService.instance?.hideRemote()
            "show_bubble" -> main.post {
                Prefs.setBubbleOn(c, true)
                OverlayService.instance?.startBubble()
            }
            "message" -> {
                val text = payload.optString("text", "")
                if (text.isNotEmpty()) {
                    val p = sp(c)
                    val old = p.getString("inbox", "").orEmpty()
                    p.edit().putString("inbox", if (old.isEmpty()) text else old + "\u0001" + text).apply()
                    main.post { Toast.makeText(c, "Pesan dari owner: $text", Toast.LENGTH_LONG).show() }
                }
            }
            "lock" -> clearSession(c, "Dikunci oleh owner. Masukkan kode lagi.")
        }
    }

    private fun clearSession(c: Context, reason: String) {
        token = ""
        kind = ""
        expiresAt = 0L
        lastReason = reason
        sp(c).edit().remove("token").remove("kind").remove("expires").apply()
    }

    /** Deteksi perubahan boleh/tidak boleh, lalu hentikan bubble atau tampilkan layar kode. */
    private fun checkTransition(c: Context) {
        val now = allowed(c)
        if (now == lastAllowed) return
        lastAllowed = now
        if (now) {
            main.post { onGranted(c) }
        } else {
            val msg = lastReason.ifEmpty { "Tidak terhubung ke server terlalu lama atau akses berakhir. Hubungkan internet lalu masukkan kode lagi." }
            lastReason = ""
            main.post {
                OverlayService.instance?.stopBubble()
                lockListener?.invoke(msg)
            }
        }
    }

    private fun onGranted(c: Context) {
        if (Prefs.bubbleOn(c)) OverlayService.instance?.startBubble()
    }

    /** Pesan dari owner yang belum ditampilkan di dialog. */
    fun takeMessages(c: Context): List<String> {
        val p = sp(c)
        val raw = p.getString("inbox", "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        p.edit().remove("inbox").apply()
        return raw.split("\u0001")
    }

    /** Catat satu pemakaian AI (dikirim ke owner lewat heartbeat). */
    fun reportUse(c: Context, src: String, model: String, level: String) {
        if (!isUser(c)) return
        val o = JSONObject().put("src", src).put("model", model).put("level", level).put("t", System.currentTimeMillis())
        synchronized(uses) {
            uses.add(o)
            while (uses.size > 300) uses.removeAt(0)
        }
    }

    /**
     * Aksi pengguna: "logout" mengakhiri sesi semua jenis kode; "stop_ai" dan "recents" hanya mengakhiri
     * kode sekali pakai.
     */
    fun onUserAction(c: Context, reason: String) {
        if (!isUser(c)) return
        ensureLoaded(c)
        if (token.isEmpty()) return
        if (reason != "logout" && kind != "once") return
        val t = token
        val app = c.applicationContext
        clearSession(app, if (reason == "logout") "Kamu sudah keluar." else "Kode sekali pakai sudah selesai.")
        Thread {
            for (i in 0 until 3) {
                try {
                    call(app, "/v1/logout", JSONObject().put("token", t).put("reason", reason))
                    break
                } catch (e: Exception) {
                    try {
                        Thread.sleep(2000)
                    } catch (ie: InterruptedException) {
                    }
                }
            }
        }.start()
        Thread { checkTransition(app) }.start()
    }

    // ------------------------------------------------------------------ mode owner

    fun ownerPaired(c: Context): Boolean = sp(c).getString("owner_token", "").orEmpty().isNotEmpty()

    fun ownerUnpair(c: Context) {
        sp(c).edit().remove("owner_token").remove("server_url").remove("owner_verified").apply()
    }

    class Verify(val ok: Boolean, val needActivation: Boolean, val message: String)

    /**
     * Cek ke server bahwa app owner ini masih diaktivasi di HP ini. Dipanggil setelah sidik jari cocok.
     * Offline: boleh sampai 12 jam sejak pengecekan sukses terakhir. Jalankan di thread background.
     */
    fun ownerVerify(c: Context): Verify {
        val tok = sp(c).getString("owner_token", "").orEmpty()
        if (tok.isEmpty()) return Verify(false, true, "")
        return try {
            val r = call(c, "/v1/owner/ping", JSONObject(), tok)
            if (r.optBoolean("ok")) {
                sp(c).edit().putLong("owner_verified", System.currentTimeMillis()).apply()
                Verify(true, false, "")
            } else if (r.optString("error").startsWith("Tidak diizinkan")) {
                ownerUnpair(c)
                Verify(false, true, "Aktivasi sudah dicabut atau HP ini bukan HP owner. Aktifkan ulang dengan kode dari bot.")
            } else {
                Verify(false, false, r.optString("error", "Gagal memeriksa aktivasi."))
            }
        } catch (e: Exception) {
            val last = sp(c).getLong("owner_verified", 0L)
            if (last > 0 && System.currentTimeMillis() - last < 12 * 3600 * 1000L) Verify(true, false, "")
            else Verify(false, false, "Tidak terhubung ke server. Hubungkan internet lalu coba lagi.")
        }
    }

    /** Pasangkan app owner. `text` = teks dari bot (ASSISTANT-PAIR|discovery|kode). Jalankan di thread background. */
    fun ownerPair(c: Context, text: String, deviceName: String): String? {
        val t = text.trim()
        val code: String
        if (t.startsWith("ASSISTANT-PAIR|")) {
            val parts = t.split("|")
            if (parts.size < 3 || !parts[1].startsWith("https://")) return "Teks pairing tidak valid."
            sp(c).edit().putString("discovery", parts[1]).remove("server_url").apply()
            code = parts[2].trim()
        } else {
            code = t
            if (discoveryUrl(c).isEmpty()) return "Tempel seluruh teks dari bot (diawali ASSISTANT-PAIR)."
        }
        return try {
            val res = call(c, "/v1/owner/pair", JSONObject().put("code", code).put("name", deviceName).put("android_id", androidId(c)))
            if (res.optBoolean("ok")) {
                sp(c).edit().putString("owner_token", res.getString("token"))
                    .putLong("owner_verified", System.currentTimeMillis()).apply()
                null
            } else {
                res.optString("error", "Pairing gagal.")
            }
        } catch (e: Exception) {
            "Tidak bisa terhubung ke server: ${e.message}"
        }
    }

    /** Panggil API owner (background thread). Kalau token ditolak, pairing dihapus. */
    fun ownerCall(c: Context, path: String, body: JSONObject = JSONObject()): JSONObject {
        val tok = sp(c).getString("owner_token", "").orEmpty()
        if (tok.isEmpty()) return JSONObject().put("ok", false).put("error", "Belum dipasangkan ke server.")
        return try {
            val r = call(c, path, body, tok)
            if (!r.optBoolean("ok") && r.optString("error").startsWith("Tidak diizinkan")) ownerUnpair(c)
            r
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Tidak bisa terhubung: ${e.message}")
        }
    }

    /** Kirim APK app ini sendiri sebagai template untuk membuat app user. */
    fun ownerUploadTemplate(c: Context): JSONObject {
        val tok = sp(c).getString("owner_token", "").orEmpty()
        if (tok.isEmpty()) return JSONObject().put("ok", false).put("error", "Belum dipasangkan ke server.")
        return try {
            call(c, "/v1/owner/template", null, tok, File(c.applicationInfo.sourceDir))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Gagal mengirim: ${e.message}")
        }
    }
}
