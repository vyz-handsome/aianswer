package com.assistant.ai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Gerbang app owner: hanya sidik jari (BIOMETRIC_STRONG, tanpa PIN/pola/sandi HP sebagai cadangan).
 * Menutupi layar sampai sidik jari cocok; lalu memudar.
 */
internal class FingerprintGate(
    private val activity: AppCompatActivity,
    private val onUnlocked: () -> Unit = {},
    /** true = setelah sidik jari cocok, server dicek dulu bahwa HP ini memang HP owner yang diaktivasi. */
    private val verifyOwner: Boolean = false,
    private val onNeedActivation: (String) -> Unit = {}
) {
    companion object {
        /** Waktu terakhir sidik jari berhasil (elapsedRealtime), 0 = belum pernah. */
        @Volatile var lastAuth = 0L
    }

    private lateinit var overlay: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var message: TextView
    private lateinit var retry: Button
    private lateinit var enroll: Button
    private var prompting = false
    private var done = false

    fun isShowing(): Boolean = ::overlay.isInitialized && overlay.parent != null && !done

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun show() {
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
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

        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(26), dp(24), dp(22))
            background = GlassDrawable(
                floatArrayOf(32f, 32f, 32f, 32f), 0x99FFFFFF.toInt(), 0x73FFFFFF, 0, 0, true
            )
        }
        val width = minOf(dp(380), activity.resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(dp(260))
        centered.addView(panel, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))

        val orb = FrameLayout(activity).apply { background = GlassPillDrawable() }
        orb.addView(PasswordCodeGate.LockGlyphView(activity), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        panel.addView(orb, LinearLayout.LayoutParams(dp(70), dp(70)).apply { bottomMargin = dp(15) })

        panel.addView(TextView(activity).apply {
            text = "Assistant Owner"
            setTextColor(0xFF1C2230.toInt())
            textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        message = TextView(activity).apply {
            text = "Gunakan sidik jari untuk membuka."
            setTextColor(0xFF737D92.toInt())
            textSize = 13.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        panel.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(16) })

        retry = Button(activity, null, 0, R.style.Btn_Primary).apply {
            text = "Buka dengan sidik jari"
            visibility = View.GONE
            setOnClickListener { prompt() }
        }
        panel.addView(retry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        enroll = Button(activity, null, 0, R.style.Btn_Secondary).apply {
            text = "Daftarkan sidik jari di HP"
            visibility = View.GONE
            setOnClickListener { openEnroll() }
        }
        panel.addView(enroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        host.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        overlay.post { prompt() }
    }

    private fun problem(text: String, canEnroll: Boolean) {
        message.text = text
        message.setTextColor(0xFFC83F59.toInt())
        retry.visibility = View.VISIBLE
        enroll.visibility = if (canEnroll) View.VISIBLE else View.GONE
    }

    private fun openEnroll() {
        try {
            val i = if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                )
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            activity.startActivity(i)
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e2: Exception) {
            }
        }
    }

    private fun prompt() {
        if (done || prompting) return
        val bm = BiometricManager.from(activity)
        when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {}
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                problem("Belum ada sidik jari yang terdaftar di HP ini. Daftarkan dulu, lalu kembali ke sini.", true)
                return
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                problem("HP ini tidak punya sensor sidik jari, jadi app owner tidak bisa dibuka.", false)
                return
            }
            else -> {
                problem("Sensor sidik jari belum bisa dipakai sekarang. Coba lagi.", false)
                return
            }
        }
        prompting = true
        message.text = "Tempelkan jari pada sensor."
        message.setTextColor(0xFF737D92.toInt())
        retry.visibility = View.GONE
        enroll.visibility = View.GONE
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    if (!verifyOwner) {
                        unlock()
                        return
                    }
                    message.text = "Memeriksa aktivasi..."
                    message.setTextColor(0xFF737D92.toInt())
                    retry.visibility = View.GONE
                    Thread {
                        val v = Remote.ownerVerify(activity)
                        activity.runOnUiThread {
                            if (done) return@runOnUiThread
                            when {
                                v.ok -> unlock()
                                v.needActivation -> {
                                    done = true
                                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                                    onNeedActivation(v.message)
                                }
                                else -> problem(v.message, false)
                            }
                        }
                    }.start()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    problem(errString.toString().ifEmpty { "Sidik jari dibatalkan." }, false)
                }

                override fun onAuthenticationFailed() {
                    // Jari tidak cocok: dialog sistem tetap terbuka dan memberi kesempatan lagi.
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Buka Assistant Owner")
            .setSubtitle("Tempelkan jari pada sensor")
            .setNegativeButtonText("Batal")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    private fun unlock() {
        if (done) return
        done = true
        lastAuth = SystemClock.elapsedRealtime()
        message.text = "Berhasil"
        message.setTextColor(0xFF2B9A78.toInt())
        panel.animate().alpha(0f).scaleX(0.975f).scaleY(0.975f).setDuration(380L)
            .setInterpolator(DecelerateInterpolator(1.7f)).start()
        overlay.animate().alpha(0f).setDuration(380L).setInterpolator(DecelerateInterpolator(1.7f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    onUnlocked()
                }
            }).start()
    }
}
