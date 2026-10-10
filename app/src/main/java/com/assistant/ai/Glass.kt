package com.assistant.ai

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Popup bergaya kaca (liquid glass): kartu bening dengan tepi bercahaya, latar belakang dikaburkan (Android 12+),
 * dan animasi muncul/hilang yang dijalankan sistem lewat animasi jendela (tanpa hitungan per frame di app),
 * jadi ringan untuk GPU.
 */
object Glass {

    /** Blur latar hanya dipakai kalau OS mendukung dan pengguna tidak mematikannya (misal lewat penghemat baterai). */
    fun blurOn(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
        } catch (e: Exception) {
            false
        }
    }

    fun builder(ctx: Context): AlertDialog.Builder = AlertDialog.Builder(ctx, R.style.GlassDialog)

    /** Pasang blur latar dan kaca bening ke dialog sebelum ditampilkan. */
    fun prepare(dlg: Dialog) {
        val w = dlg.window ?: return
        val ctx = dlg.context
        if (blurOn(ctx)) {
            w.setBackgroundDrawableResource(R.drawable.glass_bg_blur)
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val attrs = w.attributes
            // Radius tetap (tidak dianimasikan) supaya biaya GPU kecil.
            attrs.blurBehindRadius = (14 * ctx.resources.displayMetrics.density).toInt()
            w.attributes = attrs
            w.setDimAmount(0.10f)
        }
    }

    fun show(b: AlertDialog.Builder): AlertDialog {
        val dlg = b.create()
        prepare(dlg)
        dlg.show()
        return dlg
    }

    /** Daftar pilihan bergaya kaca (pengganti dropdown Spinner). */
    fun pick(act: Activity, title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val d = act.resources.displayMetrics.density
        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        val scroll = ScrollView(act)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)

        val sel = TypedValue()
        act.theme.resolveAttribute(android.R.attr.selectableItemBackground, sel, true)
        var dialog: AlertDialog? = null

        labels.forEachIndexed { i, label ->
            val row = TextView(act)
            row.textSize = 15f
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            if (i == selected) {
                row.text = "$label   ✓"
                row.setTextColor(0xFF4A3DB8.toInt())
                row.setTypeface(row.typeface, android.graphics.Typeface.BOLD)
                row.setBackgroundResource(R.drawable.glass_row_selected)
            } else {
                row.text = label
                row.setTextColor(0xFF1B1B2F.toInt())
                row.setBackgroundResource(sel.resourceId)
            }
            row.setOnClickListener {
                onPick(i)
                dialog?.dismiss()
            }
            MotionFx.installOn(row)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (2 * d).toInt()
            list.addView(row, lp)
        }

        dialog = show(builder(act).setTitle(title).setView(scroll))
        // Gulir ke pilihan yang sedang aktif supaya langsung kelihatan.
        scroll.post {
            val target = list.getChildAt(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
            if (target != null) scroll.scrollTo(0, (target.top - 2 * target.height).coerceAtLeast(0))
        }
    }

    /** Spinner tetap dipakai untuk menyimpan pilihan, tapi dropdown-nya diganti pemilih kaca. */
    fun bindSpinner(act: Activity, spinner: Spinner, title: String) {
        spinner.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                val ad = spinner.adapter
                if (ad != null && ad.count > 0) {
                    val labels = (0 until ad.count).map { ad.getItem(it).toString() }
                    pick(act, title, labels, spinner.selectedItemPosition) { spinner.setSelection(it) }
                }
            }
            true
        }
    }
}

/** Tampilkan builder sebagai dialog kaca. */
fun AlertDialog.Builder.showGlass(): AlertDialog = Glass.show(this)
