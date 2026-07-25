package org.futo.inputmethod.latin.uix

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import org.futo.inputmethod.latin.uix.addons.decodeAddonIcon
import java.io.File

fun Action.displayName(context: Context): String =
    dynamicName ?: context.getString(name)

@Composable
fun Action.displayPainter(): Painter {
    val path = dynamicIconPath
    if (path != null) {
        val lastModified = File(path).lastModified()
        val bitmap = remember(path, lastModified) {
            decodeAddonIcon(path)?.asImageBitmap()
        }
        if (bitmap != null) return remember(bitmap) { BitmapPainter(bitmap) }
    }
    return painterResource(icon)
}
