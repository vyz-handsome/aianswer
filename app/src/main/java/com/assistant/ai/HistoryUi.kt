package com.assistant.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Menu Riwayat: daftar jawaban (cari, filter, tandai benar/salah) dan Statistik yang dihitung dari riwayat itu. */
class HistoryUi(private val act: AppCompatActivity, private val onExport: () -> Unit) {

    private lateinit var segHistory: TextView
    private lateinit var segStats: TextView
    private lateinit var historyPane: View
    private lateinit var statsPane: View
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private lateinit var statsList: LinearLayout
    private lateinit var fAll: TextView
    private lateinit var fDoubt: TextView
    private lateinit var fWrong: TextView

    private var filter = 0 // 0 semua, 1 ragu, 2 salah
    private var showStats = false
    private val d = act.resources.displayMetrics.density
    private val timeFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val dayKeyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun attach() {
        segHistory = act.findViewById(R.id.segHistory)
        segStats = act.findViewById(R.id.segStats)
        historyPane = act.findViewById(R.id.historyPane)
        statsPane = act.findViewById(R.id.statsPane)
        search = act.findViewById(R.id.historySearch)
        list = act.findViewById(R.id.historyList)
        statsList = act.findViewById(R.id.statsList)
        fAll = act.findViewById(R.id.filterAll)
        fDoubt = act.findViewById(R.id.filterDoubt)
        fWrong = act.findViewById(R.id.filterWrong)

        segHistory.setOnClickListener { showStats = false; refresh() }
        segStats.setOnClickListener { showStats = true; refresh() }
        fAll.setOnClickListener { filter = 0; refresh() }
        fDoubt.setOnClickListener { filter = 1; refresh() }
        fWrong.setOnClickListener { filter = 2; refresh() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = renderHistory()
        })
        act.findViewById<TextView>(R.id.btnExportHistory).setOnClickListener { onExport() }
        act.findViewById<TextView>(R.id.btnQuizHistory).setOnClickListener { startQuiz() }
        act.findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {
            Glass.builder(act)
                .setTitle("Hapus semua riwayat?")
                .setMessage("Riwayat dan statistik ikut hilang. Ini tidak bisa dibatalkan.")
                .setPositiveButton("Hapus") { _, _ ->
                    Prefs.clearHistory(act)
                    refresh()
                    Toast.makeText(act, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .showGlass()
        }
    }

    fun refresh() {
        style(segHistory, !showStats)
        style(segStats, showStats)
        historyPane.visibility = if (showStats) View.GONE else View.VISIBLE
        statsPane.visibility = if (showStats) View.VISIBLE else View.GONE
        style(fAll, filter == 0)
        style(fDoubt, filter == 1)
        style(fWrong, filter == 2)
        if (showStats) renderStats() else renderHistory()
    }

    private fun style(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) 0xFF5A4BD1.toInt() else 0xFF8A8DA3.toInt())
        tv.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
        if (selected) tv.setBackgroundResource(R.drawable.tab_selected) else tv.background = null
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(act).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun confLabel(c: String) = when (c) {
        "high" -> "yakin"
        "medium" -> "agak ragu"
        "low" -> "ragu"
        else -> ""
    }

    private fun sourceLabel(s: String) = if (s == "chat") "Tanya AI" else "Bubble"

    // ------------------------------------------------------------------ daftar riwayat

    private fun renderHistory() {
        val q = search.text.toString().trim().lowercase()
        val items = Prefs.history(act).filter {
            val okFilter = when (filter) {
                1 -> it.confidence == "medium" || it.confidence == "low"
                2 -> it.mark == -1
                else -> true
            }
            val okSearch = q.isEmpty() || it.question.lowercase().contains(q) || it.answer.lowercase().contains(q)
            okFilter && okSearch
        }
        list.removeAllViews()
        if (items.isEmpty()) {
            val empty = text(
                "Belum ada riwayat yang cocok. Tiap jawaban dari bubble dan perintah /a /e di Tanya AI tercatat di sini.",
                13f, 0xFF9A9DB2.toInt()
            )
            empty.setPadding(dp(8), dp(24), dp(8), dp(24))
            list.addView(empty)
            return
        }
        for (item in items) list.addView(card(item))
    }

