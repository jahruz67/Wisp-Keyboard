package org.futo.inputmethod.latin.uix.addons

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

private const val DEFAULT_ADDON_MEDIA_MIME_TYPE = "application/octet-stream"
private val VALID_ADDON_MEDIA_MIME_TYPE =
    Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*")

internal fun sanitizeAddonMediaMimeType(value: String?): String {
    val mime = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    return mime.takeIf {
        it.length <= 127 && VALID_ADDON_MEDIA_MIME_TYPE.matches(it)
    } ?: DEFAULT_ADDON_MEDIA_MIME_TYPE
}

internal fun readAddonMediaMimeType(file: File): String {
    val sidecar = File(file.parentFile, "${file.name}.mime")
    val value = sidecar
        .takeIf { it.isFile && it.length() <= 256L }
        ?.readText()
    return sanitizeAddonMediaMimeType(value)
}

class AddonMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val file = resolve(uri) ?: return null
        return readAddonMediaMimeType(file)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Add-on media is read-only.")
        val file = resolve(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    private fun resolve(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val addonId = segments[0]
        val handle = segments[1]
        if (!addonId.matches(Regex("[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9_-]*)+"))) return null
        if (!handle.matches(Regex("[a-f0-9-]{36}"))) return null
        val manager = AddonManager.get(context)
        if (manager.get(addonId) == null) return null
        val directory = manager.mediaDirectory(addonId).canonicalFile
        val file = File(directory, handle).canonicalFile
        return file.takeIf {
            it.parentFile == directory && it.isFile
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
