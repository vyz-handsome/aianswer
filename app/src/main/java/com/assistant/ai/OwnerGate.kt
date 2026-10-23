package com.assistant.ai

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Gate untuk build OWNER (com.senxvv.assistant). Dua tahap:
 *  1) Pairing: sekali saja, masukkan kode dari bot Telegram (/setting pairing)
 *     -> dapat owner_token permanen, disimpan di Prefs.
 *  2) Sesudah pairing ada: wajib sidik jari tiap kali app dibuka (tanpa PIN/pola
 *     fallback, sesuai keputusan). Sukses sidik jari = overlay ini hilang.
 */
internal class OwnerGate(private val activity: AppCompatActivity) {
    private lateinit var overlay: FrameLayout

    fun show() {
        ServerConfig.refreshInBackground(activity)
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.WHITE)
        }
        host.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        if (Prefs.ownerToken(activity).isEmpty()) {
            showPairingForm()
        } else {
            showBiometricPrompt()
        }
    }

    private fun dismiss() {
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    private fun showBiometricPrompt() {
        val manager = BiometricManager.from(activity)
        val can = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(activity, "Sidik jari wajib diaktifkan dulu di pengaturan HP.", Toast.LENGTH_LONG).show()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                dismiss()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Wajib sidik jari, tidak ada jalan lain: biarkan overlay tetap, user bisa coba lagi.
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verifikasi Owner")
            .setSubtitle("Gunakan sidik jari untuk membuka app")
            .setNegativeButtonText("Batal")
            .build()
        prompt.authenticate(info)
    }

    private fun showPairingForm() {
        overlay.removeAllViews()
        val stage = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        overlay.addView(stage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        stage.addView(TextView(activity).apply {
            text = "Pairing App Owner"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        stage.addView(TextView(activity).apply {
            text = "Kirim /setting pairing ke bot Telegram, lalu masukkan kodenya di sini."
            textSize = 13f
            setTextColor(0xFF737D92.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        })

        val input = EditText(activity).apply {
            hint = "Kode pairing"
            gravity = Gravity.CENTER
            textSize = 18f
        }
        stage.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        val status = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFC83F59.toInt())
            textSize = 12f
        }
        stage.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        val btn = Button(activity).apply { text = "Pairing" }
        stage.addView(btn)

        btn.setOnClickListener {
            val code = input.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            btn.isEnabled = false
            status.text = "Memeriksa..."
            status.setTextColor(0xFF737D92.toInt())
            ApiClient.ownerPair(activity, code) { result ->
                btn.isEnabled = true
                if (result.ok) {
                    Prefs.setOwnerToken(activity, result.body!!.optString("owner_token"))
                    showBiometricPrompt()
                } else {
                    status.setTextColor(0xFFC83F59.toInt())
                    status.text = if (result.httpCode == 0)
                        "Tidak bisa terhubung ke server." else "Kode pairing salah/sudah dipakai."
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