    private fun card(item: Prefs.HistoryItem): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_ui)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        box.layoutParams = lp

        val conf = confLabel(item.confidence)
        val head = timeFmt.format(Date(item.time)) + " · " + sourceLabel(item.source) + (if (conf.isEmpty()) "" else " · $conf")
        box.addView(text(head, 11.5f, 0xFF8A8DA3.toInt()))
        box.addView(text(item.question.ifEmpty { "(tanpa judul)" }, 14f, 0xFF1B1B2F.toInt(), true).apply {
            setPadding(0, dp(4), 0, 0)
        })
        box.addView(text("" + item.answer, 14f, 0xFF5A4BD1.toInt()).apply { setPadding(0, dp(4), 0, 0) })
        if (item.model.isNotEmpty()) {
            box.addView(text(item.model, 11f, 0xFF9A9DB2.toInt()).apply { setPadding(0, dp(4), 0, 0) })
        }

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun markBtn(label: String, value: Int, onBg: Int, onColor: Int): TextView {
            val on = item.mark == value
            return TextView(act).apply {
                text = label
                textSize = 12.5f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                if (on) {
                    setBackgroundResource(onBg)
                    setTextColor(onColor)
                } else {
                    setBackgroundResource(R.drawable.chip_idle)
                    setTextColor(0xFF555A70.toInt())
                }
                setOnClickListener {
                    Prefs.setHistoryMark(act, item.time, if (on) 0 else value)
                    renderHistory()
                }
            }
        }
        val right = markBtn("✔ Benar", 1, R.drawable.chip_on, 0xFF0B7A55.toInt())
        val wrong = markBtn("✘ Salah", -1, R.drawable.chip_off, 0xFFB45F06.toInt())
        row.addView(right)
        row.addView(wrong, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        box.addView(row)

        box.setOnLongClickListener {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("riwayat", item.question + "\n➜ " + item.answer))
            Toast.makeText(act, "Disalin", Toast.LENGTH_SHORT).show()
            true
        }
        return box
    }

    // ------------------------------------------------------------------ kuis ulang

    private fun startQuiz() {
        val choices = arrayOf("Yang AI ragu (belum dicek)", "Yang sudah kutandai ✔ benar", "Semua riwayat")
        Glass.builder(act)
            .setTitle("Kuis ulang: ambil soal dari…")
            .setItems(choices) { _, which -> runQuiz(which) }
            .setNegativeButton("Batal", null)
            .showGlass()
    }

    private fun runQuiz(which: Int) {
        // Jawaban yang kamu tandai salah tidak dipakai, supaya kamu tidak menghafal jawaban yang keliru.
        val usable = Prefs.history(act).filter {
            it.mark != -1 && it.question.isNotBlank() && !it.question.startsWith("Foto") && !it.question.startsWith("(soal")
        }
        val pool = when (which) {
            0 -> usable.filter { it.mark == 0 && (it.confidence == "medium" || it.confidence == "low") }
            1 -> usable.filter { it.mark == 1 }
            else -> usable
        }
        if (pool.isEmpty()) {
            Toast.makeText(act, "Belum ada soal yang cocok. Coba pilihan lain atau tandai ✔ dulu.", Toast.LENGTH_LONG).show()
            return
        }
        quizStep(pool.shuffled().take(10), 0, 0, emptyList())
    }

    private fun quizStep(items: List<Prefs.HistoryItem>, i: Int, ok: Int, forgot: List<String>) {
        if (i >= items.size) {
            quizSummary(items.size, ok, forgot)
            return
        }
        Glass.builder(act)
            .setTitle("Soal ${i + 1} dari ${items.size}")
            .setMessage(items[i].question)
            .setCancelable(false)
            .setPositiveButton("Tampilkan jawaban") { _, _ -> quizReveal(items, i, ok, forgot) }
            .setNegativeButton("Berhenti") { _, _ -> quizSummary(i, ok, forgot) }
            .showGlass()
    }

    private fun quizReveal(items: List<Prefs.HistoryItem>, i: Int, ok: Int, forgot: List<String>) {
        val it = items[i]
        val note = if (it.mark == 1) "" else "\n\n(Jawaban AI ini belum kamu tandai ✔, cek lagi kalau ragu.)"
        Glass.builder(act)
            .setTitle("Soal ${i + 1} dari ${items.size}")
            .setMessage(it.question + "\n\n➜ " + it.answer + note)
            .setCancelable(false)
            .setPositiveButton("Ingat") { _, _ -> quizStep(items, i + 1, ok + 1, forgot) }
            .setNeutralButton("Lupa") { _, _ -> quizStep(items, i + 1, ok, forgot + it.question) }
            .setNegativeButton("Berhenti") { _, _ -> quizSummary(i + 1, ok, forgot) }
            .showGlass()
    }

    private fun quizSummary(done: Int, ok: Int, forgot: List<String>) {
        val sb = StringBuilder()
        if (done == 0) {
            sb.append("Belum ada soal yang dijawab.")
        } else {
            sb.append("Kamu ingat $ok dari $done soal (${ok * 100 / done}%).")
        }
        if (forgot.isNotEmpty()) {
            sb.append("\n\nPerlu diulang:")
            for (q in forgot) sb.append("\n• ").append(q)
        }
        Glass.builder(act)
            .setTitle("Kuis ulang selesai")
            .setMessage(sb.toString())
            .setPositiveButton("Tutup", null)
            .showGlass()
    }

    // ------------------------------------------------------------------ statistik

    private fun dayKey(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -offset)
        return dayKeyFmt.format(c.time)
    }

    private fun statCard(title: String, body: String, mono: Boolean = false): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_ui)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        box.layoutParams = lp
        box.addView(text(title, 15f, 0xFF1B1B2F.toInt(), true))
        val b = text(body, 13.5f, 0xFF3A3D52.toInt())
        if (mono) b.typeface = Typeface.MONOSPACE
        b.setPadding(0, dp(6), 0, 0)
        box.addView(b)
        return box
    }

    private fun pct(a: Int, b: Int): String = if (b == 0) "-" else "${a * 100 / b}%"

    private fun renderStats() {
        statsList.removeAllViews()
        val items = Prefs.history(act)
        if (items.isEmpty()) {
            val empty = text(
                "Belum ada data. Jawab beberapa soal dulu lewat bubble atau Tanya AI, nanti statistiknya muncul di sini.",
                13f, 0xFF9A9DB2.toInt()
            )
            empty.setPadding(dp(8), dp(24), dp(8), dp(24))
            statsList.addView(empty)
            return
        }

        val byDay = items.groupingBy { dayKeyFmt.format(Date(it.time)) }.eachCount()
        val today = byDay[dayKey(0)] ?: 0
        var week = 0
        for (i in 0..6) week += byDay[dayKey(i)] ?: 0
        val start = if (byDay.containsKey(dayKey(0))) 0 else 1
        var streak = 0
        while (byDay.containsKey(dayKey(start + streak))) streak++

        statsList.addView(
            statCard(
                "Ringkasan",
                "Total tercatat: ${items.size}\nHari ini: $today\n7 hari terakhir: $week\nStreak: $streak hari berturut-turut"
            )
        )

        val dayLabel = SimpleDateFormat("EEE dd/MM", Locale.forLanguageTag("id"))
        val bars = StringBuilder()
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val n = byDay[dayKey(i)] ?: 0
            bars.append(dayLabel.format(c.time).padEnd(11)).append(" ").append("█".repeat(minOf(n, 20))).append(" ").append(n)
            if (i > 0) bars.append('\n')
        }
        statsList.addView(statCard("7 hari terakhir", bars.toString(), true))

        val high = items.count { it.confidence == "high" }
        val mid = items.count { it.confidence == "medium" }
        val low = items.count { it.confidence == "low" }
        if (high + mid + low > 0) {
            statsList.addView(
                statCard("Keyakinan AI (bubble)", "Yakin: $high\n" + "Agak ragu: $mid\n" + "Ragu / menebak: $low")
            )
        }

        val marked = items.filter { it.mark != 0 }
        val right = marked.count { it.mark == 1 }
        if (marked.isEmpty()) {
            statsList.addView(
                statCard("Akurasi", "Belum ada yang ditandai. Tekan ✔ Benar atau ✘ Salah di tiap riwayat supaya akurasi AI bisa dihitung.")
            )
        } else {
            val sb = StringBuilder()
            sb.append("Ditandai: ${marked.size} (benar $right, salah ${marked.size - right})\n")
            sb.append("Akurasi keseluruhan: ${pct(right, marked.size)}")
            for ((lvl, name) in listOf("high" to "yakin", "medium" to "agak ragu", "low" to "ragu")) {
                val g = marked.filter { it.confidence == lvl }
                if (g.isNotEmpty()) sb.append("\nSaat AI $name: ${pct(g.count { it.mark == 1 }, g.size)} (${g.size} soal)")
            }
            statsList.addView(statCard("Akurasi (dari yang kamu tandai)", sb.toString()))
        }

        val fromChat = items.count { it.source == "chat" }
        statsList.addView(statCard("Sumber", "Bubble: ${items.size - fromChat}\nTanya AI: $fromChat"))

        val note = text("Dihitung dari ${items.size} riwayat terbaru (maksimal 300).", 11.5f, 0xFF9A9DB2.toInt())
        note.setPadding(dp(4), 0, 0, dp(16))
        statsList.addView(note)
    }
}

