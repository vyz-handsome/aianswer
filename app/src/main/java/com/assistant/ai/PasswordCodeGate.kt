package com.assistant.ai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min

/** White Liquid Glass password gate. It overlays the real app; successful unlock only fades this view out. */
internal class PasswordCodeGate(private val activity: AppCompatActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val entered = StringBuilder(4)
    private val dots = mutableListOf<View>()
    private lateinit var overlay: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var message: TextView
    private lateinit var keypad: LinearLayout
    private var unlocking = false
    private var resetRunnable: Runnable? = null

    fun show() {
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(250, 252, 255), Color.rgb(239, 243, 251), Color.rgb(250, 251, 255))
            )
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_UP || unlocking) return@setOnKeyListener false
                when (keyCode) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        addDigit((keyCode - KeyEvent.KEYCODE_0).toString()); true
                    }
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> { deleteOne(); true }
                    KeyEvent.KEYCODE_ESCAPE -> { clearCode(); true }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { checkCode(); true }
                    else -> false
                }
            }
        }
        addSoftGlow(overlay, 250, 0x3CAFCBFF, Gravity.TOP or Gravity.START, -95, -100)
        addSoftGlow(overlay, 245, 0x32D7BFFF, Gravity.BOTTOM or Gravity.END, -100, -92)
        addSoftGlow(overlay, 210, 0x30C9B8FF, Gravity.TOP or Gravity.END, -112, 125)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
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

        val stageWidth = min(dp(410), activity.resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(dp(280))
        val stage = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        centered.addView(stage, LinearLayout.LayoutParams(stageWidth, ViewGroup.LayoutParams.WRAP_CONTENT))

        val topLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val statusDot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(70, 189, 145))
            }
            elevation = dp(3).toFloat()
        }
        topLine.addView(statusDot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(8) })
        val statusText = TextView(activity).apply {
            text = "SECURE ACCESS"
            setTextColor(0xA6424F67.toInt())
            textSize = 10f
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        topLine.addView(statusText)
        stage.addView(topLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(15) })

        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(23), dp(22), dp(19))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF8FFFFFF.toInt(), 0xECFFFFFF.toInt(), 0xF4FFFFFF.toInt())
            ).apply {
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), 0xFFFDFEFF.toInt())
            }
            elevation = dp(16).toFloat()
            clipToOutline = true
        }
        stage.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val orb = FrameLayout(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.WHITE, 0xFFE0E9FA.toInt())
            ).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            elevation = dp(5).toFloat()
        }
        orb.addView(LockGlyphView(activity), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        panel.addView(orb, LinearLayout.LayoutParams(dp(70), dp(70)).apply { bottomMargin = dp(15) })

        val title = TextView(activity).apply {
            text = "Password Code"
            setTextColor(0xFF1C2230.toInt())
            textSize = 28f
            letterSpacing = -0.035f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        panel.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val subtitle = TextView(activity).apply {
            text = "Masukkan kode 4 digit untuk melanjutkan."
            setTextColor(0xFF737D92.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        panel.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(17) })

        val pinRow = LinearLayout(activity).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        repeat(4) {
            val dot = View(activity).apply { background = dotBackground(false) }
            dots.add(dot)
            pinRow.addView(dot, LinearLayout.LayoutParams(dp(13), dp(13)).apply {
                leftMargin = dp(7); rightMargin = dp(7)
            })
        }
        panel.addView(pinRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        message = TextView(activity).apply {
            text = "Masukkan kode akses kamu"
            setTextColor(0xFF788197.toInt())
            textSize = 11.5f
            gravity = Gravity.CENTER
            minHeight = dp(27)
        }
        panel.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(7) })

        keypad = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val keyRows = listOf(
            listOf(Key("1", ""), Key("2", "ABC"), Key("3", "DEF")),
            listOf(Key("4", "GHI"), Key("5", "JKL"), Key("6", "MNO")),
            listOf(Key("7", "PQRS"), Key("8", "TUV"), Key("9", "WXYZ")),
            listOf(Key("CLEAR", "", KeyAction.CLEAR), Key("0", ""), Key("⌫", "", KeyAction.DELETE))
        )
        keyRows.forEach { rowData ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowData.forEach { key -> row.addView(makeKey(key), LinearLayout.LayoutParams(0, dp(53), 1f).apply {
                leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(4); bottomMargin = dp(4)
            }) }
            keypad.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        panel.addView(keypad, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val hint = TextView(activity).apply {
            text = "◈   Akses pribadi   ·   Kode 4 digit"
            setTextColor(0x8A39445E.toInt())
            textSize = 10.5f
            gravity = Gravity.CENTER
        }
        panel.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        host.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        overlay.requestFocus()
    }

    private data class Key(val label: String, val letters: String, val action: KeyAction? = null)
    private enum class KeyAction { CLEAR, DELETE }

    private fun makeKey(key: Key): View {
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = keyBackground(key.action != null)
            contentDescription = when (key.action) {
                KeyAction.CLEAR -> "Hapus semua digit"
                KeyAction.DELETE -> "Hapus satu digit"
                null -> key.label
            }
        }
        val primary = TextView(activity).apply {
            text = key.label
            gravity = Gravity.CENTER
            setTextColor(if (key.action == null) 0xFF252D3E.toInt() else 0xB83B4861.toInt())
            textSize = if (key.action == null) 23f else if (key.action == KeyAction.CLEAR) 10f else 25f
            typeface = if (key.action == null) Typeface.create("sans-serif", Typeface.NORMAL) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (key.action == KeyAction.CLEAR) letterSpacing = 0.06f
        }
        cell.addView(primary)
        if (key.letters.isNotEmpty()) {
            cell.addView(TextView(activity).apply {
                text = key.letters
                gravity = Gravity.CENTER
                setTextColor(0x7A3A465F.toInt())
                textSize = 7.5f
                letterSpacing = 0.15f
            })
        }
        cell.setOnClickListener {
            if (unlocking) return@setOnClickListener
            when (key.action) {
                KeyAction.CLEAR -> clearCode()
                KeyAction.DELETE -> deleteOne()
                null -> if (key.label.length == 1 && key.label[0].isDigit()) addDigit(key.label)
            }
        }
        return cell
    }

    private fun addDigit(digit: String) {
        if (unlocking || entered.length >= 4) return
        panel.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        resetMessage()
        entered.append(digit)
        updateDots()
        if (entered.length == 4) handler.postDelayed({ checkCode() }, 160L)
    }

    private fun deleteOne() {
        if (unlocking || entered.isEmpty()) return
        resetMessage()
        entered.deleteCharAt(entered.length - 1)
        updateDots()
    }

    private fun clearCode() {
        if (unlocking) return
        entered.clear()
        resetMessage()
        updateDots()
    }

    private fun updateDots() {
        dots.forEachIndexed { index, dot ->
            dot.background = dotBackground(index < entered.length)
            dot.animate().cancel()
            dot.scaleX = 1f
            dot.scaleY = 1f
            if (index < entered.length) {
                dot.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100L).withEndAction {
                    dot.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                }.start()
            }
        }
    }

    private fun checkCode() {
        if (unlocking || entered.length != 4) return
        if (entered.toString() == "2580") {
            unlock()
            return
        }
        message.text = "Kode tidak benar. Coba lagi."
        message.setTextColor(0xFFC83F59.toInt())
        ObjectAnimator.ofFloat(panel, "translationX", 0f, -dp(6).toFloat(), dp(6).toFloat(), -dp(4).toFloat(), dp(4).toFloat(), 0f)
            .setDuration(340L).start()
        resetRunnable?.let { handler.removeCallbacks(it) }
        resetRunnable = Runnable {
            if (!unlocking) {
                entered.clear()
                updateDots()
                resetMessage()
            }
        }
        handler.postDelayed(resetRunnable!!, 700L)
    }

    private fun resetMessage() {
        message.text = "Masukkan kode akses kamu"
        message.setTextColor(0xFF788197.toInt())
    }

    private fun unlock() {
        if (unlocking) return
        unlocking = true
        keypad.isEnabled = false
        message.text = "Akses berhasil"
        message.setTextColor(0xFF2B9A78.toInt())
        // Only the password layer fades away; the app's actual MainActivity content is already underneath.
        panel.animate().alpha(0f).translationY(-dp(13).toFloat()).scaleX(0.975f).scaleY(0.975f)
            .setDuration(460L).setInterpolator(DecelerateInterpolator(1.7f)).start()
        overlay.animate().alpha(0f).setDuration(460L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                }
            }).start()
    }

    private fun dotBackground(filled: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (filled) intArrayOf(0xFF9BBFFF.toInt(), 0xFFC5B4FF.toInt())
        else intArrayOf(0x1A66789C, 0x1A66789C)
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(1), if (filled) 0xB8859EDB.toInt() else 0x4060749A)
    }

    private fun keyBackground(utility: Boolean): RippleDrawable {
        val shape = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (utility) intArrayOf(0xF5FFFFFF.toInt(), 0xDDEAF7FA.toInt())
            else intArrayOf(0xF5FFFFFF.toInt(), 0xDDE9EEF9.toInt())
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0xFFFDFEFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(0x55AEC7F5), shape, null)
    }

    private fun addSoftGlow(host: FrameLayout, sizeDp: Int, color: Int, gravity: Int, xDp: Int, yDp: Int) {
        val glow = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color, Color.TRANSPARENT)
            ).apply { shape = GradientDrawable.OVAL }
            alpha = 0.78f
            isClickable = false
            isFocusable = false
        }
        host.addView(glow, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), gravity).apply {
            if ((gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.START) leftMargin = dp(xDp)
            else rightMargin = dp(xDp)
            if ((gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) topMargin = dp(yDp)
            else bottomMargin = dp(yDp)
        })
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private class LockGlyphView(context: android.content.Context) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 107, 157)
            style = Paint.Style.STROKE
            strokeWidth = 2.15f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 107, 157)
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width, height) / 40f
            canvas.save()
            canvas.translate((width - 40f * scale) / 2f, (height - 40f * scale) / 2f)
            canvas.scale(scale, scale)
            val shackle = Path().apply {
                moveTo(12f, 18f)
                lineTo(12f, 12.5f)
                cubicTo(12f, 4.8f, 28f, 4.8f, 28f, 12.5f)
                lineTo(28f, 18f)
            }
            canvas.drawPath(shackle, stroke)
            canvas.drawRoundRect(8f, 16f, 32f, 35f, 5f, 5f, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(20f, 23.5f, 1.8f, fill)
            canvas.drawRoundRect(19.1f, 24f, 20.9f, 28.5f, 0.8f, 0.8f, fill)
            canvas.restore()
        }
    }
}
