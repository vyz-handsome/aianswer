package com.assistant.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Tema warna, bentuk, dan ikon diam bubble. Dipakai bubble di layar dan pratinjau di Pengaturan. */
object BubbleStyle {

    class Theme(val name: String, val color: Int)

    val THEMES: List<Theme> = listOf(
        Theme("Gelap", Color.parseColor("#111111")),
        Theme("Biru", Color.parseColor("#1E5EFF")),
        Theme("Ungu", Color.parseColor("#4640CF")),
        Theme("Teal", Color.parseColor("#0E7C86")),
        Theme("Merah", Color.parseColor("#D6336C"))
    )

    val SHAPES: List<String> = listOf("Bulat", "Kotak membulat", "Kotak")

    // Simbol internal tetap dipakai untuk mengenali kondisi idle; tampilannya dilukis sebagai vektor.
    val ICONS: List<String> = listOf("AI", "✦", "◎", "◇", "✓", "A", "✧")
    val ICON_NAMES: List<String> = listOf("Robot", "Kilau", "Cincin", "Berlian", "Centang", "Huruf A", "Bintang")

    fun color(c: Context): Int = THEMES[Prefs.bubbleTheme(c).coerceIn(0, THEMES.size - 1)].color

    fun icon(c: Context): String = ICONS[Prefs.bubbleIcon(c).coerceIn(0, ICONS.size - 1)]

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * a).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * a).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * a).toInt()
        )
    }

    fun background(c: Context, color: Int): GradientDrawable {
        val d = c.resources.displayMetrics.density
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(blend(color, Color.WHITE, 0.24f), color, blend(color, Color.BLACK, 0.16f))
        )
        g.cornerRadius = when (Prefs.bubbleShape(c)) {
            0 -> 999f * d
            1 -> 17f * d
            else -> 7f * d
        }
        g.setStroke((1.4f * d).toInt().coerceAtLeast(1), Color.argb(190, 255, 255, 255))
        return g
    }
}