/** Ubah riwayat jadi CSV (Excel/Sheets) atau teks biasa. */
object HistoryExport {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun conf(c: String) = when (c) {
        "high" -> "yakin"
        "medium" -> "agak ragu"
        "low" -> "ragu"
        else -> ""
    }

    private fun mark(m: Int) = when (m) {
        1 -> "benar"
        -1 -> "salah"
        else -> ""
    }

    private fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""

    fun csv(items: List<Prefs.HistoryItem>): String {
        val sb = StringBuilder("\uFEFF")
        sb.append("Waktu,Sumber,Keyakinan,Tanda,Soal,Jawaban,Model\r\n")
        for (h in items) {
            sb.append(q(fmt.format(Date(h.time)))).append(',')
                .append(q(if (h.source == "chat") "Tanya AI" else "Bubble")).append(',')
                .append(q(conf(h.confidence))).append(',')
                .append(q(mark(h.mark))).append(',')
                .append(q(h.question)).append(',')
                .append(q(h.answer)).append(',')
                .append(q(h.model)).append("\r\n")
        }
        return sb.toString()
    }

    fun txt(items: List<Prefs.HistoryItem>): String {
        val sb = StringBuilder("Riwayat Assistant\n\n")
        for (h in items) {
            sb.append(fmt.format(Date(h.time))).append(" | ").append(if (h.source == "chat") "Tanya AI" else "Bubble")
            val c = conf(h.confidence)
            if (c.isNotEmpty()) sb.append(" | ").append(c)
            val m = mark(h.mark)
            if (m.isNotEmpty()) sb.append(" | ").append(m)
            sb.append('\n').append(h.question).append('\n').append("-> ").append(h.answer)
            if (h.model.isNotEmpty()) sb.append("\n(").append(h.model).append(')')
            sb.append("\n\n")
        }
        return sb.toString()
    }
}
