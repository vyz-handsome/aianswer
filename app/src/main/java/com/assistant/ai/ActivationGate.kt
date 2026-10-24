package com.assistant.ai

import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Layar aktivasi app owner. App owner tidak bisa dipakai sebelum diaktivasi dengan kode dari bot Telegram
 * (kode hanya dikirim ke akun owner), dan aktivasi terikat ke satu HP.
 */
internal class ActivationGate(
    private val activity: AppCompatActivity,
    private val notice: String?,
    private val onActivated: () -> Unit
) {
    private lateinit var overlay: FrameLayout
    private lateinit var message: TextView
    private lateinit var button: Button
    private var busy = false
    private var done = false

    fun isShowing(): Boolean = ::overlay.isInitialized && overlay.parent != null && !done

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun show() {
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        overlay.addView(AuroraBackground(activity), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val centered = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        scroll.addView(centered, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        overlay.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(26), dp(24), dp(22))
            background = GlassDrawable(
                floatArrayOf(32f, 32f, 32f, 32f), 0x99FFFFFF.toInt(), 0x73FFFFFF, 0, 0, true
            )
        }
        val width = minOf(dp(400), activity.resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(dp(260))
        centered.addView(panel, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))

        val orb = FrameLayout(activity).apply { background = GlassPillDrawable() }
        orb.addView(PasswordCodeGate.LockGlyphView(activity), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        panel.addView(orb, LinearLayout.LayoutParams(dp(70), dp(70)).apply { bottomMargin = dp(15) })

        panel.addView(TextView(activity).apply {
            text = "Aktivasi Owner"
            setTextColor(0xFF1C2230.toInt())
            textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        panel.addView(TextView(activity).apply {
            text = "App ini hanya bisa dipakai di HP owner. Kirim /pair ke bot Telegram, lalu tempel seluruh teks balasannya di sini."
            setTextColor(0xFF737D92.toInt())
            textSize = 13.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(14) })

        val input = EditText(activity, null, 0, R.style.Input).apply {
            hint = "ASSISTANT-PAIR|..."
            setSingleLine(false)
            maxLines = 4
        }
        panel.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        message = TextView(activity).apply {
            text = notice.orEmpty()
            setTextColor(0xFFC83F59.toInt())
            textSize = 12.5f
            gravity = Gravity.CENTER
        }
        panel.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        button = Button(activity, null, 0, R.style.Btn_Primary).apply {
            text = "Aktifkan"
            setOnClickListener {
                if (busy) return@setOnClickListener
                val text = input.text.toString()
                if (text.isBlank()) {
                    message.text = "Tempel teks dari bot dulu."
                    return@setOnClickListener
                }
                busy = true
                isEnabled = false
                message.setTextColor(0xFF737D92.toInt())
                message.text = "Mengaktifkan..."
                Thread {
                    val err = Remote.ownerPair(activity, text, Build.MODEL.orEmpty())
                    activity.runOnUiThread {
                        busy = false
                        button.isEnabled = true
                        if (err == null) finish() else {
                            message.setTextColor(0xFFC83F59.toInt())
                            message.text = err
                        }
                    }
                }.start()
            }
        }
        panel.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        host.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun finish() {
        if (done) return
        done = true
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        onActivated()
    }
}
