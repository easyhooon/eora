package com.easyhooon.eora.internal.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.easyhooon.eora.R
import com.easyhooon.eora.RejectReason
import com.easyhooon.eora.internal.metadata.MetadataEntry
import com.easyhooon.eora.internal.metadata.ReproDescription
import com.easyhooon.eora.internal.report.ArtifactKind
import com.easyhooon.eora.internal.report.ArtifactStatus
import com.easyhooon.eora.internal.report.DraftAttachment
import com.easyhooon.eora.internal.report.IssueStatus
import com.easyhooon.eora.internal.report.ReportStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DetailsScreen(controller: ReportController, modifier: Modifier, onBack: () -> Unit, onNext: () -> Unit) {
    val draft by controller.draft.collectAsState()
    val busy by controller.busy.collectAsState()
    val current = draft ?: return
    var title by rememberSaveable { mutableStateOf(current.description.title) }
    var preconditions by rememberSaveable { mutableStateOf(current.description.preconditions) }
    var observed by rememberSaveable { mutableStateOf(current.description.observed) }
    var expected by rememberSaveable { mutableStateOf(current.description.expected) }
    var actual by rememberSaveable { mutableStateOf(current.description.actual) }
    var notes by rememberSaveable { mutableStateOf(current.description.notes) }
    var titleError by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.addMedia(uri)
    }

    Column(modifier) {
    EoraHeader(stringResource(R.string.eora_step_details), STEP_DETAILS, onBack)
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            title,
            {
                title = it
                titleError = false
            },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.eora_title_required)) },
            singleLine = true,
            isError = titleError,
            supportingText = if (titleError) ({ Text(stringResource(R.string.eora_title_missing)) }) else null,
        )
        listOf(
            Triple(preconditions, { v: String -> preconditions = v }, R.string.eora_preconditions),
            Triple(observed, { v: String -> observed = v }, R.string.eora_observed),
            Triple(expected, { v: String -> expected = v }, R.string.eora_expected),
            Triple(actual, { v: String -> actual = v }, R.string.eora_actual),
            Triple(notes, { v: String -> notes = v }, R.string.eora_notes),
        ).forEach { (value, onChange, label) ->
            OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, minLines = 2)
        }
        Text(stringResource(R.string.eora_no_personal_data), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text(stringResource(R.string.eora_files), style = MaterialTheme.typography.titleMedium)
        current.attachments.filter { it.kind != ArtifactKind.METADATA }.forEach { attachment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${attachment.fileName} · ${formatBytes(attachment.sizeBytes)}", Modifier.weight(1f))
                if (attachment.kind == ArtifactKind.MEDIA) TextButton(onClick = { controller.removeAttachment(attachment) }) { Text(stringResource(R.string.eora_remove)) }
            }
        }
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            enabled = !busy,
        ) { Text(stringResource(R.string.eora_add_media)) }
        Text(stringResource(R.string.eora_video_warning), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text(stringResource(R.string.eora_included_metadata), style = MaterialTheme.typography.titleMedium)
        current.metadata.forEach { Text("${metadataLabel(it)}: ${it.value}", style = MaterialTheme.typography.bodyMedium) }

    }
    Button(
        onClick = {
            val description = ReproDescription(title.trim(), preconditions, observed, expected, actual, notes)
            if (!description.hasTitle) {
                titleError = true
                return@Button
            }
            controller.updateDescription(description)
            onNext()
        },
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(52.dp),
    ) { Text(stringResource(R.string.eora_review)) }
    }
}

@Composable
internal fun ReviewScreen(controller: ReportController, modifier: Modifier, onBack: () -> Unit, onSent: () -> Unit) {
    val draft by controller.draft.collectAsState()
    val current = draft ?: return
    val issueText = remember(current.revision) { controller.issueText(current) }

    Column(modifier) {
    EoraHeader(stringResource(R.string.eora_step_review), STEP_REVIEW, onBack)
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.eora_destination, controller.uploaderName), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.eora_creates_issue), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.eora_issue_title_label), style = MaterialTheme.typography.titleMedium)
        Text(current.description.title, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.eora_issue_body_label), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Text(issueText, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text(stringResource(R.string.eora_exact_files), style = MaterialTheme.typography.titleMedium)
        current.attachments.forEach { attachment -> AttachmentPreview(controller, attachment) }

        HorizontalDivider()
        // The whole row toggles, not just the box: people tap the sentence.
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = current.isApproved,
                    enabled = current.isReviewable && !current.isApproved,
                    role = Role.Checkbox,
                    onValueChange = { if (it) controller.approve() },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = current.isApproved, onCheckedChange = null)
            Text(stringResource(R.string.eora_approve))
        }
    }
    Button(
        onClick = {
            controller.send()
            onSent()
        },
        enabled = current.isApproved,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(52.dp),
    ) { Text(stringResource(R.string.eora_create_and_attach, current.attachments.size)) }
    }
}

