package dev.ferride.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.ferride.core.SafDoc
import dev.ferride.core.SafException
import dev.ferride.core.SafTree

/**
 * A folder picked with the system picker (`ACTION_OPEN_DOCUMENT_TREE`),
 * served to the Rust core through `DocumentsContract`. Every call is a Binder
 * round trip to the provider, so [children] fetches a whole directory in one
 * query and the core caches listings. Errors become [SafException] (shown in
 * the UI).
 */
class SafDocumentTree(private val resolver: ContentResolver, private val treeUri: Uri) : SafTree {
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private val name: String by lazy {
        runCatching {
            resolver.query(docUri(rootDocId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: displayNameFromId(rootDocId)
    }

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    override fun rootId(): String = rootDocId

    override fun displayName(): String = name

    override fun children(dirId: String): List<SafDoc> = provider {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        SafDoc(
                            id = c.getString(0),
                            name = c.getString(1) ?: c.getString(0),
                            isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                            size = if (c.isNull(3)) 0 else c.getLong(3),
                            modifiedMs = if (c.isNull(4)) 0 else c.getLong(4),
                        ),
                    )
                }
            }
        } ?: fail("the folder can't be listed (was access revoked?)")
    }

    override fun read(id: String): ByteArray = provider {
        resolver.openInputStream(docUri(id))?.use { it.readBytes() } ?: fail("can't open for reading")
    }

    override fun write(id: String, bytes: ByteArray) = provider {
        // "wt" truncates; a few providers only accept "rwt".
        val out = runCatching { resolver.openOutputStream(docUri(id), "wt") }.getOrNull()
            ?: resolver.openOutputStream(docUri(id), "rwt")
            ?: fail("can't open for writing")
        out.use { it.write(bytes) }
    }

    override fun create(parentId: String, name: String, isDir: Boolean): String = provider {
        // octet-stream: providers keep the name as typed (no ".txt" appended).
        val mime = if (isDir) Document.MIME_TYPE_DIR else "application/octet-stream"
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name) ?: fail("can't create $name")
        DocumentsContract.getDocumentId(uri)
    }

    override fun rename(id: String, newName: String): String = provider {
        val uri = DocumentsContract.renameDocument(resolver, docUri(id), newName)
        if (uri != null) DocumentsContract.getDocumentId(uri) else id
    }

    override fun delete(id: String) = provider {
        if (!DocumentsContract.deleteDocument(resolver, docUri(id))) fail("can't delete")
    }

    private inline fun <T> provider(block: () -> T): T = try {
        block()
    } catch (e: SafException) {
        throw e
    } catch (e: Exception) {
        throw SafException.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun fail(msg: String): Nothing = throw SafException.Failed(msg)

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /** `primary:Projects/app` → `app`. */
        fun displayNameFromId(docId: String): String =
            docId.substringAfter(':').trimEnd('/').substringAfterLast('/').ifEmpty { docId.substringBefore(':') }
    }
}

/** Where external-storage tree URIs live on disk (for the foss build's all-files access). */
object StoragePaths {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * `content://com.android.externalstorage.documents/tree/primary%3AProjects%2Fapp`
     * → `/storage/emulated/0/Projects/app`; an SD card (`1A2B-3C4D:dir`) →
     * `/storage/1A2B-3C4D/dir`. Null for other providers (Drive, Termux, …):
     * those stay SAF.
     */
    fun treeUriToPath(authority: String?, treeDocumentId: String): String? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val volume = treeDocumentId.substringBefore(':', "")
        if (volume.isEmpty()) return null
        val rel = treeDocumentId.substringAfter(':').trim('/')
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
