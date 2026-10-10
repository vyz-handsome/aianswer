package com.assistant.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Lima menu: Beranda (kontrol bubble), Tanya AI (chat + command), Latihan, Riwayat (+ Statistik), dan Pengaturan. */
class MainActivity : AppCompatActivity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Beranda
    private lateinit var homeScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var testResult: TextView
    private lateinit var modelInfo: TextView
    private lateinit var logView: TextView
    private lateinit var headerIcon: ImageView
    private lateinit var headerName: TextView
    private lateinit var headerSub: TextView
    private lateinit var namePreview: ImageView
    private lateinit var iconRow: LinearLayout
    private var selName = 0
    private var selIcon = 0

    // Tanya AI
    private lateinit var chatPage: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatEmpty: TextView
    private lateinit var chatInput: EditText
    private lateinit var btnSend: Button
    private lateinit var attachRow: LinearLayout
    private lateinit var attachThumb: ImageView
    private val chatHistory = ArrayList<ChatTurn>()
    private var pendingImage: ImageUtil.Loaded? = null
    private var chatBusy = false

    private val pickChatImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attachImage(uri)
    }

    // Pengenalan suara lewat UI sistem, lalu teks hasilnya langsung dikirim sebagai pertanyaan.
    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                chatInput.setText(spoken)
                chatInput.setSelection(chatInput.text.length)
                sendChat()
            } else {
                Toast.makeText(this, "Suara belum terbaca. Coba bicara lagi.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Menu bawah
    private lateinit var bottomBar: LinearLayout
    private lateinit var navBar: NavBarView
    private var currentTab = -1
    private lateinit var latihanPage: ScrollView
    private lateinit var historyPage: LinearLayout
    private lateinit var settingsPage: ScrollView
    private lateinit var historyUi: HistoryUi

    // Ekspor riwayat lewat pemilih file sistem (tidak perlu izin penyimpanan).
    private var pendingExport = ""
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        writeExport(r.data?.data)
    }

    // Pengaturan model + key
    private var cfgCurrent: AiModel = Models.ALL[0]
    private val cfgKeys = HashMap<Provider, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomBar = findViewById(R.id.bottomBar)
        navBar = findViewById(R.id.navBar)
        navBar.style = Prefs.navStyle(this)
        navBar.onSelected = { showTab(it) }

        // targetSdk 36 = edge-to-edge dipaksa; kasih padding supaya konten nggak ketiban system bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, if (imeVisible) ime.bottom else 0)
            bottomBar.setPadding(0, 0, 0, if (imeVisible) 0 else bars.bottom)
            bottomBar.visibility = if (imeVisible) View.GONE else View.VISIBLE
            WindowInsetsCompat.CONSUMED
        }

        setupHome()
        setupChat()
        setupLatihan()
        setupSettings()
        historyPage = findViewById(R.id.historyPage)
        historyUi = HistoryUi(this) { showExportDialog() }
        historyUi.attach()

        showTab(0)
        MotionFx.install(findViewById(R.id.root))

        // Password gate is an overlay; unlocking fades it away to reveal this real app menu.
        PasswordCodeGate(this).show()
    }

    /** 0 = Beranda, 1 = Tanya AI, 2 = Latihan, 3 = Riwayat, 4 = Pengaturan. Halaman baru masuk sambil meluncur dan memudar. */
    private fun showTab(tab: Int) {
        val pages = listOf<View>(homeScroll, chatPage, latihanPage, historyPage, settingsPage)
        val old = currentTab
        val d = resources.displayMetrics.density
        for ((i, page) in pages.withIndex()) {
            if (i == tab) {
                if (page.visibility != View.VISIBLE) {
                    page.visibility = View.VISIBLE
                    if (old >= 0 && old != tab) {
                        page.alpha = 0f
                        page.translationX = (if (tab > old) 28 else -28) * d
                        page.animate().alpha(1f).translationX(0f).setDuration(260)
                            .setInterpolator(DecelerateInterpolator(1.8f)).start()
                    }
                }
            } else {
                page.animate().cancel()
                page.alpha = 1f
                page.translationX = 0f
                page.visibility = View.GONE
            }
        }
        currentTab = tab
        navBar.select(tab, true)
        if (tab == 3) historyUi.refresh()
        // Kontrol dinamis (chip/riwayat) ikut mendapat feedback sentuh saat halaman dibuka.
        MotionFx.install(findViewById(R.id.root))
    }

    // ------------------------------------------------------------------ Beranda

    private fun setupHome() {
        homeScroll = findViewById(R.id.homeScroll)
        status = findViewById(R.id.status)
        testResult = findViewById(R.id.testResult)
        modelInfo = findViewById(R.id.modelInfo)
        logView = findViewById(R.id.logView)
        headerIcon = findViewById(R.id.headerIcon)
        headerName = findViewById(R.id.headerName)
        headerSub = findViewById(R.id.headerSub)
        namePreview = findViewById(R.id.namePreview)
        iconRow = findViewById(R.id.iconRow)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
        headerSub.text = if (version.isEmpty()) "Asisten AI untuk belajar" else "AI untuk belajar · v$version"

        findViewById<Button>(R.id.btnConfig).setOnClickListener { showTab(4) }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startAi() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAi() }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        val helpBody = findViewById<TextView>(R.id.helpBody)
        val helpToggle = findViewById<TextView>(R.id.helpToggle)
        helpToggle.setOnClickListener {
            val open = helpBody.visibility != View.VISIBLE
            val d = resources.displayMetrics.density
            if (open) {
                helpBody.visibility = View.VISIBLE
                helpBody.alpha = 0f
                helpBody.translationY = -7f * d
                helpBody.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.7f)).start()
            } else {
                helpBody.animate().alpha(0f).translationY(-6f * d).setDuration(160)
                    .withEndAction {
                        helpBody.visibility = View.GONE
                        helpBody.alpha = 1f
                        helpBody.translationY = 0f
                    }.start()
            }
            helpToggle.text = if (open) "Panduan singkat  ▾" else "Panduan singkat  ▸"
        }

        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { showLog() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Prefs.log(this)))
            Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
        }

        setupLauncherPicker()
    }

    private fun updateModelInfo() {
        modelInfo.text = "Model aktif: ${AiClient.selected(this).label}"
    }

    private fun keyInfo(p: Provider): String =
        if (Secrets.builtIn(p).isNotEmpty()) {
            "Key bawaan app aktif untuk ${p.label}. Kolom di bawah opsional: isi kalau mau pakai key sendiri (misalnya kuota bawaan habis)."
        } else {
            "${p.label} belum punya key bawaan. Buat key gratis di ${p.keyUrl}, lalu tempel di kolom di bawah."
        }

    // ------------------------------------------------------------------ ekspor riwayat

    private fun launchExport(mime: String, name: String, content: String) {
        pendingExport = content
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, name)
        exportLauncher.launch(i)
    }

    private fun writeExport(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Riwayat diekspor", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
        pendingExport = ""
    }

    private fun showExportDialog() {
        val items = Prefs.history(this)
        if (items.isEmpty()) {
            Toast.makeText(this, "Riwayat masih kosong.", Toast.LENGTH_SHORT).show()
            return
        }
        val choices = arrayOf("File CSV (Excel / Sheets)", "File teks (.txt)", "Salin ke clipboard")
        Glass.builder(this)
            .setTitle("Ekspor ${items.size} riwayat")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> launchExport("text/csv", "riwayat-studyoverlayai.csv", HistoryExport.csv(items))
                    1 -> launchExport("text/plain", "riwayat-studyoverlayai.txt", HistoryExport.txt(items))
                    else -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("riwayat", HistoryExport.txt(items)))
                        Toast.makeText(this, "Riwayat disalin", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .showGlass()
    }

    // ------------------------------------------------------------------ Pengaturan

    /** Deretan chip pilihan (satu terpilih). Memilih chip memanggil onPick lalu menggambar ulang. */
    private fun chipGroup(row: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        val d = resources.displayMetrics.density
        labels.forEachIndexed { i, label ->
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 12.5f
            tv.setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            if (i == selected) {
                tv.setTextColor(0xFF5A4BD1.toInt())
                tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                tv.setBackgroundResource(R.drawable.tab_selected)
            } else {
                tv.setTextColor(0xFF8A8DA3.toInt())
            }
            tv.setOnClickListener {
                onPick(i)
                chipGroup(row, labels, i, onPick)
            }
            MotionFx.installOn(tv)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            row.addView(tv, lp)
        }
    }

    /** Pratinjau bubble dengan tema, bentuk, ikon, ukuran, dan kepekatan yang sedang dipilih. */
    private fun updateBubblePreview(scale: Int, alpha: Int) {
        val tv = findViewById<BubbleArtworkTextView>(R.id.bubblePreview)
        val d = resources.displayMetrics.density
        val sc = scale / 100f
        tv.text = BubbleStyle.icon(this)
        tv.textSize = 15f * sc
        tv.minWidth = (54 * d * sc).toInt()
        tv.minHeight = (52 * d * sc).toInt()
        tv.setPadding((10 * d * sc).toInt(), (8 * d * sc).toInt(), (10 * d * sc).toInt(), (8 * d * sc).toInt())
        tv.background = BubbleStyle.background(this, BubbleStyle.color(this))
        tv.elevation = 10f * d
        tv.alpha = alpha / 100f
        tv.showArtwork(true)
    }

    private fun setupSettings() {
        settingsPage = findViewById(R.id.settingsPage)
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            // Kunci kembali layar aplikasi dengan gate yang sama; kode tetap diminta saat dibuka lagi.
            currentFocus?.let { focused ->
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(focused.windowToken, 0)
            }
            PasswordCodeGate(this).show()
        }
        val spinner = findViewById<Spinner>(R.id.cfgModel)
        val key = findViewById<EditText>(R.id.cfgKey)
        val info = findViewById<TextView>(R.id.cfgKeyInfo)
        val accurate = findViewById<android.widget.CheckBox>(R.id.cfgAccurate)
        val fallback = findViewById<android.widget.CheckBox>(R.id.cfgFallback)
        val sizeBar = findViewById<android.widget.SeekBar>(R.id.cfgSize)
        val sizeLabel = findViewById<TextView>(R.id.cfgSizeLabel)
        val alphaBar = findViewById<android.widget.SeekBar>(R.id.cfgAlpha)
        val alphaLabel = findViewById<TextView>(R.id.cfgAlphaLabel)

        // Key pribadi per penyedia, supaya pindah-pindah model tidak menghapus isian.
        for (p in Provider.values()) cfgKeys[p] = Prefs.userKey(this, p)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Models.ALL.map { it.label }
        )
        Glass.bindSpinner(this, spinner, "Pilih model AI")
        cfgCurrent = Models.byId(Prefs.modelId(this))
        spinner.setSelection(Models.ALL.indexOf(cfgCurrent))
        key.setText(cfgKeys[cfgCurrent.provider].orEmpty())
        info.text = keyInfo(cfgCurrent.provider)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                cfgKeys[cfgCurrent.provider] = key.text.toString().trim()
                cfgCurrent = Models.ALL[position]
                key.setText(cfgKeys[cfgCurrent.provider].orEmpty())
                info.text = keyInfo(cfgCurrent.provider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnSaveModel).setOnClickListener {
            cfgKeys[cfgCurrent.provider] = key.text.toString().trim()
            Prefs.setModelId(this, cfgCurrent.id)
            for ((p, k) in cfgKeys) Prefs.setUserKey(this, p, k)
            updateModelInfo()
            Toast.makeText(this, "Model dan key tersimpan", Toast.LENGTH_SHORT).show()
        }

        // Pilihan di bawah ini langsung tersimpan begitu diubah.
        accurate.isChecked = Prefs.accurate(this)
        fallback.isChecked = Prefs.fallback(this)
        accurate.setOnCheckedChangeListener { _, on -> Prefs.setAccurate(this, on) }
        fallback.setOnCheckedChangeListener { _, on -> Prefs.setFallback(this, on) }

        // Ukuran 60..160% (progress 0..100), kepekatan 30..100% (progress 0..70).
        sizeBar.progress = Prefs.bubbleScale(this) - 60
        alphaBar.progress = Prefs.bubbleAlpha(this) - 30
        sizeLabel.text = "Ukuran bubble: ${Prefs.bubbleScale(this)}%"
        alphaLabel.text = "Kepekatan bubble: ${Prefs.bubbleAlpha(this)}%"
        sizeBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                sizeLabel.text = "Ukuran bubble: ${p + 60}%"
                updateBubblePreview(p + 60, alphaBar.progress + 30)
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                Prefs.setBubbleScale(this@MainActivity, (sb?.progress ?: 40) + 60)
                OverlayService.instance?.refreshLook()
            }
        })
        alphaBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                alphaLabel.text = "Kepekatan bubble: ${p + 30}%"
                updateBubblePreview(sizeBar.progress + 60, p + 30)
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                Prefs.setBubbleAlpha(this@MainActivity, (sb?.progress ?: 70) + 30)
                OverlayService.instance?.refreshLook()
            }
        })

        // Auto-eskalasi dan cache soal.
        val escalate = findViewById<android.widget.CheckBox>(R.id.cfgEscalate)
        val cacheBox = findViewById<android.widget.CheckBox>(R.id.cfgCache)
        escalate.isChecked = Prefs.autoEscalate(this)
        cacheBox.isChecked = Prefs.cacheOn(this)
        escalate.setOnCheckedChangeListener { _, on -> Prefs.setAutoEscalate(this, on) }
        cacheBox.setOnCheckedChangeListener { _, on -> Prefs.setCacheOn(this, on) }

        // Gaya menu bawah.
        chipGroup(findViewById(R.id.navStyleRow), listOf("Gelap + glow", "Bulat melayang", "Tetes cair", "Liquid glass"), Prefs.navStyle(this)) {
            Prefs.setNavStyle(this, it)
            navBar.style = it
        }

        // Tema, bentuk, ikon, tempel ke tepi, auto-minimize.
        val snap = findViewById<android.widget.CheckBox>(R.id.cfgSnap)
        val autoMin = findViewById<android.widget.CheckBox>(R.id.cfgAutoMin)
        snap.isChecked = Prefs.snapEdge(this)
        autoMin.isChecked = Prefs.autoMinimize(this)
        snap.setOnCheckedChangeListener { _, on ->
            Prefs.setSnapEdge(this, on)
            OverlayService.instance?.refreshLook()
        }
        autoMin.setOnCheckedChangeListener { _, on ->
            Prefs.setAutoMinimize(this, on)
            OverlayService.instance?.refreshLook()
        }
        val applyLook = {
            updateBubblePreview(sizeBar.progress + 60, alphaBar.progress + 30)
            OverlayService.instance?.refreshLook()
        }
        chipGroup(findViewById(R.id.bubbleThemeRow), BubbleStyle.THEMES.map { it.name }, Prefs.bubbleTheme(this)) {
            Prefs.setBubbleTheme(this, it)
            applyLook()
        }
        chipGroup(findViewById(R.id.bubbleShapeRow), BubbleStyle.SHAPES, Prefs.bubbleShape(this)) {
            Prefs.setBubbleShape(this, it)
            applyLook()
        }
        chipGroup(findViewById(R.id.bubbleIconRow), BubbleStyle.ICON_NAMES, Prefs.bubbleIcon(this)) {
            Prefs.setBubbleIcon(this, it)
            applyLook()
        }
        updateBubblePreview(sizeBar.progress + 60, alphaBar.progress + 30)
    }

    private fun testConnection() {
        val sel = AiClient.selected(this)
        testResult.visibility = View.VISIBLE
        if (AiClient.activeKey(this, sel).isEmpty()) {
            testResult.text = "${sel.provider.label} belum punya key. Buka menu Pengaturan dan isi key-nya, atau pilih model Gemini."
            return
        }
        testResult.text = "Mengetes ${sel.label}…"
        executor.execute {
            val result = try {
                AiClient.ask(this, "Balas hanya dengan satu kata: siap", "Jawab sesingkat mungkin.")
            } catch (e: Exception) {
                "Gagal terhubung: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { if (!isDestroyed) testResult.text = "Hasil: $result" }
        }
    }

    /** Start AI: munculkan bubble. Butuh layanan Aksesibilitas yang sudah diaktifkan. */
    private fun startAi() {
        if (OverlayService.testLock) {
            Toast.makeText(this, "Bubble AI dikunci selama latihan berjalan.", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setBubbleOn(this, true)
        val svc = OverlayService.instance
        when {
            svc != null -> {
                svc.startBubble()
                Toast.makeText(this, "Bubble AI dimulai.", Toast.LENGTH_SHORT).show()
            }
            isServiceEnabled() ->
                Toast.makeText(this, "Layanan sedang menyala, coba tekan Start AI lagi sebentar.", Toast.LENGTH_LONG).show()
            else -> {
                Toast.makeText(
                    this,
                    "Aktifkan Assistant di pengaturan Aksesibilitas dulu.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        refreshStatus()
    }

    /** Stop AI: bubble ditutup sepenuhnya (dihapus dari layar), bukan disembunyikan. */
    private fun stopAi() {
        Prefs.setBubbleOn(this, false)
        OverlayService.instance?.stopBubble()
        Toast.makeText(this, "Bubble AI dihentikan.", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isServiceEnabled()
        val bubbleOn = Prefs.bubbleOn(this)
        when {
            !enabled -> {
                status.text = "● Layanan belum aktif"
                status.setBackgroundResource(R.drawable.chip_off)
                status.setTextColor(0xFFB45F06.toInt())
            }
            bubbleOn -> {
                status.text = "● Bubble AI aktif"
                status.setBackgroundResource(R.drawable.chip_on)
                status.setTextColor(0xFF0B7A55.toInt())
            }
            else -> {
                status.text = "● Bubble AI dihentikan"
                status.setBackgroundResource(R.drawable.chip_idle)
                status.setTextColor(0xFF555A70.toInt())
            }
        }
    }

    private fun showLog() {
        logView.text = Prefs.log(this).ifBlank { "(belum ada log)" }
    }

    override fun onResume() {
        super.onResume()
        showLog()
        refreshStatus()
        updateHeader()
        updateModelInfo()
    }

    // ------------------------------------------------------------------ ganti nama + ikon app (beneran, via activity-alias)

    private fun aliasComponent(n: Int, c: Int) =
        ComponentName(packageName, "$packageName.Launcher_n${n}_c${c}")

    private fun isAliasEnabled(n: Int, c: Int): Boolean {
        val st = packageManager.getComponentEnabledSetting(aliasComponent(n, c))
        return st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && n == 0 && c == 0)
    }

    private fun currentLauncher(): Pair<Int, Int> {
        for (n in NAMES.indices) for (c in ICONS.indices) {
            if (isAliasEnabled(n, c)) return n to c
        }
        return 0 to 0
    }

    private fun setupLauncherPicker() {
        val cur = currentLauncher()
        selName = cur.first
        selIcon = cur.second

        findViewById<Button>(R.id.btnApplyLauncher).setOnClickListener { applyLauncher() }
        renderLauncherPreview()
        updateHeader()
    }

    private fun renderLauncherPreview() {
        namePreview.setImageResource(ICONS[selIcon])

        iconRow.removeAllViews()
        val d = resources.displayMetrics.density
        for (c in ICONS.indices) {
            val iv = ImageView(this)
            val sz = (56 * d).toInt()
            val lp = LinearLayout.LayoutParams(sz, sz)
            lp.marginEnd = (8 * d).toInt()
            iv.layoutParams = lp
            val pad = (4 * d).toInt()
            iv.setPadding(pad, pad, pad, pad)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            iv.setImageResource(ICONS[c])
            if (c == selIcon) iv.setBackgroundResource(R.drawable.swatch_selected)
            iv.setOnClickListener {
                selIcon = c
                renderLauncherPreview()
            }
            MotionFx.installOn(iv)
            iconRow.addView(iv)
        }
    }

    /** Aktifkan alias pilihan, lalu matikan alias lain yang masih aktif. Hanya satu ikon yang tersisa di launcher. */
    private fun applyLauncher() {
        try {
            val pm = packageManager
            pm.setComponentEnabledSetting(
                aliasComponent(0, selIcon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            for (n in NAMES.indices) for (c in ICONS.indices) {
                if (n == selName && c == selIcon) continue
                if (isAliasEnabled(n, c)) {
                    pm.setComponentEnabledSetting(
                        aliasComponent(n, c),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
            Toast.makeText(
                this,
                "Diterapkan. Ikon di layar utama bisa butuh beberapa detik untuk berubah.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menerapkan: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
        updateHeader()
    }

    private fun updateHeader() {
        val cur = currentLauncher()
        headerName.text = NAMES[cur.first]
        headerIcon.setImageResource(ICONS[cur.second])
    }

    // ------------------------------------------------------------------ Tanya AI

    private fun setupChat() {
        chatPage = findViewById(R.id.chatPage)
        chatScroll = findViewById(R.id.chatScroll)
        chatList = findViewById(R.id.chatList)
        chatEmpty = findViewById(R.id.chatEmpty)
        chatInput = findViewById(R.id.chatInput)
        btnSend = findViewById(R.id.btnSend)
        attachRow = findViewById(R.id.attachRow)
        attachThumb = findViewById(R.id.attachThumb)

        btnSend.setOnClickListener { sendChat() }
        findViewById<TextView>(R.id.btnVoice).setOnClickListener { startVoiceInput() }
        findViewById<TextView>(R.id.btnAttach).setOnClickListener { pickChatImage.launch("image/*") }
        findViewById<TextView>(R.id.btnRemoveAttach).setOnClickListener { clearAttachment() }
        findViewById<TextView>(R.id.btnNewChat).setOnClickListener { newChat() }
        findViewById<TextView>(R.id.btnHelp).setOnClickListener { showChatHelp() }

        // Chip command di atas kolom ketik: ketuk untuk menyisipkan command.
        val row = findViewById<LinearLayout>(R.id.cmdRow)
        val d = resources.displayMetrics.density
        for (spec in ChatCommands.ALL) {
            val tv = TextView(this)
            tv.text = spec.chip
            tv.textSize = 12.5f
            tv.setTextColor(0xFF5A4BD1.toInt())
            tv.setBackgroundResource(R.drawable.tab_selected)
            tv.setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            tv.setOnClickListener { onCommandChip(spec) }
            MotionFx.installOn(tv)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            row.addView(tv, lp)
        }
    }

    private fun startVoiceInput() {
        if (chatBusy) {
            Toast.makeText(this, "Tunggu jawaban AI selesai dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ucapkan pertanyaan untuk AI")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceInput.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Fitur pengenalan suara tidak tersedia. Instal/aktifkan layanan pengenalan suara di perangkat.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka mikrofon: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun newChat() {
        chatHistory.clear()
        chatList.removeAllViews()
        chatEmpty.visibility = View.VISIBLE
        clearAttachment()
    }

    private fun onCommandChip(spec: ChatCommands.Spec) {
        when (spec.key) {
            "h" -> showChatHelp()
            "n" -> newChat()
            else -> {
                val cur = chatInput.text.toString()
                val body = if (cur.startsWith("/")) cur.substringAfter(' ', "") else cur
                chatInput.setText("/${spec.key} $body")
                chatInput.setSelection(chatInput.text.length)
                chatInput.requestFocus()
            }
        }
    }

    private fun showChatHelp() {
        val d = resources.displayMetrics.density
        val body = TextView(this).apply {
            text = ChatCommands.helpText()
            setTextColor(0xFF1B1B2F.toInt())
            textSize = 13.5f
            setPadding((22 * d).toInt(), (14 * d).toInt(), (22 * d).toInt(), (6 * d).toInt())
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        Glass.builder(this)
            .setTitle("Bantuan Tanya AI")
            .setView(scroll)
            .setPositiveButton("Tutup", null)
            .showGlass()
    }

    private fun attachImage(uri: Uri) {
        executor.execute {
            val loaded = try {
                ImageUtil.loadForChat(contentResolver, uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (loaded == null) {
                    Toast.makeText(this, "Gagal membuka gambar.", Toast.LENGTH_SHORT).show()
                } else {
                    // Foto hanya dilampirkan, belum terkirim: tulis caption atau command dulu, lalu tekan Kirim.
                    pendingImage = loaded
                    attachThumb.setImageBitmap(loaded.bitmap)
                    attachRow.visibility = View.VISIBLE
                    chatInput.hint = "Caption atau command (misal /e), lalu Kirim"
                    chatInput.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(chatInput, 0)
                }
            }
        }
    }

    private fun clearAttachment() {
        pendingImage = null
        attachRow.visibility = View.GONE
        chatInput.hint = "Tulis pertanyaan atau command (/h bantuan)…"
    }

    private fun addMessage(text: String, fromUser: Boolean): TextView {
        val d = resources.displayMetrics.density
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14.5f
        tv.setTextIsSelectable(true)
        tv.setTextColor(if (fromUser) 0xFFFFFFFF.toInt() else 0xFF1B1B2F.toInt())
        tv.setBackgroundResource(if (fromUser) R.drawable.msg_user else R.drawable.msg_ai)
        tv.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = if (fromUser) Gravity.END else Gravity.START
        lp.topMargin = (8 * d).toInt()
        chatList.addView(tv, lp)
        chatEmpty.visibility = View.GONE
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun addImageMessage(bitmap: android.graphics.Bitmap) {
        val d = resources.displayMetrics.density
        val iv = ImageView(this)
        iv.setImageBitmap(bitmap)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.maxWidth = (220 * d).toInt()
        iv.maxHeight = (220 * d).toInt()
        iv.setBackgroundResource(R.drawable.msg_user)
        val pad = (4 * d).toInt()
        iv.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.END
        lp.topMargin = (8 * d).toInt()
        chatList.addView(iv, lp)
        chatEmpty.visibility = View.GONE
    }

    private fun tidy(t: String): String =
        t.replace("**", "")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\*\\s+"), "• ")
            .trim()

    private fun sendChat() {
        if (OverlayService.testLock) {
            Toast.makeText(this, "Tanya AI dikunci selama latihan berjalan.", Toast.LENGTH_SHORT).show()
            return
        }
        val typed = chatInput.text.toString().trim()
        val image = pendingImage
        if ((typed.isEmpty() && image == null) || chatBusy) return

        // Command slash (/a /e /r /q /t /s /c /h /n). Null = pesan biasa.
        val cmd = ChatCommands.parse(typed)
        if (cmd != null) {
            when (cmd.key) {
                "h" -> {
                    chatInput.setText("")
                    showChatHelp()
                    return
                }
                "n" -> {
                    chatInput.setText("")
                    newChat()
                    return
                }
                "?" -> {
                    Toast.makeText(this, "Command ${cmd.name} tidak dikenal. Ketik /h untuk bantuan.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            if (cmd.body.isEmpty() && image == null) {
                Toast.makeText(
                    this,
                    "Tulis soal atau materinya setelah ${cmd.name}, atau lampirkan foto. Ketik /h untuk contoh.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val sel = AiClient.selected(this)
        if (AiClient.activeKey(this, sel).isEmpty()) {
            Toast.makeText(this, "${sel.provider.label} belum punya key. Isi di Pengaturan atau pilih model Gemini.", Toast.LENGTH_LONG).show()
            showTab(4)
            return
        }

        // Kalau cuma kirim gambar (atau command tanpa teks), AI membahas isi gambarnya.
        val question = when {
            cmd != null -> cmd.body.ifEmpty { ChatCommands.defaultPrompt(cmd.key) }
            else -> typed.ifEmpty { ChatCommands.defaultPrompt("") }
        }
        val instructions = if (cmd != null) {
            GeminiClient.CHAT_INSTRUCTIONS + "\n\n" + ChatCommands.modeInstructions(cmd)
        } else {
            GeminiClient.CHAT_INSTRUCTIONS
        }
        val cmdBody = cmd?.body.orEmpty()
        val histQuestion = when {
            cmd == null -> question
            cmdBody.isEmpty() -> "Foto soal"
            image != null -> cmdBody
            else -> cmdBody
        }.take(120)
        val record = cmd != null && (cmd.key == "a" || cmd.key == "e")

        chatInput.setText("")
        if (image != null) addImageMessage(image.bitmap)
        if (typed.isNotEmpty()) addMessage(typed, true)
        clearAttachment()
        chatHistory.add(ChatTurn("user", question, image?.base64))
        val pending = addMessage("Mengetik…", false)
        chatBusy = true
        btnSend.isEnabled = false

        // Batasi riwayat yang dikirim, dan pastikan dimulai dari giliran user.
        val snapshot = chatHistory.takeLast(24).dropWhile { it.role != "user" }

        executor.execute {
            var ok = true
            val reply = try {
                tidy(AiClient.chat(this, snapshot, instructions))
            } catch (e: GeminiException) {
                ok = false
                e.message.orEmpty()
            } catch (e: Exception) {
                ok = false
                "Gagal terhubung: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                pending.text = reply
                if (ok) {
                    chatHistory.add(ChatTurn("model", reply))
                    if (record) {
                        Prefs.addHistory(
                            this,
                            Prefs.HistoryItem(
                                System.currentTimeMillis(), histQuestion, reply.take(300), "", AiClient.selected(this).label, "chat"
                            )
                        )
                    }
                } else if (chatHistory.isNotEmpty()) {
                    // Gagal: buang pertanyaan terakhir dari riwayat supaya bisa dicoba lagi tanpa dobel.
                    chatHistory.removeAt(chatHistory.size - 1)
                }
                chatBusy = false
                btnSend.isEnabled = true
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // ------------------------------------------------------------------ Latihan (form)

    private fun setupLatihan() {
        latihanPage = findViewById(R.id.latihanPage)
        val spKelas = findViewById<Spinner>(R.id.spKelas)
        val spMapel = findViewById<Spinner>(R.id.spMapel)
        val spLevel = findViewById<Spinner>(R.id.spLevel)
        val spLimit = findViewById<Spinner>(R.id.spLimit)
        val etMapelLain = findViewById<EditText>(R.id.etMapelLain)
        val etPg = findViewById<EditText>(R.id.etPg)
        val etEssay = findViewById<EditText>(R.id.etEssay)
        val etMinutes = findViewById<EditText>(R.id.etMinutes)
        val rgMode = findViewById<RadioGroup>(R.id.rgTestMode)
        val pinOptions = findViewById<View>(R.id.pinOptions)

        fun adapter(items: List<String>) =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

        Glass.bindSpinner(this, spKelas, "Pilih kelas")
        Glass.bindSpinner(this, spMapel, "Pilih mata pelajaran")
        Glass.bindSpinner(this, spLevel, "Pilih tingkat kesulitan")
        Glass.bindSpinner(this, spLimit, "Batas pelanggaran")
        spKelas.adapter = adapter(Curriculum.KELAS)
        spKelas.setSelection(Prefs.kelasIndex(this).coerceIn(0, Curriculum.KELAS.size - 1))
        spMapel.adapter = adapter(Curriculum.mapel(spKelas.selectedItemPosition))
        spKelas.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                spMapel.adapter = adapter(Curriculum.mapel(position))
                Prefs.setKelasIndex(this@MainActivity, position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spLevel.adapter = adapter(Curriculum.LEVELS)
        spLimit.adapter = adapter(Curriculum.LIMITS.map { if (it == 0) "0 (langsung submit)" else it.toString() })
        spLimit.setSelection(Curriculum.LIMITS.indexOf(3))

        rgMode.setOnCheckedChangeListener { _, id ->
            pinOptions.visibility = if (id == R.id.rbPinned) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnStartTest).setOnClickListener {
            val pg = etPg.text.toString().toIntOrNull() ?: 0
            val essay = etEssay.text.toString().toIntOrNull() ?: 0
            val minutes = etMinutes.text.toString().toIntOrNull() ?: 0
            val msg = when {
                pg < 0 || essay < 0 || minutes < 0 -> "Angka tidak boleh negatif."
                pg + essay == 0 -> "Isi minimal 1 soal (pilihan ganda atau essay)."
                pg > 40 -> "Pilihan ganda maksimal 40 soal."
                essay > 15 -> "Essay maksimal 15 soal."
                minutes > 300 -> "Waktu maksimal 300 menit."
                OverlayService.testLock -> "Masih ada sesi latihan yang berjalan."
                AiClient.activeKey(this).isEmpty() ->
                    "${AiClient.selected(this).provider.label} belum punya key. Buka menu Pengaturan."
                else -> null
            }
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val mapel = etMapelLain.text.toString().trim().ifEmpty { spMapel.selectedItem as String }
            val pinned = rgMode.checkedRadioButtonId == R.id.rbPinned
            startActivity(
                Intent(this, TestActivity::class.java)
                    .putExtra(TestActivity.EXTRA_KELAS, spKelas.selectedItem as String)
                    .putExtra(TestActivity.EXTRA_MAPEL, mapel)
                    .putExtra(TestActivity.EXTRA_LEVEL, spLevel.selectedItem as String)
                    .putExtra(TestActivity.EXTRA_PG, pg)
                    .putExtra(TestActivity.EXTRA_ESSAY, essay)
                    .putExtra(TestActivity.EXTRA_MINUTES, minutes)
                    .putExtra(TestActivity.EXTRA_PINNED, pinned)
                    .putExtra(
                        TestActivity.EXTRA_LIMIT,
                        Curriculum.LIMITS[spLimit.selectedItemPosition.coerceIn(0, Curriculum.LIMITS.size - 1)]
                    )
            )
        }
    }

    // ------------------------------------------------------------------ util

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, OverlayService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) ||
                it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /** Urutan harus sama dengan activity-alias Launcher_n{i}_c{j} di AndroidManifest.xml. */
        val NAMES = arrayOf("Assistant")

        private val ICONS = intArrayOf(
            R.mipmap.ic_icon0, R.mipmap.ic_icon1, R.mipmap.ic_icon2,
            R.mipmap.ic_icon3, R.mipmap.ic_icon4, R.mipmap.ic_icon5
        )
    }
}
