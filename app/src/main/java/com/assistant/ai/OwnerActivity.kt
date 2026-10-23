package com.assistant.ai

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Panel owner: pairing ke server, buat app user (nama/package/jenis password), lihat aktivitas, dan kontrol jarak jauh. */
class OwnerActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private var stoppedAt = 0L
    private var gate: FingerprintGate? = null

    private var kindIndex = 0
    private var unitIndex = 1
    private val kinds = listOf("Permanen" to "perm", "Sekali pakai" to "once", "Berbatas waktu" to "timed")
    private val units = listOf("menit" to 60, "jam" to 3600, "hari" to 86400)

    private val timeFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Remote.isOwner(this)) {
            finish()
            return
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(color(R.color.window_bg))
        root.addView(AuroraBackground(this).apply { id = R.id.aurora }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        if (SystemClock.elapsedRealtime() - FingerprintGate.lastAuth > 60_000L) showGate()
        render()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 60_000L) showGate()
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    private fun showGate() {
        if (gate?.isShowing() == true) return
        gate = FingerprintGate(this).also { it.show() }
    }

    // ------------------------------------------------------------------ util tampilan

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_ui)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextColor(color(R.color.ink))
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sub(t: String) = TextView(this).apply {
        text = t
        setTextColor(color(R.color.ink2))
        textSize = 13f
        setLineSpacing(dp(2).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    private fun btn(text: String, style: Int, onClick: () -> Unit) = Button(this, null, 0, style).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun primary(t: String, f: () -> Unit) = btn(t, R.style.Btn_Primary, f)
    private fun secondary(t: String, f: () -> Unit) = btn(t, R.style.Btn_Secondary, f)
    private fun danger(t: String, f: () -> Unit) = btn(t, R.style.Btn_Danger, f)

    private fun input(hint: String, number: Boolean = false) = EditText(this, null, 0, R.style.Input).apply {
        this.hint = hint
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun <T> bg(work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = work()
            runOnUiThread { if (!isDestroyed) done(r) }
        }.start()
    }

    private fun ts(sec: Double): String = if (sec <= 0.0) "-" else timeFmt.format(Date((sec * 1000).toLong()))

    // ------------------------------------------------------------------ halaman

    private fun render() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "Panel Owner"
            setTextColor(color(R.color.ink))
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(secondary("Kembali") { finish() })
        if (!Remote.ownerPaired(this)) renderPair() else renderPaired()
    }

    private fun renderPair() {
        val c = card()
        c.addView(title("Hubungkan ke server"))
        c.addView(sub("Kirim /pair ke bot Telegram kamu. Salin seluruh teks balasannya (diawali ASSISTANT-PAIR) lalu tempel di bawah."))
        val et = input("Tempel teks dari bot")
        c.addView(et)
        c.addView(primary("Hubungkan") {
            val text = et.text.toString()
            if (text.isBlank()) {
                toast("Tempel teks pairing dulu.")
                return@primary
            }
            toast("Menghubungkan...")
            bg({ Remote.ownerPair(this, text, Build.MODEL.orEmpty()) }) { err ->
                if (err == null) {
                    toast("Terhubung.")
                    render()
                } else toast(err)
            }
        })
        content.addView(c)
    }

    private lateinit var statusView: TextView
    private lateinit var listBox: LinearLayout

    private fun renderPaired() {
        // ---- server
        val s = card()
        s.addView(title("Server"))
        statusView = sub("Memeriksa server...")
        s.addView(statusView)
        s.addView(secondary("Kirim template ke server") {
            toast("Mengirim template...")
            bg({ Remote.ownerUploadTemplate(this) }) { r ->
                toast(if (r.optBoolean("ok")) "Template terkirim." else r.optString("error", "Gagal."))
                refreshStatus()
            }
        })
        s.addView(sub("Template = APK app ini sendiri. Kirim ulang tiap kamu memasang versi baru app."))
        s.addView(danger("Putuskan pairing") {
            Remote.ownerUnpair(this)
            render()
        })
        content.addView(s)

        // ---- generate
        val g = card()
        g.addView(title("Buat app user"))
        g.addView(sub("APK dibuat di server lalu dikirim ke bot Telegram bersama kodenya."))
        val name = input("Nama app (tampil di launcher)")
        val pkg = input("Package name (kosong = otomatis)")
        g.addView(name)
        g.addView(pkg)
        val kindBtn = secondary("Jenis password: ${kinds[kindIndex].first}") {}
        val durRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val dur = input("Lama (angka)", true)
        val unitBtn = secondary("Satuan: ${units[unitIndex].first}") {}
        durRow.addView(dur)
        durRow.addView(unitBtn)
        kindBtn.setOnClickListener {
            Glass.pick(this, "Jenis password", kinds.map { it.first }, kindIndex) {
                kindIndex = it
                kindBtn.text = "Jenis password: ${kinds[it].first}"
                durRow.visibility = if (kinds[it].second == "timed") View.VISIBLE else View.GONE
            }
        }
        unitBtn.setOnClickListener {
            Glass.pick(this, "Satuan waktu", units.map { it.first }, unitIndex) {
                unitIndex = it
                unitBtn.text = "Satuan: ${units[it].first}"
            }
        }
        g.addView(kindBtn)
        g.addView(durRow)
        g.addView(primary("Generate") {
            val kind = kinds[kindIndex].second
            val body = JSONObject().put("app_name", name.text.toString().trim())
                .put("package", pkg.text.toString().trim()).put("kind", kind)
            if (kind == "timed") {
                val n = dur.text.toString().toLongOrNull() ?: 0L
                body.put("duration_sec", n * units[unitIndex].second)
            }
            toast("Membuat...")
            bg({ Remote.ownerCall(this, "/v1/owner/generate", body) }) { r ->
                if (r.optBoolean("ok")) {
                    val a = r.getJSONObject("app")
                    Glass.builder(this).setTitle("App dibuat")
                        .setMessage("Nama: ${a.optString("app_name")}\nPackage: ${a.optString("package")}\nKode: ${a.optString("code")}\n\nAPK sedang dibuat dan dikirim ke bot Telegram.")
                        .setPositiveButton("OK", null).showGlass()
                    name.setText("")
                    pkg.setText("")
                    loadList()
                } else toast(r.optString("error", "Gagal."))
            }
        })
        content.addView(g)

        // ---- daftar
        val l = card()
        l.addView(title("App user"))
        l.addView(secondary("Muat ulang") { refreshStatus(); loadList() })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(listBox)
        content.addView(l)
        refreshStatus()
        loadList()
    }

    private fun refreshStatus() {
        bg({ Remote.ownerCall(this, "/v1/owner/ping") }) { r ->
            if (!::statusView.isInitialized) return@bg
            statusView.text = if (r.optBoolean("ok")) {
                "Terhubung. Template: " + (if (r.optBoolean("template")) "sudah ada" else "BELUM ADA, kirim dulu")
            } else r.optString("error", "Tidak terhubung.")
            if (!Remote.ownerPaired(this)) render()
        }
    }

    private fun statusLabel(s: String) = when (s) {
        "unused" -> "belum dipakai"
        "active" -> "aktif"
        "ended" -> "selesai"
        "revoked" -> "dicabut"
        "expired" -> "kedaluwarsa"
        else -> s
    }

    private fun kindText(a: JSONObject): String = when (a.optString("kind")) {
        "perm" -> "Permanen"
        "once" -> "Sekali pakai"
        else -> {
            val s = a.optLong("duration_sec")
            "Waktu " + when {
                s % 86400 == 0L -> "${s / 86400} hari"
                s % 3600 == 0L -> "${s / 3600} jam"
                else -> "${maxOf(1L, s / 60)} menit"
            }
        }
    }

    private fun loadList() {
        if (!::listBox.isInitialized) return
        bg({ Remote.ownerCall(this, "/v1/owner/list") }) { r ->
            listBox.removeAllViews()
            if (!r.optBoolean("ok")) {
                listBox.addView(sub(r.optString("error", "Gagal memuat.")))
                return@bg
            }
            val arr = r.getJSONArray("apps")
            if (arr.length() == 0) listBox.addView(sub("Belum ada app user."))
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val row = TextView(this).apply {
                    text = "#${a.optInt("id")} ${a.optString("app_name")}\n" +
                        "${statusLabel(a.optString("status"))} · ${if (a.optBoolean("online")) "online" else "offline"} · " +
                        "kode ${a.optString("code")} · AI ${a.optInt("ai_count")}x"
                    setTextColor(color(R.color.ink))
                    textSize = 14f
                    setBackgroundResource(R.drawable.card_flat)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { showDetail(a.optInt("id")) }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                }
                listBox.addView(row)
            }
        }
    }

    // ------------------------------------------------------------------ detail + kontrol

    private fun showDetail(id: Int) {
        bg({ Remote.ownerCall(this, "/v1/owner/info", JSONObject().put("id", id)) }) { r ->
            if (!r.optBoolean("ok")) {
                toast(r.optString("error", "Gagal."))
                return@bg
            }
            val a = r.getJSONObject("app")
            val sb = StringBuilder()
            sb.append("Package: ").append(a.optString("package")).append('\n')
            sb.append("Kode: ").append(a.optString("code")).append("  ·  ").append(kindText(a)).append('\n')
            sb.append("Status: ").append(statusLabel(a.optString("status")))
                .append(if (a.optBoolean("online")) " · online" else " · offline").append('\n')
            sb.append("Terakhir aktif: ").append(ts(a.optDouble("last_seen", 0.0))).append('\n')
            sb.append("Pemakaian AI: ").append(a.optInt("ai_count")).append("x (terakhir ")
                .append(ts(a.optDouble("last_ai", 0.0))).append(")\n")
            if (a.optDouble("expires_at", 0.0) > 0) sb.append("Berakhir: ").append(ts(a.optDouble("expires_at"))).append('\n')
            if (a.optString("android_id").isNotEmpty() && a.optString("android_id") != "null") {
                sb.append("HP: ").append(a.optString("brand")).append(' ').append(a.optString("model"))
                    .append(", Android ").append(a.optString("android_version"))
                    .append(", app v").append(a.optString("app_version")).append('\n')
                sb.append("ID: ").append(a.optString("android_id")).append('\n')
            }
            val bs = a.optString("build_status")
            if (bs != "ok") sb.append("Build APK: ").append(bs).append('\n')

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), dp(8))
            }
            box.addView(TextView(this).apply {
                text = sb.toString()
                setTextColor(color(R.color.ink))
                textSize = 13.5f
            })
            val sv = ScrollView(this)
            sv.addView(box)
            val dlg = Glass.builder(this).setTitle("#$id ${a.optString("app_name")}").setView(sv)
                .setPositiveButton("Tutup", null).create()
            fun act(label: String, style: Int, f: () -> Unit) {
                box.addView(btn(label, style) { dlg.dismiss(); f() })
            }
            act("Stop AI", R.style.Btn_Secondary) { command(id, "stop_ai") }
            act("Sembunyikan bubble", R.style.Btn_Secondary) { command(id, "hide_bubble") }
            act("Munculkan bubble", R.style.Btn_Secondary) { command(id, "show_bubble") }
            act("Kirim pesan", R.style.Btn_Secondary) { askMessage(id) }
            act("Ubah pengaturan", R.style.Btn_Secondary) { settingsMenu(id, a.optString("model_id")) }
            act("Lihat log", R.style.Btn_Secondary) { showLog(id, r) }
            act("Kunci paksa (minta kode lagi)", R.style.Btn_Secondary) { command(id, "lock") }
            act("Kirim ulang APK ke Telegram", R.style.Btn_Secondary) { simple("/v1/owner/resend", id, "APK dikirim ulang ke Telegram (tunggu sebentar).") }
            act("Reset kunci HP", R.style.Btn_Danger) { confirm("Reset kunci HP untuk #$id?") { simple("/v1/owner/reset", id, "Kunci HP direset.") } }
            act("Cabut akses", R.style.Btn_Danger) { confirm("Cabut akses #$id? App akan terkunci.") { simple("/v1/owner/revoke", id, "Akses dicabut.") } }
            act("Hapus dari daftar", R.style.Btn_Danger) { confirm("Hapus #$id dari daftar?") { simple("/v1/owner/delete", id, "Dihapus.") } }
            Glass.prepare(dlg)
            dlg.show()
        }
    }

    private fun simple(path: String, id: Int, okText: String) {
        bg({ Remote.ownerCall(this, path, JSONObject().put("id", id)) }) { r ->
            toast(if (r.optBoolean("ok")) okText else r.optString("error", "Gagal."))
            loadList()
        }
    }

    private fun command(id: Int, type: String, text: String = "") {
        val body = JSONObject().put("id", id).put("type", type)
        if (type == "message") body.put("text", text)
        bg({ Remote.ownerCall(this, "/v1/owner/command", body) }) { r ->
            toast(if (r.optBoolean("ok")) "Perintah dikirim. Dijalankan saat app online (maks sekitar 15 detik)." else r.optString("error", "Gagal."))
        }
    }

    private fun askMessage(id: Int) {
        val et = EditText(this, null, 0, R.style.Input).apply { hint = "Isi pesan (maks 500 karakter)" }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Glass.builder(this).setTitle("Kirim pesan ke app").setView(box)
            .setPositiveButton("Kirim") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) command(id, "message", t)
            }
            .setNegativeButton("Batal", null).showGlass()
    }

    private fun setting(id: Int, key: String, value: Any) {
        bg({ Remote.ownerCall(this, "/v1/owner/setting", JSONObject().put("id", id).put("key", key).put("value", value)) }) { r ->
            toast(if (r.optBoolean("ok")) "Pengaturan dikirim. Diterapkan saat app online." else r.optString("error", "Gagal."))
        }
    }

    private fun settingsMenu(id: Int, currentModel: String) {
        val labels = listOf("Ganti model AI", "Mode akurat: nyalakan", "Mode akurat: matikan", "Fallback model: nyalakan", "Fallback model: matikan")
        Glass.pick(this, "Pengaturan jarak jauh", labels, -1) { i ->
            when (i) {
                0 -> {
                    val cur = Models.ALL.indexOfFirst { it.id == currentModel }
                    Glass.pick(this, "Model AI", Models.ALL.map { it.label }, cur) { m -> setting(id, "model", Models.ALL[m].id) }
                }
                1 -> setting(id, "accurate", true)
                2 -> setting(id, "accurate", false)
                3 -> setting(id, "fallback", true)
                4 -> setting(id, "fallback", false)
            }
        }
    }

    private fun showLog(id: Int, info: JSONObject) {
        val ev = info.optJSONArray("events")
        val sb = StringBuilder()
        if (ev != null) {
            for (i in 0 until ev.length()) {
                val e = ev.getJSONObject(i)
                val d = e.optJSONObject("detail")
                sb.append(ts(e.optDouble("ts", 0.0))).append("  ").append(e.optString("type"))
                if (d != null) sb.append(' ').append(d.toString())
                sb.append('\n')
            }
        }
        val tv = TextView(this).apply {
            text = if (sb.isEmpty()) "(belum ada log)" else sb.toString()
            setTextColor(color(R.color.ink))
            textSize = 12.5f
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        Glass.builder(this).setTitle("Log #$id").setView(sv).setPositiveButton("Tutup", null).showGlass()
    }

    private fun confirm(text: String, yes: () -> Unit) {
        Glass.builder(this).setMessage(text)
            .setPositiveButton("Ya") { _, _ -> yes() }
            .setNegativeButton("Batal", null).showGlass()
    }
}
