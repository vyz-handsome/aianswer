package com.assistant.ai

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Info perangkat yang dibaca & dikirim ke server saat app user login (lihat owner-user-app-system). */
internal object DeviceInfo {

    @SuppressLint("HardwareIds")
    fun androidId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    fun brand(): String = Build.BRAND ?: ""

    fun model(): String = Build.MODEL ?: ""

    fun androidVersion(): String = Build.VERSION.RELEASE ?: ""

    fun appVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Nama kustom device, bisa diubah user di Pengaturan; default-nya nama model HP. */
    fun customName(c: Context): String {
        val saved = Prefs.deviceCustomName(c)
        return saved.ifBlank { model() }
    }
}
