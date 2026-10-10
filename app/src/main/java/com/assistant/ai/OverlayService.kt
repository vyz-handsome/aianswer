package com.assistant.ai

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Bubble "AI" mengambang. Tiap ketuk, bubble menampilkan huruf jawaban (A/B/C/D) untuk soal yang tampil di layar,
 * atau kartu jawaban kalau soalnya essay. Kamu yang menekan sendiri. Tidak ada popup; status ada di label bubble
 * dan log di app.
 */
class OverlayService : AccessibilityService() {

    companion object {
        /** Dipakai MainActivity (tombol Stop AI / Munculkan bubble). Satu proses, jadi cukup referensi langsung. */
        @Volatile var instance: OverlayService? = null

        /** true selama sesi Latihan berjalan: bubble AI dimatikan dan tidak bisa dimunculkan. */
        @Volatile var testLock = false

        private const val DOUBLE_TAP_MS = 350L
        private const val MAX_CHARS = 8000
        private const val MAX_DEPTH = 80
        private const val MAX_NODES = 2500
        private const val AI_MAX_TOKENS = 900
        private const val AI_MAX_TOKENS_ACCURATE = 1500

        private const val KIND_ANSWER = 0
        private const val KIND_EXPLAIN = 1
        private const val KIND_TRANSLATE = 2

        private const val MINIMIZE_DELAY_MS = 5000L
        private const val CACHE_MAX = 20
        private val COLOR_HIGH = Color.parseColor("#1B8F4E")
        private val COLOR_MID = Color.parseColor("#C98A00")
        private val COLOR_LOW = Color.parseColor("#C0392B")

        // Layar dari paket ini sengaja tidak dibaca.
        private val BLOCKED_PACKAGES = setOf("com.android.systemui", "com.android.settings")

        private const val MANUAL_INSTRUCTIONS =
            "Kamu membantu menjawab soal yang tampil di layar HP. Kamu menerima gambar layar " +
            "(dan kadang teks mentah layar yang bisa berantakan untuk rumus). Baca soal dari gambar, " +
            "hitung dengan teliti, lalu tentukan jawaban yang benar. Kalau soal sudah terjawab di layar, " +
            "tetap tentukan jawaban yang benar menurut kamu sendiri.\n" +
            "Balas HANYA JSON dengan field berikut, dalam urutan ini:\n" +
            "- \"reasoning\": perhitungan SANGAT singkat (satu kalimat, maksimal 25 kata).\n" +
            "- \"status\": \"essay\" kalau soalnya essay/uraian/isian (jawabannya harus diketik, tidak ada pilihan " +
            "A/B/C/D); selain itu \"question\".\n" +
            "- \"question\": tulis ulang soalnya singkat tapi lengkap, maksimal 25 kata, sertakan angka atau rumus yang terbaca (untuk riwayat dan kuis ulang).\n" +
            "- \"answer_letter\": huruf atau nomor pilihan yang benar, hanya hurufnya saja (misal \"B\"). " +
            "Kosongkan kalau soalnya bukan pilihan ganda.\n" +
            "- \"answer_text\": isi jawaban yang benar, sesingkat mungkin. Kosongkan kalau status \"essay\".\n" +
            "- \"confidence\": \"high\" kalau kamu yakin, \"medium\" kalau agak ragu, \"low\" kalau menebak atau soal kurang jelas.\n" +
            "- \"essay_answer\": hanya kalau status \"essay\": jawaban SINGKAT dan langsung ke inti (to the point), dalam bahasa " +
            "yang sama dengan soalnya. Standarnya 1 sampai 3 kalimat pendek, maksimal sekitar 50 kata. Tanpa pembuka, tanpa " +
            "pengulangan soal, tanpa penutup. Kalau soal meminta beberapa poin, tulis tiap poin dalam satu baris pendek. " +
            "Kalau soal secara eksplisit minta jumlah kata tertentu, ikuti itu. " +
            "Teks biasa saja, tanpa markdown (tanpa ** atau #)."

        private const val EXPLAIN_INSTRUCTIONS =
            "Kamu tutor yang sabar. Dari gambar layar, temukan soal yang sedang tampil, selesaikan dengan teliti, lalu " +
            "jelaskan dengan singkat dan jelas dalam bahasa yang sama dengan soalnya. Balas HANYA JSON: " +
            "{\"text\": \"...\"}. Isi text: baris pertama \"Jawaban: ...\", lalu langkah bernomor (maksimal 6 langkah, " +
            "tiap langkah satu kalimat pendek). Teks biasa saja, tanpa markdown (tanpa ** atau #)."

        private const val TRANSLATE_INSTRUCTIONS =
            "Terjemahkan teks soal dan semua pilihan jawaban yang terlihat di gambar layar ke Bahasa Indonesia. " +
            "Kalau teksnya sudah Bahasa Indonesia, terjemahkan ke Bahasa Inggris. Pertahankan huruf pilihan " +
            "(A, B, C, D), angka, dan rumus apa adanya. Balas HANYA JSON: {\"text\": \"...\"}. Teks biasa saja, " +
            "tanpa markdown (tanpa ** atau #)."
    }

    private class StopLoop(message: String) : Exception(message)
    private class ScreenError(message: String) : Exception(message)
    private class Cancelled : Exception()

    private class Shot(val base64: String, val width: Int, val height: Int, val hash: Int, val feat: ByteArray?)

    private class Frame(val text: String, val pkg: String, val shot: Shot?, val sig: Int)

    private class Answer(
        val status: String,
        val letter: String,
        val text: String,
        val essay: String,
        val question: String,
        val confidence: String,
        val check: String
    ) {
        val isEssay: Boolean get() = status == "essay" || essay.isNotEmpty()
    }

