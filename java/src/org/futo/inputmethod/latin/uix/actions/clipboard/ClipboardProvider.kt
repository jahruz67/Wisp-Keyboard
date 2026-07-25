package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.futo.inputmethod.latin.BuildConfig
import java.io.File
import java.util.UUID


val CLIPBOARD_AUTHORITY = BuildConfig.APPLICATION_ID + ".clipboard"
private val CODE_CLIP = 1

private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
    addURI(CLIPBOARD_AUTHORITY, "clip/*", CODE_CLIP)
}


data class ClipboardPasteRequest(
    val file: File,
    val mimeType: String,
    val expiration: Long
)

object ClipboardProviderState {
    private val requests: HashMap<UUID, ClipboardPasteRequest> = HashMap()

    @Synchronized
    fun addRequest(request: ClipboardPasteRequest): UUID {
        pruneExpiredRequests(System.currentTimeMillis())
        val uuid = UUID.randomUUID()
        requests[uuid] = request
        return uuid
    }

    @Synchronized
    fun fulfillRequest(uuid: UUID): ParcelFileDescriptor? {
        val request = getValidRequest(uuid)
        return ParcelFileDescriptor.open(request.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Synchronized
    fun getMimeType(uuid: UUID): String {
        return getValidRequest(uuid).mimeType
    }

    private fun getValidRequest(uuid: UUID): ClipboardPasteRequest {
        val now = System.currentTimeMillis()
        pruneExpiredRequests(now)
        return requests[uuid]?.takeIf { now <= it.expiration }
            ?: throw IllegalArgumentException("Invalid request")
    }

    private fun pruneExpiredRequests(now: Long) {
        requests.entries.removeAll { (_, request) -> now > request.expiration }
    }
}


class ClipboardProvider: ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String?>?,
        selection: String?,
        selectionArgs: Array<out String?>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    private fun getUUID(uri: Uri): UUID {
        if (URI_MATCHER.match(uri) != CODE_CLIP) {
            throw IllegalArgumentException("Unsupported URI: $uri")
        }

        val id = uri.lastPathSegment ?: throw IllegalArgumentException("Invalid URI")

        return UUID.fromString(id)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor?
            = ClipboardProviderState.fulfillRequest(getUUID(uri))

    override fun getType(uri: Uri): String
            = ClipboardProviderState.getMimeType(getUUID(uri))


    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = throw UnsupportedOperationException("Provider is read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String?>?
    ): Int = throw UnsupportedOperationException("Provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String?>?
    ): Int = throw UnsupportedOperationException("Provider is read-only")
}
