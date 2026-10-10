package com.assistant.ai

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import java.util.WeakHashMap

/** Feedback sentuh ringan yang dipakai ulang di halaman, pilihan dialog, dan kontrol dinamis. */
object MotionFx {
    private val installed = WeakHashMap<View, Boolean>()

    fun install(root: View?) {
        if (root == null) return
        if (isInteractive(root)) installOn(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) install(root.getChildAt(i))
        }
    }

    fun installOn(view: View) {
        if (!isInteractive(view) || installed.containsKey(view)) return
        installed[view] = true
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (v.isEnabled) {
                        v.animate().cancel()
                        v.animate().scaleX(0.96f).scaleY(0.96f)
                            .setDuration(85).setInterpolator(AccelerateInterpolator()).start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(220).setInterpolator(OvershootInterpolator(1.18f)).start()
                }
            }
            false
        }
    }

    fun popIn(view: View, duration: Long = 220L) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.translationY = 5f * view.resources.displayMetrics.density
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(duration).setInterpolator(OvershootInterpolator(0.75f)).start()
    }

    private fun isInteractive(view: View): Boolean =
        view.isClickable && (view is TextView || view is ImageView || view is CompoundButton || view is AppCompatButton)
}