    /** Jawaban yang tersimpan untuk satu tampilan layar (cache soal). */
    private class CacheEntry(val feat: ByteArray, val ans: Answer, val model: String, val accurate: Boolean)

    private val cache = ArrayList<CacheEntry>()

    // Posisi dan keadaan bubble: menempel ke tepi (-1 kiri, 1 kanan, 0 bebas) dan mode auto-minimize.
    private var bubbleColor = 0
    private var dockSide = 0
    private var minimized = false
    private var freeX = 0
    private var dockAnim: ValueAnimator? = null
    private val minimizeRunnable = Runnable { minimizeBubble() }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var cancelled = false
    @Volatile private var lastEvent = 0L
    private var worker: Thread? = null
    private var shotWarned = false

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    // Menu ketuk lama (Jelaskan / Terjemahkan / Pilih area) dan layar pemilih area.
    private var menu: LinearLayout? = null
    private var areaView: FrameLayout? = null

    // Ketuk 2x bubble = sembunyikan. Ketuk 2x di area transparan (hotspot) di tempat yang sama = munculkan lagi.
    private var bubbleHidden = false
    private var hotspot: View? = null
    private var pendingTap: Runnable? = null
    private var lastTapTime = 0L
    private var lastHotTap = 0L

    // Panel jawaban essay: bubble "melebar" jadi kartu berisi jawaban (bisa digulir, digeser, disalin).
    private var panel: LinearLayout? = null

