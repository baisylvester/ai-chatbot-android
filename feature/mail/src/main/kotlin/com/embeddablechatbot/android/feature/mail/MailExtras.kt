package com.embeddablechatbot.android.feature.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import com.embeddablechatbot.android.core.domain.mail.DownloadedFile
import com.embeddablechatbot.android.core.domain.mail.LabelColors
import com.embeddablechatbot.android.core.domain.mail.MailLabel
import com.embeddablechatbot.android.core.domain.mail.MessageAttachment
import com.embeddablechatbot.android.core.domain.mail.OutgoingAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serves downloaded attachments to the viewer app the user picks. A subclass keeps its manifest entry unique. */
class MailFileProvider : FileProvider()

fun readableSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
fun labelColor(label: MailLabel): Color =
    label.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary

@Composable
fun LabelChips(labels: List<MailLabel>, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(label.name, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Checkboxes for the user's labels; applies only the difference when confirmed. */
@Composable
fun LabelPicker(labels: List<MailLabel>, current: Set<String>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit, onNew: () -> Unit) {
    var chosen by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_edit_labels)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (labels.isEmpty()) Text(stringResource(R.string.hub_no_labels), style = MaterialTheme.typography.bodyMedium)
                labels.forEach { label ->
                    val on = label.id in chosen
                    // The whole row toggles, announced as one checkbox.
                    Row(Modifier.fillMaxWidth().toggleable(value = on, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { chosen = if (it) chosen + label.id else chosen - label.id }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(label.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = onNew) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.hub_new_label)) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(chosen) }, enabled = chosen != current) { Text(stringResource(R.string.hub_apply_labels)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_cancel)) } },
    )
}

@Composable
fun NewLabelDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_new_label)) },
        text = { OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.hub_label_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.hub_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_cancel)) } },
    )
}

/** Rename, recolor (Gmail's palette only) or delete a label. Delete asks again before acting. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditLabelDialog(label: MailLabel, onDismiss: () -> Unit, onSave: (String, String?) -> Unit, onDelete: () -> Unit) {
    var name by rememberSaveable(label.id) { mutableStateOf(label.name) }
    var color by rememberSaveable(label.id) { mutableStateOf(label.color?.lowercase()) }
    var confirmDelete by rememberSaveable(label.id) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.hub_delete_label)) },
            text = { Text(stringResource(R.string.hub_delete_label_confirm, label.name)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.hub_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.hub_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_edit_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.hub_label_name)) }, singleLine = true)
                Text(stringResource(R.string.hub_label_color), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelColors.forEachIndexed { index, hex ->
                        val chosen = color == hex
                        val description = stringResource(R.string.hub_label_color_n, index + 1)
                        Box(
                            Modifier.size(36.dp).background(Color(android.graphics.Color.parseColor(hex)), CircleShape)
                                .then(if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = hex }
                                .semantics { contentDescription = description; selected = chosen },
                            contentAlignment = Alignment.Center,
                        ) { if (chosen) Icon(Icons.Default.Check, null, tint = Color.White) }
                    }
                }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.hub_delete_label), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, color) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.hub_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_cancel)) } },
    )
}

@Composable
fun AttachmentList(attachments: List<MessageAttachment>, busy: String?, onOpen: (MessageAttachment) -> Unit, onSummarize: (MessageAttachment) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.hub_attachments, attachments.size), style = MaterialTheme.typography.labelLarge)
        attachments.forEach { file ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📎")
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(readableSize(file.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (busy == file.partId) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else {
                        if (file.readable) TextButton(onClick = { onSummarize(file) }, enabled = busy == null) { Text("✦ ${stringResource(R.string.hub_summarize)}") }
                        TextButton(onClick = { onOpen(file) }, enabled = busy == null) { Text(stringResource(R.string.hub_open)) }
                    }
                }
            }
        }
    }
}

/**
 * Writes the attachment to app-private cache and offers it to a viewer app. The file is readable
 * only through a temporary, single-URI grant; nothing is placed in shared storage.
 */
suspend fun openDownloadedFile(context: Context, file: DownloadedFile) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "mail_attachments").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val safe = file.filename.replace(Regex("[^A-Za-z0-9._ -]"), "_").trimStart('.').take(120).ifBlank { "attachment" }
        val target = File(dir, safe)
        target.writeBytes(file.bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.mailfiles", target)
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, file.filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.hub_no_viewer, Toast.LENGTH_LONG).show()
    }
}

