package org.futo.inputmethod.latin.uix.addons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.PathParser
import java.io.File

fun decodeAddonIcon(path: String, outputSize: Int = 96): Bitmap? {
    BitmapFactory.decodeFile(path)?.let { return it }
    val file = File(path)
    if (!file.isFile || file.extension.lowercase() != "svg") return null

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
        val paths = Regex("""<path\b[^>]*\bd\s*=\s*"([^"]+)"[^>]*/?>""")
            .findAll(svg)
            .map { it.groupValues[1] }
            .toList()
        require(paths.isNotEmpty())

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