    /** ScrollView yang tingginya dibatasi, supaya jawaban panjang nggak menutupi seluruh layar. */
    private class MaxHeightScroll(context: Context, private val maxH: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
            )
        }
    }
    private val logLines = ArrayList<String>()

    private val density: Float get() = resources.displayMetrics.density

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        if (bubble == null && Prefs.bubbleOn(this) && !testLock) addBubble()
    }

    // Event cuma dipakai buat tahu kapan layar "tenang" (selesai berubah). Layar BARU dibaca saat bubble diketuk.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return
        lastEvent = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeAll()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ bubble

    private fun baseColor(): Int = BubbleStyle.color(this)

    private fun idleLabel(): String = BubbleStyle.icon(this)

    private fun applyLook(tv: TextView) {
        val d = density
        val sc = Prefs.bubbleScale(this) / 100f
        tv.textSize = 15f * sc
        tv.minWidth = (54 * d * sc).toInt()
        tv.minHeight = (52 * d * sc).toInt()
        tv.setPadding((10 * d * sc).toInt(), (8 * d * sc).toInt(), (10 * d * sc).toInt(), (8 * d * sc).toInt())
        tv.background = BubbleStyle.background(this, bubbleColor)
        tv.elevation = 8f * d
        tv.alpha = Prefs.bubbleAlpha(this) / 100f * (if (minimized) 0.5f else 1f)
    }

    /** Dipanggil dari Pengaturan setelah tema, bentuk, ikon, ukuran, atau kepekatan bubble diubah. */
    fun refreshLook() {
        main.post {
            val b = bubble ?: return@post
            // Kalau bubble sedang diam (menampilkan ikon), ikut ganti ikon dan warna dasarnya.
            if (!running && BubbleStyle.ICONS.contains(b.text.toString())) {
                b.text = idleLabel()
                (b as? BubbleArtworkTextView)?.showArtwork(true)
                bubbleColor = baseColor()
            }
            applyLook(b)
            val lp = bubbleLp
            if (lp != null) runCatching { wm.updateViewLayout(b, lp) }
            if (!Prefs.snapEdge(this) && !Prefs.autoMinimize(this)) {
                dockSide = 0
            } else if (dockSide != 0) {
                redock()
            }
            scheduleMinimize()
        }
    }

    private fun paint(tv: TextView, color: Int) {
        bubbleColor = color
        tv.background = BubbleStyle.background(this, color)
        tv.elevation = 8f * density
    }

    // ------------------------------------------------------------------ menempel ke tepi & auto-minimize

    private fun screenW(): Int = resources.displayMetrics.widthPixels

    private fun bubbleW(b: View): Int {
        b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return b.measuredWidth.coerceAtLeast(1)
    }

    /** Posisi x bubble di tepi (setengah masuk kalau sedang diminimalkan). */
    private fun dockX(w: Int): Int {
        val hidden = if (minimized) (w * 0.55f).toInt() else 0
        return if (dockSide < 0) -hidden else screenW() - w + hidden
    }

    private fun moveBubbleX(target: Int, animate: Boolean) {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        dockAnim?.cancel()
        if (!animate || lp.x == target) {
            lp.x = target
            runCatching { wm.updateViewLayout(b, lp) }
            return
        }
        val anim = ValueAnimator.ofInt(lp.x, target)
        anim.duration = 245
        anim.interpolator = OvershootInterpolator(0.72f)
        anim.addUpdateListener {
            lp.x = it.animatedValue as Int
            runCatching { wm.updateViewLayout(b, lp) }
        }
        dockAnim = anim
        anim.start()
    }

    /** Lebar bubble berubah (misal huruf jawaban lebih lebar): jaga bubble tetap menempel di tepinya. */
    private fun redock() {
        val b = bubble ?: return
        if (dockSide == 0) return
        moveBubbleX(dockX(bubbleW(b)), false)
    }

    private fun snapToEdge(animate: Boolean) {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val w = bubbleW(b)
        dockSide = if (lp.x + w / 2 < screenW() / 2) -1 else 1
        moveBubbleX(dockX(w), animate)
    }

    private fun scheduleMinimize() {
        main.removeCallbacks(minimizeRunnable)
        if (!Prefs.autoMinimize(this) || bubble == null || bubbleHidden) return
        main.postDelayed(minimizeRunnable, MINIMIZE_DELAY_MS)
    }

    private fun minimizeBubble() {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        if (minimized || bubbleHidden) return
        // Lagi sibuk atau ada kartu/menu terbuka: coba lagi nanti.
        if (running || panel != null || menu != null || areaView != null) {
            scheduleMinimize()
            return
        }
        val w = bubbleW(b)
        if (dockSide == 0) {
            freeX = lp.x
            dockSide = if (lp.x + w / 2 < screenW() / 2) -1 else 1
        }
        minimized = true
        b.animate().alpha(Prefs.bubbleAlpha(this) / 100f * 0.5f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(210).setInterpolator(DecelerateInterpolator(1.4f)).start()
        moveBubbleX(dockX(w), true)
    }

    private fun expandBubble(animate: Boolean) {
        main.removeCallbacks(minimizeRunnable)
        if (!minimized) return
        minimized = false
        val b = bubble ?: return
        if (animate) {
            b.animate().alpha(Prefs.bubbleAlpha(this) / 100f).scaleX(1f).scaleY(1f)
                .setDuration(230).setInterpolator(OvershootInterpolator(1.05f)).start()
        } else {
            b.animate().cancel()
            b.alpha = Prefs.bubbleAlpha(this) / 100f
            b.scaleX = 1f
            b.scaleY = 1f
        }
        if (Prefs.snapEdge(this)) {
            moveBubbleX(dockX(bubbleW(b)), animate)
        } else {
            dockSide = 0
            moveBubbleX(freeX, animate)
        }
    }

    private fun addBubble() {
        val d = density
        minimized = false
        dockSide = 0
        bubbleColor = baseColor()
        val tv = BubbleArtworkTextView(this).apply {
            text = idleLabel()
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            showArtwork(true)
            contentDescription = "Assistant AI. Ketuk untuk menjawab, tahan untuk menu."
        }
        applyLook(tv)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * d).toInt()
            y = (220 * d).toInt()
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        tv.setOnClickListener { onBubbleClick() }
        tv.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false
            private var fired = false
            private var wasMin = false
            private var press: Runnable? = null

            private fun cancelPress() {
                press?.let { main.removeCallbacks(it) }
                press = null
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dockAnim?.cancel()
                        main.removeCallbacks(minimizeRunnable)
                        // Bubble sedang diminimalkan: sentuhan pertama cuma memunculkannya lagi, belum menjalankan AI.
                        wasMin = minimized
                        if (wasMin) expandBubble(true)
                        if (!wasMin) {
                            v.animate().cancel()
                            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(95)
                                .setInterpolator(DecelerateInterpolator()).start()
                        }
                        startX = lp.x; startY = lp.y
                        downX = e.rawX; downY = e.rawY
                        moved = false
                        fired = false
                        cancelPress()
                        if (!wasMin) {
                            val r = Runnable {
                                press = null
                                if (!moved) {
                                    fired = true
                                    showMenu()
                                }
                            }
                            press = r
                            main.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Ketukan pertama saat bubble menyembul hanya menjalankan animasi expand, bukan drag.
                        if (wasMin) return true
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && hypot(dx, dy) > slop) {
                            moved = true
                            dockSide = 0
                            cancelPress()
                        }
                        if (moved) {
                            val maxX = max(0, screenW() - bubbleW(v))
                            lp.x = (startX + dx).toInt().coerceIn(0, maxX)
                            lp.y = (startY + dy).toInt().coerceAtLeast(0)
                            wm.updateViewLayout(v, lp)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelPress()
                        v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                            .setInterpolator(OvershootInterpolator(1.1f)).start()
                        if (moved) {
                            if (Prefs.snapEdge(this@OverlayService)) snapToEdge(true)
                        } else if (!fired && !wasMin) {
                            v.performClick()
                        }
                        scheduleMinimize()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelPress()
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                        scheduleMinimize()
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(tv, lp)
        bubble = tv
        bubbleLp = lp
        main.post {
            if (Prefs.snapEdge(this)) snapToEdge(false)
            scheduleMinimize()
        }
    }

    private fun setBubble(text: String, color: Int? = null, idleArtwork: Boolean = false) {
        main.post {
            bubble?.let {
                it.text = text
                (it as? BubbleArtworkTextView)?.showArtwork(idleArtwork)
                paint(it, color ?: baseColor())
                if (dockSide != 0) redock()
            }
        }
    }

    /** Tampilkan label sebentar (misal "!" atau "OK"), lalu balik ke ikon kalau nggak lagi bekerja. */
    private fun flashBubble(text: String) {
        setBubble(text)
        main.postDelayed({
            if (!running) {
                bubble?.let {
                    it.text = idleLabel()
                    (it as? BubbleArtworkTextView)?.showArtwork(true)
                    paint(it, baseColor())
                    if (dockSide != 0) redock()
                }
                scheduleMinimize()
            }
        }, 3000)
    }

    private fun onBubbleTap() = startRun(KIND_ANSWER)

    private fun startRun(kind: Int, force: Boolean = false) {
        if (testLock) return
        if (running) {
            cancelled = true
            return
        }
        hidePanel()
        hideMenu()
        synchronized(logLines) { logLines.clear() }

        val sel = AiClient.selected(this)
        val key = AiClient.activeKey(this, sel)
        if (key.isEmpty()) {
            addLog("${sel.provider.label} belum punya API key. Buka app, masuk menu Pengaturan, lalu isi key atau pilih model Gemini.")
            flashBubble("!")
            return
        }
        running = true
        cancelled = false
        shotWarned = false
        worker = Thread {
            if (kind == KIND_ANSWER) runAnswerOnce(force) else runTextOnce(kind)
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun removeAll() {
        if (instance === this) instance = null
        closeBubbleViews()
    }

    /** Tutup bubble, kartu essay, dan area ketuk sepenuhnya (dihapus dari layar, bukan disembunyikan). */
    private fun closeBubbleViews() {
        cancelled = true
        pendingTap?.let { main.removeCallbacks(it) }
        pendingTap = null
        if (::wm.isInitialized) removeHotspot()
        bubbleHidden = false
        if (::wm.isInitialized) {
            hideMenu(true)
            removeAreaPick()
            panel?.let { runCatching { wm.removeView(it) } }
            bubble?.let { runCatching { wm.removeView(it) } }
        }
        panel = null
        bubble = null
    }

    // ------------------------------------------------------------------ log

    private fun addLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val text = synchronized(logLines) {
            logLines.add("[$stamp] $line")
            while (logLines.size > 60) logLines.removeAt(0)
            logLines.joinToString("\n")
        }
        Prefs.setLog(this, text.takeLast(12000))
    }

    private fun secs(fromMs: Long): String =
        String.format(Locale.US, "%.1f", (SystemClock.uptimeMillis() - fromMs) / 1000.0)

    // ------------------------------------------------------------------ jawaban

    private fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Cancelled) {
            setBubble(idleLabel(), idleArtwork = true)
        } catch (e: StopLoop) {
            addLog(e.message.orEmpty())
            flashBubble("!")
        } catch (e: ScreenError) {
            addLog(e.message.orEmpty())
            flashBubble("!")
        } catch (e: GeminiException) {
            addLog("Error AI: ${e.message}")
            flashBubble("!")
        } catch (e: Exception) {
            addLog("Gagal: ${e.message ?: e.javaClass.simpleName}")
            flashBubble("!")
        } finally {
            running = false
            worker = null
            main.post { scheduleMinimize() }
        }
    }

    private fun answerInstructions(accurate: Boolean): String =
        if (accurate) {
            MANUAL_INSTRUCTIONS.replace("SANGAT singkat (satu kalimat, maksimal 25 kata)", "singkat (maksimal 3 kalimat)") +
                "\n- \"check_letter\": hitung ulang soalnya dengan cara yang BERBEDA lalu tulis huruf hasilnya " +
                "(hanya untuk soal hitungan atau logika, selain itu kosongkan)."
        } else {
            MANUAL_INSTRUCTIONS
        }

    private fun viaNote(): String {
        val used = AiClient.lastLabel
        return if (used.isNotEmpty() && used != AiClient.selected(this).label) ", cadangan: $used" else ""
    }

    private fun colorFor(level: String): Int = when (level) {
        "high" -> COLOR_HIGH
        "medium" -> COLOR_MID
        "low" -> COLOR_LOW
        else -> baseColor()
    }

    private fun levelNote(level: String): String = when (level) {
        "high" -> "yakin"
        "medium" -> "agak ragu"
        "low" -> "ragu/menebak"
        else -> "keyakinan tidak diketahui"
    }

    private fun runAnswerOnce(force: Boolean = false) = runGuarded {
        setBubble("…")
        val t0 = SystemClock.uptimeMillis()
        val accurate = Prefs.accurate(this)
        if (Prefs.area(this) != null) addLog("Memakai area soal yang dipilih.")
        val frame = grabFrame()
        val feat = frame.shot?.feat

        // Cache soal: layar sama persis dengan yang barusan dijawab, pakai jawaban tersimpan.
        val hit = if (!force && Prefs.cacheOn(this) && feat != null) {
            synchronized(cache) { cache.firstOrNull { (it.accurate || !accurate) && similar(it.feat, feat) } }
        } else {
            null
        }

        var ans: Answer
        var escalatedFrom = ""
        var usedAccurate = accurate
        if (hit != null) {
            ans = hit.ans
            addLog("Soal sama dengan yang tadi, pakai jawaban tersimpan. Tekan lama bubble lalu pilih Jawab ulang kalau mau diulang.")
        } else {
            ans = askAi(frame, answerInstructions(accurate), accurate)
            if (cancelled) throw Cancelled()
            // Auto-eskalasi: AI ragu di mode cepat, ulangi sekali dengan mode akurat.
            if (!accurate && !ans.isEssay && Prefs.autoEscalate(this) &&
                (ans.confidence == "low" || ans.confidence == "medium")
            ) {
                addLog("AI ${levelNote(ans.confidence)} (${ans.letter}), ulangi dengan mode akurat.")
                setBubble("⏫")
                escalatedFrom = ans.letter
                ans = askAi(frame, answerInstructions(true), true)
                usedAccurate = true
                if (cancelled) throw Cancelled()
            }
            if (feat != null && Prefs.cacheOn(this)) {
                val entry = CacheEntry(feat, ans, AiClient.lastLabel, usedAccurate)
                synchronized(cache) {
                    cache.removeAll { similar(it.feat, feat) }
                    cache.add(0, entry)
                    while (cache.size > CACHE_MAX) cache.removeAt(cache.size - 1)
                }
            }
        }

        val via = if (hit != null) ", dari cache" else viaNote()
        val model = hit?.model ?: AiClient.lastLabel
        if (ans.isEssay) {
            if (ans.essay.isEmpty()) throw StopLoop("Soal essay terdeteksi, tapi AI tidak memberi jawaban. Coba ketuk lagi.")
            addLog("Soal essay terdeteksi (${secs(t0)} dtk$via). Jawaban ditampilkan di bubble.")
            if (hit == null) {
                Prefs.addHistory(
                    this,
                    Prefs.HistoryItem(System.currentTimeMillis(), ans.question.ifEmpty { "(soal essay)" }, ans.essay.take(300), ans.confidence, model)
                )
            }
            showEssay(ans.essay)
            return@runGuarded
        }
        // Cek ulang beda dengan jawaban utama = ragu, apa pun yang dikatakan AI soal keyakinannya.
        val conflict = ans.check.isNotEmpty() && ans.letter.isNotEmpty() && !ans.check.equals(ans.letter, ignoreCase = true)
        val level = if (conflict) (if (ans.confidence == "low") "low" else "medium") else ans.confidence
        val escNote = if (escalatedFrom.isNotEmpty()) ", dinaikkan ke mode akurat dari $escalatedFrom" else ""
        addLog(
            "Jawaban: ${ans.letter} ${ans.text} (${levelNote(level)}, ${secs(t0)} dtk$via$escNote)".replace("  ", " ") +
                if (conflict) ". Hitung ulang menghasilkan ${ans.check}, cek sendiri." else ""
        )
        if (hit == null) {
            Prefs.addHistory(
                this,
                Prefs.HistoryItem(
                    System.currentTimeMillis(),
                    ans.question.ifEmpty { "(soal pilihan ganda)" },
                    "${ans.letter} ${ans.text}".trim(),
                    level,
                    model
                )
            )
        }
        val base = if (ans.letter.isNotEmpty()) ans.letter.take(4).uppercase() else ans.text.take(8)
        var shown = if (conflict) "${ans.letter.take(2).uppercase()}/${ans.check.take(2).uppercase()}?" else base
        if (hit != null) shown += "↺"
        setBubble(shown.ifEmpty { "?" }, colorFor(level))
    }

    /** Penjelasan langkah demi langkah atau terjemahan layar; hasilnya tampil di kartu. */
    private fun runTextOnce(kind: Int) = runGuarded {
        setBubble("…")
        val t0 = SystemClock.uptimeMillis()
        val explain = kind == KIND_EXPLAIN
        if (Prefs.area(this) != null) addLog("Memakai area soal yang dipilih.")
        val frame = grabFrame()
        val raw = askRaw(
            frame,
            if (explain) EXPLAIN_INSTRUCTIONS else TRANSLATE_INSTRUCTIONS,
            1600,
            explain && Prefs.accurate(this)
        )
        if (cancelled) throw Cancelled()
        val text = parseText(raw)
        addLog("${if (explain) "Penjelasan" else "Terjemahan"} selesai (${secs(t0)} dtk${viaNote()}).")
        showEssay(text, if (explain) "Penjelasan" else "Terjemahan")
    }

    private fun sleepOrCancel(ms: Long) {
        var left = ms
        while (left > 0) {
            if (cancelled) throw Cancelled()
            val step = minOf(left, 100L)
            Thread.sleep(step)
            left -= step
        }
        if (cancelled) throw Cancelled()
    }

    // ------------------------------------------------------------------ ketuk 2x, stop, sembunyikan

    /** Ketuk satu kali ditunda sebentar supaya ketuk kedua bisa dikenali sebagai "ketuk 2x". */
    private fun onBubbleClick() {
        val now = SystemClock.uptimeMillis()
        val pending = pendingTap
        if (pending != null && now - lastTapTime <= DOUBLE_TAP_MS) {
            main.removeCallbacks(pending)
            pendingTap = null
            hideBubble()
            return
        }
        lastTapTime = now
        val r = Runnable {
            pendingTap = null
            onBubbleTap()
        }
        pendingTap = r
        main.postDelayed(r, DOUBLE_TAP_MS)
    }

    /** Start AI: pasang bubble di layar (atau munculkan lagi kalau tadi disembunyikan dengan ketuk 2x). */
    fun startBubble() {
        main.post {
            if (testLock) return@post
            if (bubble == null) addBubble() else if (bubbleHidden) showBubble()
        }
    }

    /** Sesi Latihan selesai: pasang lagi bubble kalau sebelumnya aktif. */
    fun restoreBubble() {
        main.post {
            if (!testLock && Prefs.bubbleOn(this) && bubble == null) addBubble()
        }
    }

    /** Stop AI: hentikan proses yang jalan lalu hapus bubble dari layar sampai Start AI ditekan lagi. */
    fun stopBubble() {
        Thread { AiClient.abort() }.start()
        main.post { closeBubbleViews() }
    }

    private fun hideBubble() {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        if (running) {
            cancelled = true
            Thread { AiClient.abort() }.start()
        }
        hidePanel()
        val w = if (b.width > 0) b.width else (56 * density).toInt()
        val h = if (b.height > 0) b.height else (44 * density).toInt()
        bubbleHidden = true
        b.visibility = View.GONE
        addHotspot(lp.x, lp.y, w, h)
    }

    private fun showBubble() {
        removeHotspot()
        bubbleHidden = false
        bubble?.let {
            it.text = idleLabel()
            (it as? BubbleArtworkTextView)?.showArtwork(true)
            paint(it, baseColor())
        }
        bubble?.visibility = View.VISIBLE
        scheduleMinimize()
    }

    /** Area transparan di bekas posisi bubble, sedikit lebih besar dari bubble-nya. Ketuk 2x untuk memunculkan bubble. */
    private fun addHotspot(x: Int, y: Int, w: Int, h: Int) {
        removeHotspot()
        val margin = (22 * density).toInt()
        val v = View(this)
        v.setOnClickListener { onHotspotClick() }
        val lp = WindowManager.LayoutParams(
            w + 2 * margin,
            h + 2 * margin,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - margin).coerceAtLeast(0)
            this.y = (y - margin).coerceAtLeast(0)
        }
        wm.addView(v, lp)
        hotspot = v
        lastHotTap = 0L
    }

    private fun removeHotspot() {
        hotspot?.let { runCatching { wm.removeView(it) } }
        hotspot = null
    }

    private fun onHotspotClick() {
        val now = SystemClock.uptimeMillis()
        if (lastHotTap != 0L && now - lastHotTap <= DOUBLE_TAP_MS) {
            lastHotTap = 0L
            showBubble()
        } else {
            lastHotTap = now
        }
    }

    // ------------------------------------------------------------------ menu ketuk lama

    /** Popup muncul: membesar sedikit dari bawah sambil memudar masuk. Satu animator ringan dengan layer cache. */
    private fun animateIn(v: View) {
        v.alpha = 0f
        v.scaleX = 0.86f
        v.scaleY = 0.86f
        v.translationY = 13 * density
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(255).setInterpolator(OvershootInterpolator(0.72f)).withLayer().start()
    }

    /** Popup hilang: mengecil sambil memudar, baru dilepas dari layar. */
    private fun animateOutAndRemove(v: View) {
        v.animate().alpha(0f).scaleX(0.86f).scaleY(0.86f).translationY(-5f * density)
            .setDuration(175).setInterpolator(AccelerateInterpolator()).withLayer()
            .withEndAction { runCatching { wm.removeView(v) } }.start()
    }

    private fun hideMenu(immediate: Boolean = false) {
        val m = menu
        menu = null
        if (m == null) return
        if (immediate) runCatching { wm.removeView(m) } else animateOutAndRemove(m)
    }

    private fun showMenu() {
        if (testLock || bubbleHidden || running) return
        hideMenu()
        val b = bubble ?: return
        val d = density
        val dm = resources.displayMetrics
        val hasArea = Prefs.area(this) != null

        fun row(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#27305A"))
            textSize = 13.5f
            minHeight = (46 * d).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = ContextCompat.getDrawable(this@OverlayService, R.drawable.glass_menu_row)
            setOnClickListener {
                hideMenu()
                action()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@OverlayService, R.drawable.glass_menu_card)
            elevation = 12 * d
            setPadding((7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt())
            addView(row("Jelaskan soal") { startRun(KIND_EXPLAIN) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * d).toInt() })
            addView(row("Terjemahkan layar") { startRun(KIND_TRANSLATE) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * d).toInt() })
            addView(row("Jawab ulang · tanpa cache") { startRun(KIND_ANSWER, true) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * d).toInt() })
            addView(row(if (hasArea) "Ubah area soal · aktif" else "Pilih area soal") { startAreaPick() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * d).toInt() })
            if (hasArea) {
                addView(row("Hapus area soal") {
                    Prefs.setArea(this@OverlayService, null)
                    flashBubble("OK")
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * d).toInt() })
            }
            addView(row("Tutup") {}, LinearLayout.LayoutParams(-1, -2))
        }
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                true
            } else {
                false
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bh = if (b.height > 0) b.height else (44 * d).toInt()
            val loc = IntArray(2)
            b.getLocationOnScreen(loc)
            x = loc[0].coerceAtMost(dm.widthPixels - (230 * d).toInt()).coerceAtLeast(0)
            y = (loc[1] + bh + (6 * d).toInt()).coerceAtMost(dm.heightPixels - (300 * d).toInt()).coerceAtLeast(0)
        }
        MotionFx.install(root)
        wm.addView(root, lp)
        animateIn(root)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = 7f * d
            child.animate().alpha(1f).translationY(0f).setStartDelay(i * 24L)
                .setDuration(185).setInterpolator(DecelerateInterpolator(1.4f)).start()
        }
        menu = root
        main.postDelayed({ if (menu === root) hideMenu() }, 8000)
    }

    // ------------------------------------------------------------------ pilih area soal

    private inner class AreaCanvas(
        ctx: Context,
        private val onPicked: (Float, Float, Float, Float) -> Unit
    ) : View(ctx) {
        private var sx = 0f
        private var sy = 0f
        private var ex = 0f
        private var ey = 0f
        private var has = false
        private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        private val border = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (!has) {
                c.drawRect(0f, 0f, w, h, dim)
                return
            }
            val l = min(sx, ex)
            val r = max(sx, ex)
            val t = min(sy, ey)
            val b = max(sy, ey)
            c.drawRect(0f, 0f, w, t, dim)
            c.drawRect(0f, b, w, h, dim)
            c.drawRect(0f, t, l, b, dim)
            c.drawRect(r, t, w, b, dim)
            c.drawRect(l, t, r, b, border)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.x; sy = e.y; ex = e.x; ey = e.y
                    has = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    ex = e.x; ey = e.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    ex = e.x; ey = e.y
                    val l = min(sx, ex)
                    val r = max(sx, ex)
                    val t = min(sy, ey)
                    val b = max(sy, ey)
                    if (width == 0 || height == 0 || r - l < 60 * density || b - t < 60 * density) {
                        has = false
                        invalidate()
                    } else {
                        onPicked(l / width, t / height, r / width, b / height)
                    }
                }
            }
            return true
        }
    }

    private fun startAreaPick() {
        hideMenu()
        hidePanel()
        removeAreaPick()
        val d = density
        val canvas = AreaCanvas(this) { l, t, r, b ->
            Prefs.setArea(this, floatArrayOf(l, t, r, b))
            removeAreaPick()
            addLog("Area soal disimpan. Ketuk bubble untuk mulai.")
            flashBubble("OK")
        }
        val hint = TextView(this).apply {
            text = "Seret di layar untuk memilih area soal"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val cancel = TextView(this).apply {
            text = "Batal"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = ContextCompat.getDrawable(this@OverlayService, R.drawable.bubble)
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), (10 * d).toInt())
            setOnClickListener { removeAreaPick() }
        }
        val root = FrameLayout(this).apply {
            addView(
                canvas,
                FrameLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            )
            addView(
                hint,
                FrameLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ).apply { topMargin = (70 * d).toInt() }
            )
            addView(
                cancel,
                FrameLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = (70 * d).toInt() }
            )
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        areaView = root
    }

    private fun removeAreaPick() {
        areaView?.let { runCatching { wm.removeView(it) } }
        areaView = null
    }

    // ------------------------------------------------------------------ panel jawaban essay

    private fun showEssay(answerText: String, heading: String = "Jawaban essay") {
        main.post {
            if (bubbleHidden) return@post
            hidePanel()
            val b = bubble ?: return@post
            val d = density
            val dm = resources.displayMetrics
            val width = minOf(dm.widthPixels - (24 * d).toInt(), (340 * d).toInt())
            val maxBody = (dm.heightPixels * 0.42f).toInt()

            val title = TextView(this).apply {
                this.text = heading
                setTextColor(Color.parseColor("#555555"))
                textSize = 12f
                setPadding((14 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
            }
            val copy = TextView(this).apply {
                this.text = "Salin"
                setTextColor(Color.parseColor("#1A73E8"))
                textSize = 13f
                setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            }
            val close = TextView(this).apply {
                this.text = "✕"
                setTextColor(Color.parseColor("#555555"))
                textSize = 15f
                setPadding((10 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(copy)
                addView(close)
            }

            val body = TextView(this).apply {
                this.text = answerText
                setTextColor(Color.parseColor("#111111"))
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setPadding((14 * d).toInt(), (2 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            }
            val scroll = MaxHeightScroll(this, maxBody).apply { addView(body) }

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@OverlayService, R.drawable.glass_card)
                elevation = 8 * d
                addView(header)
                addView(scroll)
            }

            val lp = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (12 * d).toInt()
                y = (bubbleLp?.y ?: (220 * d).toInt()).coerceIn(0, (dm.heightPixels * 0.4f).toInt())
            }

            // Header bisa dipakai buat geser panel.
            val slop = ViewConfiguration.get(this).scaledTouchSlop
            title.setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var downX = 0f
                private var downY = 0f
                private var moved = false

                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = lp.x; startY = lp.y
                            downX = e.rawX; downY = e.rawY
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - downX
                            val dy = e.rawY - downY
                            if (!moved && hypot(dx, dy) > slop) moved = true
                            if (moved) {
                                lp.x = (startX + dx).toInt().coerceAtLeast(0)
                                lp.y = (startY + dy).toInt().coerceAtLeast(0)
                                wm.updateViewLayout(root, lp)
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            copy.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("jawaban", answerText))
                copy.text = "Disalin ✓"
            }
            close.setOnClickListener { hidePanel() }

            MotionFx.install(copy)
            MotionFx.install(close)
            wm.addView(root, lp)
            animateIn(root)
            panel = root
            b.visibility = View.GONE
            b.text = idleLabel()
            (b as? BubbleArtworkTextView)?.showArtwork(true)
            paint(b, baseColor())
        }
    }

    private fun hidePanel() {
        val doIt = {
            val p = panel
            panel = null
            if (p != null) animateOutAndRemove(p)
            if (!bubbleHidden) bubble?.visibility = View.VISIBLE
        }
        if (Looper.myLooper() == Looper.getMainLooper()) doIt() else main.post { doIt() }
    }

    // ------------------------------------------------------------------ AI

    /** Satu panggilan AI (mode JSON) dengan tunggu-ulang kalau kena batas gratis. Mengembalikan teks mentah. */
    private fun askRaw(frame: Frame, instructions: String, maxTokens: Int, accurate: Boolean): String {
        val prompt = if (frame.text.isNotEmpty()) {
            "Teks mentah layar:\n" + frame.text
        } else {
            "Teks mentah layar: (tidak tersedia, baca dari gambar.)"
        }
        for (attempt in 0..3) {
            try {
                return AiClient.generate(
                    this, prompt, frame.shot?.base64, instructions, true, 0.0,
                    fast = true, maxTokens = maxTokens, accurate = accurate, fallback = Prefs.fallback(this)
                )
            } catch (e: GeminiException) {
                if (e.code == 429 && attempt < 3) {
                    addLog("Kena batas gratis (429), tunggu 12 detik lalu coba lagi (${attempt + 1}/3).")
                    sleepOrCancel(12_000)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                // Koneksi diputus gara-gara tombol Stop: anggap dibatalkan, bukan error.
                if (cancelled) throw Cancelled()
                throw e
            }
        }
        throw GeminiException(429, "Kena batas pemakaian gratis (429) terus-menerus.")
    }

    private fun askAi(frame: Frame, instructions: String, accurate: Boolean): Answer {
        val tokens = if (accurate) AI_MAX_TOKENS_ACCURATE else AI_MAX_TOKENS
        for (attempt in 0..1) {
            try {
                return parseAnswer(askRaw(frame, instructions, tokens, accurate))
            } catch (e: StopLoop) {
                // JSON terpotong / rusak: coba sekali lagi sebelum menyerah.
                if (attempt >= 1) throw e
                addLog("Jawaban AI rusak, coba lagi.")
            } catch (e: org.json.JSONException) {
                if (attempt >= 1) throw StopLoop("Jawaban AI bukan JSON yang valid.")
                addLog("Jawaban AI rusak, coba lagi.")
            }
        }
        throw StopLoop("Jawaban AI bukan JSON yang valid.")
    }

    private fun clean(s: String): String = if (s.equals("null", ignoreCase = true)) "" else s.trim()

    private fun jsonOf(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw StopLoop("Jawaban AI bukan JSON: ${raw.take(200)}")
        }
        return JSONObject(raw.substring(start, end + 1))
    }

    private fun parseAnswer(raw: String): Answer {
        val o = jsonOf(raw)
        return Answer(
            status = clean(o.optString("status")).lowercase(),
            letter = clean(o.optString("answer_letter")).takeWhile { it.isLetterOrDigit() },
            text = clean(o.optString("answer_text")),
            essay = clean(o.optString("essay_answer")).replace("**", "").replace("__", ""),
            question = clean(o.optString("question")).take(120),
            confidence = clean(o.optString("confidence")).lowercase(),
            check = clean(o.optString("check_letter")).takeWhile { it.isLetterOrDigit() }
        )
    }

    private fun parseText(raw: String): String {
        val t = clean(jsonOf(raw).optString("text")).replace("**", "").replace("__", "")
        if (t.isEmpty()) throw StopLoop("AI tidak memberi hasil. Coba lagi.")
        return t
    }

    // ------------------------------------------------------------------ baca layar

    /** Screenshot dulu (cepat, rumus terbaca jelas). Kalau gagal, baru baca teks layar. */
    private fun grabFrame(): Frame {
        var lastErr = "Layar ini nggak bisa dibaca. Buka halaman kuisnya dulu."
        for (attempt in 0..2) {
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == packageName) throw StopLoop("Buka halaman kuis dulu, baru ketuk bubble.")
                if (pkg in BLOCKED_PACKAGES) throw StopLoop("Layar sistem ini sengaja tidak dibaca.")

                val shot = takeShot()
                if (shot != null) return Frame("", pkg, shot, shot.hash)

                val sb = StringBuilder()
                collect(root, sb, 0)
                val text = sb.toString().trim().take(MAX_CHARS)
                if (text.length >= 10) return Frame(text, pkg, null, text.hashCode())
                lastErr = "Screenshot gagal dan nggak ada teks yang bisa dibaca di layar ini."
            }
            sleepOrCancel(500)
        }
        throw ScreenError(lastErr)
    }

    /** Kumpulkan teks yang terlihat (cadangan kalau screenshot gagal). Kolom password dan isian dilewati. */
    @Suppress("DEPRECATION")
    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (sb.length >= MAX_CHARS || depth > MAX_DEPTH) return
        if (!node.isVisibleToUser) return

        if (!node.isPassword && !node.isEditable) {
            val t = labelOf(node).trim()
            if (t.isNotEmpty() && !sb.endsWith(t + "\n")) sb.append(t).append('\n')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, sb, depth + 1)
            child.recycle()
        }
    }

    private fun takeShot(): Shot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        for (i in 0..2) {
            try {
                val s = captureScreenshot()
                if (s != null) return s
            } catch (e: Exception) {
                // coba lagi
            }
            sleepOrCancel(350)
        }
        if (!shotWarned) {
            shotWarned = true
            addLog("Screenshot gagal, pakai teks layar (rumus bisa kurang terbaca).")
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshot(): Shot? {
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Exception) {
                    } finally {
                        result.hardwareBuffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )
        if (!latch.await(5, TimeUnit.SECONDS)) return null
        val src = cropToArea(bmp ?: return null)

        val w = src.width
        val h = src.height
        val scale = minOf(1f, 1600f / max(w, h))
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
        } else {
            src
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        val bytes = bos.toByteArray()
        val feat = try {
            features(src)
        } catch (e: Exception) {
            null
        }
        return Shot(Base64.encodeToString(bytes, Base64.NO_WRAP), w, h, bytes.contentHashCode(), feat)
    }

    // Cache soal: sidik jari layar berupa gambar abu-abu kecil (tanpa bilah status dan navigasi).
    private val featW = 256

    private fun features(src: Bitmap): ByteArray {
        val fh = (src.height * featW / src.width.toFloat()).toInt().coerceIn(32, 1400)
        val small = Bitmap.createScaledBitmap(src, featW, fh, true)
        val px = IntArray(featW * fh)
        small.getPixels(px, 0, featW, 0, 0, featW, fh)
        if (small !== src) small.recycle()
        val top = (fh * 0.07f).toInt()
        val bottom = fh - (fh * 0.06f).toInt()
        val rows = max(4, bottom - top)
        val out = ByteArray(featW * rows)
        var i = 0
        for (y in top until top + rows) {
            for (x in 0 until featW) {
                val p = px[(y.coerceAtMost(fh - 1)) * featW + x]
                val g = (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                out[i++] = g.toByte()
            }
        }
        return out
    }

    /**
     * Dua layar dianggap sama kalau tiap blok 4x4 piksel nyaris identik. Satu angka atau tanda baca yang beda
     * sudah cukup membuat blok itu berubah, jadi lebih baik meleset (panggil AI lagi) daripada salah pakai cache.
     */
    private fun similar(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        val rows = a.size / featW
        var total = 0L
        var by = 0
        while (by + 4 <= rows) {
            var bx = 0
            while (bx + 4 <= featW) {
                var sum = 0
                for (yy in 0 until 4) {
                    for (xx in 0 until 4) {
                        val i = (by + yy) * featW + bx + xx
                        sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
                    }
                }
                if (sum / 16 > 8) return false
                total += sum
                bx += 4
            }
            by += 4
        }
        return total / a.size <= 1
    }

    /** Kalau user sudah memilih area soal, hanya bagian itu yang dikirim ke AI. */
    private fun cropToArea(src: Bitmap): Bitmap {
        val a = Prefs.area(this) ?: return src
        val w = src.width
        val h = src.height
        if (w < 4 || h < 4) return src
        val l = (a[0] * w).toInt().coerceIn(0, w - 2)
        val t = (a[1] * h).toInt().coerceIn(0, h - 2)
        val r = (a[2] * w).toInt().coerceIn(l + 1, w)
        val b = (a[3] * h).toInt().coerceIn(t + 1, h)
        return runCatching { Bitmap.createBitmap(src, l, t, r - l, b - t) }.getOrDefault(src)
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        n.text?.toString() ?: n.contentDescription?.toString() ?: ""


}