private suspend fun readPicked(context: Context, uri: Uri): Triple<String, String, ByteArray?> = withContext(Dispatchers.IO) {
    var name = "attachment"; var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it)?.let { n -> name = n } }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
        }
    }
    val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
    if (size > OutgoingAttachment.MAX_TOTAL_BYTES) return@withContext Triple(name, type, null)
    // Read at most one byte past the limit, whatever size the provider reported.
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(64 * 1024); var total = 0L
        while (true) { val n = input.read(chunk); if (n < 0) break; total += n; if (total > OutgoingAttachment.MAX_TOTAL_BYTES) return@use null; buffer.write(chunk, 0, n) }
        buffer.toByteArray()
    }
    Triple(name, type, bytes)
}

/** The message body with a small formatting toolbar. Formatting is sent as HTML with a plain-text copy. */
@Composable
fun ComposerBody(state: ComposerState, vm: MailHubViewModel) {
    val enabled = !state.saving && !state.uncertain
    var field by remember { mutableStateOf(TextFieldValue(state.body, TextRange(state.body.length))) }
    // Keep the local selection-aware value in step with changes made elsewhere (AI rewrite, restore).
    // Compare with the ViewModel's current text, not the collected (possibly older) state: during fast
    // typing the collected value lags one frame behind, and resetting to it would drop characters.
    LaunchedEffect(state.body) {
        val current = vm.composer.value.body
        if (field.text != current) field = TextFieldValue(current, TextRange(current.length))
    }
    fun mark(style: TextStyleMark) {
        val selection = vm.formatBody(field.selection.min, field.selection.max, style) ?: return
        field = TextFieldValue(vm.composer.value.body, TextRange(selection.first, selection.second))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.formatted, onCheckedChange = vm::setFormatted, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hub_formatted), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.formatted) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = { mark(TextStyleMark.Bold) }, enabled = enabled) { Text("B", fontWeight = FontWeight.Bold) }
            FilledTonalIconButton(onClick = { mark(TextStyleMark.Italic) }, enabled = enabled) { Text("I", fontStyle = FontStyle.Italic) }
            FilledTonalIconButton(onClick = { mark(TextStyleMark.Bullets) }, enabled = enabled) { Icon(Icons.Default.List, stringResource(R.string.hub_bullets)) }
            FilledTonalIconButton(onClick = { mark(TextStyleMark.Numbers) }, enabled = enabled) { val d = stringResource(R.string.hub_numbers); Text("1.", Modifier.semantics { contentDescription = d }, fontWeight = FontWeight.Bold) }
            FilledTonalIconButton(onClick = { mark(TextStyleMark.Link) }, enabled = enabled) { val d = stringResource(R.string.hub_link); Text("🔗", Modifier.semantics { contentDescription = d }) }
        }
        OutlinedTextField(
            field, { value -> field = value; if (value.text != state.body) vm.editComposer(body = value.text) },
            placeholder = { Text(stringResource(R.string.hub_message)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
        )
        if (state.formatted) Text(stringResource(R.string.hub_formatted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ComposerAttachments(state: ComposerState, vm: MailHubViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val enabled = !state.saving && !state.uncertain
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            uris.forEach { uri ->
                val (name, type, bytes) = runCatching { readPicked(context, uri) }.getOrElse { Triple("attachment", "", null) }
                if (bytes == null) Toast.makeText(context, resources.getString(R.string.hub_attach_failed, name), Toast.LENGTH_LONG).show()
                else vm.addAttachment(name, type, bytes)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.attachments.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📎")
                Spacer(Modifier.width(8.dp))
                Text("${file.filename} · ${readableSize(file.size)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { vm.removeAttachment(file.id) }, enabled = enabled) { Icon(Icons.Default.Close, stringResource(R.string.hub_remove_attachment, file.filename)) }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled && state.attachments.size < OutgoingAttachment.MAX_FILES) {
            Text("📎"); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.hub_attach))
        }
        if (state.attachments.isNotEmpty()) Text(stringResource(R.string.hub_attach_total, readableSize(state.attachmentBytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
