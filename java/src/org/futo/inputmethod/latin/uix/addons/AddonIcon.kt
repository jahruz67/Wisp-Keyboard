package org.futo.inputmethod.latin.uix.addons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.PathParser
import java.io.File

private const val MAX_SVG_ICON_BYTES = 256L * 1024L
private const val MAX_SVG_PATHS = 256
private const val MAX_RASTER_ICON_DIMENSION = 8_192

fun decodeAddonIcon(path: String, outputSize: Int = 96): Bitmap? {
    val file = File(path)
    if (!file.isFile || outputSize !in 1..512) return null

    if (file.extension.lowercase() != "svg") {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (
            bounds.outWidth !in 1..MAX_RASTER_ICON_DIMENSION ||
            bounds.outHeight !in 1..MAX_RASTER_ICON_DIMENSION
        ) {
            return null
        }
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > outputSize * 2 ||
            bounds.outHeight / sampleSize > outputSize * 2
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    if (file.length() > MAX_SVG_ICON_BYTES) return null

    return runCatching {
        val svg = file.readText()
        val viewBox = Regex("""viewBox\s*=\s*"([^"]+)"""")
            .find(svg)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.map { it.toFloat() }
            ?.takeIf { it.size == 4 }
            ?: listOf(0f, 0f, 24f, 24f)
        require(viewBox.all { it.isFinite() } && viewBox[2] > 0f && viewBox[3] > 0f)
        val paths = Regex("""<path\b[^>]*\bd\s*=\s*"([^"]+)"[^>]*/?>""")
            .findAll(svg)
            .map { it.groupValues[1] }
            .toList()
        require(paths.isNotEmpty() && paths.size <= MAX_SVG_PATHS)

        Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.scale(outputSize / viewBox[2], outputSize / viewBox[3])
            canvas.translate(-viewBox[0], -viewBox[1])
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            paths.forEach { data ->
                PathParser.createPathFromPathData(data)?.let { canvas.drawPath(it, paint) }
            }
        }
    }.getOrNull()
}
