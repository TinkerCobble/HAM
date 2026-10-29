package com.tinkercobble.ham.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tinkercobble.ham.data.EventTemplateStore
import com.tinkercobble.ham.data.LocalBackupContent
import com.tinkercobble.ham.data.LocalTaskStore
import com.tinkercobble.ham.data.PortableLocalBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** SAF export/import for app-owned tasks and templates; provider accounts and tokens are excluded. */
@Composable
fun LocalBackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks = remember(context) { LocalTaskStore(context) }
    val templates = remember(context) { EventTemplateStore(context) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<LocalBackupContent?>(null) }
    var importCounts by remember { mutableStateOf(0 to 0) }
    DisposableEffect(Unit) { onDispose { pendingExport?.fill(0) } }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (bytes != null) {
            if (uri == null) { bytes.fill(0); message = "Backup save canceled" }
            else scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(bytes)
                            output.flush()
                        } ?: error("Cannot open selected file")
                        true
                    } catch (_: Exception) { false }
                    finally { bytes.fill(0) }
                }
                message = if (result) "Encrypted local backup saved" else
                    "Backup could not be saved. Delete any incomplete file at the chosen location."
                busy = false
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val password = passphrase.toCharArray()
            passphrase = ""
            confirm = ""
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        val output = ByteArrayOutputStream()
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 2_000_000) error("Backup is too large")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Cannot open selected file")
                    try {
                        val content = PortableLocalBackup.decrypt(bytes, password)
                        Triple(content, tasks.additions(content.tasks), templates.additions(content.templates))
                    } finally { bytes.fill(0) }
                }.also { password.fill('\u0000') } }
                result.onSuccess { (content, taskCount, templateCount) ->
                    pendingImport = content
                    importCounts = taskCount to templateCount
                    message = ""
                }.onFailure { message = "Backup could not be opened or merged. Check its passphrase and existing local content." }
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Encrypted local content backup", style = MaterialTheme.typography.headlineMedium)
        Text("Back up local tasks and event templates. Calendar events, connections, tokens, and pending changes are excluded.")
        Text("Choose a strong passphrase and keep it safe. HAM cannot recover it. Android's file picker chooses the save location.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(passphrase, { passphrase = it.take(256) }, label = { Text("Backup passphrase") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(confirm, { confirm = it.take(256) }, label = { Text("Confirm for export") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            val password = passphrase.toCharArray()
            passphrase = ""
            confirm = ""
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { runCatching {
                    PortableLocalBackup.encrypt(LocalBackupContent(tasks.list(), templates.list()), password)
                }.also { password.fill('\u0000') } }
                result.onSuccess { bytes ->
                    pendingExport = bytes
                    saveFile.launch("ham-local-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.hambak")
                }.onFailure { message = "Local content could not be prepared for backup" }
                busy = false
            }
        }, enabled = !busy && passphrase.length >= 12 && passphrase == confirm) { Text("Save encrypted backup") }
        OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) },
            enabled = !busy && passphrase.length >= 12) { Text("Open encrypted backup") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }

    pendingImport?.let { content ->
        AlertDialog(onDismissRequest = { pendingImport = null },
            title = { Text("Restore local content?") },
            text = { Text("Backup contains ${content.tasks.size} local tasks and ${content.templates.size} event templates. ${importCounts.first} tasks and ${importCounts.second} templates would be added. Existing items stay unchanged; matching items are skipped. No calendar connection or event is restored.") },
            confirmButton = { Button(onClick = {
                pendingImport = null
                scope.launch {
                    busy = true
                    val taskResult = withContext(Dispatchers.IO) { runCatching { tasks.merge(content.tasks) } }
                    if (taskResult.isFailure) {
                        message = "Local tasks could not be restored. Existing items were kept."
                    } else {
                        val templateResult = withContext(Dispatchers.IO) { runCatching { templates.merge(content.templates) } }
                        message = if (templateResult.isSuccess)
                            "Restored ${taskResult.getOrThrow()} tasks and ${templateResult.getOrThrow()} templates"
                        else "Restored ${taskResult.getOrThrow()} tasks; templates could not be restored. Existing items were kept. You can retry the backup."
                    }
                    busy = false
                }
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } })
    }
}
