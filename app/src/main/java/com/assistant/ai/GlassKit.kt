package com.assistant.ai

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * Liquid glass sungguhan untuk app ini.
 *
 * Idenya: seluruh latar app adalah "aurora" (bola warna + cahaya) yang digambar [AuroraBackground].
 * Salinan aurora yang sudah di-blur disimpan sebagai bitmap kecil. Setiap panel kaca ([GlassDrawable])
 * menggambar bagian bitmap blur yang tepat berada di belakangnya (posisinya dihitung dari letak panel di jendela),
 * lalu menumpuk lapisan frost, kilau atas, bayangan dalam, tepi cahaya (rim) dan pergeseran warna di tepi.
 * Hasilnya: di luar panel bola warna terlihat tajam, di dalam panel terlihat kabur menembus kaca, dan
 * saat halaman digulir isi kaca ikut bergeser sesuai posisinya, seperti kaca yang nyata.
 * Semua memakai Canvas biasa (tanpa RenderScript / RenderEffect), jadi jalan di semua versi Android.
 */

/** Pemandangan aurora: dipakai untuk latar (tajam) dan untuk bitmap blur di belakang kaca. */
object AuroraScene {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFF9FAFE.toInt(), 0xFFF2F4FA.toInt())
    private val basePos = floatArrayOf(0f, 0.55f, 1f)

    /** Latar putih bersih (gradasi sangat tipis ke abu kebiruan di bawah). Bola warna sudah dihilangkan. */
    fun draw(c: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(0f, 0f, 0f, h, baseColors, basePos, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }
}

/** Latar aurora layar penuh. Juga menyimpan salinan blur yang dipakai semua panel kaca. */
class AuroraBackground @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** Salinan aurora yang sudah di-blur (resolusi kecil, diperbesar saat digambar). */
    var blurred: Bitmap? = null
        private set

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        blurred = buildBlurred(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        AuroraScene.draw(canvas, width.toFloat(), height.toFloat())
    }

    private fun buildBlurred(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        return try {
            val bw = (w / 8).coerceAtLeast(8)
            val bh = (h / 8).coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.scale(bw.toFloat() / w, bh.toFloat() / h)
            AuroraScene.draw(cv, w.toFloat(), h.toFloat())
            val px = IntArray(bw * bh)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            // Tiga kali box blur ~ gaussian. Ukurannya kecil, jadi cukup sekali hitung per ukuran layar.
            repeat(3) { boxBlur(px, bw, bh, 5) }
            bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        blurPass(px, tmp, w, h, r, true)
        blurPass(tmp, px, w, h, r, false)
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val lineStep = if (horizontal) w else 1
        val div = 2 * r + 1
        for (l in 0 until lines) {
            val base = l * lineStep
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = src[base + k.coerceIn(0, len - 1) * step]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val rem = src[base + (i - r).coerceAtLeast(0) * step]
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }
}

/** Bantuan untuk menggambar aurora blur yang sejajar dengan posisi aslinya di jendela. */
object GlassBackdrop {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // Sedikit lebih jenuh: warna di balik kaca terlihat lebih hidup (vibrancy).
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.35f) })
    }
    private val a = IntArray(2)
    private val b = IntArray(2)

    fun find(v: View): AuroraBackground? = v.rootView?.findViewById<AuroraBackground>(R.id.aurora)

    /**
     * Gambar aurora blur ke kanvas milik [v] (kanvas sudah di-clip oleh pemanggil), sejajar dengan letaknya di jendela.
     * Mengembalikan false kalau belum ada aurora / bitmap blur-nya.
     */
    fun draw(v: View, aurora: AuroraBackground?, c: Canvas): Boolean {
        val au = aurora ?: return false
        val bmp = au.blurred ?: return false
        if (au.width <= 0 || au.height <= 0) return false
        v.getLocationInWindow(a)
        au.getLocationInWindow(b)
        c.save()
        c.translate((b[0] - a[0]).toFloat(), (b[1] - a[1]).toFloat())
        c.scale(au.width.toFloat() / bmp.width, au.height.toFloat() / bmp.height)
        c.drawBitmap(bmp, 0f, 0f, paint)
        c.restore()
        return true
    }
}

object GlassKit {
    /** Paksa panel kaca menggambar ulang (dipanggil setelah animasi geser halaman selesai). */
    fun refresh(v: View) {
        if (v.background is GlassDrawable) v.invalidate()
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) refresh(v.getChildAt(i))
        }
    }
}

/**
 * Latar panel kaca. Bisa dipakai langsung dari XML: <drawable class="com.assistant.ai.GlassDrawable" />.
 * Lapisan dari bawah ke atas: bayangan luar, aurora blur, frost, tint (opsional), kilau atas, bayangan dalam,
 * garis tepi tipis, rim cahaya, pergeseran warna cyan/magenta di tepi, dan garis kilau di atas.
 */
