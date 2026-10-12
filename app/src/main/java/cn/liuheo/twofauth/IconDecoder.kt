package cn.liuheo.twofauth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG

object IconDecoder {
    fun decode(bytes: ByteArray): Bitmap? {
        require(bytes.size <= 600_000) { "图标过大" }
        val text = bytes.toString(Charsets.UTF_8)
        if (Regex("<svg(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            // Only render self-contained SVG. Never expand entities or resolve external files.
            require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)|<\\s*(?:script|foreignObject)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)) { "图标含不支持的内容" }
            require(text.count { it == '<' } <= 8000) { "图标过于复杂" }
            SVG.setInternalEntitiesEnabled(false)
            val svg = SVG.getFromString(text)
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, 128f, 128f))
                return bitmap
            } catch (failure: Exception) { bitmap.recycle(); throw failure }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

