package com.cedagova.fastreader.library.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.cedagova.fastreader.library.DocumentGateway
import com.cedagova.fastreader.library.DocumentLookup
import com.cedagova.fastreader.library.DocumentRef
import com.cedagova.fastreader.library.FolderListing
import java.io.IOException
import java.io.InputStream

/**
 * Storage Access Framework implementation of [DocumentGateway].
 *
 * Only read grants are ever taken: the app reads books where they are and never
 * writes to them (AD-1). Access failures are classified into "the file is gone"
 * and "the grant was revoked" so the library can offer removal or a re-grant.
 */
class SafDocumentGateway(context: Context) : DocumentGateway {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun persistReadPermission(uri: String, isTree: Boolean): Boolean = try {
        resolver.takePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    override fun releaseReadPermission(uri: String, isTree: Boolean) {
        try {
            resolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Already released or never held; nothing to undo.
        }
    }

    override fun lookup(uri: String): DocumentLookup {
        val parsed = uri.toUri()
        if (!hasReadGrant(parsed)) return DocumentLookup.PermissionLost
        return try {
            resolver.query(parsed, DOCUMENT_PROJECTION, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    DocumentLookup.Found(cursor.toRef(uri))
                } else {
                    DocumentLookup.Missing
                }
            }
        } catch (_: SecurityException) {
            DocumentLookup.PermissionLost
        } catch (_: Exception) {
            DocumentLookup.Missing
        }
    }

    override fun listEpubs(treeUri: String): FolderListing {
        val tree = treeUri.toUri()
        if (!hasReadGrant(tree)) return FolderListing.PermissionLost
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            return FolderListing.Missing
        }

        val found = ArrayList<DocumentRef>()
        val pending = ArrayDeque<String>().apply { add(rootId) }
        val visited = HashSet<String>()
        var rootReadable = false

        while (pending.isNotEmpty() && found.size < MAX_DOCUMENTS && visited.size < MAX_DIRECTORIES) {
            val documentId = pending.removeFirst()
            if (!visited.add(documentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val cursor: Cursor? = try {
                resolver.query(children, CHILD_PROJECTION, null, null, null)
            } catch (_: SecurityException) {
                return FolderListing.PermissionLost
            } catch (_: Exception) {
                null
            }
            if (cursor == null) {
                if (documentId == rootId) return FolderListing.Missing
                continue
            }
            if (documentId == rootId) rootReadable = true
            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getStringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                    val name = it.getStringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
                    val mimeType = it.getStringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.addLast(childId)
                    } else if (isEpub(name, mimeType)) {
                        found += DocumentRef(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString(),
                            displayName = name,
                            sizeBytes = it.getLongOrDefault(DocumentsContract.Document.COLUMN_SIZE, -1L),
                            lastModifiedEpochMs = it.getLongOrDefault(
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                                0L,
                            ),
                        )
                    }
                }
            }
        }

        return if (rootReadable) FolderListing.Listed(found) else FolderListing.Missing
    }

    override fun open(uri: String): InputStream =
        try {
            resolver.openInputStream(uri.toUri()) ?: throw IOException("could not open $uri")
        } catch (error: SecurityException) {
            throw IOException("access to the file was revoked", error)
        }

    override fun displayName(uri: String): String? {
        val parsed = uri.toUri()
        val documentUri = if (DocumentsContract.isTreeUri(parsed)) {
            try {
                DocumentsContract.buildDocumentUriUsingTree(parsed, DocumentsContract.getTreeDocumentId(parsed))
            } catch (_: Exception) {
                return null
            }
        } else {
            parsed
        }
        return try {
            resolver.query(documentUri, DOCUMENT_PROJECTION, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    cursor.getStringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A document picked from a tree is covered by the grant on that tree, so both
     * the exact URI and any tree it hangs under count.
     */
    private fun hasReadGrant(uri: Uri): Boolean {
        val target = uri.toString()
        return resolver.persistedUriPermissions.any { permission ->
            if (!permission.isReadPermission) return@any false
            val granted = permission.uri.toString()
            target == granted || target.startsWith("$granted/document/")
        }
    }

    private fun Cursor.toRef(uri: String) = DocumentRef(
        uri = uri,
        displayName = getStringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ?: uri.substringAfterLast('/'),
        sizeBytes = getLongOrDefault(DocumentsContract.Document.COLUMN_SIZE, -1L),
        lastModifiedEpochMs = getLongOrDefault(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0L),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getLongOrDefault(column: String, fallback: Long): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else fallback
    }

    private fun isEpub(displayName: String, mimeType: String?): Boolean =
        displayName.endsWith(EPUB_EXTENSION, ignoreCase = true) || mimeType == EPUB_MIME_TYPE

    companion object {
        const val EPUB_MIME_TYPE = "application/epub+zip"
        const val EPUB_EXTENSION = ".epub"

        /**
         * MIME types to open the system picker with. Many providers report EPUBs as
         * a generic binary, so restricting to the EPUB type alone hides real books.
         */
        val PICKER_MIME_TYPES = arrayOf(EPUB_MIME_TYPE, "application/octet-stream")

        private const val MAX_DOCUMENTS = 10_000
        private const val MAX_DIRECTORIES = 5_000

        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