open class GlassDrawable(
    private val radiiDp: FloatArray,
    private val frostTop: Int,
    private val frostBottom: Int,
    private val tintTop: Int,
    private val tintBottom: Int,
    private val shadow: Boolean
) : Drawable() {

    constructor() : this(floatArrayOf(24f, 24f, 24f, 24f), 0x78FFFFFF, 0x52FFFFFF, 0, 0, true)

    private val density = Resources.getSystem().displayMetrics.density
    private val path = Path()
    private val hairPath = Path()
    private val rimPath = Path()
    private val rect = RectF()
    private val rect2 = RectF()
    private val rr = FloatArray(8)
    private val rrIn = FloatArray(8)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var frostShader: Shader? = null
    private var tintShader: Shader? = null
    private var glowShader: Shader? = null
    private var shadeShader: Shader? = null
    private var rimShader: Shader? = null
    private var specShader: Shader? = null

    private var host: View? = null
    private var aurora: AuroraBackground? = null
    private var searched = false
    private var listening = false
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private val tmp = IntArray(2)

    // Kalau posisi panel di jendela berubah (gulir, animasi geser, keyboard, relayout), gambar ulang
    // supaya isi kaca selalu sejajar dengan aurora di belakangnya.
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val v = host
        if (v != null && v.isShown) {
            v.getLocationInWindow(tmp)
            if (tmp[0] != lastX || tmp[1] != lastY) {
                lastX = tmp[0]
                lastY = tmp[1]
                invalidateSelf()
            }
        }
        true
    }

    private val stateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            aurora = null
            searched = false
            startListening(v)
        }

        override fun onViewDetachedFromWindow(v: View) {
            stopListening(v)
        }
    }

    private fun startListening(v: View) {
        if (listening) return
        v.viewTreeObserver.addOnPreDrawListener(preDraw)
        listening = true
    }

    private fun stopListening(v: View) {
        if (!listening) return
        val o = v.viewTreeObserver
        if (o.isAlive) o.removeOnPreDrawListener(preDraw)
        listening = false
    }

    private fun findHost(): View? {
        var cb: Drawable.Callback? = callback
        var guard = 0
        while (cb is Drawable && guard < 6) {
            cb = cb.callback
            guard++
        }
        return cb as? View
    }

    private fun ensureHost() {
        val v = findHost()
        if (v === host) return
        host?.let {
            stopListening(it)
            it.removeOnAttachStateChangeListener(stateListener)
        }
        host = v
        aurora = null
        searched = false
        if (v == null) return
        v.addOnAttachStateChangeListener(stateListener)
        if (v.isAttachedToWindow) startListening(v)
        if (shadow) {
            // Bayangan luar digambar di luar batas panel, jadi induknya tidak boleh memotong gambar anak.
            var p = v.parent
            var n = 0
            while (p is ViewGroup && n < 6) {
                p.clipChildren = false
                p.clipToPadding = false
                p = p.parent
                n++
            }
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    private fun insetPath(dst: Path, inset: Float) {
        for (i in 0 until 8) rrIn[i] = (rr[i] - inset).coerceAtLeast(0f)
        rect2.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        dst.reset()
        dst.addRoundRect(rect2, rrIn, Path.Direction.CW)
    }

    private fun rebuild() {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bo = b.bottom.toFloat()
        val w = r - l
        val h = bo - t
        if (w <= 0f || h <= 0f) {
            frostShader = null
            return
        }
        val maxR = minOf(w, h) / 2f
        for (i in 0 until 4) {
            val v = minOf(radiiDp[i] * density, maxR)
            rr[i * 2] = v
            rr[i * 2 + 1] = v
        }
        rect.set(l, t, r, bo)
        path.reset()
        path.addRoundRect(rect, rr, Path.Direction.CW)
        insetPath(hairPath, 0.5f * density)
        insetPath(rimPath, 1.5f * density)

        frostShader = LinearGradient(0f, t, 0f, bo, frostTop, frostBottom, Shader.TileMode.CLAMP)
        tintShader = if (tintTop != 0) {
            LinearGradient(l, t, r, bo, tintTop, tintBottom, Shader.TileMode.CLAMP)
        } else null
        val glowH = minOf(h * 0.55f, 90f * density)
        glowShader = LinearGradient(0f, t, 0f, t + glowH, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        val shadeH = minOf(h * 0.5f, 26f * density)
        shadeShader = LinearGradient(0f, bo - shadeH, 0f, bo, 0x005A5FD0, 0x245A5FD0, Shader.TileMode.CLAMP)
        rimShader = LinearGradient(
            l, t, r, bo,
            intArrayOf(0xF2FFFFFF.toInt(), 0x59FFFFFF, 0x40FFFFFF, 0xD9FFFFFF.toInt()),
            floatArrayOf(0f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP
        )
        specShader = LinearGradient(
            l, 0f, r, 0f,
            intArrayOf(0x00FFFFFF, 0xE6FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun drawShadow(c: Canvas) {
        c.save()
        c.clipOutPath(path)
        fill.shader = null
        for (i in 1..6) {
            val g = i * 1.7f * density
            val a = (16 - i * 2).coerceAtLeast(2)
            fill.color = Color.argb(a, 60, 66, 190)
            rect2.set(rect.left - g, rect.top - g + 3f * density, rect.right + g, rect.bottom + g + 3f * density)
            c.drawRoundRect(rect2, rr[0] + g, rr[0] + g, fill)
        }
        c.restore()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || frostShader == null) return
        ensureHost()
        val v = host
        if (v != null && v.isAttachedToWindow) {
            if (!searched) {
                aurora = GlassBackdrop.find(v)
                searched = true
            }
            v.getLocationInWindow(tmp)
            lastX = tmp[0]
            lastY = tmp[1]
        }

        if (shadow) drawShadow(canvas)

        canvas.save()
        canvas.clipPath(path)
        val ok = v != null && GlassBackdrop.draw(v, aurora, canvas)
        if (!ok) {
            fill.shader = null
            fill.color = 0xE6F3F5FF.toInt()
            canvas.drawRect(rect, fill)
        }
        fill.shader = frostShader
        canvas.drawRect(rect, fill)
        if (tintShader != null) {
            fill.shader = tintShader
            canvas.drawRect(rect, fill)
        }
        fill.shader = glowShader
        canvas.drawRect(rect, fill)
        fill.shader = shadeShader
        canvas.drawRect(rect, fill)
        fill.shader = null
        canvas.restore()

        // Garis tepi tipis supaya panel tetap terbaca di atas latar terang.
        line.shader = null
        line.strokeWidth = 1f * density
        line.color = 0x2E4A4FD0
        canvas.drawPath(hairPath, line)

        // Pergeseran warna di tepi (cyan ke kiri, magenta ke kanan), seperti dispersi cahaya pada kaca.
        line.strokeWidth = 1.2f * density
        val shift = 0.7f * density
        canvas.save()
        canvas.translate(-shift, 0f)
        line.color = 0x2600D2FF
        canvas.drawPath(rimPath, line)
        canvas.translate(2f * shift, 0f)
        line.color = 0x26FF46AA
        canvas.drawPath(rimPath, line)
        canvas.restore()

        // Rim cahaya: terang di sudut kiri atas dan kanan bawah.
        line.shader = rimShader
        line.strokeWidth = 1.3f * density
        canvas.drawPath(rimPath, line)

        // Garis kilau tipis di bagian atas.
        val sx0 = rect.left + rr[0] * 0.9f
        val sx1 = rect.right - rr[2] * 0.9f
        if (sx1 - sx0 > 24f * density) {
            line.shader = specShader
            line.strokeWidth = 1.1f * density
            val y = rect.top + 2.2f * density
            canvas.drawLine(sx0, y, sx1, y, line)
        }
        line.shader = null
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Bubble chat milik AI: kaca lebih pekat supaya teks panjang tetap nyaman dibaca. */
class GlassBubbleDrawable : GlassDrawable(
    floatArrayOf(18f, 18f, 18f, 5f), 0xA6FFFFFF.toInt(), 0x80FFFFFF.toInt(), 0, 0, false
)

/** Kapsul kaca (kolom ketik, tombol bulat, kunci angka). */
class GlassPillDrawable : GlassDrawable(
    floatArrayOf(40f, 40f, 40f, 40f), 0x8CFFFFFF.toInt(), 0x66FFFFFF, 0, 0, true
)

/** Kartu utama bertinta ungu-biru, tetap kaca (rim, kilau, blur) tapi lebih pekat untuk teks putih. */
class GlassHeroDrawable : GlassDrawable(
    floatArrayOf(28f, 28f, 28f, 28f), 0x24FFFFFF, 0x10FFFFFF, 0xE05B4FEA.toInt(), 0xD82F6FE0.toInt(), true
)

/** Kartu kaca tanpa bayangan luar, untuk daftar yang digulir di dalam ScrollView (riwayat, soal tes). */
class GlassFlatDrawable : GlassDrawable(
    floatArrayOf(20f, 20f, 20f, 20f), 0x8CFFFFFF.toInt(), 0x66FFFFFF, 0, 0, false
)
