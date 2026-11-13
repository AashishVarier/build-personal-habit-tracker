package com.variableduck.build.habittracker.backup

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Replacing a backup file only removes the old one once the new one is written. */
@RunWith(AndroidJUnit4::class)
class BackupFolderWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun open(uri: Uri): OutputStream = FileOutputStream(File(uri.path!!))

    private fun write(dir: File, writer: (OutputStream) -> Unit): String =
        BackupFolder.writeReplacing(DocumentFile.fromFile(dir), "backup.xlsx", BackupManager.XLSX_MIME, ::open, writer)

    @Test
    fun `a failed write leaves the existing file untouched`() {
        val dir = tmp.newFolder()
        File(dir, "backup.xlsx").writeText("old")

        assertThrows(IOException::class.java) {
            write(dir) { out ->
                out.write("half".toByteArray())
                throw IOException("No space left")
            }
        }

        assertEquals("old", File(dir, "backup.xlsx").readText())
        assertEquals(listOf("backup.xlsx"), dir.list()!!.toList())
    }

    @Test
    fun `a successful write replaces the file and cleans up`() {
        val dir = tmp.newFolder()
        File(dir, "backup.xlsx").writeText("old")
        File(dir, "backup.xlsx${BackupFolder.PARTIAL_SUFFIX}").writeText("left over from an interrupted write")

        val name = write(dir) { it.write("new".toByteArray()) }

        assertEquals("backup.xlsx", name)
        assertEquals("new", File(dir, "backup.xlsx").readText())
        assertEquals(listOf("backup.xlsx"), dir.list()!!.toList())
    }
}
