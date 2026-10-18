package com.assistant.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import kotlin.math.min

/** TextView bubble yang melukis ikon vektor sendiri ketika idle (tanpa emoji/font eksternal). */
class BubbleArtworkTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextView(context, attrs) {

    private var artworkMode = false

    fun showArtwork(show: Boolean) {
        if (artworkMode != show) {
            artworkMode = show
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!artworkMode) {
            super.onDraw(canvas)
            return
        }
        drawArtwork(canvas)
    }

    private fun drawArtwork(canvas: Canvas) {
        val unit = min(width, height).toFloat().coerceAtLeast(1f) / 48f
        val cx = width / 2f
        val cy = height / 2f
        val selected = Prefs.bubbleIcon(context).coerceIn(0, BubbleStyle.ICONS.lastIndex)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(25, 255, 255, 255) }
        canvas.drawCircle(cx, cy, 16.5f * unit, glow)

        when (selected) {
            0 -> drawRobot(canvas, cx, cy, unit)
            1 -> drawSpark(canvas, cx, cy, 15f * unit, Color.WHITE)
            2 -> {
                val p = stroke(Color.WHITE, 2f * unit)
                canvas.drawCircle(cx, cy, 11f * unit, p)
                canvas.drawCircle(cx, cy, 5.5f * unit, p)
                canvas.drawCircle(cx, cy, 2f * unit, fill(0xFFFFF0A6.toInt()))
            }
            3 -> {
                val path = Path().apply {
                    moveTo(cx, cy - 15f * unit)
                    lineTo(cx + 11f * unit, cy)
                    lineTo(cx, cy + 15f * unit)
                    lineTo(cx - 11f * unit, cy)
                    close()
                }
                canvas.drawPath(path, fill(Color.WHITE))
                canvas.drawPath(path, stroke(0xFFBAF3FF.toInt(), 1.2f * unit))
            }
            4 -> {
                val path = Path().apply {
                    moveTo(cx - 12f * unit, cy)
                    lineTo(cx - 4f * unit, cy + 8f * unit)
                    lineTo(cx + 13f * unit, cy - 10f * unit)
                }
                canvas.drawPath(path, stroke(Color.WHITE, 3.4f * unit).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND })
            }
            5 -> {
                val p = fill(Color.WHITE).apply {
                    textSize = 27f * unit
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("A", cx, cy + 9f * unit, p)
            }
            else -> {
                drawSpark(canvas, cx - 4f * unit, cy + 2f * unit, 11f * unit, Color.WHITE)
                drawSpark(canvas, cx + 9f * unit, cy - 9f * unit, 5f * unit, 0xFFFFE9A6.toInt())
            }
        }
    }

    private fun drawRobot(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val antenna = stroke(0xFFEAFBFF.toInt(), 2.2f * u).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(cx, cy - 10f * u, cx, cy - 15f * u, antenna)
        canvas.drawCircle(cx, cy - 16f * u, 2.1f * u, fill(0xFFFFE59A.toInt()))

        val ear = fill(0xFFD5F8FF.toInt())
        canvas.drawRoundRect(RectF(cx - 16f * u, cy - 3f * u, cx - 12f * u, cy + 7f * u), 2f * u, 2f * u, ear)
        canvas.drawRoundRect(RectF(cx + 12f * u, cy - 3f * u, cx + 16f * u, cy + 7f * u), 2f * u, 2f * u, ear)

        val face = fill(Color.WHITE).apply {
            setShadowLayer(2.2f * u, 0f, 1f * u, 0x6646DDF5)
        }
        canvas.drawRoundRect(RectF(cx - 13f * u, cy - 10f * u, cx + 13f * u, cy + 12f * u), 6f * u, 6f * u, face)
        canvas.drawRoundRect(
            RectF(cx - 13f * u, cy - 10f * u, cx + 13f * u, cy + 12f * u),
            6f * u, 6f * u, stroke(0xFFBDEFF7.toInt(), 1.1f * u)
        )
        canvas.drawCircle(cx - 5f * u, cy - 1f * u, 2.1f * u, fill(0xFF384C82.toInt()))
        canvas.drawCircle(cx + 5f * u, cy - 1f * u, 2.1f * u, fill(0xFF384C82.toInt()))
        canvas.drawCircle(cx - 5.6f * u, cy - 1.7f * u, 0.65f * u, fill(Color.WHITE))
        canvas.drawCircle(cx + 4.4f * u, cy - 1.7f * u, 0.65f * u, fill(Color.WHITE))
        canvas.drawRoundRect(RectF(cx - 4f * u, cy + 5f * u, cx + 4f * u, cy + 6.8f * u), 1f * u, 1f * u, fill(0xFF42BFD7.toInt()))

        drawSpark(canvas, cx + 15f * u, cy - 13f * u, 4f * u, 0xFFFFE9A6.toInt())
        canvas.drawCircle(cx - 16f * u, cy - 13f * u, 1.3f * u, fill(0xFFBCEFFF.toInt()))
    }

    private fun drawSpark(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val p = Path().apply {
            moveTo(cx, cy - radius)
            quadTo(cx + radius * 0.18f, cy - radius * 0.18f, cx + radius, cy)
            quadTo(cx + radius * 0.18f, cy + radius * 0.18f, cx, cy + radius)
            quadTo(cx - radius * 0.18f, cy + radius * 0.18f, cx - radius, cy)
            quadTo(cx - radius * 0.18f, cy - radius * 0.18f, cx, cy - radius)
            close()
        }
        canvas.drawPath(p, fill(color))
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }
}
