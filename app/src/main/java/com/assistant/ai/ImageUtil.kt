package com.assistant.ai

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Baca gambar dari galeri untuk dikirim ke chat: diperkecil, diputar sesuai EXIF, jadi JPEG base64. */
object ImageUtil {
    class Loaded(val bitmap: Bitmap, val base64: String)

    fun loadForChat(cr: ContentResolver, uri: Uri, maxSide: Int = 1280): Loaded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val longest = maxOf(raw.width, raw.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f

        var bmp = raw
        if (deg != 0f || scale < 1f) {
            val m = Matrix()
            if (deg != 0f) m.postRotate(deg)
            if (scale < 1f) m.postScale(scale, scale)
            bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
        if (bmp.hasAlpha()) {
            // JPEG tidak punya transparansi: taruh di atas latar putih supaya tidak jadi hitam.
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(flat)
            c.drawColor(Color.WHITE)
            c.drawBitmap(bmp, 0f, 0f, null)
            bmp = flat
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Loaded(bmp, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}
