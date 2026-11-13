package com.variableduck.build.habittracker.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user-picked backup folder (Storage Access Framework). The tree URI permission is persisted,
 * and files live outside the app's private storage, so backups survive an uninstall.
 */
@Singleton
class BackupFolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    /** Call with the URI returned by ACTION_OPEN_DOCUMENT_TREE. */
    fun persistAccess(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }

    fun releaseAccess(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    fun hasAccess(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }

    fun displayName(uri: Uri): String? = DocumentFile.fromTreeUri(context, uri)?.name

    /** The configured folder, or null if none is set or access was lost. */
    suspend fun folder(): DocumentFile? {
        val uri = settings.current().backupFolderUri?.toUri() ?: return null
        return withContext(Dispatchers.IO) {
            if (!hasAccess(uri)) return@withContext null
            DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }
        }
    }

    /** Writes [fileName], replacing an existing file of the same name once the new one is complete. */
    suspend fun write(fileName: String, mimeType: String, writer: (OutputStream) -> Unit): String =
        withContext(Dispatchers.IO) {
            val dir = folder() ?: throw IOException("No backup folder is set, or access to it was lost.")
            writeReplacing(dir, fileName, mimeType, { uri -> context.contentResolver.openOutputStream(uri, "wt") }, writer)
        }

    /** Keeps the newest [keep] files whose names match [pattern] and deletes older ones. */
    suspend fun prune(pattern: Regex, keep: Int): Int = withContext(Dispatchers.IO) {
        val dir = folder() ?: return@withContext 0
        val matching = dir.listFiles()
            .filter { it.isFile && it.name?.matches(pattern) == true }
            .sortedByDescending { it.name }
        matching.drop(keep).count { it.delete() }
    }

    companion object {
        internal const val PARTIAL_SUFFIX = ".partial"
        private const val PARTIAL_MIME = "application/octet-stream"

        /**
         * Writes the new file under a temporary name first, so an existing [fileName] is only
         * removed once its replacement has been written completely. A failed write leaves the old
         * file untouched. Returns the name the file was saved under.
         */
        internal fun writeReplacing(
            dir: DocumentFile,
            fileName: String,
            mimeType: String,
            open: (Uri) -> OutputStream?,
            writer: (OutputStream) -> Unit,
        ): String {
            val partialName = fileName + PARTIAL_SUFFIX
            dir.findFile(partialName)?.delete() // left over from an interrupted write
            val partial = createAndWrite(dir, partialName, PARTIAL_MIME, open, writer)
            dir.findFile(fileName)?.delete()
            if (partial.renameTo(fileName)) return partial.name ?: fileName
            // This storage provider can't rename files: write the final file directly instead.
            partial.delete()
            return createAndWrite(dir, fileName, mimeType, open, writer).name ?: fileName
        }

        private fun createAndWrite(
            dir: DocumentFile,
            name: String,
            mimeType: String,
            open: (Uri) -> OutputStream?,
            writer: (OutputStream) -> Unit,
        ): DocumentFile {
            val file = dir.createFile(mimeType, name) ?: throw IOException("Could not create $name.")
            try {
                open(file.uri)?.use(writer) ?: throw IOException("Could not open $name for writing.")
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            return file
        }
    }
}
