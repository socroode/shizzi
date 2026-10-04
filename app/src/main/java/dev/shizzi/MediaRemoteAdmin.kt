package dev.shizzi

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

data class MediaRemoteDirectory(
    val uri: String,
    val name: String,
)

object MediaRemoteSources {

    fun browse(context: Context, parentRaw: String?): JSONObject {
        val parent = parentRaw.orEmpty().trim()
        val entries = if (parent.isBlank()) {
            roots(context)
        } else {
            val uri = Uri.parse(parent)
            require(isAllowed(context, uri)) { "Source Media non autorisée par Android." }
            children(context, uri)
        }

        val currentName = parent.takeIf { it.isNotBlank() }?.let {
            displayName(context, Uri.parse(it))
        }.orEmpty()

        return JSONObject().apply {
            put("currentUri", parent)
            put("currentName", currentName)
            put(
                "entries",
                JSONArray().apply {
                    entries.forEach { item ->
                        put(JSONObject().apply {
                            put("uri", item.uri)
                            put("name", item.name)
                        })
                    }
                },
            )
        }
    }

    fun isAllowed(context: Context, uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return false

        return context.contentResolver.persistedUriPermissions.any { permission ->
            if (!permission.isReadPermission) return@any false
            val root = permission.uri
            val rootAuthority = root.authority ?: return@any false
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull()
                ?: return@any false
            rootAuthority == authority && rootId == treeId
        }
    }

    fun isWritable(context: Context, uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return false

        return context.contentResolver.persistedUriPermissions.any { permission ->
            if (!permission.isWritePermission) return@any false
            val root = permission.uri
            val rootAuthority = root.authority ?: return@any false
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull()
                ?: return@any false
            rootAuthority == authority && rootId == treeId
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
            ?: "Stockage Android"
    }

    private fun roots(context: Context): List<MediaRemoteDirectory> =
        context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .mapNotNull { permission ->
                val uri = permission.uri
                if (!DocumentsContract.isTreeUri(uri)) return@mapNotNull null
                MediaRemoteDirectory(
                    uri = uri.toString(),
                    name = displayName(context, uri),
                )
            }
            .distinctBy { it.uri }
            .sortedBy { it.name.lowercase() }
            .toList()

    private fun children(context: Context, parent: Uri): List<MediaRemoteDirectory> {
        val documentId = runCatching { DocumentsContract.getDocumentId(parent) }
            .getOrElse { DocumentsContract.getTreeDocumentId(parent) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        val out = mutableListOf<MediaRemoteDirectory>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIndex)
                if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                val id = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex)?.ifBlank { "Dossier" } ?: "Dossier"
                val uri = DocumentsContract.buildDocumentUriUsingTree(parent, id)
                if (!isAllowed(context, uri)) continue
                out += MediaRemoteDirectory(uri.toString(), name)
            }
        }

        return out.sortedBy { it.name.lowercase() }
    }
}
