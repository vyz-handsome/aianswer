package com.assistant.ai

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Menu bawah melayang dengan tiga gaya:
 *  0 = pil gelap dengan garis cahaya (glow) di atas tab aktif,
 *  1 = pil putih dengan bulatan warna yang meluncur (ada lekukan di bar),
 *  2 = pil berwarna pastel yang berganti warna per tab, bulatan "muncul" dari bar dan ada percikan,
 *  3 = liquid glass: kapsul kaca bening ala iOS dengan lensa kaca yang meluncur, melar, dan bisa diseret.
 * Ikon digambar sendiri (tanpa file gambar) supaya warnanya bisa dianimasikan.
 */
class NavBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        const val STYLE_DARK = 0
        const val STYLE_FLOAT = 1
        const val STYLE_DROP = 2
        const val STYLE_GLASS = 3
    }

    var onSelected: ((Int) -> Unit)? = null

    var style: Int = STYLE_FLOAT
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    var selected: Int = 0
        private set

    private val d = resources.displayMetrics.density
    private val labels = arrayOf("Beranda", "Tanya AI", "Latihan", "Riwayat", "Pengaturan")
    private val accents = intArrayOf(
        Color.parseColor("#7B5CF0"), Color.parseColor("#E0457B"), Color.parseColor("#F08A3B"),
        Color.parseColor("#3B8EEA"), Color.parseColor("#1FA67A")
    )
    private val tints = intArrayOf(
        Color.parseColor("#E7DFFF"), Color.parseColor("#FFDCE8"), Color.parseColor("#FFE9D2"),
        Color.parseColor("#DCEBFF"), Color.parseColor("#D6F3E8")
    )

    // Ukuran (dp -> px)
    private val sideGap = 14 * d
    private val barTop = 28 * d
    private val barH = 60 * d
    private val circleR = 22 * d
    private val notchGap = 6 * d

    // Keadaan animasi
    private var prev = 0
    private var frac = 1f
    private var xPos = -1f
    private var fromX = 0f
    private var accentNow = accents[0]
    private var tintNow = tints[0]
    private var fromAccent = accents[0]
    private var fromTint = tints[0]
    private var ring = 1f
    private var anim: ValueAnimator? = null
    private var ringAnim: ValueAnimator? = null
    private val overshoot = OvershootInterpolator(1.3f)
    private val decel = DecelerateInterpolator(1.7f)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * d
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barPath = Path()

    // Liquid glass
    private val gTop = 8 * d
    private val gH = 66 * d
    private val gBottom = 12 * d
    private var press = 0f
    private var pressAnim: ValueAnimator? = null
    private var dragging = false
    private var downX = 0f
    private var lastMoveT = 0L
    private var vel = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clipPath = Path()
    private val rect2 = RectF()
    private val jellyKeys = floatArrayOf(0f, 0.25f, 0.45f, 0.65f, 0.82f, 1f)
    private val jellyX = floatArrayOf(1f, 0.82f, 1.12f, 0.96f, 1.02f, 1f)
    private val jellyY = floatArrayOf(1f, 1.12f, 0.90f, 1.04f, 0.98f, 1f)
    private val jellyOut = FloatArray(2)
    private val notchPath = Path()
    private val rect = RectF()
    private val icons = Array(5) { buildIcon(it) }

    init {
        // Dibutuhkan untuk bayangan (setShadowLayer) dan Path.op di semua versi Android.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        contentDescription = "Menu bawah"
    }

    // ------------------------------------------------------------------ ikon (kotak 24x24)

    private fun buildIcon(i: Int): Path {
        val p = Path()
        when (i) {
            0 -> {
                p.moveTo(3f, 11f); p.lineTo(12f, 3f); p.lineTo(21f, 11f)
                p.moveTo(5f, 9.5f); p.lineTo(5f, 20f); p.lineTo(10f, 20f); p.lineTo(10f, 14f)
                p.lineTo(14f, 14f); p.lineTo(14f, 20f); p.lineTo(19f, 20f); p.lineTo(19f, 9.5f)
            }
            1 -> {
                p.addRoundRect(RectF(3f, 4f, 21f, 16f), 4f, 4f, Path.Direction.CW)
                p.moveTo(8f, 16f); p.lineTo(8f, 20.5f); p.lineTo(12.5f, 16f)
                p.moveTo(8f, 9f); p.lineTo(16f, 9f)
                p.moveTo(8f, 12f); p.lineTo(13f, 12f)
            }
            2 -> {
                p.addRoundRect(RectF(5f, 4f, 19f, 21f), 2.5f, 2.5f, Path.Direction.CW)
                p.addRoundRect(RectF(9f, 2.5f, 15f, 6f), 1.5f, 1.5f, Path.Direction.CW)
                p.moveTo(8.5f, 13.5f); p.lineTo(11f, 16f); p.lineTo(15.5f, 11f)
            }
            3 -> {
                p.addCircle(12f, 12f, 9f, Path.Direction.CW)
                p.moveTo(12f, 7f); p.lineTo(12f, 12f); p.lineTo(15.5f, 14f)
            }
            else -> {
                val rows = arrayOf(floatArrayOf(7f, 9f), floatArrayOf(12f, 15f), floatArrayOf(17f, 8f))
                for (r in rows) {
                    val y = r[0]
                    val kx = r[1]
                    p.moveTo(4f, y); p.lineTo(kx - 2.4f, y)
                    p.moveTo(kx + 2.4f, y); p.lineTo(20f, y)
                    p.addCircle(kx, y, 2.4f, Path.Direction.CW)
                }
            }
        }
        return p
    }

    private fun drawIcon(c: Canvas, idx: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Float = 1f) {
        if (alpha <= 0.01f) return
        iconPaint.color = color
        iconPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.save()
        c.translate(cx, cy)
        val s = size / 24f
        c.scale(s, s)
        c.translate(-12f, -12f)
        c.drawPath(icons[idx], iconPaint)
        c.restore()
    }

    // ------------------------------------------------------------------ geometri dan warna

    private fun barLeft() = sideGap
    private fun barRight() = width - sideGap
    private fun tabW() = (barRight() - barLeft()) / 5f
    private fun cx(i: Int) = barLeft() + tabW() * (i + 0.5f)

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    private fun withAlpha(color: Int, a: Int): Int = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (style == STYLE_GLASS) gTop + gH + gBottom else barTop + barH + 8 * d
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        xPos = cx(selected)
    }

    // ------------------------------------------------------------------ pilih tab

    /** Pindah ke tab [i] (tanpa memanggil onSelected). */
    fun select(i: Int, animate: Boolean = true) {
        if (i == selected || i !in 0..4) return
        prev = selected
        selected = i
        startMove(animate)
    }

    /** Kembali ke tab terpilih (misalnya setelah digeser lalu dilepas di tab yang sama). */
    private fun settle() {
        prev = selected
        startMove(true)
    }

    private fun startMove(animate: Boolean) {
        anim?.cancel()
        fromX = if (xPos >= 0f) xPos else cx(prev)
        fromAccent = accentNow
        fromTint = tintNow
        val toX = cx(selected)
        if (!animate || width == 0) {
            frac = 1f
            xPos = toX
            accentNow = accents[selected]
            tintNow = tints[selected]
            invalidate()
            return
        }
        frac = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (style == STYLE_DROP) 620 else 520
        a.addUpdateListener {
            frac = it.animatedValue as Float
            xPos = fromX + (toX - fromX) * overshoot.getInterpolation(frac)
            val e = decel.getInterpolation(frac)
            accentNow = mix(fromAccent, accents[selected], e)
            tintNow = mix(fromTint, tints[selected], e)
            invalidate()
        }
        anim = a
        a.start()
        pulse()
    }

    /** Cincin yang melebar dan memudar di sekitar tab aktif. */
    private fun pulse() {
        ringAnim?.cancel()
        val r = ValueAnimator.ofFloat(0f, 1f)
        r.duration = 560
        r.addUpdateListener {
            ring = it.animatedValue as Float
            invalidate()
        }
        ringAnim = r
        r.start()
    }

    private fun tabIndexAt(x: Float): Int = ((x - barLeft()) / tabW()).toInt().coerceIn(0, 4)

    private fun nearestTab(x: Float): Int = Math.round((x - barLeft()) / tabW() - 0.5f).coerceIn(0, 4)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        return if (style == STYLE_GLASS) glassTouch(e) else legacyTouch(e)
    }

    /** Liquid glass: ketuk untuk pindah, atau tahan lalu geser (lensa mengikuti jari dan mengembang) lalu lepas. */
    private fun glassTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                lastMoveT = e.eventTime
                vel = 0f
                dragging = false
                animatePress(1f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) {
                    dragging = true
                    anim?.cancel()
                    frac = 1f
                }
                if (dragging) {
                    val nx = e.x.coerceIn(cx(0), cx(4))
                    val dt = (e.eventTime - lastMoveT).coerceAtLeast(1L)
                    vel = vel * 0.7f + ((nx - xPos) / dt) * 0.3f
                    lastMoveT = e.eventTime
                    xPos = nx
                    dragAccent()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                animatePress(0f)
                val target = if (dragging) nearestTab(xPos) else tabIndexAt(e.x)
                val tappedOnBar = dragging || e.y >= gTop - 10 * d
                dragging = false
                vel = 0f
                if (tappedOnBar && target != selected) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    select(target, true)
                    onSelected?.invoke(target)
                } else {
                    settle()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePress(0f)
                dragging = false
                vel = 0f
                settle()
                return true
            }
        }
        return true
    }

    private fun legacyTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (e.y >= barTop - 24 * d) {
                    val i = ((e.x - barLeft()) / tabW()).toInt().coerceIn(0, 4)
                    if (i != selected) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        select(i, true)
                        onSelected?.invoke(i)
                    } else {
                        pulse()
                    }
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Warna aksen ikut bergeser saat lensa diseret di antara dua tab. */
    private fun dragAccent() {
        val p = ((xPos - cx(0)) / tabW()).coerceIn(0f, 4f)
        val i0 = floor(p).toInt().coerceIn(0, 3)
        val k = p - i0
        accentNow = mix(accents[i0], accents[i0 + 1], k)
        tintNow = mix(tints[i0], tints[i0 + 1], k)
    }

    private fun animatePress(to: Float) {
        pressAnim?.cancel()
        val a = ValueAnimator.ofFloat(press, to)
        a.duration = 200
        a.addUpdateListener {
            press = it.animatedValue as Float
            invalidate()
        }
        pressAnim = a
        a.start()
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    // ------------------------------------------------------------------ gambar

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        if (xPos < 0f) xPos = cx(selected)
        when (style) {
            STYLE_DARK -> drawDark(c)
            STYLE_DROP -> drawDrop(c)
            STYLE_GLASS -> drawGlass(c)
            else -> drawFloat(c)
        }
    }

    private fun basePath(): Path {
        barPath.reset()
        rect.set(barLeft(), barTop, barRight(), barTop + barH)
        barPath.addRoundRect(rect, barH / 2f, barH / 2f, Path.Direction.CW)
        return barPath
    }

    private fun drawLabel(c: Canvas, idx: Int, color: Int, bold: Boolean) {
        textPaint.color = color
        textPaint.isFakeBoldText = bold
        c.drawText(labels[idx], cx(idx), barTop + 50 * d, textPaint)
    }

    // Gaya 1: bar putih, bulatan warna meluncur dengan lekukan di bar.
    private fun drawFloat(c: Canvas) {
        val accent = accentNow
        val bar = basePath()
        notchPath.reset()
        notchPath.addCircle(xPos, barTop + 2 * d, circleR + notchGap, Path.Direction.CW)
        bar.op(notchPath, Path.Op.DIFFERENCE)
        barPaint.color = Color.WHITE
        barPaint.setShadowLayer(14 * d, 0f, 4 * d, Color.argb(42, 0, 0, 0))
        c.drawPath(bar, barPaint)
        barPaint.clearShadowLayer()

        val tw = tabW()
        for (i in 0..4) {
            if (i == selected) {
                drawLabel(c, i, accent, true)
            } else {
                drawLabel(c, i, Color.parseColor("#8A8DA3"), false)
                val a = ((abs(xPos - cx(i)) - 0.15f * tw) / (0.4f * tw)).coerceIn(0f, 1f)
                drawIcon(c, i, cx(i), barTop + 24 * d, 24 * d, Color.parseColor("#3A3D52"), a)
            }
        }

        val cy = barTop + 2 * d
        val pop = 1f + 0.12f * sin(PI.toFloat() * frac)
        circlePaint.shader = LinearGradient(
            xPos - circleR, cy - circleR, xPos + circleR, cy + circleR,
            mix(accent, Color.WHITE, 0.28f), accent, Shader.TileMode.CLAMP
        )
        circlePaint.setShadowLayer(12 * d, 0f, 6 * d, withAlpha(accent, 120))
        c.drawCircle(xPos, cy, circleR * pop, circlePaint)
        circlePaint.shader = null
        circlePaint.clearShadowLayer()
        drawRing(c, xPos, cy, accent)
        drawIcon(c, selected, xPos, cy, 24 * d * pop, Color.WHITE)
    }

    private fun drawRing(c: Canvas, x: Float, y: Float, color: Int) {
        if (ring >= 1f) return
        ringPaint.strokeWidth = 2.2f * d
        ringPaint.color = withAlpha(color, ((1f - ring) * 140).toInt())
        c.drawCircle(x, y, circleR + notchGap + ring * 16 * d, ringPaint)
    }

    // Gaya 2: bar pastel yang berganti warna, bulatan muncul dari bar, plus percikan kecil.
    private fun drawDrop(c: Canvas) {
        val tint = tintNow
        val accent = accentNow
        val restY = barTop + 2 * d
        val hiddenY = barTop + 34 * d
        val riseNew = overshoot.getInterpolation(frac).coerceAtLeast(0f)
        val sinking = prev != selected && frac < 1f
        val riseOld = if (sinking) 1f - decel.getInterpolation(frac) else 0f

        val bar = basePath()
        fun cutFor(x: Float, rise: Float) {
            val k = rise.coerceIn(0f, 1.05f)
            if (k <= 0.02f) return
            val cy = hiddenY + (restY - hiddenY) * rise
            notchPath.reset()
            notchPath.addCircle(x, cy, (circleR + notchGap) * k, Path.Direction.CW)
            bar.op(notchPath, Path.Op.DIFFERENCE)
        }
        cutFor(cx(selected), riseNew)
        if (sinking) cutFor(cx(prev), riseOld)
        barPaint.color = tint
        barPaint.setShadowLayer(14 * d, 0f, 4 * d, Color.argb(36, 0, 0, 0))
        c.drawPath(bar, barPaint)
        barPaint.clearShadowLayer()

        for (i in 0..4) {
            if (i == selected) {
                drawLabel(c, i, accent, true)
                continue
            }
            drawLabel(c, i, Color.parseColor("#6B6F85"), false)
            val a = if (i == prev && sinking) (1f - riseOld).coerceIn(0f, 1f) else 1f
            drawIcon(c, i, cx(i), barTop + 24 * d, 24 * d, Color.parseColor("#3A3D52"), a)
        }

        fun drop(x: Float, rise: Float, idx: Int, color: Int) {
            if (rise <= 0.02f) return
            val cy = hiddenY + (restY - hiddenY) * rise
            val r = circleR * (0.6f + 0.4f * rise.coerceIn(0f, 1.1f))
            circlePaint.color = mix(Color.WHITE, tint, 0.3f)
            circlePaint.setShadowLayer(10 * d, 0f, 5 * d, withAlpha(color, 90))
            c.drawCircle(x, cy, r, circlePaint)
            circlePaint.clearShadowLayer()
            drawIcon(c, idx, x, cy, 24 * d * (0.7f + 0.3f * rise.coerceIn(0f, 1f)), color)
        }
        drop(cx(selected), riseNew, selected, accent)
        if (sinking) drop(cx(prev), riseOld, prev, fromAccent)

        // Percikan kecil yang melayang naik dari tab yang baru dipilih.
        if (frac < 1f) {
            glowPaint.style = Paint.Style.FILL
            for (k in 0..2) {
                val p = frac
                val x = cx(selected) + (k - 1) * 14 * d * p
                val y = barTop - 6 * d - p * (20 + 8 * k) * d
                val r = (3.2f - 2.4f * p) * d
                glowPaint.color = withAlpha(accent, ((1f - p) * 200).toInt())
                c.drawCircle(x, y, r.coerceAtLeast(0.5f * d), glowPaint)
            }
        }
        drawRing(c, cx(selected), restY, accent)
    }

    // Gaya 0: bar gelap, ikon aktif berwarna, garis cahaya di atas tab aktif.
    private fun drawDark(c: Canvas) {
        val accent = accentNow
        val bar = basePath()
        barPaint.color = Color.parseColor("#16161F")
        barPaint.setShadowLayer(14 * d, 0f, 4 * d, Color.argb(70, 0, 0, 0))
        c.drawPath(bar, barPaint)
        barPaint.clearShadowLayer()

        val e = decel.getInterpolation(frac)
        for (i in 0..4) {
            val amount = when (i) {
                selected -> e
                prev -> if (prev != selected) 1f - e else 0f
                else -> 0f
            }
            drawLabel(c, i, mix(Color.parseColor("#7A7D92"), accents[i], amount), amount > 0.5f)
            drawIcon(c, i, cx(i), barTop + 24 * d, 24 * d, mix(Color.parseColor("#9A9DB2"), accents[i], amount))
        }

        // Cahaya yang meluncur: sinar ke bawah (dipotong sesuai bentuk bar) dan garis terang di tepi atas.
        val x = xPos
        c.save()
        c.clipPath(bar)
        glowPaint.style = Paint.Style.FILL
        glowPaint.shader = LinearGradient(
            0f, barTop, 0f, barTop + 36 * d,
            withAlpha(accent, 110), withAlpha(accent, 0), Shader.TileMode.CLAMP
        )
        c.drawRect(x - 23 * d, barTop, x + 23 * d, barTop + 36 * d, glowPaint)
        glowPaint.shader = null
        c.restore()

        rect.set(x - 13 * d, barTop - 1 * d, x + 13 * d, barTop + 3 * d)
        glowPaint.color = accent
        glowPaint.setShadowLayer(10 * d, 0f, 0f, withAlpha(accent, 200))
        c.drawRoundRect(rect, 2 * d, 2 * d, glowPaint)
        glowPaint.clearShadowLayer()
    }

    // ------------------------------------------------------------------ Gaya 3: liquid glass

    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        gradPaint.shader = RadialGradient(x, y, r, color, withAlpha(color, 0), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, gradPaint)
        gradPaint.shader = null
    }

    /** Skala (x, y) "jelly" berurutan dari 0..1: gepeng, melar, memantul, lalu tenang. */
    private fun jelly(p: Float): FloatArray {
        val q = p.coerceIn(0f, 1f)
        for (i in 1 until jellyKeys.size) {
            if (q <= jellyKeys[i]) {
                val t = (q - jellyKeys[i - 1]) / (jellyKeys[i] - jellyKeys[i - 1])
                jellyOut[0] = jellyX[i - 1] + (jellyX[i] - jellyX[i - 1]) * t
                jellyOut[1] = jellyY[i - 1] + (jellyY[i] - jellyY[i - 1]) * t
                return jellyOut
            }
        }
        jellyOut[0] = 1f
        jellyOut[1] = 1f
        return jellyOut
    }

    private fun darker(color: Int): Int = mix(color, Color.BLACK, 0.18f)

    // Kaca ala iOS: kapsul bening berembun dengan "aurora" warna di dalamnya, tepi bercahaya, dan lensa kaca
    // yang meluncur (melar saat bergerak, membesar saat disentuh, bisa diseret). Semua digambar dengan Canvas.
    private fun drawGlass(c: Canvas) {
        val l = barLeft()
        val r = barRight()
        val t = gTop
        val b = gTop + gH
        val rad = gH / 2f
        val accent = accentNow
        val tw = tabW()
        val cy = t + gH / 2f
        rect.set(l, t, r, b)

        // 1) Bayangan lembut dan pendar warna di bawah lensa.
        blurPaint.reset()
        blurPaint.isAntiAlias = true
        blurPaint.color = Color.argb(48, 40, 50, 110)
        blurPaint.maskFilter = BlurMaskFilter(16 * d, BlurMaskFilter.Blur.NORMAL)
        rect2.set(l + 4 * d, t + 9 * d, r - 4 * d, b + 9 * d)
        c.drawRoundRect(rect2, rad, rad, blurPaint)
        blurPaint.color = withAlpha(accent, 46)
        blurPaint.maskFilter = BlurMaskFilter(22 * d, BlurMaskFilter.Blur.NORMAL)
        rect2.set(xPos - 34 * d, t + 10 * d, xPos + 34 * d, b + 12 * d)
        c.drawRoundRect(rect2, rad, rad, blurPaint)
        blurPaint.maskFilter = null

        // 2) Badan kaca (dipotong sesuai kapsul): dasar berembun, aurora warna, kilau atas, bayangan dalam bawah.
        clipPath.reset()
        clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(clipPath)
        gradPaint.shader = LinearGradient(
            0f, t, 0f, b, Color.argb(218, 255, 255, 255), Color.argb(150, 236, 241, 255), Shader.TileMode.CLAMP
        )
        c.drawRect(rect, gradPaint)
        gradPaint.shader = null
        val shift = (xPos - width / 2f) * 0.10f
        blob(c, xPos, t + gH * 0.55f, 78 * d, withAlpha(accent, 105))
        blob(c, l + 0.16f * (r - l) + shift, t + gH * 0.30f, 90 * d, Color.argb(72, 110, 160, 255))
        blob(c, l + 0.84f * (r - l) + shift, t + gH * 0.70f, 90 * d, Color.argb(62, 255, 130, 210))
        gradPaint.shader = LinearGradient(
            0f, t, 0f, t + gH * 0.6f, Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        c.drawRect(l, t, r, t + gH * 0.6f, gradPaint)
        gradPaint.shader = LinearGradient(
            0f, b - 16 * d, 0f, b, Color.argb(0, 60, 80, 160), Color.argb(36, 60, 80, 160), Shader.TileMode.CLAMP
        )
        c.drawRect(l, b - 16 * d, r, b, gradPaint)
        gradPaint.shader = null
        c.restore()

        // 3) Tepi kapsul: garis gelap tipis (kontras di latar terang), rim cahaya, garis kilau di atas.
        strokePaint.shader = null
        strokePaint.strokeWidth = 1f * d
        strokePaint.color = Color.argb(36, 50, 60, 120)
        rect2.set(l - 0.5f * d, t - 0.5f * d, r + 0.5f * d, b + 0.5f * d)
        c.drawRoundRect(rect2, rad, rad, strokePaint)
        strokePaint.strokeWidth = 1.4f * d
        strokePaint.shader = LinearGradient(
            l, t, r, b,
            intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.argb(210, 255, 255, 255)),
            floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        rect2.set(l + 0.7f * d, t + 0.7f * d, r - 0.7f * d, b - 0.7f * d)
        c.drawRoundRect(rect2, rad, rad, strokePaint)
        strokePaint.strokeWidth = 1.2f * d
        strokePaint.shader = LinearGradient(
            l, 0f, r, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(235, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawLine(l + 0.12f * (r - l), t + 1.8f * d, r - 0.12f * (r - l), t + 1.8f * d, strokePaint)
        strokePaint.shader = null

        // 4) Lensa kaca: melar saat bergerak atau diseret, membesar saat disentuh.
        val moveStretch = if (frac < 1f) {
            0.30f * sin(PI.toFloat() * frac) * (abs(cx(selected) - fromX) / (tw * 2f)).coerceIn(0.35f, 1f)
        } else 0f
        val dragStretch = min(0.38f, abs(vel) / d / 1.6f)
        val stretch = max(moveStretch, dragStretch)
        val lw = (tw - 6 * d) * (1f + stretch) * (1f + 0.08f * press)
        val lh = (gH - 10 * d) * (1f - stretch * 0.26f) * (1f + 0.08f * press)
        val left = xPos - lw / 2f
        val right = xPos + lw / 2f
        val top = cy - lh / 2f
        val bottom = cy + lh / 2f
        val lr = lh / 2f

        blurPaint.reset()
        blurPaint.isAntiAlias = true
        blurPaint.color = withAlpha(accent, (70 + 45 * press).toInt().coerceAtMost(255))
        blurPaint.maskFilter = BlurMaskFilter(12 * d, BlurMaskFilter.Blur.NORMAL)
        rect2.set(left + 6 * d, top + 8 * d, right - 6 * d, bottom + 6 * d)
        c.drawRoundRect(rect2, lr, lr, blurPaint)
        blurPaint.maskFilter = null

        rect.set(left, top, right, bottom)
        gradPaint.shader = LinearGradient(
            left, top, right, bottom,
            withAlpha(mix(Color.WHITE, accent, 0.10f), 238), withAlpha(mix(Color.WHITE, accent, 0.30f), 150),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, lr, lr, gradPaint)
        gradPaint.shader = null

        notchPath.reset()
        notchPath.addRoundRect(rect, lr, lr, Path.Direction.CW)
        c.save()
        c.clipPath(notchPath)
        blob(c, xPos, bottom - 2 * d, lw * 0.6f, withAlpha(accent, 125))
        blurPaint.color = Color.argb(175, 255, 255, 255)
        blurPaint.maskFilter = BlurMaskFilter(3 * d, BlurMaskFilter.Blur.NORMAL)
        c.save()
        c.rotate(-10f, xPos, cy)
        rect2.set(left + lw * 0.12f, top + lh * 0.07f, left + lw * 0.62f, top + lh * 0.36f)
        c.drawOval(rect2, blurPaint)
        c.restore()
        blurPaint.maskFilter = null
        c.restore()

        // Tepi lensa: sedikit pergeseran warna (cyan dan magenta) lalu rim putih yang terang di dua sudut.
        strokePaint.strokeWidth = 1.2f * d
        strokePaint.color = Color.argb(48, 0, 210, 255)
        rect2.set(left - 0.8f * d, top, right - 0.8f * d, bottom)
        c.drawRoundRect(rect2, lr, lr, strokePaint)
        strokePaint.color = Color.argb(48, 255, 70, 170)
        rect2.set(left + 0.8f * d, top, right + 0.8f * d, bottom)
        c.drawRoundRect(rect2, lr, lr, strokePaint)
        strokePaint.strokeWidth = 1.5f * d
        strokePaint.shader = LinearGradient(
            left, top, right, bottom,
            intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(235, 255, 255, 255)),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        rect2.set(left + 0.7f * d, top + 0.7f * d, right - 0.7f * d, bottom - 0.7f * d)
        c.drawRoundRect(rect2, lr, lr, strokePaint)
        strokePaint.shader = null
        strokePaint.strokeWidth = 1f * d
        strokePaint.color = withAlpha(accent, 80)
        rect2.set(left - 0.5f * d, top - 0.5f * d, right + 0.5f * d, bottom + 0.5f * d)
        c.drawRoundRect(rect2, lr, lr, strokePaint)

        // 5) Ikon dan label (teks sama dengan gaya lain). Yang dekat lensa menyala, naik sedikit, dan "kenyal".
        val jl = jelly(frac)
        for (i in 0..4) {
            val near = (1f - abs(xPos - cx(i)) / (tw * 0.8f)).coerceIn(0f, 1f)
            val amount = near * near * (3f - 2f * near)
            val col = mix(Color.rgb(74, 78, 107), darker(accents[i]), amount)
            var sx = 1f + 0.06f * amount
            var sy = sx
            if (i == selected && frac < 1f) {
                sx *= jl[0]
                sy *= jl[1]
            }
            c.save()
            c.translate(cx(i), t + gH * 0.37f - 2f * d * amount)
            c.scale(sx, sy)
            drawIcon(c, i, 0f, 0f, 23 * d, col)
            c.restore()

            textPaint.color = col
            textPaint.isFakeBoldText = amount > 0.5f
            c.save()
            c.translate(cx(i), t + gH * 0.84f)
            c.scale(1f + 0.03f * amount, 1f + 0.03f * amount)
            c.drawText(labels[i], 0f, 0f, textPaint)
            c.restore()
        }
    }

}
