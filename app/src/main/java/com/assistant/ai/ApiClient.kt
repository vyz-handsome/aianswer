package com.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Hasil satu panggilan API: kode HTTP + body JSON (atau null kalau gagal total/jaringan). */
internal class ApiResult(val httpCode: Int, val body: JSONObject?, val error: String? = null) {
    val ok: Boolean get() = body?.optBoolean("ok", false) == true
}

/** Panggilan HTTP generik ke server backend. Jalan di background thread, callback balik ke main thread. */
internal object NetworkClient {
    private val main = Handler(Looper.getMainLooper())

    fun request(
        baseUrl: String,
        path: String,
        method: String,
        json: JSONObject?,
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (ApiResult) -> Unit
    ) {
        if (baseUrl.isBlank()) {
            main.post { callback(ApiResult(0, null, "server belum diketahui (cek koneksi internet)")) }
            return
        }
        Thread {
            var result: ApiResult
            try {
                val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/json")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (json != null) {
                        doOutput = true
                        outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText().orEmpty()
                val body = try {
                    if (text.isNotBlank()) JSONObject(text) else null
                } catch (e: Exception) {
                    null
                }
                conn.disconnect()
                result = ApiResult(code, body)
            } catch (e: Exception) {
                result = ApiResult(0, null, e.message ?: "koneksi gagal")
            }
            main.post { callback(result) }
        }.start()
    }
}

/**
 * Jembatan ke semua endpoint server.py (lihat README backend).
 * Dipakai bareng oleh gate password user (ApiClient.verify/heartbeat/...)
 * dan dashboard/fingerprint owner (ApiClient.ownerXxx).
 */
internal object ApiClient {

    private fun base(c: Context): String {
        val cached = ServerConfig.cachedBaseUrl(c)
        return cached.ifBlank { "" }
    }

    // ---------------------------------------------------------------- user

    fun verify(
        c: Context,
        password: String,
        callback: (ApiResult) -> Unit
    ) {
        val body = JSONObject().apply {
            put("password", password)
            put("android_id", DeviceInfo.androidId(c))
            put("device_name", DeviceInfo.customName(c))
            put("brand", DeviceInfo.brand())
            put("model", DeviceInfo.model())
            put("android_version", DeviceInfo.androidVersion())
            put("app_version", DeviceInfo.appVersion(c))
        }
        NetworkClient.request(base(c), "/api/verify", "POST", body, callback = callback)
    }

    fun heartbeat(c: Context, callback: (ApiResult) -> Unit) {
        val token = Prefs.sessionToken(c)
        if (token.isEmpty()) {
            callback(ApiResult(0, null, "belum login")); return
        }
        val body = JSONObject().put("session_token", token)
        NetworkClient.request(base(c), "/api/heartbeat", "POST", body, callback = callback)
    }

    fun reportAiUsage(c: Context) {
        val token = Prefs.sessionToken(c)
        if (token.isEmpty()) return  // build owner / belum login -> diam saja
        val body = JSONObject().put("session_token", token)
        NetworkClient.request(base(c), "/api/ai_usage", "POST", body) { /* fire-and-forget */ }
    }

    fun messageAck(c: Context) {
        val token = Prefs.sessionToken(c)
        if (token.isEmpty()) return
        val body = JSONObject().put("session_token", token)
        NetworkClient.request(base(c), "/api/message_ack", "POST", body) { }
    }

    fun logout(c: Context, reason: String) {
        val token = Prefs.sessionToken(c)
        if (token.isEmpty()) return
        val body = JSONObject().put("session_token", token).put("reason", reason)
        NetworkClient.request(base(c), "/api/logout", "POST", body) { }
        Prefs.clearSession(c)
    }

    // --------------------------------------------------------------- owner

    fun ownerPair(c: Context, pairingCode: String, callback: (ApiResult) -> Unit) {
        val body = JSONObject().apply {
            put("pairing_code", pairingCode)
            put("device_name", DeviceInfo.model())
        }
        NetworkClient.request(base(c), "/api/owner/pair", "POST", body, callback = callback)
    }

    private fun ownerHeaders(c: Context) = mapOf("Owner-Token" to Prefs.ownerToken(c))

    fun ownerGenerate(c: Context, appName: String, ptype: String, durationHours: Double?, callback: (ApiResult) -> Unit) {
        val body = JSONObject().apply {
            put("app_name", appName)
            put("ptype", ptype)
            if (durationHours != null) put("duration_hours", durationHours) else put("duration_hours", JSONObject.NULL)
        }
        NetworkClient.request(base(c), "/api/owner/generate", "POST", body, ownerHeaders(c), callback)
    }

    fun ownerList(c: Context, callback: (ApiResult) -> Unit) {
        NetworkClient.request(base(c), "/api/owner/list", "GET", null, ownerHeaders(c), callback)
    }

    fun ownerInfo(c: Context, code: String, callback: (ApiResult) -> Unit) {
        NetworkClient.request(base(c), "/api/owner/info/$code", "GET", null, ownerHeaders(c), callback)
    }

    fun ownerRevoke(c: Context, code: String, callback: (ApiResult) -> Unit) {
        NetworkClient.request(base(c), "/api/owner/revoke", "POST", JSONObject().put("code", code), ownerHeaders(c), callback)
    }

    fun ownerReset(c: Context, code: String, callback: (ApiResult) -> Unit) {
        NetworkClient.request(base(c), "/api/owner/reset", "POST", JSONObject().put("code", code), ownerHeaders(c), callback)
    }

    fun ownerStop(c: Context, code: String, stop: Boolean, callback: (ApiResult) -> Unit) {
        val body = JSONObject().put("code", code).put("stop", stop)
        NetworkClient.request(base(c), "/api/owner/stop", "POST", body, ownerHeaders(c), callback)
    }

    fun ownerMessage(c: Context, code: String, text: String, callback: (ApiResult) -> Unit) {
        val body = JSONObject().put("code", code).put("text", text)
        NetworkClient.request(base(c), "/api/owner/message", "POST", body, ownerHeaders(c), callback)
    }
}
