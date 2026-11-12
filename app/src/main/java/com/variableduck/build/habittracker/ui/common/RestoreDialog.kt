package com.variableduck.build.habittracker.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.variableduck.build.habittracker.backup.RestorePreview

/** Shows what a backup contains and asks for confirmation before replacing all data. */
@Composable
fun RestorePreviewDialog(
    preview: RestorePreview,
    hasBackupFolder: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formatter = rememberFormatter()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore this backup?") },
        text = {
            Column {
                preview.exportedOn?.let { Text("Backup made on ${formatter.date(it)}") }
                val range = if (preview.firstDate != null && preview.lastDate != null) {
                    "${formatter.date(preview.firstDate)} – ${formatter.date(preview.lastDate)}"
                } else {
                    "no dates"
                }
                Text("${preview.dayCount} days ($range)")
                Text(
                    "Habits: " + preview.activeHabitNames.joinToString(", ").ifEmpty { "none" } +
                        if (preview.archivedHabitCount > 0) " (+${preview.archivedHabitCount} archived)" else "",
                )
                Text("${preview.targetChangeCount} target changes")
                Spacer(Modifier.height(12.dp))
                Text(
                    "All current data will be replaced.",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    if (hasBackupFolder) {
                        "A copy of the current data is saved to your backup folder first."
                    } else {
                        "No backup folder is set, so no safety copy of the current data will be made."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