@Composable
private fun AttachmentPreview(controller: ReportController, attachment: DraftAttachment) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${attachment.fileName} · ${attachment.mimeType} · ${formatBytes(attachment.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
            when {
                attachment.mimeType.startsWith("image/") -> {
                    val image = rememberDecodedImage(controller.fileOf(attachment))
                    if (image != null) {
                        Image(image, contentDescription = attachment.fileName, Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit)
                    }
                }
                attachment.kind == ArtifactKind.METADATA -> {
                    val text by produceState("", attachment.artifactId) { value = withContext(Dispatchers.IO) { controller.readText(attachment) } }
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                else -> Text(stringResource(R.string.eora_video_no_preview), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Decodes the file that will be sent, downsampled for display; never the in-memory editor state. */
@Composable
private fun rememberDecodedImage(file: File): ImageBitmap? = produceState<ImageBitmap?>(null, file.path) {
    value = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= PREVIEW_MAX_PX || bounds.outHeight / (sample * 2) >= PREVIEW_MAX_PX) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}.value

private const val PREVIEW_MAX_PX = 1080

@Composable
internal fun ResultScreen(controller: ReportController, modifier: Modifier, onEdit: () -> Unit, onDone: () -> Unit, onDeleted: () -> Unit) {
    val draft by controller.draft.collectAsState()
    val current = draft ?: return
    val status = current.status

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(statusLabel(status)), style = MaterialTheme.typography.titleLarge)
        if (status == ReportStatus.NEEDS_CONFIRMATION) {
            Text(stringResource(R.string.eora_needs_confirmation_help), style = MaterialTheme.typography.bodyMedium)
        }
        IssueCard(controller, current.issue)
        current.attachments.forEach { attachment ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(attachment.fileName, style = MaterialTheme.typography.bodyMedium)
                    Text(artifactStatusLabel(attachment.status), style = MaterialTheme.typography.bodySmall)
                    if (attachment.status is ArtifactStatus.Unknown) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.resolveUnknown(attachment, arrived = true) }) { Text(stringResource(R.string.eora_it_is_attached)) }
                            OutlinedButton(onClick = { controller.resolveUnknown(attachment, arrived = false) }) { Text(stringResource(R.string.eora_it_is_missing)) }
                        }
                    }
                }
            }
        }
        // Nothing reached the tracker (creation failed or was refused), so the user may fix the report and send again.
        if (!current.hasTransferStarted && status == ReportStatus.FAILED) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.eora_edit_report)) }
        }
        if (current.canSend && status != ReportStatus.SENDING) {
            Button(onClick = controller::send, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (current.createdIssue == null) {
                        stringResource(R.string.eora_retry_create)
                    } else {
                        stringResource(R.string.eora_send_remaining, current.sendableAttachments().size)
                    },
                )
            }
        }
        if (status != ReportStatus.SENDING) {
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.eora_close_keep)) }
            TextButton(
                onClick = {
                    controller.deleteDraft()
                    onDeleted()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.eora_delete_draft)) }
            Text(stringResource(R.string.eora_delete_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun IssueCard(controller: ReportController, issue: IssueStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (issue) {
                is IssueStatus.Created -> {
                    Text(issue.issue.key, style = MaterialTheme.typography.titleMedium)
                    issue.issue.title?.let { Text(it) }
                    Text(
                        stringResource(if (issue.confirmedByUser) R.string.eora_issue_created_confirmed else R.string.eora_issue_created),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IssueStatus.NotCreated -> Text(stringResource(R.string.eora_issue_not_created))
                IssueStatus.Creating -> Text(stringResource(R.string.eora_issue_creating))
                is IssueStatus.Rejected -> Text(
                    stringResource(R.string.eora_issue_rejected, stringResource(rejectLabel(issue.reason))) + (issue.message?.let { " ($it)" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                )
                is IssueStatus.Failed -> Text(stringResource(R.string.eora_issue_failed) + (issue.message?.let { " ($it)" } ?: ""))
                is IssueStatus.Unknown -> {
                    Text(stringResource(R.string.eora_issue_unknown) + (issue.message?.let { " ($it)" } ?: ""))
                    var key by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.eora_found_issue_key)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { controller.resolveUnknownIssue(key) }, enabled = key.isNotBlank()) {
                            Text(stringResource(R.string.eora_issue_exists))
                        }
                        OutlinedButton(onClick = { controller.resolveUnknownIssue(null) }) { Text(stringResource(R.string.eora_issue_missing)) }
                    }
                }
            }
        }
    }
}

internal fun statusLabel(status: ReportStatus): Int = when (status) {
    ReportStatus.DRAFT -> R.string.eora_status_draft
    ReportStatus.REVIEWABLE -> R.string.eora_status_reviewable
    ReportStatus.APPROVED -> R.string.eora_status_approved
    ReportStatus.SENDING -> R.string.eora_status_sending
    ReportStatus.SUCCEEDED -> R.string.eora_status_succeeded
    ReportStatus.PARTIALLY_SUCCEEDED -> R.string.eora_status_partial
    ReportStatus.FAILED -> R.string.eora_status_failed
    ReportStatus.NEEDS_CONFIRMATION -> R.string.eora_status_needs_confirmation
    ReportStatus.CANCELLED -> R.string.eora_status_cancelled
}

@Composable
private fun artifactStatusLabel(status: ArtifactStatus): String {
    val detail = when (status) {
        is ArtifactStatus.Rejected -> status.message
        is ArtifactStatus.Failed -> status.message
        is ArtifactStatus.Unknown -> status.message
        else -> null
    }
    val label = when (status) {
        ArtifactStatus.Pending -> stringResource(R.string.eora_file_pending)
        ArtifactStatus.Uploading -> stringResource(R.string.eora_file_uploading)
        is ArtifactStatus.Uploaded -> stringResource(if (status.confirmedByUser) R.string.eora_file_uploaded_confirmed else R.string.eora_file_uploaded)
        is ArtifactStatus.Rejected -> stringResource(R.string.eora_file_rejected, stringResource(rejectLabel(status.reason)))
        is ArtifactStatus.Failed -> stringResource(R.string.eora_file_failed)
        is ArtifactStatus.Unknown -> stringResource(R.string.eora_file_unknown)
    }
    // The detail comes from the uploader and is shown as is.
    return if (detail.isNullOrBlank()) label else "$label ($detail)"
}

private fun rejectLabel(reason: RejectReason): Int = when (reason) {
    RejectReason.ISSUE_NOT_FOUND -> R.string.eora_reject_not_found
    RejectReason.PERMISSION_DENIED -> R.string.eora_reject_permission
    RejectReason.INVALID_FIELDS -> R.string.eora_reject_invalid_fields
    RejectReason.TOO_LARGE -> R.string.eora_reject_too_large
    RejectReason.UNSUPPORTED_TYPE -> R.string.eora_reject_type
    RejectReason.CONFLICT -> R.string.eora_reject_conflict
    RejectReason.OTHER -> R.string.eora_reject_other
}

/** Host custom fields keep their own key as the label. */
@Composable
private fun metadataLabel(entry: MetadataEntry): String = when (entry.key) {
    "report_id" -> stringResource(R.string.eora_meta_report_id)
    "created_at" -> stringResource(R.string.eora_meta_created_at)
    "environment" -> stringResource(R.string.eora_meta_environment)
    "app_version_name" -> stringResource(R.string.eora_meta_app_version_name)
    "app_version_code" -> stringResource(R.string.eora_meta_app_version_code)
    "android_version" -> stringResource(R.string.eora_meta_android_version)
    "device" -> stringResource(R.string.eora_meta_device)
    "screen" -> stringResource(R.string.eora_meta_screen)
    else -> entry.label
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
