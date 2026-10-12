package cn.liuheo.twofauth

import android.content.ContentResolver
import android.graphics.*
import android.net.Uri
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object QrImages {
    fun qrBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    private fun image(resolver: ContentResolver, uri: Uri, maxSize: Int): Bitmap {
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                require(output.size() + n <= 16_000_000) { "图片超过 16 MB，请使用较小的截图" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法读取所选图片")
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是有效图片" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalArgumentException("图片无法解码")
        } finally { bytes.fill(0) }
    }

    fun decode(resolver: ContentResolver, uri: Uri): String {
        val original = image(resolver, uri, 2048)
        try {
            for (rotation in listOf(0f, 90f, 180f, 270f)) {
                val bitmap = if (rotation == 0f) original else Bitmap.createBitmap(original, 0, 0,
                    original.width, original.height, Matrix().apply { postRotate(rotation) }, true)
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                    for (luminance in listOf(source, source.invert())) {
                        val result = runCatching {
                            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(luminance)), mapOf(
                                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
                        }.getOrNull()
                        if (result != null) return AccountCodec.safeOtpauth(result.text)
                    }
                } finally { if (bitmap !== original) bitmap.recycle() }
            }
            throw IllegalArgumentException("没有识别到二维码，请选择清晰、完整的二维码图片")
        } finally { original.recycle() }
    }

    fun icon(resolver: ContentResolver, uri: Uri): ByteArray {
        val original = image(resolver, uri, 512)
        val target = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(target)
            val scale = minOf(256f / original.width, 256f / original.height)
            val width = original.width * scale; val height = original.height * scale
            canvas.drawBitmap(original, null, RectF((256-width)/2, (256-height)/2, (256+width)/2, (256+height)/2), Paint(Paint.ANTI_ALIAS_FLAG))
            return ByteArrayOutputStream().apply { target.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        } finally { original.recycle(); target.recycle() }
    }

    fun serverIcon(server: String, filename: String): Bitmap? {
        val connection = URL("$server/storage/icons/$filename").openConnection() as HttpsURLConnection
        return try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 6000; connection.readTimeout = 6000
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 600_000) { "图标过大" }; output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            IconDecoder.decode(bytes)
        } finally { connection.disconnect() }
    }
}

